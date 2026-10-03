package com.kiki.android;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 语音壳(Android 原生):TTS 念回答 + 语音识别听你说话。
 *
 * 为什么手机上比 PC 强
 * --------------------
 * Windows 那台机器只有英文语音、识别引擎 0 个,而且沙箱里 SAPI 拿不到权限。
 * Android 系统自带中文 TTS 与识别引擎(多数机型离线可用)。
 *
 * 【为什么到处是 try/catch】
 * 上一版"一按麦克风就闪退",根因是这些调用在国产 ROM(小米/华为/OPPO 等)上
 * 会抛异常或返回 null,而异常发生在主线程 = 直接闪退:
 *   - createOnDeviceSpeechRecognizer(API 31+) 在没装离线识别包时抛异常
 *   - createSpeechRecognizer 可能返回 null(没有识别服务)
 *   - startListening 可能抛 SecurityException / IllegalStateException(服务未就绪)
 *   - 部分 ROM 的 isRecognitionAvailable() 自身就抛 NPE
 * 所以本类的原则是:**任何语音操作失败都只影响这一次操作,绝不崩溃**,
 * 失败时通过回调把原因告诉用户,让她还能打字继续用。
 */
public class SpeechManager {

    public interface StringCallback {
        void onString(String value);
    }

    public interface Boolean2Callback {
        void onReady(boolean ok, boolean chinese);
    }

    private final Context context;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean hasChinese = false;

    private SpeechRecognizer recognizer;
    private boolean listening = false;

    public StringCallback onResult;
    public StringCallback onPartial;
    public StringCallback onError;

    /** 上一次失败原因(便于界面显示) */
    public volatile String lastError = "";

    /**
     * "念完了"回调 —— 原版是"她说完就自动开始听",所以必须知道 TTS 何时结束。
     * TTS 不可用时(没装引擎),调用方会走超时兜底,不会卡住对话循环。
     */
    public Runnable onSpeakDone;

    /**
     * 已报过的错误(去重)。
     * 实测:识别流程正常拿到结果后,系统仍可能再补一发 ERROR_CLIENT/onError,
     * 而 startListening 的 catch 分支也可能同时报错 —— 于是界面上出现两次同样的提示。
     * 这里记住上一条错误文案与时间,短时间内同样的话不再重复显示。
     */
    private String lastErrorShown = "";
    private long lastErrorAt = 0L;

    /**
     * 识别会话序号:每开一次麦 +1。
     * 旧会话(比如用户手动停掉、或识别器被销毁时)补发的回调会被忽略,
     * 不会打断新会话,也不会重复弹提示。
     */
    private int session = 0;

    /** "识别器忙"自动重试次数(每次开麦重置) */
    private int busyRetry = 0;

    /** 调试日志开关(用 adb 打开:setprop debug.kiki.log 1 需 root;这里默认开,只打少量行) */
    private static final String TAG = "kiki-speech";

