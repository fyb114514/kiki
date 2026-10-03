package com.marisa.kiki;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 离线语音模型的位置解析、首次解压与自检。
 *
 * 模型**打进 APK 的 assets**(`assets/model-cn.zip`,约 42MB),
 * 首次启动时解压到应用私有目录,之后直接用。这样用户不需要手动推模型,
 * 代价是安装包变大(3.3MB → 约 46MB)—— 这是刻意的取舍。
 *
 * 解压目标:`<应用私有目录>/model/vosk-model-small-cn-0.22/`
 * 模型必需结构(缺一不可):
 *     am/final.mdl        声学模型
 *     conf/mfcc.conf      特征配置
 *     解码图(两种布局任一):
 *         graph/HCLG.fst                合并式
 *         graph/Gr.fst + graph/HCLr.fst 分离式(官方 small-cn 用的是这个)
 */
public class ModelInstaller {

    private static final String TAG = "kiki-vosk";
    private static final String DIR_NAME = "vosk-model-small-cn-0.22";
    /** APK assets 里的模型压缩包 */
    private static final String ASSET_ZIP = "model-cn.zip";

    /**
     * 必需文件(相对模型根目录)。
     *
     * 注意:解码图有两种常见布局,必须都接受 ——
     *   a) 合并式:graph/HCLG.fst
     *   b) 分离式:graph/Gr.fst + graph/HCLr.fst
     * 实测官方 vosk-model-small-cn-0.22 用的是 (b),一开始我只校验 HCLG.fst,
     * 结果把好模型判成了"缺文件"。
     */
    private static final String[] REQUIRED_ALWAYS = {
            "am/final.mdl",
            "conf/mfcc.conf",
    };
    private static final String[][] GRAPH_ALTERNATIVES = {
            {"graph/HCLG.fst"},
            {"graph/Gr.fst", "graph/HCLr.fst"},
    };

    /**
     * 找到可用的模型:先看已解压的,再尝试从 assets 解压一次。
     * 返回 null 表示不可用(用 describeProblem 拿到原因)。
     */
    public static File find(Context context) {
        // 1. 已经解压过(或之前用 adb 推过)的
        File internal = new File(context.getFilesDir(), "model/" + DIR_NAME);
        if (isValid(internal)) {
            Log.i(TAG, "使用已就绪的模型: " + internal.getAbsolutePath());
            return internal;
        }

        // 2. 之前用 adb 推到外部目录的也算数(兼容旧安装)
        File external = new File(context.getExternalFilesDir(null), "model/" + DIR_NAME);
        if (isValid(external)) {
            Log.i(TAG, "使用外部目录的模型: " + external.getAbsolutePath());
            return external;
        }

        // 3. 首次运行:从 assets 解压
        File extracted = installFromAssets(context);
        if (isValid(extracted)) return extracted;

        return null;
    }

    /**
     * 把 assets/model-cn.zip 解压到私有目录。
     * zip 里的顶层目录名是 vosk-model-small-cn-0.22,解到 model/ 下即得到
     * model/vosk-model-small-cn-0.22/... —— 与 DIR_NAME 一致。
     */
    private static File installFromAssets(Context context) {
        File outRoot = new File(context.getFilesDir(), "model");
        ZipInputStream zis = null;
        InputStream is = null;
        try {
            long t0 = System.currentTimeMillis();
            Log.i(TAG, "首次运行:开始从 assets 解压语音模型…");
            is = context.getAssets().open(ASSET_ZIP);
            zis = new ZipInputStream(is);
            byte[] buf = new byte[65536];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 关键:zip 条目名可能是 Windows 风格的反斜杠(用 .NET 的
                // CreateFromDirectory 造包时就是 `vosk-model-small-cn-0.22\am\final.mdl`)。
                // ZipInputStream 不保证把 `\` 规范化成 `/`,不处理的话会解出
                // 字面量文件名 `am\final.mdl`,自检必然失败、每次都重新解压。
                String name = entry.getName().replace('\\', '/');
                // 防目录穿越:拒绝绝对路径与 ..
                if (name.contains("..") || name.startsWith("/")) {
                    Log.w(TAG, "跳过可疑条目: " + name);
                    continue;
                }
                File target = new File(outRoot, name);
                if (entry.isDirectory() || name.endsWith("/")) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(target);
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                zis.closeEntry();
            }
            Log.i(TAG, "模型解压完成,耗时 " + (System.currentTimeMillis() - t0) + " ms");
            return new File(outRoot, DIR_NAME);
        } catch (Throwable t) {
            Log.e(TAG, "从 assets 解压模型失败: " + t);
            return null;
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Throwable ignored) {
            }
            try {
                if (is != null) is.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 判断一个目录是否是完整的模型 */
    public static boolean isValid(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        for (String rel : REQUIRED_ALWAYS) {
            if (!new File(dir, rel).isFile()) return false;
        }
        // 解码图:任一种布局成立即可
        for (String[] alt : GRAPH_ALTERNATIVES) {
            boolean all = true;
            for (String rel : alt) {
                if (!new File(dir, rel).isFile()) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    /** 出问题时的提示(模型已内置在 APK,所以这里只报告解压/自检失败) */
    public static String describeProblem(Context context) {
        File d = new File(context.getFilesDir(), "model/" + DIR_NAME);
        StringBuilder sb = new StringBuilder();
        sb.append("离线语音模型不可用(应从 APK 内置资源自动解压)。期望位置: ")
                .append(d.getAbsolutePath());
        sb.append(" 需要的文件: am/final.mdl, conf/mfcc.conf, 以及解码图 ")
                .append("(graph/HCLG.fst 或 graph/Gr.fst+graph/HCLr.fst)");
        if (d.isDirectory()) {
            sb.append(" 该目录存在,但缺少: ");
            for (String rel : REQUIRED_ALWAYS) {
                if (!new File(d, rel).isFile()) sb.append(rel).append(" ");
            }
            if (!graphOk(d)) sb.append("解码图(graph/HCLG.fst 或 Gr.fst+HCLr.fst)");
        } else {
            sb.append(" 目录不存在 —— 可能解压失败或存储空间不足");
        }
        return sb.toString();
    }

    /** 解码图是否齐全(两种布局任一) */
    private static boolean graphOk(File d) {
        for (String[] alt : GRAPH_ALTERNATIVES) {
            boolean all = true;
            for (String rel : alt) {
                if (!new File(d, rel).isFile()) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }
}

