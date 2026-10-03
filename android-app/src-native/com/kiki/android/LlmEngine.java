package com.kiki.android;

import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地对话模型(Qwen2.5-0.5B, ONNX Runtime 推理) —— 让 kiki 真的会说话。
 *
 * 为什么要它
 * ----------
 * 之前用纯 Java 手写的字符级语言模型(NeuralLM)生成的是
 * "黑果我生吉家壳是别什么地是什么是黑什么区国地心么轼人地级买累低镆镄么炼腺备用首诗"
 * 这种字串 —— 每个字合法、整句毫无意义。原因是模型太小(numpy 量级),
 * 学到的只是"字与字的搭配",不是语言。
 *
 * 换成 **Qwen2.5-0.5B**(预训练 Transformer,4.6 亿参数)后,实测输出:
 *     你是谁        -> 我是Kiki,一个充满活力的AI助手…
 *     比尔盖茨是谁   -> 比尔·盖茨是微软的创始人和CEO。
 *     我好累啊      -> 给你点休息时间吧。
 * 这才是"会说话"。
 *
 * 接口要点(从 ONNX 图里读出来的,不是猜的)
 * ----------------------------------------
 *   输入 51 个:
 *     input_ids                (1, seq)
 *     attention_mask           (1, past+seq)
 *     position_ids             (1, seq)
 *     past_key_values.N.key    (1, 2, past, 64)   N = 0..23
 *     past_key_values.N.value  (1, 2, past, 64)
 *   输出 49 个:
 *     logits                   (1, seq, 151936)
 *     present.N.key/value      (1, 2, past+seq, 64)
 *   模型:24 层、2 个 KV 头、head_dim 64、词表 151936、eos=151645
 *
 * 【量化档位的坑】官方 `model_int8.onnx` 量化有缺陷,输出重复胡话
 * ("你好，回答一下，我需要什么，有什么问题…" 反复);
 * 换成 **`model_q4f16.onnx` 才正确**(实测 4 个问题全部回答通顺,
 * 而且更快:17~18 字/秒 vs int8 的 9.6 字/秒)。所以内置的是 q4f16。
 */
public class LlmEngine {

    private static final String TAG = "kiki-llm";

    /** 模型结构常量(与 config.json 一致) */
    private static final int N_LAYER = 24;
    private static final int N_KV_HEAD = 2;
    private static final int HEAD_DIM = 64;
    private static final long EOS_ID = 151645L;

    private OrtEnvironment env;
    private OrtSession session;
    private QwenTokenizer tok;

    /** 会话上下文(多轮对话用) */
    private final List<int[]> history = new ArrayList<int[]>();

    private volatile boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    public QwenTokenizer tokenizer() {
        return tok;
    }

    /**
     * 载入模型与分词器。**必须在后台线程调用**(载入 460MB 模型要几秒)。
     *
     * @param modelFile   已释放到磁盘的 model.onnx
     * @param assets      用于读分词器文件
     * @param assetDir    assets 里的目录名(如 "llm")
     */
    public boolean load(File modelFile, android.content.res.AssetManager assets, String assetDir) {
        try {
            tok = new QwenTokenizer();
            if (!tok.load(assets, assetDir)) {
                Log.e(TAG, "分词器载入失败");
                return false;
            }
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            // 0.5B 模型不需要太多线程,给 2 个避免抢 UI
            opts.setIntraOpNumThreads(2);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            long t0 = System.currentTimeMillis();
            session = env.createSession(modelFile.getAbsolutePath(), opts);
            Log.i(TAG, "模型载入完成,耗时 " + (System.currentTimeMillis() - t0) + " ms");
            for (String in : session.getInputNames()) {
                // 只打印前几个,避免刷屏
                if (!in.startsWith("past_key_values.")) Log.i(TAG, "  输入: " + in);
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "载入模型失败: " + t);
            ready = false;
            return false;
        }
    }

    /** 一次推理:返回 logits(取最后一个位置的 argmax 由调用方决定)与新的 KV 缓存 */
    private static final class Step {
        float[] lastLogits;         // 词表大小的 logits(最后一个位置)
        /**
         * session.run() 的返回句柄。
         *
         * 【必须持有它,否则会 SIGSEGV】present.*.key/value 张量的原生内存
         * 归这个 Result 所有;一旦 Result 被 GC 回收,张量就成了悬空指针,
         * 下一轮再喂给 session.run 就会段错误。实测踩过:
         *     Fatal signal 11 (SIGSEGV) in libonnxruntime.so
         *     Java_ai_onnxruntime_OrtSession_run
         * 所以 Step 里存住 Result,并在切到下一步时才 close 它。
         */
        OrtSession.Result holder;
        OnnxTensor[] present;       // 2*N_LAYER 个 present 张量(由 holder 拥有)
    }

