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
    /** 同音词纠正 + 记忆库消歧 */
    private HomophoneCorrector homophone;
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
        // 同音词纠正器:需要拼音表(pinyin.txt)+ 事实图里的实体
        if (homophone == null && bot != null && fact != null) {
            homophone = new HomophoneCorrector(fact);
            try {
                homophone.indexAll();
                android.util.Log.i("kiki-hr", "同音词索引建立完成: " + homophone.size() + " 组");
            } catch (Throwable t) {
                android.util.Log.w("kiki-hr", "建索引失败: " + t);
            }
        }
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
    /** 管理指令前缀:说 "system清除数据" 就清空全部用户数据 */
    public static final String CMD_CLEAR = "system清除数据";

    public Answer reply(String input) {
        String raw = input == null ? "" : KikiBot.trim(input);

        // ---------- 0. 管理指令(最高优先级) ----------
        // 必须在**同音纠正之前**判断:否则"清除数据"可能被纠正成别的词。
        // 也必须在状态机之前:否则她停在"等你回答"时,这句会被当成答案。
        if (isClearCommand(raw)) {
            String msg = clearAllUserData();
            return new Answer(msg, "管理指令:清除数据", "cmd-clear");
        }

        // 【同音词纠正】识别结果如果不在已知实体里,用拼音比对纠正。
        // 用户教过的生造词/人名/专有名词靠这一步会越用越准。
        if (homophone != null && !raw.isEmpty()) {
            try {
                String fixed = homophone.correct(raw);
                if (fixed != null && !fixed.equals(raw)) {
                    homophone.noteContext(raw);
                    raw = fixed;
                } else {
                    homophone.noteContext(raw);
                }
            } catch (Throwable ignored) {
            }
        }
        if (raw.isEmpty()) {
            return new Answer("嗯？", "空输入", "miss");
        }

        // ---------- 0. 问句意图 ----------
        // 【100 轮测试暴露的问题】"三角函数怎么做" 被她当成"用户在教知识",
        // 回"明白了"并把垃圾写进库,之后互相污染、回答越来越荒谬。
        // 所以先判断意图:是**问句**就不该被当答案消费。
        Intent intent = intentOf(raw);
        boolean isQuestion = (intent != Intent.OTHER);

        // ---------- 1. 对话状态机 ----------
        // 注意:这里**不做**"看起来像问句就跳过"的判断 ——
        // 因为追问阶段的回答可能是"没错",而反问阶段的回答可能是长句。
        // 原版行为是:你正在被问,你说的话就是答案。
        //
        // 【例外】明确的"怎么做/如何"问句要在状态机之前拦下来:
        // 它不是对反问的回答,而是一个新问题。
        if (intent == Intent.HOW) {
            return answerHow(raw);
        }

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

        // 只有"为什么…"这类因果问句才需要在学到身份后继续追问原因。
        // "X 是什么/是谁"问到身份就够了,再追问一句会显得啰嗦
        // (100 轮测试里同一句式出现 46 次,用户反馈"只会问…出名吗")。
        boolean why = (intentOf(raw) == Intent.WHY);
        KikiDialog.Reply r = dialog.askWho(subject, relation, why);
        return new Answer(r.text, "未知(主动提问)", "ask");
    }

    /**
     * 回答"怎么做 / 如何"这类问题。
     *
     * 【诚实优先】她不知道做法时**不该编**,也不该把问句当知识记下来。
     * 原版性格是"不知道就问",这里沿用:
     *   - 库里如果有该主语的"做法"事实,就照答
     *   - 否则老实说不会,并请用户教 —— 但不进入"反复追问"的闭环
     *     (因为做法属于开放性问题,追问因果没有意义)
     */
    private Answer answerHow(String raw) {
        String subj = extractSubject(raw);
        if (subj != null && fact != null) {
            List<String> how = fact.forward(subj, "做法");
            if (!how.isEmpty()) {
                return new Answer(subj + "的做法是" + join(how, "、"), "做法检索", "how");
            }
        }
        if (subj != null && !subj.isEmpty()) {
            return new Answer("这个我还没学会，" + subj + "要怎么做呀？",
                    "不会做法,请求指教", "ask-how");
        }
        return new Answer("这个我还没学会，你教教我好不好？", "不会做法,请求指教", "ask-how");
    }

    /**
     * 是不是"清除数据"指令。
     *
     * 宽松匹配:允许用户说 "system 清除数据" / "system清除数据" /
     * "SYSTEM清除数据" / 带标点 —— 语音识别常把空格和大小写弄乱。
     */
    static boolean isClearCommand(String raw) {
        if (raw == null) return false;
        String t = raw.replace(" ", "").replace("　", "")
                      .replace("，", "").replace(",", "")
                      .replace("。", "").replace(".", "")
                      .replace("！", "").replace("!", "").trim();
        return t.equalsIgnoreCase(CMD_CLEAR);
    }

    /**
     * 执行清除:本地库 + 内存事实图 + 对话状态 + 上下文。
     *
     * 【不删内置知识】内置的 1795 条事实在 assets 里,不属于"用户数据"。
     * 内存事实图用 resetToBuiltin() 重建,只恢复内置部分。
     */
    public String clearAllUserData() {
        int before = 0;
        try {
            if (store != null) {
                before = store.loadFacts().size();
                store.clearUserData();
            }
        } catch (Throwable t) {
            Log.w(TAG, "清库失败: " + t);
        }
        try {
            if (fact != null) fact.resetToBuiltin();
        } catch (Throwable t) {
            Log.w(TAG, "重置事实图失败: " + t);
        }
        // 对话状态可能停在"等你回答",必须复位,否则下一句会被当答案
        try { if (dialog != null) dialog.resetState(); } catch (Throwable ignored) {}
        // 同音索引重建(里面含刚被删掉的用户词)
        try {
            if (homophone != null) {
                homophone = new HomophoneCorrector(fact);
                homophone.indexAll();
            }
        } catch (Throwable ignored) {}
        Log.i("kiki-clear", "清除用户数据完成,原有自学事实 " + before + " 条");
        return "好的，我把之前学的都忘掉了。现在只记得出厂的知识。";
    }

    /** 状态机当前状态名(自检/日志用) */
    public String stateName() {
        try {
            return dialog == null ? "?" : dialog.state().name();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 问句意图 */
    public enum Intent {
        WHAT,   // X 是什么 / 是谁
        WHY,    // 为什么 X …
        HOW,    // 怎么 X / X 怎么做 / 如何 X
        WHERE,  // X 在哪
        OTHER   // 其它(陈述句/闲聊)
    }

    /**
     * 判断问句意图。
     *
     * 【为什么需要它】100 轮自动对话测试暴露:
     *   "三角函数怎么做" 被当成了"用户在教知识" —— 她回"明白了",
     *   还把垃圾写进事实库;之后用这些垃圾推理,回答越来越荒谬。
     * 根因就是**从来没有区分过问句类型**,反问状态机无条件把任何输入当答案。
     */
    public static Intent intentOf(String raw) {
        String s = stripPunct(KikiBot.trim(raw == null ? "" : raw));
        if (s.isEmpty()) return Intent.OTHER;
        for (String p : new String[]{"为什么", "为何", "为啥"}) {
            if (s.startsWith(p)) return Intent.WHY;
        }
        if (s.contains("为什么") || s.contains("为何")) return Intent.WHY;
        // 怎么做 / 如何 / 怎么办
        if (s.startsWith("怎么") || s.startsWith("如何") || s.contains("怎么做")
                || s.contains("怎么办") || s.contains("怎么才能") || s.contains("如何做")) {
            return Intent.HOW;
        }
        if (s.endsWith("是什么") || s.endsWith("是谁") || s.endsWith("是啥")
                || s.endsWith("是什么东西") || s.contains("是什么")) {
            return Intent.WHAT;
        }
        if (s.endsWith("在哪里") || s.endsWith("在哪") || s.endsWith("在哪儿")) {
            return Intent.WHERE;
        }
        return Intent.OTHER;
    }

    /**
     * 主语质量闸:抽出来的主语**像不像一个实体**。
     *
     * 100 轮测试里出现了这些垃圾主语(整句当实体):
     *   "为什么红楼梦很出名"  "微波炉有什么特点"  "康神为什么存在"
     * 它们被当事实存下来后互相污染,回答就变得荒谬。
     * 所以落盘前必须过闸:过长、含疑问词、含标点的都拒收。
     */
    static boolean looksLikeEntity(String w) {
        if (w == null) return false;
        String s = w.trim();
        if (s.isEmpty() || s.length() > 10) return false;

        // 【单字不成实体】
        // 截图实测:"为什么微软公司很出名" 反问后用户答了一个"微",
        // 就被存成 "微软公司 | 是 | 微",之后回答全乱
        // (截图里出现过"康氏是谁是微软公司的一种")。
        // 汉语里单个字几乎不构成实体,而那些单字虚词/量词正是我们不想学的。
        // 所以直接拒收单字。
        if (s.length() < 2) return false;

        // 含疑问词 -> 不是实体
        for (String q : new String[]{"为什么", "为何", "怎么", "如何", "什么", "谁",
                                     "哪里", "哪儿", "哪", "吗", "呢", "多少", "几"}) {
            if (s.contains(q)) return false;
        }
        // 含标点/空格 -> 不是实体
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '，' || c == ',' || c == '。' || c == '、' || c == '？' || c == '?'
                    || c == '！' || c == '!' || c == ' ' || c == '的' && s.length() > 6) {
                return false;
            }
        }
        return true;
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
        if (!sub.isEmpty() && looksLikeEntity(sub)) return sub;

        // 【兜底】还是抽不出,就把疑问尾巴全部剥掉再试。
        // 100 轮测试里 "微波炉有什么特点" 曾整句被当主语,就是少了这一步。
        String fallback = s;
        for (String tail : new String[]{"有什么特点", "是什么东西", "为什么存在",
                                        "怎么办", "怎么做", "怎么用", "如何",
                                        "怎么样", "是什么", "是谁", "在哪里", "在哪"}) {
            int k = fallback.indexOf(tail);
            if (k > 0) fallback = fallback.substring(0, k);
        }
        fallback = stripPunct(fallback).trim();
        if (!fallback.isEmpty() && looksLikeEntity(fallback)) return fallback;

        // 实在抽不出像样的实体 -> 返回 null,走通用反问。
        // 不再硬编一个主语(硬编出来的是"为什么红楼梦很出名"这种整句,
        // 存进库会互相污染,回答就越来越荒谬)。
        return null;
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
    /**
     * 从"X<关系>"里切出实体 X。
     *
     * 【为什么不再用规则法】我在这上面打了四五次补丁,每次都顾此失彼:
     *   标记表里有"要"  -> "康神重要" 被切成 "康神重"(从"重要"中间切了)
     *   给"要"设边界    -> "微软公司" 里的"是"也失效了
     *   长标记优先       -> "量子力学重要" 切成了 "量子力"
     * 原因是:哪个字是实体的结尾,取决于**哪些字构成一个词**,
     * 这不是几个 if 能判出来的。
     *
     * 【现在的做法】用现成的 **4 万词表**(KikiBot.words)词表驱动:
     *   试所有切点,选**最长的、且这个词在词表里存在**的前缀。
     *   量子力学重要 -> "量子力学"在词表里 -> 取它 ✔
     *   微软公司     -> 整个在词表里        -> 取它 ✔
     *   红楼梦很出名  -> "红楼梦"在词表里    -> 取它 ✔
     * 词表里找不到任何前缀时才退回到旧的规则法。
     */
    String cutAtPredicate(String s) {
        if (s == null || s.isEmpty()) return s;

        // ---------- 用分词器的词边界 ----------
        // 这是本轮最重要的改动。之前我用"从长到短试前缀"的土办法,
        // 于是"量子力学"被更短的"量子"抢先(词表里两者都有),
        // "测试甲""康神"这种生造词更是完全抽不出来 ——
        // 而实体抽错 = 用错的键去记,表现就是用户说的"记不住"。
        java.util.List<String> words = null;
        if (bot != null) {
            try { words = bot.segmentPublic(s); } catch (Throwable ignored) {}
        }
        if (words == null || words.isEmpty()) return cutAtPredicateByRules(s);

        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w == null || w.isEmpty()) continue;
            boolean isPred = PREDICATES.contains(w);
            // 第一个内容词
            if (sb.length() == 0) {
                if (isPred || isQuestionWord(w)) continue;   // 跳过疑问词
                sb.append(w);
                continue;
            }
            // 后续:谓词就停;虚词跳过;**单字且非谓词就粘上**(处理生造词)
            if (isPred || isQuestionWord(w)) break;
            if (w.length() == 1 && !isParticle(w.charAt(0))) {
                sb.append(w);        // 未知词粘连:"测试" + "甲" = "测试甲"
            } else if (w.length() >= 2 && sb.length() <= 6) {
                sb.append(w);        // "量子" + "力学"(分词没合起来时兜底)
            } else {
                break;
            }
            if (sb.length() > 8) break;
        }
        String cut = sb.toString();
        if (cut.length() >= 2) return cut;

        return cutAtPredicateByRules(s);
    }

    /** 谓词/程度词:遇到就认为实体已结束 */
    private static final java.util.HashSet<String> PREDICATES =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "很", "非常", "特别", "挺", "是", "会", "能", "可以", "要", "重要",
                    "在", "有", "被", "让", "叫", "做", "怎么样", "出名", "存在",
                    "为什么", "怎么", "如何", "什么", "谁", "哪里", "哪"));

    private static boolean isPredicateWord(String w) {
        return PREDICATES.contains(w);
    }

    private static boolean isQuestionWord(String w) {
        return w.startsWith("为什么") || "怎么".equals(w) || "如何".equals(w)
                || "什么".equals(w) || "谁".equals(w) || "哪里".equals(w);
    }

    /** 虚词单字(助词/语气词)不该粘到实体上 */
    private static boolean isParticle(char c) {
        String p = "的了着过吗呢吧啊呀哦嗯和跟与及或在地得之其";
        return p.indexOf(c) >= 0;
    }

    /**
     * 规则法兜底:在常见的谓词/副词标记处切。
     * 只用于词表里查不到的情况,所以不必追求完美。
     */
    private static String cutAtPredicateByRules(String s) {
        // 长的优先,避免"重要"被"要"从中间切开
        String[] marks = {"非常重要", "是不是", "特别", "非常", "可以", "重要",
                          "很", "挺", "是", "会", "能", "要", "在", "有",
                          "被", "让", "叫", "做"};
        int cut = -1;
        for (String m : marks) {
            int i = s.indexOf(m);
            if (len2(m)) {
                // 多字标记:跳过"词中命中"(前面是汉字 -> 它可能属于更长的词)
                while (i > 0 && isHan(s.charAt(i - 1))) i = s.indexOf(m, i + 1);
            }
            if (i > 0 && (cut < 0 || i < cut)) cut = i;
        }
        return cut > 0 ? s.substring(0, cut).trim() : s;
    }

    private static boolean len2(String m) {
        return m != null && m.length() >= 2;
    }

    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
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

        // 【质量闸】主语必须像个实体。
        // 100 轮自动对话测试里出现了这些垃圾主语(整句当实体):
        //   "为什么红楼梦很出名"  "微波炉有什么特点"  "康神为什么存在"
        // 它们一旦入库就会互相污染,回答越来越荒谬
        // (实测出现过 "三角函数是咖啡的一种")。这里直接拒收。
        if (!isFactWorthSaving(s, rel, o)) {
            Log.i(TAG, "拒收不像实体的事实: " + s + " | " + rel + " | " + o);
            return;
        }

        fact.addFact(s, rel, o, "taught");
        if (store != null) {
            try {
                store.learnFact(s, rel, o);
            } catch (Throwable t) {
                Log.e(TAG, "落盘失败: " + t);
            }
        }
        // 新学的实体补进同音词索引,下次就不会再听错
        if (homophone != null) {
            try { homophone.index(s); homophone.index(o); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "学到: " + s + " | " + rel + " | " + o);
    }

    /** 把状态机这一轮学到的事实落盘 */
    /**
     * 落盘前过一遍主语质量闸。
     * 100 轮测试里"为什么红楼梦很出名"这种整句被当主语存下来,
     * 之后互相污染 -> 回答荒谬。这里直接拒收。
     */
    /**
     * 落盘前的质量闸。
     *
     * 【主语严格、宾语宽松】这是踩过的坑:
     * 我给 looksLikeEntity 加了"单字不成实体"之后,特性 2 自检从 12/12 掉到 11/12 ——
     * 因为有些**正当事实的宾语就是单字**(例如"丙 | 喜欢 | 旧")。
     * 所以:
     *   主语  -> 必须像实体(长度 >= 2、不含疑问词、不含标点)
     *   宾语  -> 只要不太长就行
     * 这样"微软公司 | 是 | 微"这种(用户只答了一个字)被拒,
     * 而正当的单字宾语不受影响。
     */
    private boolean isFactWorthSaving(String s, String rel, String o) {
        if (!looksLikeEntity(s)) return false;
        // 宾语也要 >= 2 字。
        // 截图实测:反问后用户只答了一个"微",就被存成
        //     "微软公司 | 是 | 微"
        // 之后所有回答都乱("康氏是谁是微软公司的一种")。
        // 单字宾语没有信息量,拒收。
        if (o == null || o.length() < 2) return false;
        if (o.length() > 30) return false;
        return true;
    }

    private void persistLearned() {
        // remember() 已经在写入时落盘了,这里只是语义钩子
    }

    /**
     * 忘掉一条事实 —— 用户说"不是"时调用。
     * 内存事实图与本地数据库都要清,否则重启后旧知识又回来了。
     */
    void forget(String s, String rel, String o) {
        if (s == null || rel == null || o == null) return;
        // 【为什么要规范化】Brain.addFact 会把主宾语规范化后再存,
        // 而删除时如果拿原始串去匹配,键对不上就删不掉 ——
        // 用户截图里"说了不是她还记着"就是这个原因(之一)。
        String ns = fact == null ? s : fact.normalize(s);
        String no = fact == null ? o : fact.normalize(o);
        try {
            if (fact != null) {
                fact.removeFact(ns, rel, no);
                // 原始串也删一遍,覆盖"存的时候没规范化"的老数据
                if (!ns.equals(s) || !no.equals(o)) fact.removeFact(s, rel, o);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (store != null) {
                store.forgetFact(s, rel, o);
                if (!ns.equals(s) || !no.equals(o)) store.forgetFact(ns, rel, no);
            }
        } catch (Throwable ignored) {
        }
        android.util.Log.i("kiki-learn", "忘掉事实: " + ns + " | " + rel + " | " + no);
    }

    /**
     * 把本地库里攒下的事实**灌回内存事实图**。
     *
     * 【这是"退出就忘"的根因】persistLearned() 一直在写 Store(fact 表),
     * 但启动时从来没有把 loadFacts() 读回来 ——
     * 于是重启之后内存事实图是空的,所有自学知识全丢。
     */
    void restoreFacts() {
        if (store == null || fact == null) return;
        try {
            int n = 0;
            for (String[] f : store.loadFacts()) {
                if (f == null || f.length < 3) continue;
                fact.addFact(f[0], f[1], f[2], Store.SRC_TAUGHT);
                n++;
            }
            android.util.Log.i("kiki-learn", "启动时恢复自学事实 " + n + " 条");
        } catch (Throwable t) {
            android.util.Log.w("kiki-learn", "恢复事实失败: " + t);
        }
    }

    /** 供联网学习后把事实补进内存图(NetworkLearner 只写库,不知道内存图) */
    void reloadFacts() {
        restoreFacts();
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
