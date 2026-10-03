package com.kiki.android;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真正离线的中文语音识别(Vosk + 本地模型)。
 *
 * 为什么必须自己做
 * ----------------
 * 手机上唯一的系统识别服务是小米的**在线**引擎(日志实测 mUseLocal=false),
 * 而它对第三方调用返回服务器错误 50010004:
 *     D/AsrService: error code: 50010004
 *     I/kiki-speech: onError 触发 code=1 (ERROR_NETWORK)
 * 换网络、关梯子都无效,系统里也没有第二个识别服务可选。
 *
 * 所以这里直接用 Vosk + 中文小模型在**本机**做识别:不联网、不看任何服务器脸色。
 *
 * 实现要点
 * --------
 * - 自己开 AudioRecord(16kHz 单声道 PCM16),把音频喂给 Vosk 的 Recognizer
 * - 用 RMS 能量做"说完了"判定,静音 1.5 秒就收尾,不需要用户再按一次
 * - 全程在后台线程,主线程只收回调
 */
public class OfflineRecognizer {

    private static final String TAG = "kiki-vosk";

    public interface Callback {
        /** 实时中间结果(说话过程中不断刷新) */
        void onPartial(String text);

        /** 最终结果 */
        void onFinal(String text);

        /** 出错(不会崩,只提示) */
        void onError(String message);
    }

    private final Context context;
    private Model model;
    private Recognizer recognizer;
    private AudioRecord record;
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private Callback callback;

    /** 采样率:Vosk 中文模型要求 16kHz */
    private static final int SAMPLE_RATE = 16000;
    /** 静音多久算说完(毫秒) */
    private static final int SILENCE_MS = 2800;
    /** 静音判定阈值下限(RMS);实际门槛取 max(它, 底噪×2) */
    private static final double SILENCE_RMS = 500.0;
    /** 底噪校准帧数(每帧约 4096/16000 ≈ 0.26 秒) */
    private static final int CALIB_FRAMES = 6;

    public OfflineRecognizer(Context context) {
        this.context = context;
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public boolean isReady() {
        return model != null;
    }

    /**
     * 加载模型。modelDir 必须是包含 am/ conf/ graph/ 等子目录的模型根目录。
     * 加载较慢(首次 1-3 秒),建议在后台线程调用。
     */
    public boolean loadModel(File modelDir) {
        if (model == null) {
            try {
                long t0 = System.currentTimeMillis();
                model = new Model(modelDir.getAbsolutePath());
                Log.i(TAG, "模型加载完成,耗时 " + (System.currentTimeMillis() - t0) + " ms: " + modelDir);
            } catch (Throwable t) {
                Log.e(TAG, "模型加载失败: " + t);
                if (callback != null) callback.onError("离线语音模型加载失败: " + t.getMessage());
                model = null;
                return false;
            }
        }
        return true;
    }

    /** 开始听(非阻塞)。已经忙时:**强制重置后重新开始**,绝不静默忽略。 */
    public void start() {
        if (model == null) {
            if (callback != null) callback.onError("离线语音模型还没准备好");
            return;
        }
        // 【重要】这里不能写成 if (busy.get()) return;
        // 实测踩过:上一次录音如果没正常收尾(record.read 阻塞 / 用户快速连点),
        // busy 会永远是 true,之后所有点击都被静默忽略 —— 表现为"点不动、没反应、
        // 连日志都不打"。所以忙的时候做一次强制复位,保证按钮始终有响应。
        if (busy.get()) {
            Log.w(TAG, "上一次录音还没收尾,强制复位后重新开始");
            running.set(false);
            destroyRecord();
        }
        busy.set(true);
        running.set(true);
        stopRequestedAt = 0L;      // 必须清零,否则新会话会被旧时间戳立刻中止
        lastStopWatchdog = 0L;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "kiki-asr");
        thread.start();
    }

    /** 停止并尽早收尾 */
    public void stop() {
        running.set(false);
        stopRequestedAt = System.currentTimeMillis();
    }

    /** 松手后仍继续喂音频的宽限期:让 Vosk 有时间把这句话"收尾" */
    private static final long TAIL_GRACE_MS = 500;
    private volatile long stopRequestedAt = 0L;

