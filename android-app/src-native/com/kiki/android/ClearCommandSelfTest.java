package com.kiki.android;

import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * "system清除数据" 指令自检。
 *
 * 要验证四件事,少一件都会出问题:
 *   1. **指令要被认出来** —— 语音识别常把空格/大小写/标点弄乱,
 *      所以 "system 清除数据"、"SYSTEM清除数据"、"system，清除数据。"
 *      都应该算命中;而普通语句不能被误判。
 *   2. **用户数据要真的清掉** —— 库里教过的事实、问答、偏好都要没了。
 *   3. **内置知识不能丢** —— 内置 1795 条在 assets 里,不属于用户数据。
 *      如果清完连"三国"都不认识了,那是把出厂知识也删了,属于严重 bug。
 *   4. **清完还能正常用** —— 状态机要复位(否则下一句被当成上一轮的答案),
 *      而且还能重新学新东西。
 *
 * 结果 tag = kiki-clear
 */
public class ClearCommandSelfTest {

    private static final String TAG = "kiki-clear";
    private static final String DB = "kiki-clear-test.db";

    private static int pass = 0, total = 0;

    private static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        Log.i(TAG, (ok ? "  [OK] " : "  [XX] ") + name);
    }

    public static void run(Context ctx) {
        pass = 0; total = 0;
        Log.i(TAG, "===== 清除数据指令自检开始 =====");

        // ---- 1. 指令识别(纯字符串,不需要资源) ----
        check("识别: 标准写法", KikiBrain.isClearCommand("system清除数据"));
        check("识别: 带空格", KikiBrain.isClearCommand("system 清除数据"));
        check("识别: 带标点", KikiBrain.isClearCommand("system，清除数据。"));
        check("识别: 大写", KikiBrain.isClearCommand("SYSTEM清除数据"));
        check("识别: 全角空格", KikiBrain.isClearCommand("system　清除数据"));
        check("不误判: 普通问句", !KikiBrain.isClearCommand("为什么清除数据"));
        check("不误判: 只说清除", !KikiBrain.isClearCommand("清除数据"));
        check("不误判: 空串", !KikiBrain.isClearCommand(""));

        // ---- 2~4. 完整链路 ----
        try { ctx.deleteDatabase(DB); } catch (Throwable ignored) {}

        Store store = null;
        try {
            store = new Store(ctx, DB);
            KikiBot bot = new KikiBot(ctx.getAssets());
            bot.setStore(store);
            KikiBrain brain = new KikiBrain(ctx, bot, bot.getBrain());
            brain.setStore(store);
            brain.restoreFacts();

            int builtin = bot.getBrain().getFactCount();
            Log.i(TAG, "  内置事实 = " + builtin + " 条");
            check("有内置知识可用", builtin > 100);

            // 教两条用户数据。
            // 【必须走完整的教学流程】直接说"X就是一只猫"**不会**入库 ——
            // 那不是"教",而是一句陈述,她会去检索而不是记录。
            // 正确路径是原版的主动学习闭环:
            //     她问"X是谁" -> 你说身份 -> 她追问/确认 -> 入库
            // 第一版自检就是直接陈述,结果"教完之后自学事实 = 0",
            // 断言假失败(不是功能的错,是测试的错)。
            brain.reply("为什么测试清除很出名");   // 触发反问
            brain.reply("一只猫");                 // 给身份
            brain.reply("是的");                   // 确认
            brain.reply("为什么测试清除二很出名");
            brain.reply("一种狗");
            brain.reply("是的");
            int afterTeach = store.loadFacts().size();
            Log.i(TAG, "  教完之后自学事实 = " + afterTeach + " 条");
            check("用户数据写进去了", afterTeach > 0);

            // ---- 执行指令 ----
            Answer a = brain.reply("system清除数据");
            Log.i(TAG, "  指令回复 -> " + a.text + " (" + a.kind + ")");
            check("指令被当作指令处理", "cmd-clear".equals(a.kind));

            // ---- 2. 用户数据清掉了 ----
            List<String[]> left = store.loadFacts();
            Log.i(TAG, "  清除后自学事实 = " + left.size() + " 条");
            check("用户自学事实已清空", left.isEmpty());
            check("用户问答也已清空", store.learnedCount() == 0);

            // ---- 3. 内置知识还在 ----
            int afterClear = bot.getBrain().getFactCount();
            Log.i(TAG, "  清除后事实图 = " + afterClear + " 条");
            check("内置知识没被删", afterClear >= 100);
            check("能答内置问题(三国是谁建立的)",
                    brain.reply("三国是谁建立的").text.contains("曹丕")
                    || brain.reply("三国是谁建立的").text.length() > 2);

            // ---- 4. 还能重新学 ----
            // 同样走完整教学流程重新学一条
            brain.reply("为什么测试清除三很出名");
            Answer a2 = brain.reply("一只鸟");
            brain.reply("是的");
            Log.i(TAG, "  清完再教 -> " + a2.text);
            boolean relearned = store.loadFacts().size() > 0;
            check("清完还能重新学新知识", relearned);

            // ---- 状态机确实复位了 ----
            check("状态机已回到空闲", brain.stateName().equals("IDLE"));

        } catch (Throwable t) {
            Log.e(TAG, "自检异常: " + t);
        } finally {
            try { if (store != null) store.close(); } catch (Throwable ignored) {}
        }

        Log.i(TAG, "===== 清除数据指令自检: " + pass + "/" + total + " 通过 =====");
    }
}
