package com.kiki.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 让 kiki 用上真正的对话模型 —— 负责"把模型从 APK 拿出来、载入引擎"。
 *
 * 两件事
 * ------
 * 1. **首次启动把 model.onnx 释放到私有目录**。
 *    模型 460MB 打包在 APK 的 assets 里,而 ONNX Runtime 需要一个**文件路径**
 *    (也有 byte[] 接口,但 460MB 一次性读进堆会 OOM,所以走文件)。
 *    释放一次约 1~2 秒,之后直接复用。
 *
 * 2. **asset 名字的坑**:aapt2 打包时会把 `assets/llm/model.onnx` 存成
 *    `assets/llm\model.onnx`(**反斜杠**!)。而 AssetManager 通常要求正斜杠,
 *    直接 open 会失败。所以这里两种写法都试,哪种通用哪种 —— 实测为准,不猜。
 */
public class LlmBootstrap {

    private static final String TAG = "kiki-llm";

    /** 模型在 assets 里的目录名 */
    private static final String DIR = "llm";
    private static final String[] FILES = {
            "vocab.json", "merges.txt", "tokenizer_config.json"
    };
    /** 大模型单独处理(要释放到磁盘) */
    private static final String MODEL_ASSET = "model.onnx";
    private static final String MODEL_FILE = "llm_model.onnx";

    /** 探测 AssetManager 到底认哪种分隔符 */
    public static String assetPath(AssetManager am, String rel) {
        String[] cands = {DIR + "/" + rel, DIR + "\\" + rel, rel};
        for (String c : cands) {
            InputStream is = null;
            try {
                is = am.open(c);
                is.close();
                Log.i(TAG, "asset 可访问: " + c);
                return c;
            } catch (Throwable t) {
                // 试下一个
            } finally {
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
            }
        }
        Log.e(TAG, "asset 都打不开: " + rel);
        return null;
    }

    /** 释放大模型到私有目录(已存在且大小正确就跳过) */
    public static File ensureModel(Context ctx, AssetManager am) {
        File out = new File(ctx.getFilesDir(), MODEL_FILE);
        String asset = assetPath(am, MODEL_ASSET);
        if (asset == null) {
            Log.e(TAG, "找不到模型 asset");
            return null;
        }
        try {
            // 已释放过就直接用(用长度粗略校验)
            InputStream probe = am.open(asset);
            long assetLen = probe.available();
            probe.close();
            if (out.isFile() && out.length() == assetLen && assetLen > 1000) {
                Log.i(TAG, "模型已释放过,直接复用: " + out.length() + " 字节");
                return out;
            }

            long t0 = System.currentTimeMillis();
            Log.i(TAG, "首次释放模型(" + (assetLen / 1048576) + " MB)…");
            InputStream is = am.open(asset);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.close();
            is.close();
            Log.i(TAG, "模型释放完成: " + (total / 1048576) + " MB, 耗时 "
                    + (System.currentTimeMillis() - t0) + " ms");
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "释放模型失败: " + t);
            return null;
        }
    }

    /**
     * 初始化引擎(阻塞,后台线程调用)。
     * 返回可用的引擎,失败返回 null(上层会退回到规则引擎)。
     */
    public static LlmEngine init(Context ctx, String systemPrompt) {
        try {
            AssetManager am = ctx.getAssets();
            File model = ensureModel(ctx, am);
            if (model == null) return null;

            // 用探测到的实际路径前缀(可能是 llm/ 也可能是 llm\)
            String probe = assetPath(am, "vocab.json");
            String prefix = DIR;
            if (probe != null) {
                int cut = Math.max(probe.lastIndexOf('/'), probe.lastIndexOf('\\'));
                if (cut > 0) prefix = probe.substring(0, cut);
            }
            Log.i(TAG, "分词器前缀: " + prefix);

            LlmEngine e = new LlmEngine();
            if (!e.load(model, am, prefix)) return null;
            return e;
        } catch (Throwable t) {
            Log.e(TAG, "初始化对话模型失败: " + t);
            return null;
        }
    }
}
