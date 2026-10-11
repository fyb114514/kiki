package com.kiki.android;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 主动学习闭环自检 —— 把特性文档里的那个例子做成可自动跑的用例。
 *
 * 为什么要做成自检
 * ----------------
 * 特性文档给的行为是这样的:
 *
 *   用户：为什么比尔盖茨很出名      (比尔盖茨不在数据库内)
 *   AI  ：我不知道，他是谁
 *   用户：微软公司CEO
 *   AI  ：是微软公司出名吗？
 *   用户：没错
 *   AI  ：明白了
 *   用户：比尔盖茨很出名
 *   AI  ：因为他是微软公司CEO
 *
 * 这是整个项目的**核心能力**,却最难人工验证(要对着手机连说 5 轮)。
 * 所以把它做成不依赖界面的自动化用例:直接驱动 {@link KikiDialog} 状态机,
 * 断言每一轮的回答该出现什么关键词。结果写 logcat(tag: kiki-dialog-test)。
 *
 * 顺带覆盖:状态转移、实体抽取、是/否判断、超时清理。
 */
public class DialogSelfTest {

    private static final String TAG = "kiki-dialog-test";

    /** 用例:{第几轮, 用户说, 期望回答里必须出现的词(空=不作答) } */
    private static final String[][] BILL_GATES = {
            {"1", "为什么比尔盖茨很出名", "是谁"},
            {"2", "微软公司CEO", "微软公司"},
            {"3", "没错", "明白了"},
            {"4", "比尔盖茨很出名", "微软公司"},
    };

    /** 记录学到的事实,供断言 */
    private static final List<String[]> learned = new ArrayList<String[]>();

    /** 内存版事实图:够验证闭环,不碰真实资源 */
    private static class MemSink implements KikiDialog.Sink {
        final List<String[]> facts = new ArrayList<String[]>();

        @Override
        public void remember(String s, String rel, String o) {
            facts.add(new String[]{s, rel, o});
            learned.add(new String[]{s, rel, o});
        }

        @Override
        public List<String> lookup(String e, String rel) {
            List<String> out = new ArrayList<String>();
            for (String[] f : facts) {
                if (f[0].equals(e) && f[1].equals(rel)) out.add(f[2]);
            }
            return out;
        }

        @Override
        public boolean knowsEntity(String e) {
            for (String[] f : facts) {
                if (f[0].equals(e) || f[2].equals(e)) return true;
            }
            return false;
        }

        @Override
        public boolean knowsAnything(String e) {
            return knowsEntity(e);
        }

        /**
         * 真的把事实删掉 —— 不能只写个空实现。
         * 空实现会让"否定后身份已删除"这条断言**永远为真**,是假通过。
         */
        @Override
        public void forget(String s, String rel, String o) {
            for (int i = facts.size() - 1; i >= 0; i--) {
                String[] f = facts.get(i);
                if (f[0].equals(s) && f[1].equals(rel) && f[2].equals(o)) {
                    facts.remove(i);
                }
            }
        }
    }

    public static void run() {
        int pass = 0, total = 0;

        // ---------- 用例 1:特性文档的完整闭环 ----------
        total++;
        if (runBillGates()) pass++;

        // ---------- 用例 2:否定回答 ----------
        total++;
        if (runNegative()) pass++;

        // ---------- 用例 2.5:身份抽取(用户截图暴露的 bug) ----------
        total++;
        if (runPredicate()) pass++;

        // ---------- 用例 3:实体抽取 ----------
        total++;
        if (runExtract()) pass++;

        // ---------- 用例 4:超时清理 ----------
        total++;
        if (runTimeout()) pass++;

        Log.i(TAG, "=== 对话闭环自检: " + pass + "/" + total + " 通过 ===");
    }