    private void log(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public SpeechManager(Context context) {
        this.context = context;
    }

    // ---------------- TTS ----------------
    public void initTts(final Boolean2Callback onReady) {
        try {
            tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    boolean ok = false;
                    boolean zh = false;
                    try {
                        if (status == TextToSpeech.SUCCESS && tts != null) {
                            int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                            zh = (r != TextToSpeech.LANG_MISSING_DATA
                                    && r != TextToSpeech.LANG_NOT_SUPPORTED);
                            if (!zh) {
                                try {
                                    tts.setLanguage(Locale.getDefault());
                                } catch (Exception ignored) {
                                }
                            }
                            try {
                                tts.setSpeechRate(1.05f);
                            } catch (Exception ignored) {
                            }
                            ok = true;
                        }
                    } catch (Exception e) {
                        lastError = "TTS 初始化异常: " + e;
                    }
                    ttsReady = ok;
                    hasChinese = zh;
                    if (onReady != null) onReady.onReady(ok, zh);
                }
            });
        } catch (Exception e) {
            ttsReady = false;
            lastError = "TTS 不可用: " + e;
            if (onReady != null) onReady.onReady(false, false);
        }
    }

    public boolean isChineseAvailable() {
        return ttsReady && hasChinese;
    }

    /** 念一句话(异步,不阻塞界面;失败只记录不抛) */
    public void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.length() == 0) {
            android.util.Log.w(TAG, "跳过朗读: ttsReady=" + ttsReady + " 文本长度="
                    + (text == null ? -1 : text.length()));
            return;
        }
        try {
            final Runnable done = onSpeakDone;
            if (done != null) {
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        android.util.Log.i("kiki-loop", "TTS onDone 触发");
                        done.run();
                    }

                    @Override
                    public void onError(String utteranceId) {
                        android.util.Log.w("kiki-loop", "TTS onError 触发");
                        done.run();
                    }
                });
            }
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kiki");
            android.util.Log.i(TAG, "朗读(" + r + "): " + text);
        } catch (Exception e) {
            lastError = "朗读失败: " + e;
            android.util.Log.e(TAG, "朗读异常: " + e);
        }
    }

    public void stopSpeaking() {
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 语音识别 ----------------
    /** 本机是否有可用的识别服务(有些 ROM 这里自己会抛 NPE,所以包住) */
    public boolean canRecognize() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(context);
        } catch (Throwable t) {
            lastError = "识别服务不可用: " + t;
            return false;
        }
    }

    public boolean isListening() {
        return listening;
    }

    /** 开麦听一句。任何失败都走 onError 回调,不崩溃。 */
    public void listen() {
        log("listen() 进入, canRecognize=" + canRecognize());
        if (!canRecognize()) {
            fail("本机没有可用的语音识别服务。你仍然可以打字聊天。");
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = createRecognizer();
            }
            if (recognizer == null) {
                fail("创建语音识别器失败(本机可能没有识别服务)。");
                return;
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

            log("准备 startListening, recognizer=" + (recognizer == null ? "null" : "ok")
                    + ", session=" + (session + 1));
            session++;              // 新会话:旧会话的补发回调会被忽略
            busyRetry = 0;
            listening = true;
            recognizer.startListening(intent);
            log("startListening 已调用");
        } catch (Throwable t) {
            log("startListening 抛异常: " + t);
            listening = false;
            destroyRecognizer();   // 识别器可能已处于坏状态,丢掉重来
            fail("启动语音识别失败: " + t);
        }
    }

    /**
     * 创建识别器,失败则降级。
     * Android 12(API 31)起可以优先用**设备端离线识别**,
     * 但很多 ROM 没装离线语言包,这时要退回普通识别,而不是崩掉。
     */
    private SpeechRecognizer createRecognizer() {
        SpeechRecognizer r = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            } catch (Throwable t) {
                r = null;   // 没装离线识别包,退回普通识别
            }
        }
        if (r == null) {
            try {
                r = SpeechRecognizer.createSpeechRecognizer(context);
            } catch (Throwable t) {
                lastError = "创建识别器失败: " + t;
                return null;
            }
        }
        log("createRecognizer 结果=" + (r == null ? "null" : r.getClass().getName())
                + ", canRecognize=" + canRecognize());
        if (r == null) return null;
        try {
            r.setRecognitionListener(new Listener(session));   // 绑定当前会话号
        } catch (Throwable t) {
            lastError = "注册识别回调失败: " + t;
            return null;
        }
        return r;
    }

    public void stopListening() {
        session++;   // 让这个会话的后续回调失效(用户主动停止不该再弹任何提示)
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Throwable ignored) {
        }
        listening = false;
    }

    private void destroyRecognizer() {
        try {
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
            }
        } catch (Throwable ignored) {
        }
        recognizer = null;
    }

    private void fail(String msg) {
        lastError = msg;
        long now = System.currentTimeMillis();
        // 同一句话在 2 秒内只显示一次(避免生命周期噪音刷两条一样的提示)
        if (msg != null && msg.equals(lastErrorShown) && (now - lastErrorAt) < 2000) {
            return;
        }
        lastErrorShown = msg;
        lastErrorAt = now;
        if (onError != null) onError.onString(msg);
    }

    private void deliver(String text) {
        listening = false;
        if (text == null || text.trim().length() == 0) {
            fail("没听清,再说一次?");
            return;
        }
        if (onResult != null) onResult.onString(text);
    }

    /**
     * 识别回调:每个方法都包住,防止 ROM 抛异常导致闪退。
     * 同时带会话号:旧会话补发的回调直接丢弃,不会打断新会话或重复弹提示。
     */
    private class Listener implements RecognitionListener {

        private final int mySession;

        Listener(int mySession) {
            this.mySession = mySession;
        }

        /** 是否是当前有效会话 */
        private boolean current() {
            return mySession == session;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            log("onReadyForSpeech 就绪");
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            try {
                if (!current()) return;
                log("onPartialResults 收到部分结果");
                ArrayList<String> list = partialResults == null ? null
                        : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && onPartial != null) {
                    onPartial.onString(list.get(0));
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onResults(Bundle results) {
            log("onResults 触发");
            try {
                if (!current()) return;
                ArrayList<String> list = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    deliver(list.get(0));
                    return;
                }
            } catch (Throwable ignored) {
            }
            if (current()) deliver(null);
        }

        @Override
        public void onError(int error) {
            log("onError 触发 code=" + error);
            if (!current()) return;   // 旧会话的补发错误,忽略
            // ERROR_CLIENT 是**生命周期噪音**,不是真失败:
            // 实测在正常拿到识别结果之后,系统还会再补一发 ERROR_CLIENT(等价于"已结束"),
            // 若把它当错误报出来,用户就会看到莫名其妙的"识别被中断"。
            if (error == SpeechRecognizer.ERROR_CLIENT) {
                return;
            }
            // 识别器被占用:多半是上一个会话还没完全释放,给一次自动重试
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                listening = false;
                destroyRecognizer();
                if (busyRetry < 1) {
                    busyRetry++;
                    listen();
                    return;
                }
                busyRetry = 0;
                fail("识别服务忙,请等一下再试");
                return;
            }
            listening = false;
            fail(recognizeErrorText(error));
        }
    }

    private String recognizeErrorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "没听清,再说一次?";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没听到声音(请对着话筒说话)";
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音出错,可能麦克风被其他应用占用";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "没有麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "识别服务连不上(可在系统设置里下载离线语音包)";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别服务忙,等一下再试";
            case SpeechRecognizer.ERROR_CLIENT:
                // 正常情况下这一码不会走到这里(见 onError 里的忽略逻辑)
                return "语音识别被中断";
            default:
                return "识别失败(代码 " + error + ")";
        }
    }

    public void release() {
        stopSpeaking();
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        tts = null;
        ttsReady = false;
        destroyRecognizer();
    }
}
