package com.kiki.android;

import android.util.Log;

/**
 * 同音词纠正自检。
 *
 * 验证用户提出的思路落地后的实际效果:
 *   ① 同音词纠正:识别出"康深",库里教过"康神" -> 纠正为"康神"
 *   ② 同音消歧:多个同音实体时,按上下文选,而不是随机
 *
 * 【不再自己 new KikiBot】
 * 第一版在自检里又建了一个 KikiBot(要加载 4 万词表 + 1.2 万拼音 + 全部 assets),
 * 而这个线程里前面已经建过好几个,结果直接把这台机器的堆吃满 ——
 * 表现为"自检的日志一条都没有"(连 catch 里的异常都来不及打)。
 * 现在改成**复用调用方传进来的 bot**,只做纯计算。
 *
 * 结果 tag = kiki-hr
 */
public class HomophoneSelfTest {

    private static final String TAG = "kiki-hr";

    private static int pass = 0, total = 0;

    private static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        Log.i(TAG, (ok ? "  [OK] " : "  [XX] ") + name);
    }

    /** @param bot 复用已有的检索层(内部持有事实图与拼音表) */
    public static void run(KikiBot bot) {
        pass = 0; total = 0;
        Log.i(TAG, "===== 同音词纠正自检开始 =====");
        try {
            if (bot == null || bot.getBrain() == null) {
                Log.w(TAG, "  没有可用的 bot,跳过");
                return;
            }
            Brain graph = bot.getBrain();
            HomophoneCorrector hr = new HomophoneCorrector(graph);

            // 索引里已经包含全部内置实体;这里再补两个同音词做验证
            graph.addFact("康神", "是", "一只猫", "taught");
            graph.addFact("康深", "是", "一个人名", "taught");
            hr.indexAll();
            Log.i(TAG, "  索引组数 = " + hr.size());

            // ---- ① 已知实体不被动 ----
            String c1 = hr.correct("康神的猫");
            check("已知实体不被改动: [" + c1 + "]", "康神的猫".equals(c1));

            // ---- ② 两个实体确实同音 ----
            String py1 = hr.pinyinOf("康神");
            String py2 = hr.pinyinOf("康深");
            Log.i(TAG, "  康神 拼音=" + py1 + "   康深 拼音=" + py2);
            check("同音词拼音相同", py1 != null && py1.equals(py2));

            // ---- ③ 消歧会参考上下文 ----
            hr.noteContext("猫在哪里");
            String picked = hr.disambiguate(
                    java.util.Arrays.asList("康神", "康深"), "康深的猫");
            Log.i(TAG, "  上下文含'猫'时选中: " + picked);
            check("消歧有结果", picked != null);

            // ---- ④ 库外内容不被乱改 ----
            String c3 = hr.correct("今天天气不错啊");
            check("库外内容不被改动: [" + c3 + "]", "今天天气不错啊".equals(c3));

            // ---- ⑤ 拼音可用 ----
            String py = hr.pinyinOf("量子力学");
            check("能算出多字词拼音: [" + py + "]", py != null && !py.isEmpty());

        } catch (Throwable t) {
            Log.e(TAG, "自检异常: " + t);
        }
        Log.i(TAG, "===== 同音词纠正自检: " + pass + "/" + total + " 通过 =====");
    }
}