    /** 特性 1 的核心例子:反问 -> 追问 -> 记住 -> 推导 */
    private static boolean runBillGates() {
        learned.clear();
        MemSink sink = new MemSink();
        KikiDialog d = new KikiDialog(sink);

        boolean ok = true;

        // 第 1 轮:遇到不懂的 -> 反问
        // 【为什么传 true】模拟用户问的是"为什么比尔盖茨很出名" ——
        // 只有**因果问句**才需要在学到身份后追问原因。
        // "X 是什么/是谁"问到身份就够了,再追问一句会显得啰嗦
        // (100 轮测试里同一句式出现 46 次,用户反馈"只会问…出名吗")。
        KikiDialog.Reply r1 = d.askWho("比尔盖茨", "很出名", true);
        ok &= check("1.反问", r1.text, "是谁");
        ok &= checkState("1.状态", d.state(), KikiDialog.State.ASKING_WHO);

        // 第 2 轮:用户答身份 -> 应追问因果,并记住身份
        KikiDialog.Reply r2 = d.handle("微软公司CEO");
        ok &= check("2.追问", r2.text, "微软公司");
        ok &= checkState("2.状态", d.state(), KikiDialog.State.ASKING_CAUSE);
        // 【键已统一】身份存的是**抽取后的实体**("微软公司"),不是完整身份
        // ("微软公司CEO")。因为事实图与查询方都用抽取后的实体,两边键必须一致 ——
        // 原来存完整身份,查询对不上,表现就是"刚教完又问"(100 轮测试反复出现)。
        // 追问句仍然用完整身份问("微软公司CEO 出名吗?"),读起来自然。
        ok &= checkFact("2.记住身份(抽取后的实体)", sink.facts, "比尔盖茨", "是", "微软公司");

        // 第 3 轮:用户确认 -> 写因果 -> 回到空闲
        KikiDialog.Reply r3 = d.handle("没错");
        ok &= check("3.确认", r3.text, "明白了");
        ok &= checkState("3.状态", d.state(), KikiDialog.State.IDLE);
        ok &= checkFact("3.记住因果", sink.facts, "比尔盖茨", "因为", "微软公司");

        // 第 4 轮:用学到的因果回答原问题
        // (真实链路由 KikiBrain.answerFromFacts 完成,这里验证底层事实已就位)
        List<String> why = sink.lookup("比尔盖茨", "因为");
        ok &= check("4.推导", why.isEmpty() ? "" : why.get(0), "微软公司");

        return ok;
    }

    /**
     * 追问被否定:应**真的删掉**那次学到的身份,并优雅收尾。
     *
     * 【用例变更记录】原来断言"身份仍记住"("记下了"),
     * 但那个行为是错的:用户说"不是",她还留着错的知识继续用
     * (用户反馈原话"我都说了不是了他还记着")。
     * 现在改成断言身份被删除、且措辞是"忘掉"。
     */
    private static boolean runNegative() {
        learned.clear();
        MemSink sink = new MemSink();
        KikiDialog d = new KikiDialog(sink);

        d.askWho("张三", "很出名", true);
        d.handle("某公司老板");
        KikiDialog.Reply r = d.handle("不是");

        boolean ok = true;
        ok &= check("否定.收尾", r.text, "忘掉");
        ok &= checkState("否定.状态", d.state(), KikiDialog.State.IDLE);

        // 【核心断言】被否定的身份必须**已经删掉**,不能再留着
        boolean stillThere = false;
        for (String[] f : sink.facts) {
            if ("张三".equals(f[0]) && "是".equals(f[1]) && "某公司老板".equals(f[2])) {
                stillThere = true;
                break;
            }
        }
        if (stillThere) {
            Log.w(TAG, "  ✗ 否定.身份应被删除,但仍在库里");
            ok = false;
        } else {
            Log.i(TAG, "  ✓ 否定.身份已删除(不再记着错的)");
        }

        // 不应写入因果
        boolean hasCause = !sink.lookup("张三", "因为").isEmpty();
        if (hasCause) {
            Log.w(TAG, "  ✗ 否定.不应写因果,但写入了");
            ok = false;
        } else {
            Log.i(TAG, "  ✓ 否定.未写因果(正确)");
        }
        return ok;
    }

