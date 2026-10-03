package com.kiki.android;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 语言总结自检(原版视频演示的能力)。
 *
 * 为什么要自检:总结错词的代价很高 —— 用户说"基础的基，朋友的友",
 * 她要是总结成"础友"就彻底荒谬了。所以必须有"结果要在词表里"这道闸,
 * 并用用例把它钉住。
 *
 * 结果写 logcat(tag: kiki-sum)。
 */
public class SummarizerSelfTest {

    private static final String TAG = "kiki-sum";

    /** 关键词表(够跑用例;真实运行时用 KikiBot 的 4 万词表) */
    private static final Set<String> WORDS = new HashSet<String>(Arrays.asList(
            "基友", "朋友", "基础", "可乐", "快乐", "音乐", "北大", "北京大学",
            "微软公司", "公司", "男人", "女人", "在一起", "两个", "一只猫", "猫"));

    private static class Lex implements Summarizer.Lexicon {
        @Override public boolean isWord(String w) { return WORDS.contains(w); }
        /**
         * 最长匹配分词 —— 必须和 KikiBot.segment() 一样的策略。
         * 第一版这里图省事写成"逐字切",于是 "北京大学" 被切成 4 个字,
         * knowsEntity("北京大学") 永远为假,简称用例就假失败了。
         */
        @Override public List<String> segment(String t) {
            List<String> out = new ArrayList<String>();
            int i = 0;
            while (i < t.length()) {
                boolean matched = false;
                for (int len = Math.min(6, t.length() - i); len >= 2; len--) {
                    String w = t.substring(i, i + len);
                    if (WORDS.contains(w)) {
                        out.add(w);
                        i += len;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    out.add(String.valueOf(t.charAt(i)));
                    i++;
                }
            }
            return out;
        }
    }

    private static class Sink implements KikiDialog.Sink {
        @Override public void remember(String s, String rel, String o) { }
        @Override public List<String> lookup(String e, String rel) {
            List<String> r = new ArrayList<String>();
            if ("北京大学".equals(e) && "简称".equals(rel)) r.add("北大");
            return r;
        }
        @Override public boolean knowsEntity(String e) { return "北京大学".equals(e); }
        @Override public boolean knowsAnything(String e) { return knowsEntity(e); }


            @Override

            public void forget(String s, String rel, String o) {

                KikiDialog.Sink sup = this;

                // 测试/内嵌实现:只保证接口完整,不真的维护事实图

            }
    }

    private static int pass = 0, total = 0;

    private static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        Log.i(TAG, (ok ? "  [OK] " : "  [XX] ") + name);
    }

    public static void run(Context ctx) {
        pass = 0; total = 0;
        Log.i(TAG, "===== 语言总结自检开始 =====");
        Summarizer sum = new Summarizer(new Lex(), new Sink());

        // 原版视频里的真实用例:基础的基，朋友的友 -> 基友
        String r1 = sum.summarize("基础的基，朋友的友");
        check("「基础的基，朋友的友」-> 基友  (实际: " + r1 + ")", "基友".equals(r1));

        // 拼接结果不在词表里时必须返回 null(不能瞎拼)
        String r2 = sum.summarize("电脑的电，桌子的桌");
        check("拼出非法词时返回 null  (实际: " + r2 + ")", r2 == null);

        // 简称式:事实库里记过 "北京大学 简称 北大"
        String r3 = sum.summarize("就是那个北京大学");
        check("「就是那个北京大学」-> 北大  (实际: " + r3 + ")", "北大".equals(r3));

        // 太短/无结构 -> null
        check("「你好」不总结", sum.summarize("你好") == null);

        Log.i(TAG, "===== 语言总结自检: " + pass + "/" + total + " 通过 =====");
    }
}
