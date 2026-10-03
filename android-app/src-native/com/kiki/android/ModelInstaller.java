package com.kiki.android;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 把 sherpa-onnx 的 Paraformer 模型从 assets 释放到私有目录。
 *
 * 为什么必须释放到磁盘
 * --------------------
 * aapt2 会把超过 1MB 的 asset **压缩**打包(213MB 的 onnx 必然被压)。
 * 而 ONNX Runtime 需要**内存映射**才能高效加载这个文件,
 * 压缩存放的 asset 没法直接 mmap。
 * 所以沿用已验证过的做法:首次启动释放到私有目录,之后按路径加载。
 *
 * ============================================================
 * 【模型指纹】为什么需要它
 * ============================================================
 * 最早的版本只检查"文件在不在"。后来把单语中文模型换成**双语 zh-en 模型**
 * (原版能力清单里有"中英文混合分辨")时踩了坑:
 * 文件名没变,旧的 213MB 模型一直躺在私有目录里,
 * isValid() 一看文件在就复用了 —— **新模型永远释放不出来**,
 * 表现是"代码改了、APK 也换了,识别行为却一点没变"。
 *
 * 现在 assets 里多放一个 model.id(由模型内容算出的指纹),
 * 释放时一并写入 .model.id;启动时比对:
 *   不一致 -> 删掉旧模型,重新释放
 * 这样就保证"换了模型一定会生效"。
 */
public class ModelInstaller {

    private static final String TAG = "kiki-asr";

    /** assets 下的目录 */
    private static final String ASSET_DIR = "asr";
    /** 释放到 files/model-paraformer/ */
    private static final String OUT_DIR = "model-paraformer";
    /** 需要的模型文件 */
    private static final String[] FILES = {"model.int8.onnx", "tokens.txt"};
    /** 模型指纹(内容标识),用于判断是否需要重新释放 */
    private static final String ID_ASSET = "model.id";
    private static final String ID_LOCAL = ".model.id";

    /**
     * 找到可用的模型目录;没有或**版本不符**就从 assets 重新释放。
     * 返回 null 表示不可用(用 {@link #describeProblem} 取原因)。
     */
    public static File find(Context context) {
        File out = new File(context.getFilesDir(), OUT_DIR);
        String want = readAssetId(context);
        String have = readLocalId(out);

        if (isValid(out) && (want == null || want.equals(have))) {
            Log.i(TAG, "复用已释放的模型(" + have + "): " + out.getAbsolutePath());
            return out;
        }
        if (want != null && !want.equals(have)) {
            Log.i(TAG, "模型已更新(" + have + " -> " + want + "),重新释放");
            clear(out);
        }
        install(context, out, want);
        return isValid(out) ? out : null;
    }

    /** 读出 assets 里的模型指纹;读不到返回 null */
    private static String readAssetId(Context context) {
        BufferedReader br = null;
        try {
            InputStream is = openAsset(context, ID_ASSET);
            if (is == null) return null;
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = br.readLine();
            return (line == null) ? null : line.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    /** 读本地已释放的模型指纹 */
    private static String readLocalId(File dir) {
        BufferedReader br = null;
        try {
            File f = new File(dir, ID_LOCAL);
            if (!f.isFile()) return null;
            br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line = br.readLine();
            return (line == null) ? null : line.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    /** 删掉旧的模型文件(换模型时用) */
    private static void clear(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.delete()) Log.i(TAG, "  已删除旧模型文件 " + f.getName());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "清理旧模型失败: " + t);
        }
    }

    /**
     * 打开 assets 里的文件。
     *
     * 【aapt2 反斜杠坑】打包后 asset 名会变成 "asr\\model.int8.onnx"
     * (目录分隔符是**反斜杠**),用正斜杠 open 会 FileNotFoundException。
     * 实测:APK 里条目名就是 assets/asr\model.int8.onnx。
     * 所以两种写法都试一遍,哪种能开用哪种。
     */
    private static InputStream openAsset(Context context, String name) {
        String[] cands = {ASSET_DIR + "/" + name, ASSET_DIR + "\\" + name, name};
        for (String cand : cands) {
            try {
                InputStream is = context.getAssets().open(cand);
                Log.i(TAG, "  用路径打开成功: " + cand);
                return is;
            } catch (Throwable ignored) {
            }
        }
        Log.e(TAG, "asset 打不开: " + name + " (试过 " + java.util.Arrays.toString(cands) + ")");
        return null;
    }

    /** 模型文件都在且非空才算有效 */
    public static boolean isValid(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        for (String f : FILES) {
            File x = new File(dir, f);
            if (!x.isFile() || x.length() < 1024) return false;
        }
        return true;
    }

    /** 从 assets/asr/ 释放到私有目录 */
    private static void install(Context context, File out, String id) {
        long t0 = System.currentTimeMillis();
        try {
            if (!out.isDirectory() && !out.mkdirs()) {
                Log.e(TAG, "无法创建目录: " + out);
                return;
            }
            for (String name : FILES) {
                File dst = new File(out, name);
                Log.i(TAG, "释放模型文件: " + name);
                InputStream is = openAsset(context, name);
                if (is == null) return;
                FileOutputStream fos = new FileOutputStream(dst);
                byte[] buf = new byte[1 << 16];
                int n;
                long total = 0;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                fos.close();
                is.close();
                Log.i(TAG, "  " + name + " -> " + (total / 1048576) + " MB");
            }
            // 写指纹:下次启动靠它判断要不要重新释放
            if (id != null) {
                FileOutputStream fo = new FileOutputStream(new File(out, ID_LOCAL));
                fo.write((id + "\n").getBytes("UTF-8"));
                fo.close();
                Log.i(TAG, "  指纹已写入: " + id);
            }
            Log.i(TAG, "模型释放完成,耗时 " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable e) {
            Log.e(TAG, "释放模型失败: " + e);
        }
    }

    /** 不可用时给出一句可读的原因 */
    public static String describeProblem(Context context) {
        File out = new File(context.getFilesDir(), OUT_DIR);
        StringBuilder sb = new StringBuilder("语音模型不可用:");
        for (String f : FILES) {
            File x = new File(out, f);
            sb.append(" ").append(f).append("=")
              .append(x.isFile() ? (x.length() / 1048576 + "MB") : "缺失");
        }
        sb.append(" id=").append(readLocalId(out));
        return sb.toString();
    }
}
