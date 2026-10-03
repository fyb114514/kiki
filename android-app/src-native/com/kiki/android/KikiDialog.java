package com.kiki.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话状态机 —— 实现原版 kiki 最核心的能力:**遇到不知道的会主动学**。
 *
 * 原版行为(来自特性文档,逐字对应)
 * --------------------------------
 *   用户：为什么比尔盖茨很出名        (比尔盖茨不在库里)
 *   AI  ：我不知道，他是谁             <- ASKING_WHO   反问
 *   用户：微软公司CEO
 *   AI  ：是微软公司出名吗？            <- ASKING_CAUSE 追问(这里是"因果")
 *   用户：没错
 *   AI  ：明白了                       <- 记住
 *   用户：比尔盖茨很出名
 *   AI  ：因为他是微软公司CEO           <- 用学到的推导
 *
 * 关键设计
 * --------
 * 1. **多轮状态**:不是"一问一答",而是跨轮次的会话状态。所以内部有 state 字段,
 *    并把"当前在问什么"持久化(用户可能中途切屏)。
 * 2. **回答不作答**:处于提问态时,用户说的话**被当作答案**,而不是新问题。
 *    这是原版与普通问答机器人的根本区别。
 * 3. **学会后能推导**:反问阶段拿到的是"他是谁"的答案(比尔盖茨 -> 微软公司),
 *    追问阶段拿到的是"因果"的确认(微软公司出名 -> 所以比尔盖茨出名)。
 *    两个事实合起来才能回答"为什么出名"。
 * 4. **超时保护**:提问后 90 秒没回应就放弃,避免用户说别的话被误当答案。
 */
public class KikiDialog {

    private static final String TAG = "kiki-dialog";

    /** 会话状态 */
    public enum State {
        IDLE,           // 空闲:正常回答
        ASKING_WHO,     // 刚问"X 是谁/是什么",等用户说 X 的身份
        ASKING_CAUSE,   // 刚问"是 Y 让 X 出名/重要吗",等用户确认
    }

    /** 提问超时(毫秒):超时后用户的话不再被当作答案 */
    private static final long PENDING_TTL_MS = 90_000L;

    private State state = State.IDLE;
    private String pendingSubject = null;    // 不懂的那个东西(比尔盖茨)
    private String pendingRelation = null;   // 当时想查的关系(为什么…很出名)
    private String learnedWho = null;

    /**
     * 反问阶段学到的**身份原文**(如"某公司老板")。
     *
     * 为什么要跟 learnedWho 分开存:learnedWho 在追问时会被覆盖成
     * **抽取后的实体**(extractEntity("某公司老板") -> "某公司"),
     * 而身份事实存的是**原文**。用户说"不是"要删掉这条身份时,
     * 必须用原文才删得中 —— 用抽取后的实体去删是删不掉的
     * (自检用例"否定.身份应被删除"就是这么暴露出来的)。
     */
    private String learnedIdentity = null;

    private long stateAt = 0L;

    /** 本轮对话里积累的临时事实(还没落盘) */
    private final List<String[]> learned = new ArrayList<String[]>();

    public interface Sink {
        /** 把学到的事实落盘(主语, 关系, 宾语) */
        void remember(String s, String rel, String o);

        /** 查一条事实,返回所有宾语 */
        List<String> lookup(String s, String rel);

        /** 实体是否已知(库里出现过) */
        boolean knowsEntity(String e);

        /** 追问用的:实体 Y 是否"出名/重要"这类(库里有没有关于它的信息) */
        boolean knowsAnything(String e);

        /**
         * 忘掉一条事实 —— 用户说"不是"时要**真的删掉**。
         *
         * 之前这里没有这个方法,否定分支只回一句"好的，我记下了",
         * 被否定的错误知识一直留着继续参与回答
         * (用户反馈:"我都说了不是了他还记着")。
         */
        void forget(String s, String rel, String o);
    }

    private final Sink sink;

    public KikiDialog(Sink sink) {
        this.sink = sink;
    }

    public State state() {
        return state;
    }

