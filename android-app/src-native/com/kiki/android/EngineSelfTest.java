package com.kiki.android;

import android.content.res.AssetManager;
import android.util.Log;

/**
 * 引擎自检 —— 在真机上直接跑一遍推理用例,结果写进日志。
 *
 * 为什么需要:Python 侧的回归测试跑不了手机上的 Java 引擎(两边是独立实现),
 * 而我没有别的办法在真机上验证推理是否生效。所以把用例内置成一个探针,
 * 首次启动时跑一次,输出 tag: kiki-selftest。
 *
 * **覆盖的是因果推理** —— 这是用户最关心的能力:
 * "知道比尔盖茨是微软 CEO,就能推出他很出名" 那一类。
 */
public class EngineSelfTest {

    private static final String TAG = "kiki-engine";

    /** 用例:{问题, 答案里必须出现的关键词} */
    private static final String[][] CASES = {
            // 因果推理:逆关系
            {"东吴是谁建立的", "孙权"},
            {"谁建立了东吴", "孙权"},
            {"活字印刷术是谁发明的", "毕昇"},
            {"谁发明了活字印刷术", "毕昇"},
            {"北京是谁的首都", "中国"},
            {"昆明是谁的省会", "云南"},
            // 因果推理:蕴含(这就是"从 CEO 推出出名"那一类)
            {"孙权是历史人物吗", "对"},
            {"毕昇是发明家吗", "对"},
            // 属性继承链
            {"卡卡是生物吗", "对"},
            {"卡卡是猫吗", "对"},
            {"卡卡是什么", "猫"},
            // 既有能力回归(不能被因果层破坏)
            {"企鹅会飞吗", "不会"},
            {"乌龟走得快吗", "慢"},
            {"上海市位于哪里", "华东"},
            {"小明的爷爷是谁", "张建国"},
            {"长江有多长", "6300"},
            {"氧原子序数", "8"},
            {"谁发明了电话", "贝尔"},
            {"一分钟是几秒", "60"},
            {"秦朝建立于哪一年", "221"},
    };

    /**
     * 在后台线程跑全部用例。需要 AssetManager 来建引擎。
     */
    public static void run(AssetManager assets) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    KikiBot bot = new KikiBot(assets);
                    int ok = 0;
                    StringBuilder fails = new StringBuilder();
                    for (String[] c : CASES) {
                        String q = c[0], must = c[1];
                        String ans;
                        try {
                            Answer a = bot.reply(q);
                            ans = a == null ? "(null)" : a.text;
                        } catch (Throwable t) {
                            ans = "(异常 " + t + ")";
                        }
                        boolean hit = ans != null && ans.contains(must);
                        if (hit) {
                            ok++;
                            Log.i(TAG, "[OK] " + q + " -> " + ans);
                        } else {
                            Log.w(TAG, "[!!] " + q + " -> " + ans + "   (期望含 " + must + ")");
                            fails.append(q).append("; ");
                        }
                    }
                    Log.i(TAG, "=== 引擎自检: " + ok + "/" + CASES.length + " 通过 ===");
                    if (fails.length() > 0) {
                        Log.w(TAG, "=== 未通过: " + fails + " ===");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "引擎自检异常: " + t);
                }
            }
        }, "kiki-engine-test").start();
    }
}
