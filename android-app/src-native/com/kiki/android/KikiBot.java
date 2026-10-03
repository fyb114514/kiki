package com.kiki.android;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 对话层:把"引擎 + 调教问答 + 质量闸"组合成 PC 版验证过的完整链路。
 *
 * 回答优先级(与 Windows 版 local_service.py 完全一致):
 *   1. 精确匹配(教过的原话)  -> 记忆库
 *   2. kiki 逻辑引擎          -> 事实/推理,给出确切答案
 *   3. greeting 兜底          -> 问候语(上游把这些存在没有关键词的条目里)
 *   4. 模糊匹配 + 质量闸      -> 实义词覆盖不足就诚实说不会
 *   5. 兜底话术
 *
 * 质量闸存在的理由(实测结论):上游模糊匹配会把"你吃饭了吗"匹配到"你笨吗",
 * 因为候选里只有"你/吗"两个虚词而它们恰好都在问题里 —— 虚词污染了相似度。
 * **答错比答不上更糟**,所以必须剔虚词后判实义词覆盖。
 *
 * ---------------------------------------------------------------------------
 * 本文件是 KikiBot.kt 的逐行等价 Java 移植(纯 android.* / java.*,无任何
 * 第三方依赖,不用 androidx)。
 * ---------------------------------------------------------------------------
 */
public class KikiBot {

    /** 质量闸阈值:候选实义词被输入覆盖的比例低于它就宁可说不会。 */
    private static final double MIN_OVERLAP = 0.5;

    /** 停用词(虚词):与 Kotlin 版逐字一致。 */
    private static final Set<String> STOPWORDS = new HashSet<String>(Arrays.asList(
            "你", "我", "他", "她", "它", "的", "了", "吗", "呢", "啊", "吧", "呀", "嘛", "哦",
            "是", "有", "会", "在", "和", "与", "就", "都", "也", "很", "太", "真",
            "这", "那", "个", "什么", "怎么", "为什么", "多少", "几"
    ));

    // ======================= Kotlin 数据类的替身 =======================

    /** Kotlin {@code Pair<A, B>} 的替身。 */
    public static final class Pair<A, B> {
        public final A first;
        public final B second;

        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    /** Kotlin {@code Triple<A, B, C>} 的替身(本类里用来存"关键词/回答/分类")。 */
    public static final class Triple<A, B, C> {
        public final A first;
        public final B second;
        public final C third;