    public String pendingSubject() {
        return pendingSubject;
    }

    /** 超时检查:调用方每次交互前先跑一下 */
    private void expireIfStale() {
        if (state != State.IDLE && System.currentTimeMillis() - stateAt > PENDING_TTL_MS) {
            reset();
        }
    }

    private void reset() {
        state = State.IDLE;
        pendingSubject = null;
        pendingRelation = null;
        learnedWho = null;
        stateAt = 0L;
    }

    /**
     * 结果:要么给出回答,要么提出问题继续等。
     * consumed=true 表示这句话已被状态机消化(调用方不要再走普通问答)。
     */
    public static final class Reply {
        public final String text;      // 要显示/朗读的话
        public final boolean consumed; // 是否已处理(不再走普通检索)
        public final boolean learned;  // 这句话是否让 AI 学到了东西

        Reply(String text, boolean consumed, boolean learned) {
            this.text = text;
            this.consumed = consumed;
            this.learned = learned;
        }

        public static Reply pass() {
            return new Reply(null, false, false);
        }
    }

    /**
     * 处理一句话。返回 pass() 表示状态机不管,交给普通问答链路。
     *
     * @param raw 用户原话
     */
    public Reply handle(String raw) {
        expireIfStale();
        if (raw == null) return Reply.pass();
        String text = raw.trim();
        if (text.isEmpty()) return Reply.pass();

        switch (state) {
            case ASKING_WHO:
                return onAnswerWho(text);
            case ASKING_CAUSE:
                return onAnswerCause(text);
            default:
                return Reply.pass();
        }
    }

    /**
     * 反问阶段:用户告诉了"X 是谁"。
     *
     * 例子:待学的是"比尔盖茨很出名"的**原因**,用户答"微软公司CEO"。
     * 我们要做两件事:
     *   1. 记住 X 的身份(比尔盖茨 -> 微软公司CEO)  —— 让 X 变成已知实体
     *   2. 追问因果:是不是"微软公司"让 X 出名/重要? —— 这一步体现"因果"
     */
    private Reply onAnswerWho(String answer) {
        String subject = pendingSubject;
        String relation = pendingRelation;
        learnedWho = answer;
        learnedIdentity = answer;      // 【原文】存一份,否定时要用它才删得中
        stateAt = System.currentTimeMillis();

        // 1. 记住身份。用"是"作为关系(最常见的表述)
        sink.remember(subject, "是", answer);
        learned.add(new String[]{subject, "是", answer});

        // 2. 从答案里抽出关键实体(用于追问)
        String keyEntity = extractEntity(answer);

        // 3. 追问因果:这个实体是不是让 X 出名的原因?
        state = State.ASKING_CAUSE;
        pendingRelation = relation;   // 保留原问题想查的关系
        if (keyEntity != null && !keyEntity.isEmpty()) {
            learnedWho = keyEntity;
            // 【修 Bug】这里原来硬编码成 "是…让…出名的吗？" ——
            // 于是无论你问什么(为什么天是蓝的 / 为什么水会沸腾),
            // 追问句永远是"…出名的吗？",用户反馈"为什么只有出名这个问题"。
            // 现在改用**用户真实问的那个关系**,并把程度副词(很/非常…)去掉,
            // 否则"让比尔盖茨很出名吗"读起来别扭。
            String base = baseRelation(relation);
            return new Reply("是" + keyEntity + "让" + subject + base + "吗？", true, true);
        }
        // 抽不出实体就只记身份,不再追问
        reset();
        return new Reply("明白了", true, true);
    }

