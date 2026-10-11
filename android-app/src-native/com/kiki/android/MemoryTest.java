package com.kiki.android;

import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * 记忆持久化专项测试。
 *
 * 用户反馈"还是记不住"。之前我一直在猜,这次做成**确定性**的验证:
 *   1. 教她两条知识(经过完整的 KikiBrain 管线)
 *   2. 关闭 Store(模拟退出)
 *   3. 重新打开同一个库,新建 KikiBrain
 *   4. 问她 —— 看还记不记得
 * 每一步都用日志把"库里实际有什么"打出来,这样一旦失败能直接看出
 * 是**没写进去**,还是**写了但没读回来**。
 *
 * 结果 tag = kiki-mem
 */
public class MemoryTest {

    private static final String TAG = "kiki-mem";
    private static final String DB = "kiki-mem-test.db";

    private static int pass = 0, total = 0;

    private static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        Log.i(TAG, (ok ? "  [OK] " : "  [XX] ") + name);
    }

    public static void run(Context ctx) {
        pass = 0; total = 0;
        Log.i(TAG, "===== 记忆持久化测试开始 =====");

        try { ctx.deleteDatabase(DB); } catch (Throwable ignored) {}

        try {
            // ---------- 第 1 阶段:教 ----------
            Log.i(TAG, "--- 阶段1:教她两条知识 ---");
            Store s1 = new Store(ctx, DB);
            KikiBot bot1 = new KikiBot(ctx.getAssets());
            bot1.setStore(s1);
            KikiBrain b1 = new KikiBrain(ctx, bot1, bot1.getBrain());
            b1.setStore(s1);
            b1.restoreFacts();

            // 走完整管线:反问 -> 给身份 -> 确认
            Answer a1 = b1.reply("为什么测试甲很出名");
            Log.i(TAG, "  问'为什么测试甲很出名' -> " + a1.text + " (" + a1.kind + ")");
            Answer a2 = b1.reply("一种测试用的东西");
            Log.i(TAG, "  答身份 -> " + a2.text + " (" + a2.kind + ")");
            Answer a3 = b1.reply("是的");
            Log.i(TAG, "  确认 -> " + a3.text);

            // 直接教一条简单的
            b1.reply("测试乙是一种动物");

            // 库里现在有什么
            List<String[]> f1 = s1.loadFacts();
            Log.i(TAG, "  阶段1结束,库里有 " + f1.size() + " 条:");
            for (String[] f : f1) Log.i(TAG, "     " + f[0] + " | " + f[1] + " | " + f[2]);
            check("阶段1:确实写进了库", f1.size() > 0);

            // 关库(模拟退出应用)
            s1.close();
            Log.i(TAG, "  已关闭库(模拟退出应用)");

            // ---------- 第 2 阶段:重开,看还记不记得 ----------
            Log.i(TAG, "--- 阶段2:重新打开,检查是否记得 ---");
            Store s2 = new Store(ctx, DB);
            List<String[]> f2 = s2.loadFacts();
            Log.i(TAG, "  重开后库里有 " + f2.size() + " 条:");
            for (String[] f : f2) Log.i(TAG, "     " + f[0] + " | " + f[1] + " | " + f[2]);
            check("重开后事实还在库里", f2.size() == f1.size());

            // 新建 KikiBrain,灌回事实图
            KikiBot bot2 = new KikiBot(ctx.getAssets());
            bot2.setStore(s2);
            KikiBrain b2 = new KikiBrain(ctx, bot2, bot2.getBrain());
            b2.setStore(s2);
            b2.restoreFacts();

            // 直接查事实图
            int inGraph = bot2.getBrain().getFactCount();
            Log.i(TAG, "  重启后事实图里有 " + inGraph + " 条");
            check("事实已灌回事实图", inGraph >= f2.size());

            // 问她
            Answer q = b2.reply("测试甲是什么");
            Log.i(TAG, "  问'测试甲是什么' -> " + q.text + " (" + q.kind + ")");
            check("能答出学过的东西", q.text != null && !q.text.contains("我不知道")
                    && !q.text.contains("查不到"));

            s2.close();
        } catch (Throwable t) {
            Log.e(TAG, "记忆测试异常: " + t);
        }

        Log.i(TAG, "===== 记忆持久化测试: " + pass + "/" + total + " 通过 =====");
    }
}