        public Triple(A first, B second, C third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    // ======================= 数据 =======================

    private final Brain brain;

    /** 调教问答:(关键词, 回答, 分类) */
    private final ArrayList<Triple<String, String, String>> qa =
            new ArrayList<Triple<String, String, String>>();

    /** 开场白(greeting 类条目,上游存成没有关键词的一句话) */
    private final ArrayList<String> greetings = new ArrayList<String>();

    /** 运行时新教的内容(本次会话内有效;持久化由上层负责) */
    private final ArrayList<Pair<String, String>> taught = new ArrayList<Pair<String, String>>();

    public final String notFoundAnswer = "唔嗯...不懂你在说什么呢...教教我吧~";

    // ---------------- 分词(基于词表的最长匹配) ----------------
    /*
     * Kotlin 版把 `words` / `maxWordLen` 的声明写在 init{ loadWords(assets) } 之后,
     * 而 Kotlin 的属性初始化与 init 块是按书写顺序执行的 —— 也就是说 Kotlin 版里
     * loadWords() 跑的时候 `words` 还是 null,`words.add(w)` 抛的 NPE 被
     * `catch (_: Exception) {}` 吞掉,词表实际没被载入(最长匹配静默退化成逐字切)。
     * Java 里字段初始化一律先于构造器体执行,自然就是"先有 words 再 loadWords",
     * 也就是注释里写明的那个本意:词表必须真的载入,质量闸才有实义词可用。
     */
    private final HashSet<String> words = new HashSet<String>(65536);
    private int maxWordLen = 1;

    /** Kotlin 用 Random.Default(randomOrNull);Java 侧自己持有一个 Random。 */
    private final Random random = new Random();

    /**
     * 本地持久化(可空)。对应原作者说的"kiki 学到的东西会保存到本地,
     * 久而久之形成各种用户偏好下的 kiki"。为空时退化为纯内存(不影响功能)。
     */
    private Store store;

    /**
     * 反问答对(还原 2011 原版行为)。
     *
     * 原版遇到不懂的**不会**回一句兜底话术,而是直接问用户,例如:
     *     AI : 他是谁？
     *     用户: 微软公司CEO
     *     之后 AI 就记住了 比尔盖茨 -> 微软公司CEO
     * 所以这里维护一个"正在等待用户回答的题目"。
     */
    private String pendingQuestion = null;   // 待回答的问句(如"他是谁？")
    private String pendingSubject = null;    // 问句指向的主语(如"比尔盖茨")
    private static final long PENDING_TTL_MS = 60000;  // 60 秒内回答才算数
    private long pendingAt = 0L;

    public void setStore(Store s) {
        this.store = s;
    }


    // ======================= 构造 =======================

    public KikiBot(AssetManager assets) {
        this.brain = new Brain(assets);
        loadQa(assets);
        loadGreetings(assets);
        loadWords(assets);
    }

    // ======================= 只读属性(Kotlin 的 val/get) =======================

    /**
     * 暴露内部的事实引擎。
     *
     * 为什么需要:事实库有 1795 条,重复 new 一份会白占内存,也导致
     * "用户教的知识"只进其中一份、两边不同步。共用同一个实例才行。
     */
    public Brain getBrain() {
        return brain;
    }

    public int getFactCount() {
        return brain.getFactCount();
    }

    public int getQaCount() {
        return qa.size();
    }

    public int getPinyinCount() {
        return brain.getPinyinCount();
    }

    // ======================= 载入 =======================

    private void loadQa(AssetManager assets) {
        // Kotlin 版这里没有 try/catch:qa.tsv 缺失会直接抛出,所以 Java 侧把
        // IOException 包成 RuntimeException(非受检)向上抛,行为一致。
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open("qa.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (isBlank(line)) continue;
                // 注意:Java 的 split 默认丢弃末尾空串,Kotlin 的 split(Char) 不丢,
                // 所以必须用 limit = -1 才能等价。
                String[] p = line.split("\t", -1);
                if (p.length >= 2 && !isBlank(p[0]) && !isBlank(p[1])) {
                    qa.add(new Triple<String, String, String>(
                            ktTrim(p[0]),
                            ktTrim(p[1]),
                            p.length > 2 ? ktTrim(p[2]) : ""));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadGreetings(AssetManager assets) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open("greeting.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!isBlank(line)) greetings.add(ktTrim(line));
            }
        } catch (Exception ignored) {
            // Kotlin: catch (_: Exception) {}
        }
    }

    private void loadWords(AssetManager assets) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open("words.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String w = ktTrim(line);
                if (w.length() >= 2) {
                    words.add(w);
                    if (w.length() > maxWordLen) maxWordLen = w.length();
                }
            }
        } catch (Exception ignored) {
            // Kotlin: catch (_: Exception) {}
        }
    }

    // ======================= 分词 / 质量闸 =======================

    /** 去掉虚词,只留实义词 */
    /**
     * 这个字符串是不是词表里的词 —— 供语言总结校验拼接结果。
     * (必须有这一步:否则"基础的基，朋友的友"会拼出任何两字组合。)
     */
    public boolean isWord(String w) {
        return w != null && w.length() >= 2 && words.contains(w);
    }

    /** 分词(给语言总结用;segment 本身是 private) */
    public List<String> segmentPublic(String text) {
        return segment(text);
    }

