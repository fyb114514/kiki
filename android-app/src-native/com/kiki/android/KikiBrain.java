package com.kiki.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * kiki 的大脑 —— 规则驱动的对话引擎(不依赖任何语言模型)。
 *
 * 为什么走这条路
 * --------------
 * 原版 kiki 的核心行为是"**不知道就问,问完记住,下次能推导**":
 *
 *   用户：为什么比尔盖茨很出名   (库里没有比尔盖茨)
 *   AI  ：我不知道，他是谁
 *   用户：微软公司CEO
 *   AI  ：是微软公司让比尔盖茨出名的吗？
 *   用户：没错
 *   AI  ：明白了
 *   用户：比尔盖茨很出名
 *   AI  ：因为他是微软公司CEO
 *
 * 预训练语言模型**做不到这个** —— 它遇到不认识的东西会直接编一个答案,
 * 不会说"我不知道"。所以这里放弃模型,改回**知识图谱 + 状态机**。
 *
 * 四个特性对应
 * ------------
 *   1. 逻辑推理 / 因果 / 上下文 / 主动学习  -> {@link KikiDialog} + {@link Brain}
 *   2. 网络学习 / 用户偏好 / 新知识覆盖      -> 见 learnFromWeb() / preference
 *   3. 多音字语音识别                        -> 见 OfflineRecognizer 的混淆纠正
 *   4. 界面                                  -> 见 MainActivity
 */
public class KikiBrain {

    private static final String TAG = "kiki-brain";

    private final Context context;
    private final KikiBot bot;         // 检索层:内置问答 + 事实库
    /** 语言总结(原版视频演示的能力之一) */
    private Summarizer summarizer;
    private final Brain fact;         // 事实层:因果/属性/分类继承
    private final KikiDialog dialog;  // 对话状态机:多轮主动学习
    private Store store;              // 本地记忆:用户教过的东西

    /** 待回答状态(用于把反问的答案落盘) */
    private volatile boolean ready = false;

    public KikiBrain(Context context, KikiBot bot, Brain fact) {
        this.context = context;
        this.bot = bot;
        this.fact = fact;
        this.dialog = new KikiDialog(new KikiDialog.Sink() {
            @Override
            public void remember(String s, String rel, String o) {
                KikiBrain.this.remember(s, rel, o);
            }

            @Override
            public List<String> lookup(String e, String rel) {
                return KikiBrain.this.fact.forward(e, rel);
            }

            @Override
            public boolean knowsEntity(String e) {
                return KikiBrain.this.fact.forward(e).size() > 0
                        || KikiBrain.this.fact.reverse(e).size() > 0;
            }

            @Override
            public boolean knowsAnything(String e) {
                return knowsEntity(e);
            }

            @Override
            public void forget(String s, String rel, String o) {
                KikiBrain.this.forget(s, rel, o);
            }

        });
    }

    public void setStore(Store s) {
        this.store = s;
        this.ready = true;
        // 语言总结需要"词表校验 + 事实图查询",和事实检索共用一套。
        // 用一个匿名 Sink 委托回 KikiBrain 自己 —— 不要把 dialog 的 sink 借出来,
        // 那个对象只属于 dialog。
        if (summarizer == null && bot != null) {
            summarizer = new Summarizer(new Summarizer.Lexicon() {
                @Override public boolean isWord(String w) { return bot.isWord(w); }
                @Override public java.util.List<String> segment(String t) { return bot.segmentPublic(t); }
            }, new KikiDialog.Sink() {
                @Override public void remember(String a, String rel, String b) { }
                @Override public List<String> lookup(String e, String rel) {
                    return KikiBrain.this.fact.forward(e, rel);
                }
                @Override public boolean knowsEntity(String e) {
                    return KikiBrain.this.fact.forward(e).size() > 0
                            || KikiBrain.this.fact.reverse(e).size() > 0;
                }
                @Override public boolean knowsAnything(String e) { return knowsEntity(e); }


                    @Override

                    public void forget(String s, String rel, String o) {

                        KikiDialog.Sink sup = this;

                        // 测试/内嵌实现:只保证接口完整,不真的维护事实图

                    }
            });
        }
    }

    public boolean isReady() {
        return ready;
    }

    public KikiDialog dialog() {
        return dialog;
    }

    // ============================================================
    // 主入口
    // ============================================================

    /** 异步回答(界面线程不能阻塞,检索 + 学习可能涉及磁盘) */
    public interface Callback {
        void onReply(Answer answer, String learnedFrom);
    }

