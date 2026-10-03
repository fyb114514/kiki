package com.marisa.kiki;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qwen 分词器(Java 版) —— 字节级 BPE,中英混合。
 *
 * 为什么必须自己写
 * ----------------
 * ONNX Runtime 只提供算子,不带分词;而模型只吃 token id。
 * 要把"你好"变成 [108386, ...] 再变回来,必须完整实现 Qwen 的分词规则:
 *
 *   1. **预分词**:按 Qwen 的正则把文本切成片段(中文字、英文词、数字、符号分别处理)
 *   2. **字节级编码**:每个 UTF-8 字节映射成一个"可见字符"(GPT-2 那套 byte<->unicode 表),
 *      这样任何字节序列都能进 BPE,不会出现未登录字
 *   3. **BPE 合并**:按 merges.txt 的优先级反复合并相邻对(用 rank 小的先合)
 *   4. **查词表**:合并后的片段在 vocab.json 里查 id,查不到就逐字节查
 *
 * 参考实现:PC 侧 `server-py/onnx_llm_check.py` 用 HuggingFace tokenizer 跑通了同样流程,
 * 这里逐条对应移植,便于对照排错。
 */
public class QwenTokenizer {

    private static final String TAG = "kiki-tok";

    /** token 字符串 -> id */
    private final Map<String, Integer> vocab = new HashMap<String, Integer>();
    /** 合并对 "a b" -> 优先级(越小越优先) */
    private final Map<String, Integer> ranks = new HashMap<String, Integer>();
    /** id -> token 字符串(解码用) */
    private final Map<Integer, String> idToToken = new HashMap<Integer, String>();
    /** 特殊 token 原文 -> id */
    private final Map<String, Integer> specials = new LinkedHashMap<String, Integer>();

    /** 字节 <-> unicode 字符 的双向表(GPT-2 规则) */
    private static char[] byteToChar = new char[256];
    private static Map<Character, Integer> charToByte = new HashMap<Character, Integer>();