    /** 单次录音的最长时间,超过就强制收尾(防止线程卡死导致后续全部失灵) */
    private static final long MAX_RECORD_MS = 8000;
    private volatile long lastStopWatchdog = 0L;

    /** 释放 AudioRecord(供强制复位用) */
    private void destroyRecord() {
        try {
            if (record != null) {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                record.release();
            }
        } catch (Throwable ignored) {
        }
        record = null;
    }

    /**
     * 把最近一次录音的**原始音频**存成 WAV(16kHz 单声道 PCM),便于排障。
     *
     * 为什么需要:我用 TTS 合成的"干净音频"做基准时,小模型字错率只有 0.7~1.5%,
     * 与用户"识别不准"的体验不符 —— 说明真正的难点在**真实人声**(口音、语速、
     * 环境噪声),合成音频测不出来。所以要拿到用户真实的一句话,
     * 才能在 PC 上用不同模型离线对比,精确定位是模型问题还是别的。
     */
    private void saveRecording(byte[] pcm, int len) {
        java.io.FileOutputStream fos = null;
        try {
            java.io.File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            java.io.File out = new java.io.File(dir, "last_recording.wav");
            fos = new java.io.FileOutputStream(out);
            int dataLen = len;
            int byteRate = SAMPLE_RATE * 2;
            // 标准 44 字节 WAV 头
            fos.write(new byte[]{'R', 'I', 'F', 'F'});
            writeIntLE(fos, 36 + dataLen);
            fos.write(new byte[]{'W', 'A', 'V', 'E'});
            fos.write(new byte[]{'f', 'm', 't', ' '});
            writeIntLE(fos, 16);
            writeShortLE(fos, 1);              // PCM
            writeShortLE(fos, 1);              // 单声道
            writeIntLE(fos, SAMPLE_RATE);
            writeIntLE(fos, byteRate);
            writeShortLE(fos, 2);              // block align
            writeShortLE(fos, 16);             // bits per sample
            fos.write(new byte[]{'d', 'a', 't', 'a'});
            writeIntLE(fos, dataLen);
            fos.write(pcm, 0, len);
            Log.i(TAG, "已保存本次录音: " + out.getAbsolutePath()
                    + " (" + (dataLen / 1024) + " KB)");
        } catch (Throwable t) {
            Log.w(TAG, "保存录音失败: " + t);
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeIntLE(java.io.OutputStream o, int v) throws java.io.IOException {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(java.io.OutputStream o, int v) throws java.io.IOException {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private void loop() {
        Log.i(TAG, "录音线程启动");
        long startedAt = System.currentTimeMillis();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, SAMPLE_RATE);   // 至少 1 秒缓冲
        try {
            record = openRecord(bufSize);
            if (record == null) {
                fail("录音设备初始化失败(可能被其他应用占用)");
                return;
            }
            recognizer = new Recognizer(model, SAMPLE_RATE);
            recognizer.setWords(false);
            record.startRecording();

            byte[] buf = new byte[4096];
            // 同时缓存原始 PCM,录音结束后存成 wav 供排障
            java.io.ByteArrayOutputStream pcmAll = new java.io.ByteArrayOutputStream(320000);
            long lastVoiceAt = System.currentTimeMillis();
            boolean gotAny = false;
            boolean timedOut = false;
            // 静音判定的滑动窗口:WIN 帧内统计"有语音"的比例
            final int WIN = 12;
            boolean[] win = new boolean[WIN];
            int winIdx = 0; int winFill = 0;
            double gate = SILENCE_RMS;   // 循环内会重算;提到外面便于收尾日志引用
            String best = "";

            // 自适应底噪:开头若干帧测环境噪声。
            //
            // 【关键修正】这里必须用**中位数**,不能用最大值。
            // 实测踩过:用户按下的瞬间有"碰触噪声",那一下被 max 当成底噪,
            // 于是门槛被抬到 5000+,之后所有语音都被判成"静音",
            // 日志表现为 底噪=1697 峰值=468 是否说过话=false。
            // 中位数能忽略这种瞬时尖峰。
            int calibFrames = 0;
            double noiseFloor = 0;
            double peakRms = 0;

            while (true) {
                // 看门狗:单次录音最长 MAX_RECORD_MS,超了就强制收尾。
                // 防止 record.read() 阻塞导致线程永不退出、busy 永远是 true。
                if (System.currentTimeMillis() - startedAt > MAX_RECORD_MS) {
                    Log.w(TAG, "录音超时(" + MAX_RECORD_MS + "ms),强制收尾");
                    timedOut = true;
                    break;
                }
                // 松手后**不要立刻停** —— 继续读 500ms 让 Vosk 收尾。
                // 实测:用户快速松手时上层马上 stop(),若立即掐断音频,
                // Vosk 的 FinalResult 会是空的(话说到一半就断了),表现为"没听清"。
                if (!running.get()) {
                    if (stopRequestedAt == 0L) stopRequestedAt = System.currentTimeMillis();
                    if (System.currentTimeMillis() - stopRequestedAt > TAIL_GRACE_MS) break;
                }

                int n = record.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (pcmAll.size() < SAMPLE_RATE * 2 * 20) pcmAll.write(buf, 0, n);

                double rms = rms(buf, n);

                // 底噪估计:用**观察到的最小值**,而不是开头几帧的中位数。
                //
                // 为什么:开头几帧可能正好是用户调整手机/碰触话筒的瞬间,
                // 中位数会被污染。实测踩过:
                //     底噪校准完成(中位数): 5135   <- 其实是碰触噪声
                //     本次录音: 底噪=5135 峰值=2248 是否说过话=false  <- 话被当静音
                // 最小值几乎必然来自真正的静音片段,尖峰自然被排除。
                if (calibFrames == 0 || rms < noiseFloor) {
                    noiseFloor = rms;
                }
                calibFrames++;
                if (calibFrames == CALIB_FRAMES) {
                    Log.i(TAG, "底噪估计(最小值): " + (int) noiseFloor);
                }

                // 门槛:底噪的 2 倍,但不超过 3000(避免异常高底噪把门槛抬到无法触发),
                // 也不低于 SILENCE_RMS。
                // 门槛上限刻意压低到 2000:实测有过一次 底噪=5135、峰值=2248,
                // 门槛被抬到 3000 以上,小声说话全被判成静音 -> 卡 5 秒才提示。
                gate = Math.max(SILENCE_RMS, Math.min(noiseFloor * 1.8, 2000.0));

                if (rms > peakRms) peakRms = rms;
                boolean voiced = rms > gate;
                if (voiced) {
                    lastVoiceAt = System.currentTimeMillis();
                    gotAny = true;
                }
                // 滑动窗口:记录最近若干帧"是否有语音"
                win[winIdx] = voiced;
                winIdx = (winIdx + 1) % WIN;
                if (winFill < WIN) winFill++;

                boolean done = recognizer.acceptWaveForm(buf, n);
                if (done) {
                    String t = extractText(recognizer.getResult(), "text");
                    if (t != null && !t.isEmpty()) best = t;
                } else {
                    String p = extractText(recognizer.getPartialResult(), "partial");
                    if (p != null && !p.isEmpty() && callback != null) callback.onPartial(p);
                }

                // 收尾判定:用**滑动窗口比例**,而不是"最后一次超过门槛的时间"。
                //
                // 为什么必须改:原来只要环境里偶尔有一声超过门槛的响动(椅子、呼吸、
                // 空调风),就把计时器清零,于是永远到不了静音判停 —— 用户反馈
                // "一直不停",录音一路跑到 8 秒上限才结束。
                // 现在要求窗口内"有语音的帧占比"低于 15%,短促响动不会再重置计时。
                if (gotAny && winFill == WIN) {
                    int voicedCount = 0;
                    for (int i = 0; i < WIN; i++) if (win[i]) voicedCount++;
                    if (voicedCount <= (int) (WIN * 0.15)
                            && System.currentTimeMillis() - lastVoiceAt > SILENCE_MS) {
                        break;
                    }
                }
                // 一直没人说话,5 秒超时
                if (!gotAny && System.currentTimeMillis() - lastVoiceAt > 5000) {
                    Log.w(TAG, "没听到声音: 底噪=" + (int) noiseFloor + " 门槛=" + (int) gate
                            + " 峰值=" + (int) peakRms);
                    fail("没听到声音,请对着话筒说话");
                    return;
                }
            }
            Log.i(TAG, "本次录音: 底噪=" + (int) noiseFloor + " 峰值=" + (int) peakRms
                    + " 信噪比=" + (noiseFloor > 0 ? String.format("%.1f", peakRms / noiseFloor) : "-")
                    + " 门槛=" + (int) gate
                    + " 时长=" + (System.currentTimeMillis() - startedAt) + "ms"
                    + " 超时收尾=" + timedOut
                    + " 是否说过话=" + gotAny);

            // 收尾:拿最终结果
            try {
                String fin = extractText(recognizer.getFinalResult(), "text");
                if (fin != null && !fin.isEmpty()) best = fin;
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "识别结果: [" + best + "]");
            saveRecording(pcmAll.toByteArray(), pcmAll.size());
            if (best == null || best.trim().isEmpty()) {
                fail("没听清,再说一次?");
            } else if (callback != null) {
                callback.onFinal(best.trim());
            }
        } catch (Throwable t) {
            Log.e(TAG, "识别线程异常: " + t);
            fail("离线识别出错: " + t.getMessage());
        } finally {
            cleanup();
            busy.set(false);
            Log.i(TAG, "录音线程结束,耗时 " + (System.currentTimeMillis() - startedAt)
                    + " ms,busy 已释放");
        }
    }

    private void fail(String msg) {
        Log.w(TAG, "失败: " + msg);
        if (callback != null) callback.onError(msg);
    }

    /**
     * 打开录音设备 —— **音频源选错会显著拉低识别率**,这里按优先级尝试。
     *
     * 实测过程(小米 K70E / Android 16):
     *   - VOICE_RECOGNITION:带降噪+AGC,破坏 Vosk 依赖的声学特征 → 第一版就是它,识别很差
     *   - UNPROCESSED:测到底噪 ~1600、人声峰值只有 ~1200(信噪比 < 1),
     *     这台设备的"原始"通道增益/噪声异常,反而更差
     *   - **MIC**:普通麦克风,表现最稳,所以放在第一位
     *
     * 优先级:FALLBACK → MIC → UNPROCESSED → VOICE_RECOGNITION
     * (FALLBACK 让系统自己挑,通常是该机型最优的默认选择)
     */
    private AudioRecord openRecord(int bufSize) {
        int[] sources = {MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.UNPROCESSED};
        String[] names = {"MIC", "VOICE_RECOGNITION", "UNPROCESSED"};
        for (int i = 0; i < sources.length; i++) {
            AudioRecord r = null;
            try {
                r = new AudioRecord(sources[i], SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "音频源: " + names[i]);
                    return r;
                }
                Log.w(TAG, "音频源 " + names[i] + " 初始化失败,降级");
            } catch (Throwable t) {
                Log.w(TAG, "音频源 " + names[i] + " 异常: " + t);
            }
            try {
                if (r != null) r.release();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void cleanup() {
        try {
            if (record != null) {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                record.release();
            }
        } catch (Throwable ignored) {
        }
        record = null;
        try {
            if (recognizer != null) recognizer.close();
        } catch (Throwable ignored) {
        }
        recognizer = null;
    }

    public void release() {
        stop();
        cleanup();
        try {
            if (model != null) model.close();
        } catch (Throwable ignored) {
        }
        model = null;
    }

    /** 计算一段 PCM16 的 RMS 能量 */
    private static double rms(byte[] buf, int len) {
        long sum = 0;
        int count = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            sum += (long) s * s;
        }
        return count == 0 ? 0 : Math.sqrt((double) sum / count);
    }

    /**
     * 从 Vosk 返回的 JSON 里取文本字段。
     * 形如 {"text":"你好世界"} 或 {"partial":"你好"};
     * 不想为一个字段引入 JSON 依赖,所以手写一个最小解析。
     */
    static String extractText(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int p = start + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else if (n == 'u' && p + 4 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                        p += 4;
                    } catch (Throwable ignored) {
                    }
                } else sb.append(n);
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }
}