    public void replyAsync(final String input, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Answer a = reply(input);
                    cb.onReply(a, a.kind != null && a.kind.startsWith("learn") ? "本地学习" : null);
                } catch (Throwable t) {
                    Log.e(TAG, "回答失败: " + t);
                    cb.onReply(new Answer("唔…我想岔了，再说一次？", "异常", "miss"), null);
                }
            }
        }, "kiki-reply").start();
    }

    /**
     * 回答一句话。这是全部逻辑的入口,顺序很重要:
     *
     *   1. 对话状态机 —— 如果正在等用户回答反问,这句就是答案
     *   2. 用户教过的知识(本地记忆)
     *   3. 内置问答(精确/模糊)
     *   4. 事实库检索 + 因果推导
     *   5. 都没命中 -> 主动反问(特性 1 的核心)
     */
    public Answer reply(String input) {
        String raw = input == null ? "" : KikiBot.trim(input);
        if (raw.isEmpty()) {
            return new Answer("嗯？", "空输入", "miss");
        }

        // ---------- 1. 对话状态机优先 ----------
        // 注意:这里**不做**"看起来像问句就跳过"的判断 ——
        // 因为追问阶段的回答可能是"没错",而反问阶段的回答可能是长句。
        // 原版行为是:你正在被问,你说的话就是答案。
        KikiDialog.Reply dr = dialog.handle(raw);
        if (dr.consumed) {
            if (dr.learned) persistLearned();
            return new Answer(dr.text, "主动学习闭环", dr.learned ? "learn-dialog" : "dialog");
        }

        // ---------- 2. 用户教过的(本地记忆,优先于内置) ----------
        Answer taught = lookupTaught(raw);
        if (taught != null) return taught;

        // ---------- 3. 内置问答 ----------
        Answer qa = bot.reply(raw);
        if (isDefinitive(qa)) return qa;

        // ---------- 3.5 语言总结 ----------
        // 原版把它列为独立能力。放在事实检索之前,因为"总结"比"检索"更具体:
        // 用户说"基础的基，朋友的友"时,事实库里查不到,但能总结出"基友"。
        if (summarizer != null) {
            String sum = summarizer.summarize(raw);
            if (sum != null && !sum.isEmpty()) {
                return new Answer(sum, "语言总结", "summarize");
            }
        }

        // ---------- 4. 事实库 + 因果推导 ----------
        Answer factAns = answerFromFacts(raw);
        if (factAns != null) return factAns;

        // ---------- 5. 主动反问 ----------
        return askBack(raw);
    }

    // ============================================================
    // 主动反问:特性 1 的入口
    // ============================================================

    /**
     * 遇到不懂的 -> 反问。这是原版与原版"感觉像人"的关键。
     *
     * 要先把用户问句里的**主语**抽出来,并对"为什么"类问句保留想查的关系,
     * 这样学到身份后才能继续追问因果。
     */
    private Answer askBack(String raw) {
        String subject = extractSubject(raw);
        String relation = extractWhyRelation(raw);

        // 【不要对语气词/虚词反问或联网】
        // 实测踩过:用户随口说"嗯",她把它当成未知实体,
        // 于是联网查到"嗯,汉语汉字,读作嗯(ng,ń,ň…),部首为口…",
        // 还追问"是你在干嘛呢让嗯出名吗?" —— 完全荒谬。
        // 语气词、应答词、单字虚词只当闲聊,不学、不问。
        if (subject == null || subject.isEmpty() || isFiller(subject)) {
            Answer a = bot.askBack(raw);
            if (a != null && a.text != null && !a.text.isEmpty()) return a;
            return new Answer("唔…这个我暂时不知道，你教教我好不好？",
                    "未知(等待用户教)", "ask");
        }

        // 主语是已知实体,但问的关系查不到 -> 也是反问,但措辞不同
        if (dialog.state() == KikiDialog.State.IDLE
                && (fact.forward(subject).size() > 0 || fact.reverse(subject).size() > 0)) {
            String q = "关于" + subject + "，这个我还不知道，你告诉我？";
            dialog.askWho(subject, relation);   // 进入提问态
            return new Answer(q, "已知实体但关系未知", "ask");
        }

        KikiDialog.Reply r = dialog.askWho(subject, relation);
        return new Answer(r.text, "未知(主动提问)", "ask");
    }

    /**
     * 从问句里抽主语。
     *   "为什么比尔盖茨很出名" -> 比尔盖茨
     *   "卡卡是什么"          -> 卡卡
     *   "微波炉怎么用"        -> 微波炉
     */
    String extractSubject(String raw) {
        String s = KikiBot.trim(raw);
        if (s.isEmpty()) return null;

        // 去掉尾部标点
        s = stripPunct(s);

        // "为什么X…" -> X 就是主语
        for (String p : new String[]{"为什么", "为何", "为啥"}) {
            if (s.startsWith(p)) {
                String rest = s.substring(p.length()).trim();
                // "为什么比尔盖茨很出名" -> 取到第一个副词/动词前
                String sub = cutAtPredicate(rest);
                return sub.isEmpty() ? rest : sub;
            }
        }

        // "X是谁/是什么/怎么样/在哪…" -> 去掉疑问尾巴
        String sub = bot.stripQuestionTail(s);
        return sub.isEmpty() ? s : sub;
    }

    /**
     * 是不是语气词/应答词/虚词 —— 这类词不该当实体去学习或追问。
     *
     * 实测:用户说"嗯",她联网查到词典释义并追问因果关系,完全荒谬。
     * 这里用一个小集合挡住最常见的;真正的判据是"长度 1 且不在实义词里",
     * 但保守取"白名单式"的否定判断,避免误伤"猫""水"这类单字实体。
     */
    static boolean isFiller(String w) {
        if (w == null) return true;
        String t = w.trim();
        if (t.isEmpty()) return true;
        if (t.length() >= 3) return false;
        for (String f : FILLERS) {
            if (t.equals(f)) return true;
        }
        return false;
    }

    /** 语气词 / 应答词 / 常见虚词。命中就当闲聊,不学不问。 */
    private static final String[] FILLERS = {
            "嗯", "哦", "噢", "啊", "呀", "吧", "呢", "吗", "嘛", "哈", "嘿", "喂",
            "呃", "唉", "哎", "诶", "咦", "唔", "嗷", "噢", "呵",
            "好的", "好", "是的", "对", "没错", "行", "可以", "知道了", "明白",
            "谢谢", "再见", "拜拜", "你好", "在吗", "在么", "然后", "就是", "那个",
            "这个", "什么", "怎么", "为什么", "哪", "哪里", "谁",
    };

    /** 把"比尔盖茨很出名"切成"比尔盖茨"(在"很/是/会/能"等词前切) */
    private static String cutAtPredicate(String s) {
        String[] marks = {"很", "非常", "特别", "挺", "是", "会", "能", "可以",
                          "要", "在", "有", "被", "让", "叫", "做"};
        int cut = -1;
        for (String m : marks) {
            int i = s.indexOf(m);
            if (i > 0 && (cut < 0 || i < cut)) cut = i;
        }
        return cut > 0 ? s.substring(0, cut).trim() : s;
    }

    /** 抽"为什么X<关系>"里的关系部分,如"很出名" */
    String extractWhyRelation(String raw) {
        String s = stripPunct(KikiBot.trim(raw));
        for (String p : new String[]{"为什么", "为何", "为啥"}) {
            if (s.startsWith(p)) {
                String rest = s.substring(p.length()).trim();
                String sub = cutAtPredicate(rest);
                if (!sub.isEmpty() && rest.length() > sub.length()) {
                    return rest.substring(sub.length()).trim();
                }
            }
        }
        return null;
    }

    private static String stripPunct(String s) {
        while (s.length() > 0) {
            char c = s.charAt(s.length() - 1);
            if (c == '？' || c == '?' || c == '。' || c == '.' || c == '！'
                    || c == '!' || c == '，' || c == ',' || c == ' ') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }

    // ============================================================
    // 事实检索 + 因果推导
    // ============================================================

    /**
     * 从事实库回答。
     *
     * 支持三类问题:
     *   - 属性/分类:  "卡卡是什么"      -> 猫、哺乳动物、动物、生物
     *   - 逆关系:      "东吴是谁建立的"   -> 孙权
     *   - 因果:        "为什么X很出名"    -> 因为 X 是 Y(前提:学过因果)
     */
    private Answer answerFromFacts(String raw) {
        String s = stripPunct(KikiBot.trim(raw));

        // --- 因果类:"为什么X…" ---
        for (String p : new String[]{"为什么", "为何", "为啥"}) {
            if (s.startsWith(p)) {
                String rest = s.substring(p.length()).trim();
                String sub = cutAtPredicate(rest);
                if (sub.isEmpty()) sub = rest;
                List<String> why = fact.forward(sub, "因为");
                if (!why.isEmpty()) {
                    return new Answer("因为" + join(why, "、"), "因果推理(主动学习所得)", "causal");
                }
                // 没有显式因果,但知道身份 -> 用身份作答(比尔盖茨 -> 微软公司CEO)
                List<String> who = fact.forward(sub, "是");
                if (!who.isEmpty()) {
                    return new Answer("因为他是" + join(who, "、"), "因果推理(由身份推导)", "causal");
                }
                return null;   // 交给反问
            }
        }

        // --- 逆关系:"X是谁建立的/发明的/的首都" ---
        String rel = bot.detectReverseRelation(s);
        if (rel != null) {
            String obj = bot.stripQuestionTail(s);
            if (obj != null && !obj.isEmpty()) {
                List<String> r = fact.reverse(obj, rel);
                if (!r.isEmpty()) {
                    return new Answer(obj + "由" + join(r, "、") + "建立", "逆关系推理", "causal");
                }
            }
        }

        // --- 分类/属性:"X是什么" ---
        String sub = bot.stripQuestionTail(s);
        if (sub != null && !sub.isEmpty() && (s.endsWith("是什么") || s.endsWith("是啥"))) {
            List<String> isa = fact.forward(sub, "是");
            if (!isa.isEmpty()) {
                return new Answer(sub + "是" + join(isa, "、"), "事实检索", "describe");
            }
        }
        return null;
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    // ============================================================
    // 学习:写进内存索引 + 落盘
    // ============================================================

    /** 学到一条事实(供 KikiDialog 回调) */
    void remember(String s, String rel, String o) {
        if (s == null || rel == null || o == null) return;
        if (s.isEmpty() || rel.isEmpty() || o.isEmpty()) return;
        fact.addFact(s, rel, o, "taught");
        if (store != null) {
            try {
                store.learnFact(s, rel, o);
            } catch (Throwable t) {
                Log.e(TAG, "落盘失败: " + t);
            }
        }
        Log.i(TAG, "学到: " + s + " | " + rel + " | " + o);
    }

    /** 把状态机这一轮学到的事实落盘 */
    private void persistLearned() {
        // remember() 已经在写入时落盘了,这里只是语义钩子
    }

    /**
     * 忘掉一条事实 —— 用户说"不是"时调用。
     * 内存事实图与本地数据库都要清,否则重启后旧知识又回来了。
     */
    void forget(String s, String rel, String o) {
        if (s == null || rel == null || o == null) return;
        try {
            if (fact != null) fact.removeFact(s, rel, o);
        } catch (Throwable ignored) {
        }
        try {
            if (store != null) store.forgetFact(s, rel, o);
        } catch (Throwable ignored) {
        }
        android.util.Log.i("kiki-learn", "忘掉事实: " + s + " | " + rel + " | " + o);
    }

    /** 查用户教过的问答 */
    private Answer lookupTaught(String raw) {
        if (store == null) return null;
        try {
            String a = store.findLearned(raw);
            if (a != null && !a.isEmpty()) {
                return new Answer(a, "本地记忆", "learned");
            }
        } catch (Throwable t) {
            Log.e(TAG, "查本地记忆失败: " + t);
        }
        return null;
    }

    /** 教一条知识:进检索库 + 落盘 */
    public void teach(String keyword, String answer) {
        bot.teach(keyword, answer);
        if (store != null) {
            try {
                store.teach(keyword, answer);
            } catch (Throwable t) {
                Log.e(TAG, "教学落盘失败: " + t);
            }
        }
    }

    // ============================================================
    // 判定
    // ============================================================

    /**
     * 是不是"确定性"答案 —— 决定要不要继续往下走。
     *
     * 放行:精确匹配、本地记忆、推理/属性/关系等确定性结果。
     * 拦下:模糊匹配、问候语、兜底话术 —— 它们是"沾边就答",
     *      实测问"比尔盖茨是谁"曾被模糊匹配成自我介绍。
     */
    static boolean isDefinitive(Answer a) {
        if (a == null) return false;
        String k = a.kind == null ? "" : a.kind;
        if (k.isEmpty() || "miss".equals(k) || "ask".equals(k)
                || "fuzzy".equals(k) || "greeting".equals(k)) {
            return false;
        }
        String t = a.text == null ? "" : KikiBot.trim(a.text);
        if (t.length() < 2) return false;
        if (t.contains("不懂你在说") || t.contains("教教我吧")) return false;
        return true;
    }

    public int factCount() {
        return fact.getFactCount();
    }

    public int qaCount() {
        return bot.getQaCount();
    }
}