    private Step run(List<Integer> ids, Step prev) throws Exception {
        int n = ids.size();
        OnnxTensor[] past = prev == null ? null : prev.present;
        int pastLen = 0;
        if (past != null) {
            long[] shp = past[0].getInfo().getShape();
            pastLen = (int) shp[2];
        }

        Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
        long[] idsArr = new long[n];
        for (int i = 0; i < n; i++) idsArr[i] = ids.get(i);
        feed.put("input_ids", OnnxTensor.createTensor(env,
                LongBuffer.wrap(idsArr), new long[]{1, n}));

        long[] mask = new long[pastLen + n];
        for (int i = 0; i < mask.length; i++) mask[i] = 1;
        feed.put("attention_mask", OnnxTensor.createTensor(env,
                LongBuffer.wrap(mask), new long[]{1, pastLen + n}));

        long[] pos = new long[n];
        for (int i = 0; i < n; i++) pos[i] = pastLen + i;
        feed.put("position_ids", OnnxTensor.createTensor(env,
                LongBuffer.wrap(pos), new long[]{1, n}));

        for (int i = 0; i < N_LAYER; i++) {
            if (past == null) {
                feed.put("past_key_values." + i + ".key", OnnxTensor.createTensor(env,
                        java.nio.FloatBuffer.allocate(0), new long[]{1, N_KV_HEAD, 0, HEAD_DIM}));
                feed.put("past_key_values." + i + ".value", OnnxTensor.createTensor(env,
                        java.nio.FloatBuffer.allocate(0), new long[]{1, N_KV_HEAD, 0, HEAD_DIM}));
            } else {
                feed.put("past_key_values." + i + ".key", past[i * 2]);
                feed.put("past_key_values." + i + ".value", past[i * 2 + 1]);
            }
        }

        OrtSession.Result res = session.run(feed);
        // 我们自己创建的输入张量可以立刻释放;present 由 res 拥有,稍后随 res 一起释放
        for (Map.Entry<String, OnnxTensor> e : feed.entrySet()) {
            if (e.getKey().startsWith("past_key_values.") && past != null) continue;
            try { e.getValue().close(); } catch (Throwable ignored) {}
        }

        // 取 logits (1, n, V) 的最后一个位置
        OnnxTensor logitsT = (OnnxTensor) res.get("logits").get();
        float[][][] logits = (float[][][]) logitsT.getValue();
        int V = logits[0][n - 1].length;
        float[] last = new float[V];
        System.arraycopy(logits[0][n - 1], 0, last, 0, V);

        Step st = new Step();
        st.lastLogits = last;
        st.holder = res;
        st.present = new OnnxTensor[N_LAYER * 2];
        for (int i = 0; i < N_LAYER; i++) {
            st.present[i * 2] = (OnnxTensor) res.get("present." + i + ".key").get();
            st.present[i * 2 + 1] = (OnnxTensor) res.get("present." + i + ".value").get();
        }
        return st;
    }

    private static int argmax(float[] a) {
        int bi = 0;
        float bv = a[0];
        for (int i = 1; i < a.length; i++) {
            if (a[i] > bv) {
                bv = a[i];
                bi = i;
            }
        }
        return bi;
    }

    /**
     * 生成回答(阻塞,请在后台线程调用)。
     *
     * @param system    角色设定
     * @param user      用户这句话
     * @param maxNew    最多生成多少 token
     */
    public String generate(String system, String user, int maxNew) {
        if (!ready) return null;
        Step st = null;
        try {
            List<Integer> prompt = tok.buildChat(system, user);
            Log.i(TAG, "提示词 " + prompt.size() + " token");

            long t0 = System.currentTimeMillis();
            st = run(prompt, null);
            long tPrefill = System.currentTimeMillis() - t0;

            List<Integer> out = new ArrayList<Integer>();
            long t1 = System.currentTimeMillis();
            for (int i = 0; i < maxNew; i++) {
                int nxt = argmax(st.lastLogits);
                if (nxt == EOS_ID) break;
                out.add(nxt);
                // 下一步:先把上一轮的 Result 关掉(它拥有旧的 present 内存),再跑新的
                Step prevStep = st;
                st = run(java.util.Collections.singletonList(nxt), prevStep);
                try { prevStep.holder.close(); } catch (Throwable ignored) {}
            }
            long tGen = System.currentTimeMillis() - t1;
            String text = tok.decode(out);
            Log.i(TAG, "prefill " + tPrefill + "ms, 生成 " + out.size() + " token, "
                    + tGen + "ms (" + (out.size() * 1000 / Math.max(1, tGen)) + " token/s)");
            Log.i(TAG, "回答: " + text);
            return text;
        } catch (Throwable t) {
            Log.e(TAG, "生成失败: " + t);
            return null;
        } finally {
            if (st != null) {
                try { st.holder.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