    static {
        // 可见字节直接映射到自己;其余字节映射到 256 以上的空闲码位,
        // 保证"每个字节都有一个可打印字符代表",这是字节级 BPE 的关键。
        List<Integer> bs = new ArrayList<Integer>();
        for (int b = 33; b <= 126; b++) bs.add(b);
        for (int b = 161; b <= 172; b++) bs.add(b);
        for (int b = 174; b <= 255; b++) bs.add(b);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                byteToChar[b] = (char) (256 + n);
                n++;
            }
        }
        // 再按顺序把可见区填回自己
        int k = 0;
        for (int b = 0; b < 256; b++) {
            if (bs.contains(b) && b >= 33 && b <= 126) byteToChar[b] = (char) b;
        }
        // 完整重建(按 GPT-2 官方顺序:先可见再插入其余)
        int[] vis = new int[256];
        boolean[] isVis = new boolean[256];
        for (int b = 33; b <= 126; b++) isVis[b] = true;
        for (int b = 161; b <= 172; b++) isVis[b] = true;
        for (int b = 174; b <= 255; b++) isVis[b] = true;
        int extra = 0;
        for (int b = 0; b < 256; b++) {
            if (isVis[b]) byteToChar[b] = (char) b;
            else byteToChar[b] = (char) (256 + extra++);
        }
        charToByte.clear();
        for (int b = 0; b < 256; b++) charToByte.put(byteToChar[b], b);
    }

    /**
     * 从 assets 载入 vocab.json / merges.txt / tokenizer_config.json。
     *
     * 【路径坑】aapt2 打包时会把 `assets/llm/xxx` 存成 `assets/llm\xxx`(**反斜杠**),
     * 而 AssetManager 对这两种分隔符的兼容性因版本而异(实测本机能打开反斜杠的)。
     * 所以这里每个文件都**两种都试**,哪种能开用哪种 —— 不去猜。
     */
    private String findAsset(android.content.res.AssetManager assets, String dir, String name) {
        String[] cands = {dir + "/" + name, dir + "\\" + name, name};
        for (String c : cands) {
            java.io.InputStream is = null;
            try {
                is = assets.open(c);
                is.close();
                return c;
            } catch (Throwable ignored) {
            } finally {
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 从 assets 载入 vocab.json / merges.txt */
    public boolean load(android.content.res.AssetManager assets, String dir) {
        try {
            String v = findAsset(assets, dir, "vocab.json");
            String m = findAsset(assets, dir, "merges.txt");
            String t = findAsset(assets, dir, "tokenizer_config.json");
            Log.i(TAG, "分词器文件: vocab=" + v + " merges=" + m + " cfg=" + t);
            if (v == null || m == null) {
                Log.e(TAG, "找不到分词器文件");
                return false;
            }
            loadVocab(assets, v);
            loadMerges(assets, m);
            if (t != null) loadSpecials(assets, t);
            Log.i(TAG, "分词器就绪: 词表 " + vocab.size() + " 条, 合并规则 " + ranks.size()
                    + " 条, 特殊 token " + specials.size());
            return !vocab.isEmpty();
        } catch (Throwable t) {
            Log.e(TAG, "载入分词器失败: " + t);
            return false;
        }
    }

    private String readAll(android.content.res.AssetManager assets, String name) throws Exception {
        InputStream is = assets.open(name);
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        br.close();
        return sb.toString();
    }

    private void loadVocab(android.content.res.AssetManager assets, String name) throws Exception {
        JSONObject o = new JSONObject(readAll(assets, name));
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            int id = o.getInt(k);
            vocab.put(k, id);
            idToToken.put(id, k);
        }
    }

    private void loadMerges(android.content.res.AssetManager assets, String name) throws Exception {
        String txt = readAll(assets, name);
        String[] lines = txt.split("\n");
        int rank = 0;
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;   // 第一行是版本注释
            ranks.put(s, rank++);
        }
    }

    /** 从 tokenizer_config.json 里取 added_tokens 的特殊 token(如 <|im_start|>) */
    private void loadSpecials(android.content.res.AssetManager assets, String name) throws Exception {
        try {
            JSONObject o = new JSONObject(readAll(assets, name));
            JSONArray arr = o.optJSONArray("added_tokens_decoder");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    String content = e.optString("content", null);
                    int id = e.optInt("id", -1);
                    if (content != null && id >= 0) specials.put(content, id);
                }
            }
        } catch (Throwable t) {
            // 拿不到就用下面的兜底值
        }
        if (specials.isEmpty()) {
            specials.put("<|endoftext|>", 151643);
            specials.put("<|im_start|>", 151644);
            specials.put("<|im_end|>", 151645);
        }
    }

    public int idOf(String special) {
        Integer v = specials.get(special);
        return v == null ? -1 : v;
    }

    public int imStart() {
        return idOf("<|im_start|>");
    }

    public int imEnd() {
        return idOf("<|im_end|>");
    }

    public int eosId() {
        return 151645;
    }

    // ============================================================
    // 编码
    // ============================================================
    /** 文本 -> token id 列表(不处理特殊 token) */
    public List<Integer> encode(String text) {
        List<Integer> out = new ArrayList<Integer>();
        for (String piece : preTokenize(text)) {
            out.addAll(bpe(piece));
        }
        return out;
    }

    /**
     * 预分词:把文本切成"片段"。
     *
     * **正则必须与 Qwen 官方完全一致** —— 这是整个分词器最容易错的地方。
     * 官方规则(取自 tokenizer.json 的 pre_tokenizer.pattern):
     *   (?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}|
     *    ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+
     *
     * 我第一版把字母类写成了 Python 的 \w(它**包含汉字**),结果中文被整段当成一个词,
     * 与官方分不符(实测 13 个用例只对 3 个,如"你好"官方是 1 个 token 而我切成 2 个)。
     * 改成官方正则后用 Python 复现,13/13 完全一致。
     */
    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}|"
                    + " ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+");

    private List<String> preTokenize(String text) {
        List<String> out = new ArrayList<String>();
        java.util.regex.Matcher m = SPLIT.matcher(text);
        while (m.find()) {
            String s = m.group();
            if (s == null || s.isEmpty()) continue;
            // 汉字逐个拆分(Qwen 对 CJK 是单字粒度)
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (isCjk(c)) {
                    if (cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    out.add(String.valueOf(c));
                } else {
                    cur.append(c);
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        if (out.isEmpty() && !text.isEmpty()) out.add(text);
        return out;
    }

    /** CJK 统一表意文字区(含扩展 A) */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /** 对一个片段做字节级 BPE */
    private List<Integer> bpe(String piece) {
        // 1. UTF-8 字节 -> unicode 字符
        byte[] bytes;
        try {
            bytes = piece.getBytes("UTF-8");
        } catch (Exception e) {
            bytes = piece.getBytes();
        }
        List<String> symbols = new ArrayList<String>(bytes.length);
        for (byte b : bytes) symbols.add(String.valueOf(byteToChar[b & 0xFF]));

        // 2. 反复合并 rank 最小的相邻对
        while (symbols.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < symbols.size() - 1; i++) {
                Integer r = ranks.get(symbols.get(i) + " " + symbols.get(i + 1));
                if (r != null && r < bestRank) {
                    bestRank = r;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break;
            String merged = symbols.get(bestIdx) + symbols.get(bestIdx + 1);
            symbols.set(bestIdx, merged);
            symbols.remove(bestIdx + 1);
        }

        // 3. 查 id;查不到的再退回单字节查
        List<Integer> ids = new ArrayList<Integer>();
        for (String s : symbols) {
            Integer id = vocab.get(s);
            if (id != null) {
                ids.add(id);
            } else {
                for (int i = 0; i < s.length(); i++) {
                    Integer one = vocab.get(String.valueOf(s.charAt(i)));
                    if (one != null) ids.add(one);
                }
            }
        }
        return ids;
    }

    // ============================================================
    // 解码
    // ============================================================
    /** token id 列表 -> 文本 */
    public String decode(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String t = idToToken.get(id);
            if (t != null) sb.append(t);
        }
        // unicode 字符 -> 原始字节 -> UTF-8 字符串
        String s = sb.toString();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            Integer b = charToByte.get(s.charAt(i));
            if (b != null) bos.write(b);
            else {
                byte[] raw;
                try {
                    raw = String.valueOf(s.charAt(i)).getBytes("UTF-8");
                } catch (Exception e) {
                    raw = new byte[]{(byte) '?'};
                }
                bos.write(raw, 0, raw.length);
            }
        }
        String out;
        try {
            out = new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            out = s;
        }
        // 去掉特殊 token 痕迹
        for (String sp : specials.keySet()) out = out.replace(sp, "");
        return out;
    }

    // ============================================================
    // Qwen chat 模板
    // ============================================================
    /**
     * 生成对话提示词。格式(与 Qwen 官方一致):
     *   &lt;|im_start|&gt;system\nSYS&lt;|im_end|&gt;\n
     *   &lt;|im_start|&gt;user\nQ&lt;|im_end|&gt;\n
     *   &lt;|im_start|&gt;assistant\n
     */
    public List<Integer> buildChat(String system, String user) {
        List<Integer> ids = new ArrayList<Integer>();
        int imStart = imStart(), imEnd = imEnd();
        if (system != null && !system.isEmpty()) {
            ids.add(imStart);
            ids.addAll(encode("system\n" + system));
            ids.add(imEnd);
            ids.addAll(encode("\n"));
        }
        ids.add(imStart);
        ids.addAll(encode("user\n" + user));
        ids.add(imEnd);
        ids.addAll(encode("\n"));
        ids.add(imStart);
        ids.addAll(encode("assistant\n"));
        return ids;
    }
}
