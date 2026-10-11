package com.kiki.android;

import android.content.Context;
import android.util.Log;

/**
 * 复现用户截图里的场景,逐条定位问题。
 *
 * 截图里的现象(2026-10-05):
 *   1. 教"康神是一条狗" -> 回"明白了";但再问"康神是谁"时
 *      却答了"一条狗是谁"的内容,而且把"康神是一条狗"存成了身份
 *   2. 网络查"康氏是谁" -> 把**整段维基正文**当回答吐出来(几百字),
 *      还把"康氏是谁"这种**问题串**当成事实键存了
 *   3. "为什么微软公司很出名" -> "微是比尔盖茨的一种"(单字"微"入库)
 *   4. "微软公司的ceo是谁" -> 反复回"他是谁？"
 *
 * 这个自检把每一步的 reply 与库内容都打出来,便于精确定位。
 * 结果 tag = kiki-repro
 */
public class ReproTest {

    private static final String TAG = "kiki-repro";
    private static final String DB = "kiki-repro.db";

    private static void say(KikiBrain brain, String input) {
        Answer a = brain.reply(input);
        Log.i(TAG, String.format("  说: %-22s -> [%s] %s",
                input, a.kind, a.text.length() > 90 ? a.text.substring(0, 90) + "…" : a.text));
    }

    private static void dumpStore(Store s, String when) {
        java.util.List<String[]> f = s.loadFacts();
        Log.i(TAG, "  ---- 库内容(" + when + "): " + f.size() + " 条 ----");
        for (String[] x : f) Log.i(TAG, "     " + x[0] + " | " + x[1] + " | " + x[2]);
    }

    public static void run(Context ctx) {
        Log.i(TAG, "===== 截图场景复现 =====");
        try { ctx.deleteDatabase(DB); } catch (Throwable ignored) {}
        Store store = null;
        try {
            store = new Store(ctx, DB);
            KikiBot bot = new KikiBot(ctx.getAssets());
            bot.setStore(store);
            KikiBrain brain = new KikiBrain(ctx, bot, bot.getBrain());
            brain.setStore(store);
            brain.restoreFacts();

            Log.i(TAG, "--- 场景1: 教 康神是一条狗, 再问康神是谁 ---");
            say(brain, "康神是谁");          // 期望反问
            say(brain, "康神是一条狗");       // 期望:抽身份 -> 狗
            dumpStore(store, "教完后");
            say(brain, "康神是谁");          // 期望:正确回答"康神是一条狗"
            say(brain, "一条狗是谁");        // 期望:转去问狗(内置知识)

            Log.i(TAG, "--- 场景2: 单字实体不该入库 ---");
            say(brain, "为什么微软公司很出名");
            say(brain, "微");                // 单字,不该被当身份
            dumpStore(store, "单字测试后");

            Log.i(TAG, "--- 场景3: 反问句不该被当答案 ---");
            say(brain, "康氏是谁");
            say(brain, "康氏是谁");          // 再问一次,看会不会被当答案消费

            Log.i(TAG, "--- 场景4: 库外词查询 ---");
            say(brain, "cmjjkk是谁");

            // ---- 自动检查 ----
            Log.i(TAG, "--- 检查 ---");
            boolean hasSingle = false, hasQuestionKey = false;
            for (String[] x : store.loadFacts()) {
                if (x[0].length() == 1) hasSingle = true;
                if (x[0].contains("谁") || x[0].contains("为什么") || x[0].contains("是什么"))
                    hasQuestionKey = true;
                Log.i(TAG, "     检查: [" + x[0] + "] len=" + x[0].length());
            }
            Log.i(TAG, (hasSingle ? "  [XX] " : "  [OK] ") + "没有单字实体入库");
            Log.i(TAG, (hasQuestionKey ? "  [XX] " : "  [OK] ") + "没有问题串被当事实键");

        } catch (Throwable t) {
            Log.e(TAG, "复现异常: " + t);
        } finally {
            try { if (store != null) store.close(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "===== 复现结束 =====");
    }
}
