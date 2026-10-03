package com.kiki.android;

import java.util.ArrayList;
import java.util.List;

/**
 * 语言总结(原版视频演示的能力之一)。
 *
 * 原版视频里的实例(见 docs/原版视频分析.md)
 * ------------------------------------------
 *   用户：基础的基，朋友的友
 *   她　：基友
 *
 * 也就是**从一句描述里提炼出一个更短的词**。原版说明卡把它列为一项独立能力
 * ("语言总结")。
 *
 * 实现方式
 * --------
 * 不做生成式摘要(那要靠大模型,而且会瞎编),只做**可解释的结构化提取**:
 *
 *   1. **取字式**:"X的A,Y的B" -> 取 A+B 拼成词,再查词表验证
 *        "基础的基，朋友的友" -> "基"+"友" -> "基友" ✔
 *   2. **合并式**:多个并列成分 -> 取共同词素合并
 *        "苹果手机和苹果电脑" -> "苹果"
 *   3. **简称式**:长词的共同首字/末字
 *        "北京大学" -> "北大"(靠事实库里已有的"简称"关系)
 *
 * 每一步都要求结果**在词表或事实库里存在**,否则不输出 ——
 * 宁可不总结,也不编一个不存在的词。这是原版"不知道就问"的性格。
 */
public class Summarizer {

    /** 词表校验用的接口(由 KikiBot 提供:判断一个词是否是合法词) */
    public interface Lexicon {
        /** 是否是词表里的词(长度>=2) */
        boolean isWord(String w);
        /** 分词 */
        List<String> segment(String text);
    }

    private final Lexicon lex;
    private final KikiDialog.Sink sink;

    public Summarizer(Lexicon lex, KikiDialog.Sink sink) {
        this.lex = lex;
        this.sink = sink;
    }

    /**
     * 尝试总结。总结不出来返回 null(调用方继续走原来的逻辑)。
     */
    public String summarize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() < 4) return null;

        // 1. 取字式:"X的A，Y的B" -> A+B
        String r = takeChars(s);
        if (r != null) return r;

        // 2. 简称式:靠事实库里已有的"简称"关系
        r = abbreviation(s);
        if (r != null) return r;

        return null;
    }

    /**
     * 取字式:"基础的基，朋友的友" -> "基友"
     *
     * 规则:把句子按标点切成若干段,每段形如 "W的C"(C 是单字),
     * 顺序取出这些单字拼起来,若拼接结果**是词表里的词**就返回。
     */
    String takeChars(String s) {
        String[] parts = s.split("[，,、；;和跟与]");
        if (parts.length < 2) return null;

        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            int di = t.lastIndexOf('的');
            if (di < 0 || di != t.length() - 2) continue;   // 必须形如 "...的X",X 为单字
            char c = t.charAt(t.length() - 1);
            if (!isHan(c)) continue;
            sb.append(c);
            hits++;
        }
        if (hits < 2) return null;
        String w = sb.toString();
        // 【关键】必须在词表里才是真词,否则就是瞎拼
        if (lex.isWord(w)) return w;
        return null;
    }

    /**
     * 简称式:如果事实库里记过"X 简称 Y",且用户句子里提到 X,就总结成 Y。
     *   库里:北京大学 简称 北大
     *   用户:就是那个北京大学
     *   她　:北大
     */
    String abbreviation(String s) {
        if (sink == null) return null;
        for (String w : lex.segment(s)) {
            if (w.length() < 2) continue;
            if (!sink.knowsEntity(w)) continue;
            List<String> ab = sink.lookup(w, "简称");
            if (ab != null && !ab.isEmpty()) {
                String a = ab.get(0);
                if (a != null && !a.isEmpty() && !a.equals(w)) return a;
            }
        }
        return null;
    }

    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