    /**
     * 身份抽取:回答"康神是一条狗"时,身份应该是"一条狗"而不是整句。
     *
     * 用户截图暴露的 bug:整句被当身份存下来,追问句就成了
     * "是康神是一条狗让康神出名吗?" —— 荒诞。
     */
    private static boolean runPredicate() {
        boolean ok = true;
        ok &= eq("身份.康神是一条狗", KikiDialog.extractPredicate("康神是一条狗"), "一条狗");
        ok &= eq("身份.微软公司CEO", KikiDialog.extractPredicate("微软公司CEO"), "微软公司CEO");
        ok &= eq("身份.他是微软公司CEO", KikiDialog.extractPredicate("他是微软公司CEO"), "微软公司CEO");
        ok &= eq("身份.一只猫", KikiDialog.extractPredicate("一只猫"), "一只猫");
        // 追问句要用人话
        String q = "是" + KikiDialog.extractEntity(KikiDialog.extractPredicate("康神是一条狗"))
                 + "让康神出名吗？";
        ok &= eq("身份.追问句自然", q, "是一条狗让康神出名吗？");
        return ok;
    }

    /** 实体抽取:职位后缀要剥掉 */
    private static boolean runExtract() {
        boolean ok = true;
        ok &= eq("抽取.微软公司CEO", KikiDialog.extractEntity("微软公司CEO"), "微软公司");
        ok &= eq("抽取.苹果公司创始人", KikiDialog.extractEntity("苹果公司创始人"), "苹果公司");
        ok &= eq("抽取.某公司总裁", KikiDialog.extractEntity("某公司总裁"), "某公司");
        ok &= eq("抽取.清华大学", KikiDialog.extractEntity("清华大学"), "清华大学");
        return ok;
    }

    /** 超时后不应再把新话当答案 */
    private static boolean runTimeout() {
        MemSink sink = new MemSink();
        KikiDialog d = new KikiDialog(sink);
        d.askWho("李四", "很出名", true);
        // 手动把时间戳改老,模拟超时
        String ser = d.serialize();
        String[] p = ser.split("\t", -1);
        // 【索引变更记录】serialize 的字段顺序是
        //   state, pendingSubject, pendingRelation, learnedWho, learnedIdentity, stateAt
        // 加了 learnedIdentity 之后时间戳从 p[4] 挪到了 p[5]。
        // 这里用 length-1 取最后一格,以后再加字段也不会失效。
        p[p.length - 1] = String.valueOf(System.currentTimeMillis() - 200_000L);   // 200 秒前
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append('\t');
            sb.append(p[i]);
        }
        d.restore(sb.toString());
        boolean ok = true;
        ok &= checkState("超时.已复位", d.state(), KikiDialog.State.IDLE);
        return ok;
    }

    // ---------- 断言工具 ----------

    private static boolean check(String name, String actual, String mustContain) {
        boolean ok = actual != null && actual.contains(mustContain);
        Log.i(TAG, (ok ? "  ✓ " : "  ✗ ") + name + " -> " + actual
                + (ok ? "" : "   (缺少: " + mustContain + ")"));
        return ok;
    }

    private static boolean checkState(String name, KikiDialog.State actual, KikiDialog.State want) {
        boolean ok = actual == want;
        Log.i(TAG, (ok ? "  ✓ " : "  ✗ ") + name + " = " + actual + (ok ? "" : "  (期望 " + want + ")"));
        return ok;
    }

    private static boolean checkFact(String name, List<String[]> facts,
                                     String s, String rel, String o) {
        for (String[] f : facts) {
            if (f[0].equals(s) && f[1].equals(rel) && f[2].equals(o)) {
                Log.i(TAG, "  ✓ " + name + " (" + s + "|" + rel + "|" + o + ")");
                return true;
            }
        }
        Log.w(TAG, "  ✗ " + name + " 未写入 (" + s + "|" + rel + "|" + o + ")");
        for (String[] f : facts) {
            Log.w(TAG, "      实际有: " + f[0] + "|" + f[1] + "|" + f[2]);
        }
        return false;
    }

    private static boolean eq(String name, String actual, String want) {
        boolean ok = want.equals(actual);
        Log.i(TAG, (ok ? "  ✓ " : "  ✗ ") + name + " -> " + actual
                + (ok ? "" : "   (期望 " + want + ")"));
        return ok;
    }
}
