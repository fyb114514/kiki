package com.kiki.android;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 100 轮真实对话测试。
 *
 * 与上一版的区别(很重要)
 * ----------------------
 * 上一版只提问、从不回答 —— 于是她反问后,下一句问句被她当成了"答案",
 * 记下"量子力学 | 是 | 红楼梦很出名"这种垃圾,后面互相污染,
 * 看起来像 AI 崩了,其实是**测试不是对话**。
 *
 * 这一版按真实对话推进:她反问 -> 我给一个合理身份 -> 继续。
 *
 * 三类问题(用户指定重点):是什么 / 为什么 / 怎么做。
 * 结果写 logcat, tag = kiki-100。
 */
public class Dialog100Test {

    private static final String TAG = "kiki-100";

    /** 提问模板 */
    private static final String[] ASK_WHAT = {
            "%s是什么", "%s是谁", "%s有什么特点", "%s在哪里",
    };
    private static final String[] ASK_WHY = {
            "为什么%s很出名", "为什么%s重要", "%s为什么存在",
    };
    private static final String[] ASK_HOW = {
            "%s怎么做", "怎么做%s", "如何%s", "%s怎么用",
    };

    /** 实体表:有些是真实概念,有些是生造词(看生造词时她的反应) */
    private static final String[] SUBJECTS = {
            "量子力学", "康神", "微波炉", "红楼梦", "咖啡",
            "三角函数", "长城", "光合作用", "比特币", "孙悟空",
    };

    /** 她反问"X 是谁"时,我给的合理身份(模拟真人回答) */
    private static final String[] IDENTITIES = {
            "一门物理学科", "一只猫", "一种家用电器", "一本小说", "一种饮料",
            "数学工具", "世界文化遗产", "植物的生理过程", "一种数字货币", "神话人物",
    };

    private static final StringBuilder TRANSCRIPT = new StringBuilder();
    private static int round = 0;

    /** 统计各类回答的条数 */
    private static final HashMap<String, Integer> SHAPES = new HashMap<String, Integer>();

    private static void rec(String ask, Answer a) {
        round++;
        String kind = (a == null) ? "null" : a.kind;
        String text = (a == null) ? "" : a.text;
        String line = String.format("%03d | %-20s | [%s] %s", round, ask, kind, text);
        Log.i(TAG, line);
        TRANSCRIPT.append(line).append("\n");
        tally(kind, text);
    }

    /** 把回答归类,直接回答"是不是只会问…出名吗" */
    private static void tally(String kind, String text) {
        String shape;
        if (text.contains("出名的吗") || text.contains("出名吗")) {
            shape = "★ 是…让…出名吗？(用户抱怨的那句)";
        } else if (text.startsWith("我不知道") && text.contains("是谁")) {
            shape = "我不知道，X是谁？";
        } else if (text.startsWith("我查不到")) {
            shape = "我查不到「X」";
        } else if (text.startsWith("这个我还没学会")) {
            shape = "这个我还没学会(怎么做)";
        } else if (kind != null && kind.contains("ask")) {
            shape = "其它反问";
        } else if (kind != null && kind.startsWith("describe")) {
            shape = "【描述性回答】";
        } else if (kind != null && kind.startsWith("causal")) {
            shape = "【因果回答】";
        } else if ("明白了".equals(text)) {
            shape = "明白了(接受纠正)";
        } else {
            shape = "【其它回答】kind=" + kind;
        }
        Integer n = SHAPES.get(shape);
        SHAPES.put(shape, n == null ? 1 : n + 1);
    }

    /**
     * 问一句,并且**像真人那样应对她的反问**。
     * 最多追 4 层,避免无限循环。
     */
    private static void ask(KikiBrain brain, String q, int identityIdx) {
        String cur = q;
        for (int depth = 0; depth < 4; depth++) {
            Answer a = brain.reply(cur);
            rec(cur, a);

            String t = a == null ? "" : a.text;
            String k = a == null ? "" : a.kind;

            // 她反问"X 是谁" -> 给身份(这就是"教她")
            if (t.contains("是谁？") || (k != null && k.contains("ask") && t.contains("是谁"))) {
                cur = IDENTITIES[identityIdx % IDENTITIES.length];
                continue;
            }
            // 她追问"是…让…出名吗" -> 答"是的"
            if (t.contains("出名吗") || k.contains("learn-dialog")) {
                cur = "是的";
                continue;
            }
            // 她问"这个我还不知道，你告诉我？"
            if (t.contains("你告诉我") || t.contains("你教教我")) {
                cur = IDENTITIES[identityIdx % IDENTITIES.length];
                continue;
            }
            break;   // 正常回答了,结束这一轮
        }
    }

    public static void run(Context ctx) {
        Log.i(TAG, "===== 100 轮真实对话测试开始 =====");
        TRANSCRIPT.setLength(0);
        SHAPES.clear();
        round = 0;

        // 每次全新库:上一版的垃圾会污染这一版,实测踩过
        try {
            ctx.deleteDatabase("kiki-test-100.db");
            Log.i(TAG, "已清空测试库,从零开始");
        } catch (Throwable ignored) {
        }

        Store store = null;
        try {
            store = new Store(ctx, "kiki-test-100.db");
            KikiBot bot = new KikiBot(ctx.getAssets());
            bot.setStore(store);
            KikiBrain brain = new KikiBrain(ctx, bot, bot.getBrain());
            brain.setStore(store);
            brain.restoreFacts();

            int i = 0;
            // 33 组 × 3 类 = 99 轮,再补 1 轮收尾
            for (int g = 0; g < 33; g++) {
                String subj = SUBJECTS[g % SUBJECTS.length];

                ask(brain, String.format(ASK_WHAT[g % ASK_WHAT.length], subj), g);
                i++;
                ask(brain, String.format(ASK_WHY[g % ASK_WHY.length], subj), g + 1);
                i++;
                ask(brain, String.format(ASK_HOW[g % ASK_HOW.length], subj), g + 2);
                i++;
            }
            ask(brain, "谢谢", 0);
            i++;

            Log.i(TAG, "----- 回答类型分布 -----");
            int total = 0;
            for (Integer v : SHAPES.values()) total += v;
            for (Map.Entry<String, Integer> e : SHAPES.entrySet()) {
                Log.i(TAG, String.format("  %3d 条 (%2.0f%%)  %s",
                        e.getValue(), 100.0 * e.getValue() / Math.max(1, total), e.getKey()));
            }
            Log.i(TAG, "  合计 " + total + " 条回答");

            // 事实库健康度:有没有垃圾主语混进去
            int dirty = 0, facts = 0;
            for (String[] f : store.loadFacts()) {
                facts++;
                if (!KikiBrain.looksLikeEntity(f[0])) dirty++;
            }
            Log.i(TAG, "----- 事实库健康度 -----");
            Log.i(TAG, "  共 " + facts + " 条, 其中主语不像实体的 " + dirty + " 条");

        } catch (Throwable t) {
            Log.e(TAG, "测试异常: " + t);
        } finally {
            try { if (store != null) store.close(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "===== 100 轮真实对话测试结束, 共 " + round + " 轮 =====");
    }
}
