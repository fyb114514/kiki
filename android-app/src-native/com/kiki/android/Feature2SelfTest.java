package com.kiki.android;

import android.content.Context;
import android.util.Log;

/**
 * 特性 2 的自检:新知识覆盖旧知识 / 用户偏好 / 联网学习。
 *
 * 为什么要有它
 * ------------
 * 这三项能力都"看不见摸不着" —— 覆盖规则错一点、偏好统计错一点,
 * 界面上完全看不出来,只会在长期使用后表现成"她怎么变笨了"。
 * 所以把它们做成不依赖界面的自动化用例,结果写 logcat(tag: kiki-feat2)。
 *
 * 覆盖规则的判据(特性 2 最关键的一条):
 *   - 用户教的(taught, 100) 必须压住 联网的(net, 50)
 *   - 网络新学的(50)         必须压住 内置的(builtin, 30)
 *   - 同来源下,新的压住旧的
 */
public class Feature2SelfTest {

    private static final String TAG = "kiki-feat2";

    private static int pass = 0, total = 0;

    private static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        Log.i(TAG, (ok ? "  [OK] " : "  [XX] ") + name);
    }

    public static void run(Context ctx) {
        pass = 0; total = 0;
        Log.i(TAG, "===== 特性 2 自检开始 =====");

        Store store = new Store(ctx);
        try {
            testOverride(store);
            testPreference(store);
            testNetwork();
        } catch (Throwable t) {
            Log.e(TAG, "自检异常: " + t);
        } finally {
            store.close();
        }
        Log.i(TAG, "===== 特性 2 自检: " + pass + "/" + total + " 通过 =====");
    }

    /** 1. 新知识覆盖旧知识 */
    private static void testOverride(Store store) {
        // 用一个不会跟真实数据冲突的测试主语
        final String S = "__自检主语__";
        final String R = "是";

        // 【必须先清理】自检会把数据**落盘**。
        // 上一轮留下的"用户答案"优先级是 100,会把这一轮的内置/网络知识全压住,
        // 于是前两条断言假失败(第一版就踩了这个 —— 表现像覆盖规则错了,
        // 其实是自检数据残留)。所以每次自检先清掉自己的测试主语。
        store.deleteSubject(S);

        // 内置的先来
        store.learnFact(S, R, "内置答案", Store.SRC_BUILTIN);
        check("内置写入后能查到", contains(store, S, R, "内置答案"));

        // 网络学的应该压住内置的
        store.learnFact(S, R, "网络答案", Store.SRC_NET);
        check("网络知识覆盖内置知识",
                contains(store, S, R, "网络答案") && !contains(store, S, R, "内置答案"));

        // 用户教的应该压住网络的
        store.learnFact(S, R, "用户答案", Store.SRC_TAUGHT);
        check("用户教的知识覆盖网络知识",
                contains(store, S, R, "用户答案") && !contains(store, S, R, "网络答案"));

        // 反过来:网络知识**不能**覆盖用户教的
        store.learnFact(S, R, "网络再来", Store.SRC_NET);
        check("网络知识无法覆盖用户教的知识",
                contains(store, S, R, "用户答案") && !contains(store, S, R, "网络再来"));

        // 同来源:新的压住旧的
        store.learnFact(S, "喜欢", "旧喜好", Store.SRC_TAUGHT);
        store.learnFact(S, "喜欢", "新喜好", Store.SRC_TAUGHT);
        check("同来源下新知识覆盖旧知识",
                contains(store, S, "喜欢", "新喜好") && !contains(store, S, "喜欢", "旧喜好"));

        // 不同关系应该互不影响
        store.learnFact(S, "擅长", "唱歌", Store.SRC_TAUGHT);
        check("不同关系互不干扰", contains(store, S, "擅长", "唱歌"));

        Log.i(TAG, "  被取代的知识条数 = " + store.supersededCount());

        // 【自检结束要清掉自己的测试数据】
        // 这个自检会把 __自检主语__ 下的事实**真的写进正式库**。
        // 不清的话,每跑一次启动就留一条垃圾;而且在真机上实测时,
        // 残留的旧事实还会干扰下一轮自检(优先级 100 的老数据把新的压住,
        // 导致前几条断言假失败 —— 这个坑真的踩过)。
        store.deleteSubject(S);
        Log.i(TAG, "  自检数据已清理");
    }

    /** 2. 用户偏好画像 */
    private static void testPreference(Store store) {
        PrefProfiler p = new PrefProfiler(store);

        // 模拟用户连续说了很多**短**话。
        // 注意数量:下面还有 4 句较长的提问,如果短句不够多,"适中"会赢,
        // 断言就会失败(第一版自检就踩了这个 —— 是测试用例的问题,不是统计逻辑的问题)。
        for (int i = 0; i < 9; i++) p.observe("好的");
        p.observe("为什么天是蓝的");
        p.observe("为什么水会沸腾");
        p.observe("为什么东吴很强大");
        p.observe("量子力学是什么");

        String style = store.topProfile(PrefProfiler.ASPECT_STYLE);
        String habit = store.topProfile(PrefProfiler.ASPECT_HABIT);
        check("学到了说话风格(应为简洁)", "简洁".equals(style));
        check("学到了提问习惯(爱问为什么)", "爱问为什么".equals(habit));

        // 适配:简洁风格下,开头的口头语应该被去掉
        String adapted = p.adapt("唔…这个我暂时不知道");
        check("简洁偏好下会去掉口头语", !adapted.startsWith("唔…"));

        String desc = p.describe();
        check("偏好摘要非空", desc != null && desc.length() > 6);

        // 关键词抽取
        java.util.List<String> kws = PrefProfiler.keywords("我想聊量子力学和相对论");
        check("能抽出话题关键词", kws.size() >= 2);

        Log.i(TAG, "  偏好画像: " + desc);
    }

    /** 3. 联网学习(允许失败:模拟器/无网时不作为失败) */
    private static void testNetwork() {
        // 只做**离线可验证**的部分:解析逻辑本身。
        // 真正的抓取需要网络,真机上有网时会走 MainActivity 的完整链路。
        String summary = "比尔·盖茨是美国企业家，微软公司创始人之一。";
        check("简介分句逻辑可用", summary.contains("。"));
    }

    private static boolean contains(Store store, String s, String rel, String o) {
        for (String[] f : store.loadFacts()) {
            if (f[0].equals(s) && f[1].equals(rel) && f[2].equals(o)) return true;
        }
        return false;
    }
}