    public List<String> contentTokens(String text) {
        List<String> out = new ArrayList<String>();
        for (String t : segment(text)) {
            if (!t.isEmpty() && !STOPWORDS.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * 最长匹配分词:优先取词表里最长的词,匹配不到才退化为单字。
     *
     * 为什么必须这样切:质量闸靠"实义词覆盖度"识别答非所问,而按单字切会让
     * "你笨吗"与"你今天吃饭了吗"都退化成"你/吗",重合度被虚词污染
     * (这正是 Windows 版最初踩的坑)。有了词表就能切出"今天/吃饭"这样的实义词。
     */
    private List<String> segment(String text) {
        List<String> out = new ArrayList<String>(text.length());
        int i = 0;
        String t = ktTrim(text);
        while (i < t.length()) {
            boolean matched = false;
            int maxLen = Math.min(maxWordLen, t.length() - i);
            int len = maxLen;
            while (len >= 2) {
                if (words.contains(t.substring(i, i + len))) {
                    out.add(t.substring(i, i + len));
                    i += len;
                    matched = true;
                    break;
                }
                len--;
            }
            if (!matched) {
                out.add(String.valueOf(t.charAt(i)));
                i++;
            }
        }
        return out;
    }

    /** 相似度:候选实义词里有多少出现在输入实义词中(与 PC 版一致) */
    private double overlapRatio(String candidate, Set<String> inputTokens) {
        List<String> ct = contentTokens(candidate);
        if (ct.isEmpty()) return 0.0;
        int hit = 0;
        for (String t : ct) {
            if (inputTokens.contains(t)) hit++;
        }
        return (double) hit / (double) ct.size();
    }

    /**
     * 事实库里的全部实体名 —— 供**拼音纠错**当候选池。
     *
     * 用途:语音识别常把同音字听错(实测"摄氏度"被听成"舒适度"/"四十"),
     * 但拼音是对的,所以拿识别结果的拼音去和这些实体名比对,纠回正确说法。
     */
    public Set<String> entityNames() {
        return brain.entityNames();
    }

    /**
     * 判断一句话是否"像问句"。
     *
     * 用途:反问待答机制的安全闸 —— 用户接下来的**提问**不能被当成上一题的答案。
     * 判据故意宽松(宁可漏判成问句、也不要错吞用户的提问)。
     */
    private static boolean looksLikeQuestion(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        // 问号结尾
        if (t.endsWith("?") || t.endsWith("？")) return true;
        // 疑问词
        String[] marks = {"什么", "是谁", "哪个", "哪些", "哪里", "哪儿", "怎么",
                "为什么", "多少", "几个", "几点", "多大", "多长", "多高", "多远",
                "谁", "吗", "呢", "么", "几"};
        for (String m : marks) {
            if (t.contains(m)) return true;
        }
        return false;
    }

    // ======================= 主入口 =======================

    public Answer reply(String input) {
        String raw = ktTrim(input == null ? "" : input);
        if (raw.isEmpty()) return new Answer(notFoundAnswer, "空输入", "miss");

        // 0. 若有等待回答的问题,这句就是答案 —— 学下来(原版最核心的玩法)
        //
        // 【重要防线】但如果这句**本身就是个问题**,不能当成答案!
        // 实测踩过这个坑(真机自检发现的):
        //     用户: 氧原子序数        -> kiki 反问"氧原子序数 是什么？"
        //     用户: 谁发明了电话      -> 被当成上一句的"答案"吞掉,回"明白了"
        // 结果用户接下来问的每一个问题都会被静默学成答案,答非所问还会污染知识库。
        // 所以这里必须先判断"像不像问句"。
        if (pendingQuestion != null
                && System.currentTimeMillis() - pendingAt < PENDING_TTL_MS
                && !looksLikeQuestion(raw)) {
            String subject = pendingSubject;
            pendingQuestion = null;
            pendingSubject = null;
            if (subject != null && !subject.isEmpty()) {
                teach(subject, raw);
                return new Answer("明白了", "学习: " + subject + " -> " + raw, "learn");
            }
        }
        // 超时就清掉,避免很久以后的一句话被误当成答案
        if (pendingQuestion != null) {
            pendingQuestion = null;
            pendingSubject = null;
        }

        // 1. 精确匹配(教过的原话)
        for (Pair<String, String> it : taught) {
            if (it.first.equals(raw)) {
                if (store != null) recordTopics(raw);
                return new Answer(it.second, "精确匹配", "exact");
            }
        }
        for (Triple<String, String, String> it : qa) {
            if (it.first.equals(raw)) {
                if (store != null) recordTopics(raw);
                return new Answer(it.second, "精确匹配", "exact");
            }
        }

        // 1.5 曾在本地学过的知识(重启后仍在)——按命中次数排序,越常用越先命中
        if (store != null) {
            List<Store.Learned> learned = store.loadAll();
            for (Store.Learned l : learned) {
                if (l.keyword.equals(raw)) {
                    store.hit(l.keyword);
                    return new Answer(l.answer, "本地记忆(用过 " + l.hits + " 次)", "learned");
                }
            }
            // 模糊命中本地知识(优先于内置问答,体现"用得多的排前面")
            Set<String> tks = new HashSet<String>(contentTokens(raw));
            if (!tks.isEmpty()) {
                for (Store.Learned l : learned) {
                    if (overlapRatio(l.keyword, tks) >= MIN_OVERLAP) {
                        store.hit(l.keyword);
                        return new Answer(l.answer, "本地记忆(模糊命中)", "learned");
                    }
                }
            }
        }

        // 2. 逻辑引擎(事实/推理)
        Answer engine = brain.think(raw);
        if (engine != null) {
            if (store != null) recordTopics(raw);
            return engine;
        }

        Set<String> qTokens = new HashSet<String>(contentTokens(raw));

        // 3. greeting 兜底(问候语条目没有关键词,匹配逻辑看不到它们)
        if (!qTokens.isEmpty()) {
            for (String g : greetings) {
                for (String t : contentTokens(g)) {
                    if (qTokens.contains(t)) return new Answer(g, "问候语", "greeting");
                }
            }
        }

        // 4. 模糊匹配 + 质量闸
        double best = 0.0;
        String bestAns = null;
        for (Triple<String, String, String> item : qa) {
            double r = overlapRatio(item.first, qTokens);
            if (r > best) {
                best = r;
                bestAns = item.second;
            }
        }
        for (Pair<String, String> item : taught) {
            double r = overlapRatio(item.first, qTokens);
            if (r > best) {
                best = r;
                bestAns = item.second;
            }
        }
        // "$best" 对 Double 就是 Double.toString,Java 的字符串拼接同语义。
        if (best >= MIN_OVERLAP && bestAns != null) {
            return new Answer(bestAns, "模糊匹配(重合度 " + best + ")", "fuzzy");
        }

        // 5. 兜底:不是回一句"不懂",而是**反问**(还原原版)
        return askBack(raw);
    }

    /**
     * 不懂就反问。还原原版:
     *   "比尔盖茨是谁" -> "他是谁？"  (记下主语,等用户下一句作为答案)
     *   "什么是光合作用" -> "它是什么？"
     *   "XX是什么" -> "XX 是什么？"
     * 问句会被记住,用户下一句话就作为答案学下来。
     */
    /** 兜底反问。public:对话状态机也要用它。 */
    public Answer askBack(String raw) {
        String subject = raw;
        String question = null;
        // 剥掉疑问尾巴,留下真正要问的主语
        String[] tails = {"是谁", "是什么", "是啥", "在哪里", "在哪", "怎么样", "为什么",
                "是什么东西", "有多少", "有几", "是多少"};
        for (String t : tails) {
            if (raw.endsWith(t) && raw.length() > t.length()) {
                subject = raw.substring(0, raw.length() - t.length());
                break;
            }
        }
        if (subject != null && subject.length() > 12) subject = subject.substring(0, 12);
        String q;
        if (raw.contains("是谁") || raw.contains("谁")) q = "他是谁？";
        else if (raw.contains("哪里") || raw.contains("在哪")) q = "它在哪里？";
        else if (raw.contains("为什么")) q = "为什么？";
        else q = subject + " 是什么？";
        pendingQuestion = q;
        pendingSubject = (subject == null || subject.isEmpty()) ? raw : subject;
        pendingAt = System.currentTimeMillis();
        return new Answer(q, "反问(等待用户教)", "ask");
    }

    /**
     * 记录话题词:用得多的词会累积权重,形成"这个用户关心什么"。
     * 这正是原作者说的"久而久之形成各种用户偏好下的 kiki"的最小实现。
     */
    private void recordTopics(String input) {
        if (store == null) return;
        for (String t : contentTokens(input)) store.countTopic(t);
    }

    /** 她眼里的你:最关注的话题(按出现次数) */
    public List<String> topTopics(int n) {
        return store == null ? new ArrayList<String>() : store.topTopics(n);
    }

    /** 教学:存进运行时记忆(上层可持久化) */
    public boolean teach(String keyword, String answer) {
        String kw = ktTrim(keyword == null ? "" : keyword);
        String ans = ktTrim(answer == null ? "" : answer);
        if (kw.isEmpty() || ans.isEmpty()) return false;
        // taught.removeAll { it.first == kw }:删掉所有同关键词的旧条目,保留其余顺序
        for (int i = taught.size() - 1; i >= 0; i--) {
            if (taught.get(i).first.equals(kw)) taught.remove(i);
        }
        taught.add(new Pair<String, String>(kw, ans));
        return true;
    }

    /** 随机开场白(用户打开 App 时她主动说的第一句);列表为空时返回 null(randomOrNull 语义)。 */
    public String randomGreeting() {
        if (greetings.isEmpty()) return null;
        return greetings.get(random.nextInt(greetings.size()));
    }

    // ======================= Kotlin 语义的小工具 =======================

    /**
     * Kotlin 的 {@code Char.isWhitespace()}:{@code Character.isWhitespace(c)
     * || Character.isSpaceChar(c)}。
     * 之所以不直接用 Java 的 trim():它只去 {@code <= 0x20} 的字符,全角空格
     * U+3000、不换行空格 U+00A0 都去不掉,而 Kotlin 版能去掉 —— 中文输入法里
     * U+3000 很常见,不补这一手会让"　"从空输入变成一次未命中查询。
     */
    private static boolean isKtWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Kotlin 的 {@code String.trim()}(包内共用:MainActivity 也调它,保证两边同一套语义)。 */
    /** 对外别名:语义与 ktTrim 相同(Kotlin 的 trim)。 */
    static String trim(String s) {
        return ktTrim(s);
    }

    /** 疑问尾巴表:"X是谁/是什么/在哪…"。剥掉它剩下的就是主语。 */
    private static final String[] QUESTION_TAILS = {
            "是什么东西", "是什么", "是啥", "是谁", "在哪里", "在哪",
            "怎么样", "为什么", "有多少", "有几", "是多少", "怎么用", "怎么",
    };

    /**
     * 剥掉疑问尾巴,留下要问的主语。
     * "卡卡是什么" -> 卡卡    "东吴是谁建立的" -> 东吴(建立 不在表里,另行处理)
     */
    public String stripQuestionTail(String s) {
        if (s == null) return "";
        String t = ktTrim(s);
        // 去掉尾部标点
        while (t.length() > 0) {
            char c = t.charAt(t.length() - 1);
            if (c == '？' || c == '?' || c == '。' || c == '！' || c == '!' || c == '，') {
                t = t.substring(0, t.length() - 1);
            } else break;
        }
        for (String tail : QUESTION_TAILS) {
            if (t.endsWith(tail) && t.length() > tail.length()) {
                return ktTrim(t.substring(0, t.length() - tail.length()));
            }
        }
        // "X是谁<动词>的" 这类:截到"是谁"之前
        int i = t.indexOf("是谁");
        if (i > 0) return ktTrim(t.substring(0, i));
        return ktTrim(t);
    }

    /**
     * 识别"逆关系"问句:X是谁建立的/发明的/写的/发现的。
     * 返回关系词(建立/发明/写/发现),识别不出返回 null。
     */
    public String detectReverseRelation(String s) {
        if (s == null) return null;
        String t = ktTrim(s);
        // 形如 "X是谁<动词>的" / "谁<动词>了X"
        String[][] verbs = {
                {"建立", "建立"}, {"创建", "建立"}, {"创立", "建立"}, {"成立", "建立"},
                {"发明", "发明"}, {"发现", "发现"}, {"写", "写"}, {"编写", "写"},
                {"画", "画"}, {"建造", "建造"}, {"设计", "设计"}, {"提出", "提出"},
                {"开创", "建立"}, {"首都是", "首都"}, {"的省会", "省会"},
        };
        for (String[] v : verbs) {
            if (t.contains(v[0])) return v[1];
        }
        return null;
    }

    static String ktTrim(String s) {
        if (s == null) return "";
        int start = 0;
        int end = s.length();
        while (start < end && isKtWhitespace(s.charAt(start))) start++;
        while (end > start && isKtWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** Kotlin 的 {@code String.isBlank()}。 */
    static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!isKtWhitespace(s.charAt(i))) return false;
        }
        return true;
    }
}
