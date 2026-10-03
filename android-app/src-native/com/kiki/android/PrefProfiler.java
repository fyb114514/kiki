package com.kiki.android;

import android.util.Log;

import java.util.List;

/**
 * 用户偏好画像(特性 2)。
 *
 * 原版特性文档的说法:
 * > 通过本地加网络的方式实现学习,久而久之形成**各种用户偏好下的 kiki**
 *
 * 也就是说,她用久了应该"像为你定制的"。这里不做玄学,只做**可解释的统计**:
 *
 *   观察什么                      -> 学到什么偏好        -> 怎么用
 *   ------------------------------------------------------------------
 *   你每次说的话很短              -> style = 简洁        -> 她回话也短
 *   你每次说的话很长              -> style = 详细        -> 她回话更展开
 *   你经常问"为什么"              -> habit = 爱问为什么   -> 开场白/回应更偏解释
 *   你常提到的词                  -> topic = <词>        -> 她偶尔主动提起
 *
 * 偏好只影响**措辞**,不影响事实 —— 事实永远按优先级和来源判定。
 * 这样"个性化"不会污染知识正确性。
 */
public class PrefProfiler {

    private static final String TAG = "kiki-pref";

    /** 偏好方面 */
    public static final String ASPECT_STYLE = "style";    // 说话风格
    public static final String ASPECT_HABIT = "habit";    // 提问习惯
    public static final String ASPECT_TOPIC = "topic";    // 常聊话题

    private final Store store;

    public PrefProfiler(Store store) {
        this.store = store;
    }

    /**
     * 观察用户这一句话,更新画像。
     * 在每次收到识别结果时调用(很轻量,只有几次数据库自增)。
     */
    public void observe(String userText) {
        if (userText == null || userText.trim().isEmpty()) return;
        String t = userText.trim();

        // --- 1. 说话长度 -> 风格 ---
        int len = t.length();
        if (len <= 6) {
            store.countProfile(ASPECT_STYLE, "简洁");
        } else if (len >= 16) {
            store.countProfile(ASPECT_STYLE, "详细");
        } else {
            store.countProfile(ASPECT_STYLE, "适中");
        }

        // --- 2. 提问习惯 ---
        if (t.contains("为什么") || t.contains("为何") || t.contains("为啥")) {
            store.countProfile(ASPECT_HABIT, "爱问为什么");
        } else if (t.endsWith("?") || t.endsWith("？") || t.contains("吗")
                || t.contains("什么") || t.contains("谁")) {
            store.countProfile(ASPECT_HABIT, "爱提问");
        } else if (t.contains("不对") || t.contains("错了") || t.contains("不是")) {
            store.countProfile(ASPECT_HABIT, "爱纠正");
        }

        // --- 3. 常聊话题(简单分词:取 2-4 字的连续汉字片段) ---
        for (String w : keywords(t)) {
            store.countProfile(ASPECT_TOPIC, w);
            store.countTopic(w);      // 兼容旧的 topic 统计
        }
    }

    /**
     * 她根据偏好调整自己的措辞。
     *
     * @param reply 原本要说的内容
     * @return 调整后的内容(风格影响下)
     */
    public String adapt(String reply) {
        if (reply == null || reply.isEmpty()) return reply;
        String style = store.topProfile(ASPECT_STYLE);
        if (style == null) return reply;
        if ("简洁".equals(style)) {
            // 你说话短,她也不啰嗦:去掉开头的客套
            String r = reply;
            for (String p : new String[]{"唔…", "嗯…", "那个，", "其实，"}) {
                if (r.startsWith(p)) r = r.substring(p.length());
            }
            return r;
        }
        if ("详细".equals(style)) {
            // 你说话长,她多说一句背景
            String topic = store.topProfile(ASPECT_TOPIC);
            if (topic != null && reply.length() < 12 && !reply.contains(topic)) {
                return reply + "(你最近常聊「" + topic + "」)";
            }
        }
        return reply;
    }

    /** 她的开场白是否该提一句"我记得你" */
    public String memoryLine() {
        List<String> topics = store.topProfiles(ASPECT_TOPIC, 2);
        String habit = store.topProfile(ASPECT_HABIT);
        if (topics.isEmpty() && habit == null) return null;
        StringBuilder sb = new StringBuilder("你好像");
        if (habit != null) sb.append(habit);
        if (!topics.isEmpty()) {
            if (habit != null) sb.append("，而且");
            sb.append("总聊「").append(topics.get(0)).append("」");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 偏好画像摘要(日志/自检用) */
    public String describe() {
        String style = store.topProfile(ASPECT_STYLE);
        String habit = store.topProfile(ASPECT_HABIT);
        List<String> topics = store.topProfiles(ASPECT_TOPIC, 3);
        return "风格=" + style + " 习惯=" + habit + " 话题=" + topics;
    }

    /**
     * 极简关键词抽取:把连续的 2-4 字汉字片段当候选话题。
     * 不做真正分词(那要几 MB 词典),够用来做偏好统计。
     */
    static java.util.List<String> keywords(String t) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (isHan(c)) {
                cur.append(c);
            } else {
                flush(cur, out);
            }
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, java.util.List<String> out) {
        int n = cur.length();
        if (n >= 2) {
            // 长片段切成 2 字词(简单滑动)
            if (n <= 4) {
                out.add(cur.toString());
            } else {
                for (int i = 0; i + 2 <= n; i += 2) {
                    out.add(cur.substring(i, i + 2));
                }
            }
        }
        cur.setLength(0);
    }

    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