    /**
     * 去掉关系前面的程度副词,让追问句读起来自然。
     *
     *   "很出名" -> "出名"     => 是微软公司让比尔盖茨出名吗?
     *   "会沸腾" -> "沸腾"     => 是加热让水沸腾吗?
     *   "是CEO"  -> "CEO"      => 是微软公司让比尔盖茨CEO吗? (关系不明确时也能用)
     *
     * 关系为空时兜底成"出名" —— 保持旧行为,至少不会生成残缺句子。
     */
    static String baseRelation(String relation) {
        if (relation == null) return "出名";
        String r = relation.trim();
        if (r.isEmpty()) return "出名";
        // 剥掉开头的程度副词 / 判断词
        String[] prefixes = {"非常", "特别", "十分", "极其", "比较", "很", "挺",
                             "是", "会", "能", "可以", "要", "在", "有"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String p : prefixes) {
                if (r.length() > p.length() && r.startsWith(p)) {
                    r = r.substring(p.length());
                    changed = true;
                }
            }
        }
        return r.isEmpty() ? "出名" : r;
    }

    /**
     * 追问阶段:用户确认"是 Y 让 X 出名"。
     *
     * 确认后写入**因果事实**:X 因为 Y 而 <原关系>。
     * 这样下次问"为什么 X 很出名"就能答出来。
     */
    private Reply onAnswerCause(String answer) {
        boolean yes = isYes(answer);
        boolean no = isNo(answer);
        String subject = pendingSubject;
        String cause = learnedWho;
        String relation = pendingRelation;

        if (yes) {
            // 写入因果:X --因为--> Y
            sink.remember(subject, "因为", cause);
            // 同时把原关系也标上(比尔盖茨 很出名)
            if (relation != null && !relation.isEmpty()) {
                sink.remember(subject, relation, "因为" + cause);
            }
            learned.add(new String[]{subject, "因为", cause});
            reset();
            return new Reply("明白了", true, true);
        }
        if (no) {
            // 【修 Bug】用户说"不是"时,之前只是回一句"好的，我记下了",
            // **并没有删掉那个被否定的知识** —— 于是错误事实一直留在库里,
            // 下次问到还会拿它回答(用户反馈:"我都说了不是了他还记着")。
            // 现在真的删:把这次学到的错误因果/身份从事实库里去掉。
            if (subject != null && cause != null) {
                sink.forget(subject, "因为", cause);
            }
            if (subject != null && relation != null && cause != null) {
                sink.forget(subject, relation, "因为" + cause);
            }
            if (learnedIdentity != null && subject != null) {
                // 用**身份原文**删 —— learnedWho 这时已经被覆盖成抽取后的实体了,
                // 拿它去删是删不中的(自检"否定.身份应被删除"暴露过这个问题)。
                sink.forget(subject, "是", learnedIdentity);
            }
            reset();
            return new Reply("好的，我忘掉这个", true, true);
        }
        // 说的不是是/否 -> 当作补充信息,继续记身份
        sink.remember(subject, "是", answer);
        learned.add(new String[]{subject, "是", answer});
        reset();
        return new Reply("明白了", true, true);
    }

    /**
     * 遇到不懂的东西时,启动反问。
     *
     * @param subject  不懂的那个东西(比尔盖茨)
     * @param relation 用户想问的关系(为什么…很出名 -> "很出名"),可为空
     */
    public Reply askWho(String subject, String relation) {
        this.state = State.ASKING_WHO;
        this.pendingSubject = subject;
        this.pendingRelation = relation;
        this.learnedWho = null;
        this.learnedIdentity = null;   // 新一轮提问,旧身份作废
        this.stateAt = System.currentTimeMillis();
        return new Reply("我不知道，" + subject + "是谁？", true, false);
    }

    /** 判断是否肯定回答 */
    static boolean isYes(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        String[] yes = {"是", "对", "没错", "嗯", "是的", "对的", "正确", "有",
                        "算是", "应该是", "yes", "y", "yeah", "ok", "好"};
        for (String y : yes) {
            if (t.equals(y) || t.startsWith(y)) return true;
        }
        return false;
    }

    /** 判断是否否定回答 */
    static boolean isNo(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        String[] no = {"不是", "不", "错", "没有", "不对", "并没有", "no", "n", "nope"};
        for (String n : no) {
            if (t.equals(n) || t.startsWith(n)) return true;
        }
        return false;
    }

    /**
     * 从"微软公司CEO"这类回答里抽出**可作因果主语的实体**。
     *
     * 只剥**职位/身份后缀**,例:
     *     微软公司CEO   -> 微软公司     (剥 CEO)
     *     苹果公司创始人 -> 苹果公司     (剥 创始人)
     *     某公司总裁     -> 某公司       (剥 总裁)
     *     清华大学      -> 清华大学     (无后缀,原样)
     *
     * 【踩过的坑,两次】
     *  1. 第一版只剥一次:剥掉 "CEO" 得到 "微软公司" 就停了 —— 其实这是对的,
     *     但我当时误以为还要继续剥,于是加了循环。
     *  2. 第二版循环剥离时把 "公司/集团/大学" 也放进了后缀表,结果把
     *     "微软公司" 又剥成 "微软"(自检:期望 微软公司,实际 微软)。
     *     **"公司"是实体的一部分,不是身份后缀** —— 所以要剥的只有职位词。
     *  自检用例现在同时覆盖这两类,不会再退化。
     */
    static String extractEntity(String answer) {
        if (answer == null) return null;
        String s = answer.trim();
        if (s.isEmpty()) return null;

        // 只列**职位/身份**后缀。
        //
        // 【第三次踩坑】不要在这里写 "公司CEO" / "的创始人" 这类**组合变体** ——
        // 它们比基本词长,会先被匹配到,把 "微软公司CEO" 整个剥掉只剩 "微软"。
        // 正确做法:只列基本职位词,靠下面的 while 循环**逐步**剥
        //     微软公司CEO --(CEO)--> 微软公司
        // 实测(自检):加上组合变体会退化成 "微软"。
        String[] suffixes = {
                "创始人", "首席执行官", "CEO", "ceo", "总裁", "董事长",
                "老板", "经理", "发明者", "发现者", "总统", "主席",
                "作者", "画家", "导演", "歌手", "演员", "作家",
        };

        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 4) {
            changed = false;
            for (String suf : suffixes) {
                if (s.length() > suf.length() + 1 && s.endsWith(suf)) {
                    String head = trimTailParticle(
                            s.substring(0, s.length() - suf.length()).trim());
                    // 剥完至少剩 2 字,否则宁可保留原文(避免"甲公司"->"甲")
                    if (head.length() >= 2) {
                        s = head;
                        changed = true;
                        break;   // 重新扫一遍,处理"某公司创始人兼CEO"这类叠加
                    }
                }
            }
        }
        return trimTailParticle(s);
    }

    /**
     * 去掉末尾的虚字:"张三的老板" 剥掉 "老板" 后会剩下 "张三的",
     * 所以这里要再把结尾的 "的/了/呢/啊/吧" 清掉。
     * 限制 length > 2:避免把 "甲的" 这种 2 字词剥空。
     */
    private static String trimTailParticle(String s) {
        while (s.length() > 2) {
            char c = s.charAt(s.length() - 1);
            if (c == '的' || c == '了' || c == '呢' || c == '啊' || c == '吧'
                    || c == '是' || c == '在') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }

    /** 本会话学到的事实(调试用) */
    public List<String[]> learnedFacts() {
        return new ArrayList<String[]>(learned);
    }

    /** 保存/恢复状态(用于跨启动保留半截对话) */
    public String serialize() {
        return state.name() + "\t"
                + nz(pendingSubject) + "\t"
                + nz(pendingRelation) + "\t"
                + nz(learnedWho) + "\t"
                + nz(learnedIdentity) + "\t"
                + stateAt;
    }

    public void restore(String s) {
        if (s == null || s.isEmpty()) return;
        String[] p = s.split("\t", -1);
        if (p.length < 6) return;
        try {
            state = State.valueOf(p[0]);
            pendingSubject = emptyToNull(p[1]);
            pendingRelation = emptyToNull(p[2]);
            learnedWho = emptyToNull(p[3]);
            learnedIdentity = emptyToNull(p[4]);
            stateAt = Long.parseLong(p[5]);
            expireIfStale();
        } catch (Throwable t) {
            reset();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 供调用方在给出回答后手动清理 */
    public void clear() {
        reset();
    }
}
