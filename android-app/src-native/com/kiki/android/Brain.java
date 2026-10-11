package com.kiki.android;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * kiki 逻辑引擎（Android / Java 版）
 *
 * 这是 {@code Brain.kt}（Kotlin 版 KikiBrain）的逐行等价 Java 移植，算法一一对应，
 * 只做了语言层面的改写：
 *   - 事实库：TSV 载入内存 + 正查/反查索引（PC 版用 SQLite，这里数据量小，内存更快）
 *   - 推理：分类继承（链式）、传递规则、亲属路径（家谱树 LCA）、否定优先、反义对
 *   - 语言：中英混排归一、近音错字表、拼音同音匹配（多音节分辨）
 *   - 输出：按关系类型选择句式，避免"水的在是100摄氏度"这类病句
 *
 * 设计原则：**引擎只负责"给出确切答案"，答不出就返回 null**，
 * 由上层决定是走模糊匹配、质量闸还是兜底话术。
 *
 * 语言层改写对照（Kotlin -> Java）：
 *   - data class Fact                -> {@link Fact}（静态内部类，字段 public final）
 *   - Triple&lt;A,B,C&gt; / Pair&lt;A,B&gt; -> {@link Triple} / java.util.AbstractMap.SimpleEntry
 *     （java.util 里没有 Pair，Map.Entry 是等价的键值对载体）
 *   - ArrayDeque/LinkedHashSet/...   -> java.util.ArrayDeque/LinkedHashSet/...
 *   - useLines                       -> BufferedReader + readLine() 循环（try-with-resources 关流）
 *   - "$x" 字符串模板                 -> Java 字符串拼接
 *   - when / ?.let / ?:              -> if-else / null 判断 / 三元
 *   - (a to b) 解构、_h 占位          -> 显式 getKey()/getValue()
 */
public class Brain {

    // ======================= Kotlin 数据类的替身 =======================

    /** Kotlin {@code data class Fact} 的 Java 版（静态内部类，字段 public final）。 */
    public static final class Fact {
        public final String s;
        public final String rel;
        public final String o;
        public final String source;

        public Fact(String s, String rel, String o, String source) {
            this.s = s;
            this.rel = rel;
            this.o = o;
            this.source = source;
        }
    }

    /**
     * Kotlin stdlib 的 {@code Triple<A,B,C>} 替身（kotlin.Triple 不能用）。
     *
     * 本类里它承担两个角色，与 Kotlin 版完全一致：
     *   - {@code transitive} 表里的 (r1, r2, outRel)
     *   - {@code toTriple}/{@code matchTriple} 的 (主语, 关系, 宾语)
     */
    public static final class Triple {
        public final String a;
        public final String b;
        public final String c;

        public Triple(String a, String b, String c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    // ======================= 数据 =======================

    private final ArrayList<Fact> facts = new ArrayList<Fact>(2048);
    private final HashMap<String, List<Fact>> bySubject = new HashMap<String, List<Fact>>();
    private final HashMap<String, List<Fact>> byObject = new HashMap<String, List<Fact>>();
    private final LinkedHashSet<String> relations = new LinkedHashSet<String>();
    private final HashMap<Character, String> pinyin = new HashMap<Character, String>();        // 汉字 -> 无声调拼音
    private final HashMap<String, String> pinyinIndex = new HashMap<String, String>();         // 无声调拼音串 -> 实体名(惰性构建)

    /** 事实库条数（Kotlin: val factCount: Int get() = facts.size） */
    public int getFactCount() {
        return facts.size();
    }

    /** 拼音表条数（Kotlin: val pinyinCount: Int get() = pinyin.size） */
    public int getPinyinCount() {
        return pinyin.size();
    }

    /** 关系词条数（Kotlin: val relationCount: Int get() = relations.size） */
    public int getRelationCount() {
        return relations.size();
    }

    /** 保存下来,供"清除数据"后重新加载内置知识 */
    private final AssetManager assetsRef;

    public Brain(AssetManager assets) {
        this.assetsRef = assets;
        loadFacts(assets);
        loadPinyin(assets);
        loadRelations(assets);
    }

    /**
     * 重置为**只有内置知识**的状态。
     *
     * 为什么用"全部清空 + 重新加载",而不是"挑出用户教的那几条删掉":
     * 事实图里内置事实和用户事实混在同一个列表与两个索引里,
     * 没有来源标记,逐条辨认既慢又容易漏。
     * 直接重建最可靠 —— 内置知识本来就在 assets 里,随时能读回来。
     *
     * 注意:**拼音表与关系表保留**(它们是语言基础,不是用户数据),
     * 所以这两个不清、也不重载。
     */
    public void resetToBuiltin() {
        if (assetsRef == null) return;
        facts.clear();
        bySubject.clear();
        byObject.clear();
        loadFacts(assetsRef);
    }

    private void loadFacts(AssetManager assets) {
        // Kotlin: assets.open("facts.tsv").bufferedReader(Charsets.UTF_8).useLines { lines -> ... }
        // useLines -> BufferedReader + readLine() 循环；Kotlin 这里没有 catch，
        // IOException 会直接抛出，所以 Java 侧包成非受检的 RuntimeException 向上抛。
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open("facts.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (kTrim(line).isEmpty()) continue;                       // line.isBlank()
                // 必须 -1：Java 的 split 默认丢弃末尾空串，Kotlin 的 split(Char) 不丢
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                Fact f = new Fact(kTrim(p[0]), kTrim(p[1]), kTrim(p[2]),
                        p.length > 3 ? kTrim(p[3]) : "");
                if (f.s.isEmpty() || f.rel.isEmpty() || f.o.isEmpty()) continue;
                facts.add(f);
                getOrPutList(bySubject, f.s).add(f);
                getOrPutList(byObject, f.o).add(f);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadPinyin(AssetManager assets) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open("pinyin.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf('\t');
                if (i <= 0) continue;
                String ch = line.substring(0, i);
                String py = kTrim(line.substring(i + 1));
                if (ch.length() == 1 && !py.isEmpty()) pinyin.put(ch.charAt(0), py);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadRelations(AssetManager assets) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(assets.open("relations.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String rel = kTrim(line);
                if (!rel.isEmpty()) relations.add(rel);
            }
        } catch (Exception ignored) {
            // 关系词清单缺失不影响主流程(动态关系问句会退化)
        }
    }

    // ---------------- 查询 ----------------

    /** Kotlin: fun forward(s: String, rel: String? = null): List&lt;String&gt; */
    public List<String> forward(String s, String rel) {
        List<Fact> bucket = bySubject.get(s);
        if (bucket == null) return Collections.emptyList();                // bySubject[s] ?: emptyList()
        ArrayList<String> out = new ArrayList<String>();
        for (Fact f : bucket) {
            if (rel == null || f.rel.equals(rel)) out.add(f.o);
        }
        return out;
    }

    /** Kotlin 默认参数 rel = null 的 Java 重载。 */
    public List<String> forward(String s) {
        return forward(s, null);
    }

    /** Kotlin: fun reverse(o: String, rel: String? = null): List&lt;String&gt; */
    public List<String> reverse(String o, String rel) {
        List<Fact> bucket = byObject.get(o);
        if (bucket == null) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<String>();
        for (Fact f : bucket) {
            if (rel == null || f.rel.equals(rel)) out.add(f.s);
        }
        return out;
    }

    /** Kotlin 默认参数 rel = null 的 Java 重载。 */
    public List<String> reverse(String o) {
        return reverse(o, null);
    }

    /** Kotlin: fun entityNames(): Set&lt;String&gt; = bySubject.keys */
    public Set<String> entityNames() {
        return bySubject.keySet();
    }

    // ---------------- 文本归一化 ----------------

    // Kotlin: private val normalizeMap = mapOf(...)，mapOf 保持插入顺序（LinkedHashMap）
    private final LinkedHashMap<String, String> normalizeMap = new LinkedHashMap<String, String>();
    {
        normalizeMap.put("reimu", "");
        normalizeMap.put("alice", "");
        normalizeMap.put("sakuya", "咲夜");
        normalizeMap.put("android", "安卓");
        normalizeMap.put("ai", "人工智能");
        normalizeMap.put("pc", "电脑");
    }

    private final LinkedHashMap<String, String> phoneticFix = new LinkedHashMap<String, String>();
    {
        phoneticFix.put("卡卡西", "卡卡");
        phoneticFix.put("零梦", "");
        phoneticFix.put("林梦", "");
        phoneticFix.put("爱丽斯", "");
        phoneticFix.put("波利", "博丽");
    }

    public String normalize(String text) {
        String t = kTrim(text).replaceAll("[\\s\\u3000]+", "");
        // Kotlin 的 lowercase() = toLowerCase(Locale.ROOT)（与系统 locale 无关）
        String low = t.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : normalizeMap.entrySet()) {
            if (!e.getKey().equals(e.getValue())) low = low.replace(e.getKey(), e.getValue());
        }
        t = low;
        for (Map.Entry<String, String> e : phoneticFix.entrySet()) {
            if (!e.getKey().equals(e.getValue())) t = t.replace(e.getKey(), e.getValue());
        }
        // 全角 -> 半角
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(c >= 0xFF01 && c <= 0xFF5E ? (char) (c - 0xFEE0) : c);
        }
        return trimChars(sb.toString(), "。！？!?~～,，、 ");
    }

    /** 疑难句尾语气词剥离 */
    private final String tailWords = "呢啊呀哦吧嘛";

    public String stripTail(String text) {
        String t = kTrim(text);
        while (!t.isEmpty() && (tailWords.indexOf(t.charAt(t.length() - 1)) >= 0
                || "？?！!。，,".indexOf(t.charAt(t.length() - 1)) >= 0)) {
            t = t.substring(0, t.length() - 1);                            // dropLast(1)
        }
        for (String tail : new String[]{"怎么样", "如何"}) {
            if (t.endsWith(tail)) t = t.substring(0, t.length() - tail.length());
        }
        return kTrim(t);
    }

    // ---------------- 拼音（多音节分辨） ----------------

    public String pinyinKey(String word) {
        if (word.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            String py = pinyin.get(word.charAt(i));
            if (py == null) return null;                                   // pinyin[c] ?: return null
            sb.append(py);
        }
        return sb.toString();
    }

    private void ensurePinyinIndex() {
        if (!pinyinIndex.isEmpty()) return;
        for (String e : entityNames()) {
            String k = pinyinKey(e);
            if (k == null) continue;
            if (!pinyinIndex.containsKey(k)) pinyinIndex.put(k, e);         // putIfAbsent
        }
    }

    /** 同音实体:kiki -> kiki */
    public String homophoneEntity(String word) {
        if (word.length() < 2) return null;
        ensurePinyinIndex();
        String key = pinyinKey(word);
        if (key == null) return null;                                      // pinyinKey(word) ?: return null
        return pinyinIndex.get(key);
    }

    // ---------------- 实体归一 ----------------

    public String fuzzyFind(String raw) {
        String t = kTrim(raw);
        if (t.isEmpty()) return t;
        String fix = phoneticFix.get(t);
        if (fix != null) return fix;                                       // phoneticFix[t]?.let { return it }
        if (bySubject.containsKey(t)) return t;
        // 与事实库实体做包含匹配
        for (String e : entityNames()) {
            if (!e.equals(t) && e.length() > 1 && t.contains(e)) return e;
        }
        for (String e : entityNames()) {
            if (!e.equals(t) && e.length() > 1 && e.contains(t)) return e;
        }
        // 拼音同音
        String h = homophoneEntity(t);
        if (h != null) return h;                                           // homophoneEntity(t)?.let { return it }
        // 叠字归一
        for (String dup : new String[]{"狗狗", "猫猫", "鸟鸟", "车车"}) {
            if (t.equals(dup)) return fuzzyFind(dup.substring(0, 1));
        }
        return t;
    }

    public String normEntity(String raw) {
        return fuzzyFind(stripMeasure(stripTail(raw)));
    }

    private final String measureChars = "个只条张本头匹件位名门辆架朵把颗";

    public String stripMeasure(String text) {
        String t = kTrim(text);
        if (t.length() < 2) return t;
        // "一个猫" -> 去掉"一个";"张伟" 不能动(张不是量词误判)
        if ("一这那".indexOf(t.charAt(0)) >= 0 && t.length() >= 3
                && measureChars.indexOf(t.charAt(1)) >= 0) return t.substring(2);
        return t;
    }

    // ---------------- 关系词 ----------------

    private final LinkedHashMap<String, List<String>> relAliases = new LinkedHashMap<String, List<String>>();
    {
        relAliases.put("是", Arrays.asList("是", "就是", "属于"));
        relAliases.put("有", Arrays.asList("有", "拥有", "具有"));
        relAliases.put("喜欢", Arrays.asList("喜欢", "最爱", "爱"));
        relAliases.put("会", Arrays.asList("会", "能", "可以"));
        relAliases.put("住在", Arrays.asList("住在", "生活在", "位于"));
        relAliases.put("朋友", Arrays.asList("朋友", "好友"));
        relAliases.put("爸爸", Arrays.asList("爸爸", "父亲", "爹"));
        relAliases.put("妈妈", Arrays.asList("妈妈", "母亲", "娘"));
        relAliases.put("爷爷", Arrays.asList("爷爷", "祖父"));
        relAliases.put("奶奶", Arrays.asList("奶奶", "祖母"));
        relAliases.put("哥哥", Arrays.asList("哥哥", "兄长"));
        relAliases.put("弟弟", Arrays.asList("弟弟"));
        relAliases.put("姐姐", Arrays.asList("姐姐"));
        relAliases.put("妹妹", Arrays.asList("妹妹"));
        relAliases.put("妻子", Arrays.asList("妻子", "老婆"));
        relAliases.put("丈夫", Arrays.asList("丈夫", "老公"));
        relAliases.put("儿子", Arrays.asList("儿子"));
        relAliases.put("女儿", Arrays.asList("女儿"));
    }

    private final LinkedHashMap<String, String> measureRel = new LinkedHashMap<String, String>();
    {
        measureRel.put("长", "长度");
        measureRel.put("高", "高度");
        measureRel.put("大", "面积");
        measureRel.put("重", "重量");
        measureRel.put("久", "时长");
        measureRel.put("远", "距离");
        measureRel.put("深", "深度");
        measureRel.put("宽", "宽度");
        measureRel.put("厚", "厚度");
    }

    /** Kotlin 原版里这个字段没有被任何代码使用，为保持逐行对应照样保留。 */
    private final LinkedHashSet<String> nonIsa = new LinkedHashSet<String>(Arrays.asList(
            "首都", "省会", "符号", "人口", "面积", "沸点", "光速", "住在", "位于",
            "绕着", "有", "会", "喜欢", "妻子", "丈夫", "朋友"));

    private boolean isaRel(String rel) {
        return "是".equals(rel) || "属于".equals(rel);
    }

    public String canonRel(String word) {
        String w = stripTail(word);
        if (w.isEmpty()) return null;
        for (Map.Entry<String, List<String>> e : relAliases.entrySet()) {
            if (w.equals(e.getKey()) || e.getValue().contains(w)) return e.getKey();
        }
        return relations.contains(w) ? w : null;
    }

    private List<String> relCandidates(String raw) {
        String w = stripTail(raw);
        if (w.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String canon = canonRel(w);
        if (canon != null) out.add(canon);                                 // canonRel(w)?.let { out.add(it) }
        if (relations.contains(w)) out.add(w);
        if (w.length() >= 2) {
            for (String k : relations) {
                if (k.length() > w.length() && k.endsWith(w)) out.add(k);
            }
        }
        return new ArrayList<String>(out);                                 // out.toList()
    }

    // ---------------- 关系链 / 继承 ----------------

    private List<String> isaChain(String entity) {
        return isaChain(entity, 0, new HashSet<String>());
    }

    /** Kotlin: isaChain(entity, depth = 0, seen = HashSet()) */
    private List<String> isaChain(String entity, int depth, Set<String> seen) {
        if (depth > 6 || !seen.add(entity)) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<String>();
        for (String o : forward(entity, "是")) {
            if (seen.contains(o)) continue;
            out.add(o);
            out.addAll(isaChain(o, depth + 1, seen));
        }
        return out;
    }

    /** 查关系:直接事实 -> 分类继承 */
    private List<String> lookup(String entity, String rel) {
        List<String> direct = forward(entity, rel);
        if (!direct.isEmpty()) return direct;
        for (String cls : isaChain(entity)) {
            List<String> got = forward(cls, rel);
            if (!got.isEmpty()) return got;
        }
        return Collections.emptyList();
    }

    /** 查否定关系:不会/没有/不喜欢,支持继承(企鹅不会飞 要盖过 鸟会飞) */
    private List<String> lookupNeg(String entity, String rel) {
        String neg;
        if ("会".equals(rel) || "能".equals(rel) || "可以".equals(rel)) {
            neg = "不" + rel;
        } else if ("有".equals(rel)) {
            neg = "没有";
        } else {
            neg = "不" + rel;
        }
        ArrayList<String> targets = new ArrayList<String>();                // listOf(entity) + isaChain(entity)
        targets.add(entity);
        targets.addAll(isaChain(entity));
        for (String t : targets) {
            List<String> got = forward(t, neg);
            if (!got.isEmpty()) return got;
        }
        return Collections.emptyList();
    }

    private final Triple[] transitive = new Triple[]{
            new Triple("爸爸", "爸爸", "爷爷"),
            new Triple("妈妈", "妈妈", "外婆"),
            new Triple("爸爸", "妈妈", "奶奶"),
            new Triple("妈妈", "爸爸", "外公"),
            new Triple("儿子", "儿子", "孙子"),
            new Triple("朋友", "朋友", "朋友")
    };

    /** Kotlin: 返回 List&lt;Pair&lt;String,String&gt;&gt;，Java 用 Map.Entry 承载键值对。 */
    private List<Map.Entry<String, String>> transitive(String a, String rel) {
        ArrayList<Map.Entry<String, String>> out = new ArrayList<Map.Entry<String, String>>();
        for (Triple t : transitive) {
            if (!t.a.equals(rel) && !t.c.equals(rel)) continue;            // r1 != rel && outRel != rel
            for (String mid : forward(a, t.a)) {
                if (mid.equals(a)) continue;
                for (String c : forward(mid, t.b)) {
                    out.add(new AbstractMap.SimpleEntry<String, String>(c,
                            a + "的" + t.a + "是" + mid + "," + mid + "的" + t.b + "是" + c));
                }
            }
        }
        return out;
    }

    // ---------------- 亲属路径（家谱树 LCA） ----------------

    private final LinkedHashSet<String> parentRels = new LinkedHashSet<String>(Arrays.asList("爸爸", "妈妈"));
    private final LinkedHashSet<String> childRels = new LinkedHashSet<String>(Arrays.asList("儿子", "女儿"));
    private final LinkedHashSet<String> spouseRels = new LinkedHashSet<String>(Arrays.asList("妻子", "丈夫"));

    private static final class Kin {
        final HashMap<String, Set<String>> parent;
        final HashMap<String, Set<String>> child;
        final HashMap<String, Set<String>> spouse;
        final HashMap<String, String> relName;

        Kin(HashMap<String, Set<String>> parent, HashMap<String, Set<String>> child,
            HashMap<String, Set<String>> spouse, HashMap<String, String> relName) {
            this.parent = parent;
            this.child = child;
            this.spouse = spouse;
            this.relName = relName;
        }
    }

    private Kin kinEdges() {
        HashMap<String, Set<String>> parent = new HashMap<String, Set<String>>();
        HashMap<String, Set<String>> child = new HashMap<String, Set<String>>();
        HashMap<String, Set<String>> spouse = new HashMap<String, Set<String>>();
        HashMap<String, String> relName = new HashMap<String, String>();
        for (Fact f : facts) {
            if (parentRels.contains(f.rel)) {
                getOrPutSet(parent, f.s).add(f.o);
                getOrPutSet(child, f.o).add(f.s);
                relName.put(f.s + "|" + f.o, f.rel);
            } else if (childRels.contains(f.rel)) {
                getOrPutSet(child, f.s).add(f.o);
                getOrPutSet(parent, f.o).add(f.s);
                relName.put(f.o + "|" + f.s, f.rel);
            } else if (spouseRels.contains(f.rel)) {
                getOrPutSet(spouse, f.s).add(f.o);
                getOrPutSet(spouse, f.o).add(f.s);
                relName.put(f.s + "|" + f.o, f.rel);
            }
        }
        return new Kin(parent, child, spouse, relName);
    }

    /** Kotlin 默认参数 maxUp = 4 的调用方在这里显式传 4。 */
    private HashMap<String, Integer> ancestorIndex(String node, Map<String, Set<String>> parent, int maxUp) {
        HashMap<String, Integer> idx = new HashMap<String, Integer>();
        idx.put(node, 0);
        ArrayList<String> frontier = new ArrayList<String>();               // listOf(node)
        frontier.add(node);
        for (int d = 1; d <= maxUp; d++) {
            ArrayList<String> next = new ArrayList<String>();
            for (String cur : frontier) {
                for (String p : orEmptySet(parent, cur)) {
                    if (!idx.containsKey(p)) {
                        idx.put(p, d);
                        next.add(p);
                    }
                }
            }
            if (next.isEmpty()) break;
            frontier = next;
        }
        return idx;
    }

    /** 路径元素:方向/主语/宾语/关系名 */
    private static final class Edge {
        final String dir;
        final String a;
        final String b;
        final String rel;

        Edge(String dir, String a, String b, String rel) {
            this.dir = dir;
            this.a = a;
            this.b = b;
            this.rel = rel;
        }
    }

    private List<Edge> kinPath(String a, String b) {
        return kinPath(a, b, 4);
    }

    /** Kotlin: kinPath(a, b, maxUp = 4) */
    private List<Edge> kinPath(String a, String b, int maxUp) {
        if (a.equals(b)) return Collections.emptyList();
        Kin kin = kinEdges();
        // 同父母即兄弟姐妹
        for (String p : orEmptySet(kin.parent, a)) {
            if (!b.equals(a) && orEmptySet(kin.child, p).contains(b)) {
                return Collections.singletonList(new Edge("down", a, b, "兄弟姐妹"));
            }
        }
        // (kin.spouse[a] ?: emptySet()).firstOrNull { it == b }?.let { ... }
        for (String sp : orEmptySet(kin.spouse, a)) {
            if (sp.equals(b)) {
                String rn = kin.relName.get(a + "|" + b);
                return Collections.singletonList(new Edge("spouse", a, b, rn != null ? rn : "配偶"));
            }
        }
        HashMap<String, Integer> ia = ancestorIndex(a, kin.parent, maxUp);
        HashMap<String, Integer> ib = ancestorIndex(b, kin.parent, maxUp);
        // ia.keys.intersect(ib.keys)：保持 ia.keys 的顺序，等价于 Kotlin 的 intersect
        LinkedHashSet<String> common = new LinkedHashSet<String>(ia.keySet());
        common.retainAll(ib.keySet());
        if (!common.isEmpty()) {
            // common.minByOrNull { (ia[it] ?: 99) + (ib[it] ?: 99) }：严格小于 => 取第一个最小
            String ancestor = null;
            int bestScore = Integer.MAX_VALUE;
            for (String k : common) {
                Integer va = ia.get(k);
                Integer vb = ib.get(k);
                int score = (va != null ? va : 99) + (vb != null ? vb : 99);
                if (score < bestScore) {
                    bestScore = score;
                    ancestor = k;
                }
            }
            // 关键：upChain 传的是"起点自己的祖先距离表"（a 用 ia，b 用 ib）
            List<Edge> up = upChain(a, ancestor, ia, kin, 8);
            List<Edge> down = upChain(b, ancestor, ib, kin, 8);
            if (up != null && down != null) {
                ArrayList<Edge> downPath = new ArrayList<Edge>();
                for (int i = down.size() - 1; i >= 0; i--) {                // down.reversed()
                    Edge e = down.get(i);
                    // 反向成"父->子"方向,用子女称谓
                    String pr = kin.relName.get(e.a + "|" + e.b);
                    if (pr == null) pr = kin.relName.get(e.b + "|" + e.a);
                    if (pr == null) pr = "";
                    downPath.add(new Edge("down", e.b, e.a, downRelName(pr)));
                }
                ArrayList<Edge> path = new ArrayList<Edge>(up);             // up + downPath
                path.addAll(downPath);
                return path;
            }
        }
        // 配偶一侧有血缘
        for (String sp : orEmptySet(kin.spouse, a)) {
            List<Edge> sub = kinPath(sp, b, maxUp);
            if (sub != null) {
                ArrayList<Edge> path = new ArrayList<Edge>();
                String rn = kin.relName.get(a + "|" + sp);
                path.add(new Edge("spouse", a, sp, rn != null ? rn : "配偶"));
                path.addAll(sub);
                return path;
            }
        }
        return null;
    }

    private List<Edge> upChain(String start, String target, Map<String, Integer> dist, Kin kin) {
        return upChain(start, target, dist, kin, 8);
    }

    /** Kotlin 默认参数 limit = 8；BFS 队列元素是 Pair&lt;String, List&lt;Edge&gt;&gt;。 */
    private List<Edge> upChain(String start, String target, Map<String, Integer> dist, Kin kin, int limit) {
        if (start.equals(target)) return Collections.emptyList();
        ArrayDeque<Map.Entry<String, List<Edge>>> queue = new ArrayDeque<Map.Entry<String, List<Edge>>>();
        queue.addLast(new AbstractMap.SimpleEntry<String, List<Edge>>(start, Collections.<Edge>emptyList()));
        HashSet<String> seen = new HashSet<String>();
        seen.add(start);                                                   // HashSet<String>().apply { add(start) }
        while (!queue.isEmpty()) {
            Map.Entry<String, List<Edge>> item = queue.removeFirst();
            String cur = item.getKey();                                    // val (cur, chain) = ...
            List<Edge> chain = item.getValue();
            if (chain.size() >= limit) continue;
            for (String p : orEmptySet(kin.parent, cur)) {
                if (!dist.containsKey(p) || seen.contains(p)) continue;
                ArrayList<Edge> nc = new ArrayList<Edge>(chain);            // chain + Edge(...)
                String rn = kin.relName.get(cur + "|" + p);
                nc.add(new Edge("up", cur, p, rn != null ? rn : "爸爸/妈妈"));
                if (p.equals(target)) return nc;
                seen.add(p);
                queue.addLast(new AbstractMap.SimpleEntry<String, List<Edge>>(p, nc));
            }
        }
        return null;
    }

    private final LinkedHashMap<String, String> downRel = new LinkedHashMap<String, String>();
    {
        downRel.put("爸爸", "子女");
        downRel.put("妈妈", "子女");
        downRel.put("爷爷", "孙辈");
        downRel.put("奶奶", "孙辈");
        downRel.put("哥哥", "弟弟/妹妹");
        downRel.put("弟弟", "哥哥/姐姐");
        downRel.put("姐姐", "弟弟/妹妹");
        downRel.put("妹妹", "哥哥/姐姐");
        downRel.put("丈夫", "妻子");
        downRel.put("妻子", "丈夫");
    }

    private String downRelName(String parentRel) {
        String v = downRel.get(parentRel);
        return v != null ? v : "子女";                                      // downRel[parentRel] ?: "子女"
    }

    /** Kotlin 的键是 {@code (ups to downs)}，Java 用 "ups,downs" 编码（无歧义）。 */
    private final LinkedHashMap<String, String> kinTerms = new LinkedHashMap<String, String>();
    {
        kinTerms.put("1,0", "父母");
        kinTerms.put("2,0", "祖父母");
        kinTerms.put("3,0", "曾祖父母");
        kinTerms.put("0,1", "子女");
        kinTerms.put("0,2", "孙辈");
        kinTerms.put("1,1", "兄弟姐妹");
        kinTerms.put("2,1", "叔伯/舅舅/姑姑/姨");
        kinTerms.put("1,2", "侄辈");
        kinTerms.put("2,2", "堂表兄弟姐妹");
        kinTerms.put("3,1", "曾祖辈的兄弟姐妹");
    }

    /** Kotlin: 返回 Pair&lt;String?, String&gt;（term 可能为 null）。 */
    private Map.Entry<String, String> labelPath(List<Edge> path) {
        int ups = 0;
        int downs = 0;
        boolean spouses = false;
        for (Edge e : path) {
            if ("up".equals(e.dir)) ups++;                                  // path.count { it.dir == "up" }
            if ("down".equals(e.dir)) downs++;
            if ("spouse".equals(e.dir)) spouses = true;                     // path.any { it.dir == "spouse" }
        }
        LinkedHashSet<String> rels = new LinkedHashSet<String>();
        for (Edge e : path) rels.add(e.rel);                                // path.map { it.rel }.toSet()
        boolean sibling = false;
        for (String r : rels) {
            if ("兄弟姐妹".equals(r) || "弟弟/妹妹".equals(r) || "哥哥/姐姐".equals(r)) {
                sibling = true;
                break;
            }
        }
        String term = sibling ? "兄弟姐妹" : kinTerms.get(ups + "," + downs);
        if (spouses && term == null) term = "姻亲";
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {                             // joinToString(" -> ") { ... }
            if (i > 0) detail.append(" -> ");
            Edge e = path.get(i);
            detail.append(e.a).append("的").append(e.rel).append("是").append(e.b);
        }
        return new AbstractMap.SimpleEntry<String, String>(term, detail.toString());
    }

    private Answer directRel(String a, String b) {
        for (Fact f : factsOf(bySubject, a)) {
            if (f.o.equals(b) && !isaRel(f.rel)) {
                return new Answer(f.s + "的" + f.rel + "是" + f.o + "。", "直接事实", "rel");
            }
        }
        for (Fact f : factsOf(bySubject, b)) {
            if (f.o.equals(a) && !isaRel(f.rel)) {
                return new Answer(f.s + "的" + f.rel + "是" + f.o + "。", "直接事实", "rel");
            }
        }
        return null;
    }

    private Answer crossRel(String a, String b) {
        Answer direct = directRel(a, b);
        if (direct != null) return direct;                                  // directRel(a, b)?.let { return it }
        List<Edge> path = kinPath(a, b);
        if (path != null) {
            Map.Entry<String, String> labeled = labelPath(path);
            String term = labeled.getKey();                                 // val (term, detail) = labelPath(path)
            String detail = labeled.getValue();
            if (term != null) {
                if ("兄弟姐妹".equals(term) || "堂表兄弟姐妹".equals(term)
                        || "父母".equals(term) || "子女".equals(term)) {
                    return new Answer(a + "和" + b + "是" + term + "。", detail, "infer");
                } else {
                    return new Answer(a + "是" + b + "的" + term + "(" + detail + ")。", detail, "infer");
                }
            }
            return new Answer(a + "和" + b + "的关系是:" + detail + "。", detail, "infer");
        }
        // 共享中间实体
        for (Fact f1 : factsOf(bySubject, a)) {
            for (Fact f2 : factsOf(bySubject, b)) {
                if (f1.o.equals(f2.o) && !isaRel(f1.rel) && !isaRel(f2.rel)) {
                    return new Answer(a + "的" + f1.rel + "和" + b + "的" + f2.rel + "是同一个人(" + f1.o
                                    + "),所以他们是亲戚关系。",
                            a + "的" + f1.rel + "=" + f1.o, "infer");
                }
            }
        }
        return null;
    }

    // ---------------- 句式 ----------------

    private String phrase(String entity, String rel, String obj) {
        String r = kTrim(rel);
        String o = kTrim(obj);
        if (r.isEmpty()) return entity + o;
        if (Arrays.asList("有", "长", "会", "能", "可以", "喜欢", "讨厌", "需要", "没有", "不会").contains(r)) {
            return entity + r + o;
        }
        if (isaRel(r)) return entity + "是" + o;
        if ("绕着".equals(r)) return entity + "绕着" + o;
        if ("走得".equals(r) || "跑得".equals(r)) return entity + r + o;
        if ("在".equals(r)) return entity + "在" + o;
        if ("住在".equals(r) || "位于".equals(r)) return entity + r + o;
        if (r.endsWith("于")) return entity + r + o;
        return entity + "的" + r + "是" + o;
    }

    private final LinkedHashSet<String> skipReverse = new LinkedHashSet<String>(Arrays.asList(
            "颜色", "叫声", "口头禅", "首都", "绕着", "在", "走得", "需要"));

    private Answer describe(String entity) {
        if (entity.isEmpty()) return null;
        List<String> classes = isaChain(entity);
        ArrayList<Fact> ownFacts = new ArrayList<Fact>();                    // filter { isaRel(it.rel) }
        for (Fact f : factsOf(bySubject, entity)) {
            if (isaRel(f.rel)) ownFacts.add(f);
        }
        ArrayList<Fact> attrFacts = new ArrayList<Fact>();                   // filter { !isaRel(it.rel) }
        for (Fact f : factsOf(bySubject, entity)) {
            if (!isaRel(f.rel)) attrFacts.add(f);
        }
        List<Fact> rev = factsOf(byObject, entity);
        if (classes.isEmpty() && ownFacts.isEmpty() && attrFacts.isEmpty() && rev.isEmpty()) return null;

        ArrayList<String> parts = new ArrayList<String>(6);
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        for (Fact f : ownFacts) merged.add(f.o);
        for (String c : classes) merged.add(c);
        if (!merged.isEmpty()) {
            ArrayList<String> head = new ArrayList<String>();                // merged.take(4)
            for (String item : merged) {
                if (head.size() >= 4) break;
                head.add(item);
            }
            parts.add(entity + "是" + join(head, "、"));
        }
        int taken = 0;
        for (Fact f : attrFacts) {                                           // attrFacts.take(4)
            if (taken >= 4) break;
            parts.add(phrase(entity, f.rel, f.o));
            taken++;
        }
        for (Fact f : rev) {
            if (skipReverse.contains(f.rel) || !isaRel(f.rel)) continue;
            String inv;
            if ("是".equals(f.rel)) {
                inv = "的一种";
            } else if ("有".equals(f.rel)) {
                inv = "属于";
            } else if ("喜欢".equals(f.rel)) {
                inv = "被喜欢";
            } else if ("会".equals(f.rel)) {
                inv = "的能力属于";
            } else if ("住在".equals(f.rel)) {
                inv = "的居民包括";
            } else if ("朋友".equals(f.rel)) {
                inv = "的朋友";
            } else {
                inv = null;
            }
            if (inv != null) {
                parts.add(entity + "是" + f.s + inv);
                break;
            }
        }
        if (parts.isEmpty()) return null;
        return new Answer(join(parts, ";") + "。我记性很好的。", "分类继承+属性检索", "describe");
    }

    // ---------------- 计数与换算 ----------------

    private final LinkedHashMap<Character, Integer> cnDigits = new LinkedHashMap<Character, Integer>();
    {
        cnDigits.put('零', 0);
        cnDigits.put('〇', 0);
        cnDigits.put('一', 1);
        cnDigits.put('二', 2);
        cnDigits.put('两', 2);
        cnDigits.put('三', 3);
        cnDigits.put('四', 4);
        cnDigits.put('五', 5);
        cnDigits.put('六', 6);
        cnDigits.put('七', 7);
        cnDigits.put('八', 8);
        cnDigits.put('九', 9);
    }

    private final LinkedHashMap<Character, Integer> cnUnits = new LinkedHashMap<Character, Integer>();
    {
        cnUnits.put('十', 10);
        cnUnits.put('百', 100);
        cnUnits.put('千', 1000);
    }

    public Integer cnNumber(String text) {
        if (text.isEmpty()) return null;
        int total = 0;
        int section = 0;
        int num = 0;
        boolean seen = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Integer digit = cnDigits.get(ch);
            Integer unit = cnUnits.get(ch);
            if (digit != null) {
                num = digit;
                seen = true;
            } else if (unit != null) {
                if (num == 0) num = 1;
                section += num * unit;
                num = 0;
                seen = true;
            } else {
                return null;
            }
        }
        if (!seen) return null;
        return total + section + num;
    }

    private Integer countUnit(String value, String unit) {
        int idx = value.indexOf(unit);
        if (idx <= 0) return null;
        String prefix = value.substring(0, idx);
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)$").matcher(prefix);
        if (m.find()) return (int) Double.parseDouble(m.group(1));           // groupValues[1].toDouble().toInt()
        return cnNumber(prefix);
    }

    // ---------------- 意图模式 ----------------

    private final Pattern pCount = Pattern.compile(
            "(.+?)(?:一共)?(?:有|长)几(?:个|只|条|张|本|头|匹|件|位|名|门|辆|架|朵|把|颗)?(.)[呢？?]*$");
    private final Pattern pCount2 = Pattern.compile("(.+?)(?:一共)?(?:有|长了)(?:几|多少)(.{1,10})$");
    private final Pattern pFood = Pattern.compile("(.+?)(?:最)?(?:喜欢|爱)(?:吃|喝|玩)(?:什么|啥)[呢？?]*$");
    private final Pattern pLike = Pattern.compile("(.+?)(?:最)?喜欢什么[呢？?]*$");
    private final Pattern pCan = Pattern.compile("(.+?)(?:会|能|可以)(?:什么|啥|干什么|做什么)[呢？?]*$");
    private final Pattern pAttr = Pattern.compile(
            "(.+?)的([^的是什么谁哪]+?)(?:是多少|是几|是什么|是啥|是哪个|是谁|叫什么|叫啥|呢|？|\\?)$");
    private final Pattern pWhatIs = Pattern.compile(
            "(.+?)(?:是|属于)(?:什么|啥|哪种|哪类|什么样的)(?:东西|动物|人|植物)?[呢？?]*$");
    private final Pattern pWhatIs2 = Pattern.compile("^什么是(.+?)[呢？?]*$");
    private final Pattern pWhoIs = Pattern.compile("(.+?)(?:是|叫)(?:谁|啥人|什么人)[呢？?]*$");
    private final Pattern pWhere = Pattern.compile("(.+?)(?:住在|生活在|位于)(?:哪里|哪儿|什么地方|哪)?[呢？?]*$");
    private final Pattern pWhere2 = Pattern.compile("(.+?)(?:在|去)(?:哪|哪里|哪儿)[呢？?]*$");
    private final Pattern pYesNo = Pattern.compile(
            "(.+?)(?:是不是|是|会|能|可以|属于)([^吗么嘛？?]+?)(?:吗|么|嘛|吧)?[？?]*$");
    private final Pattern pRelBetween = Pattern.compile("(.+?)(?:和|跟|与)([^是]+?)(?:是)?(?:什么)?关系[呢？?]*$");
    private final Pattern pValue = Pattern.compile("(.{1,20}?)(?:是|等于)(?:多少|几|多大|什么值)[呢？?]*$");
    private final Pattern pMeasure = Pattern.compile("(.{1,20}?)有多(长|高|大|重|久|远|深|宽|厚)[呢？?]*$");
    private final Pattern pWhoDid = Pattern.compile("^(?:谁|是哪位|是哪个人)(.{2,6}?)(?:了|的)?(.{1,20}?)[呢？?]*$");
    private final Pattern pRelWhen = Pattern.compile(
            "(.{1,20}?)(?:的)?([^的是什么谁哪]{1,9}?)(?:是哪一年|是哪年|是哪一天|是什么时候|是何时|哪一年|哪年|什么时候|何时)[呢？?]*$");
    private final Pattern pRelWhere = Pattern.compile(
            "(.+?)(?:的)?([^的是什么谁哪]{1,8}?)是(?:哪里|哪儿|哪个城市|什么地方)[呢？?]*$");
    private final Pattern pConvert = Pattern.compile("^(.{1,20}?)是(?:多少|几)(.{1,6}?)[呢？?]*$");
    private final Pattern pYesNoAdj = Pattern.compile(
            "(.+?)(走得|跑得|飞得|游得|长得)?([快慢大小多少高矮长短胖瘦冷热新旧早晚])(?:吗|么|嘛)[？?]*$");
    private final Pattern pChain = Pattern.compile(
            "^(.+?)的((?:[^的是什么谁哪]+?的)*[^的是什么谁哪]+?)(?:是|叫)(?:谁|什么|啥|哪个)(?:呢|？|\\?)?$");

    private final String[] questionMarkers = new String[]{
            "什么", "谁", "哪", "吗", "呢", "几", "多少", "多长", "多高", "多大", "多重",
            "多久", "多远", "怎么样", "如何", "为什么", "是不是", "有没有", "能不能",
            "会不会", "可不可以", "请问", "?", "？"
    };

    private boolean isQuestion(String t) {
        for (String marker : questionMarkers) {
            if (t.contains(marker)) return true;                            // questionMarkers.any { t.contains(it) }
        }
        return false;
    }

    // Kotlin 的 List<Pair<String,String>>，Java 用 String[2] 承载（保持顺序与重复项）
    private final String[][] antonymPairs = new String[][]{
            {"快", "慢"}, {"大", "小"}, {"多", "少"}, {"高", "矮"}, {"高", "低"},
            {"长", "短"}, {"胖", "瘦"}, {"冷", "热"}, {"新", "旧"}, {"早", "晚"},
            {"会", "不会"}, {"有", "没有"}, {"喜欢", "讨厌"}
    };

    private boolean contradicts(String claim, String fact) {
        for (String[] pair : antonymPairs) {
            String x = pair[0];
            String y = pair[1];
            if ((claim.contains(x) && fact.contains(y)) || (claim.contains(y) && fact.contains(x))) return true;
        }
        return false;
    }

    /** 已知实体最长匹配:解决 "秦朝建立于哪一年" 被切成 (秦, 朝建立于) */
    private Map.Entry<String, String> splitEntityRel(String text) {
        String t = kTrim(text);
        if (t.isEmpty()) return new AbstractMap.SimpleEntry<String, String>(t, "");   // t to ""
        Set<String> known = entityNames();
        String best = "";
        for (String name : known) {
            if (t.startsWith(name) && name.length() > best.length()) best = name;
        }
        if (!best.isEmpty() && best.length() < t.length()) {
            return new AbstractMap.SimpleEntry<String, String>(best, t.substring(best.length()));
        }
        ArrayList<String> relsSorted = new ArrayList<String>(relations);
        // sortedByDescending { it.length } 是稳定排序，Collections.sort 也是稳定的
        Collections.sort(relsSorted, new Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                return y.length() - x.length();
            }
        });
        for (String rel : relsSorted) {
            int idx = t.indexOf(rel);
            if (idx > 0) {
                String head = t.substring(0, idx);
                if (known.contains(head)) return new AbstractMap.SimpleEntry<String, String>(head, rel);
            }
        }
        return new AbstractMap.SimpleEntry<String, String>(t, "");
    }

    // ---------------- 主入口 ----------------

    public Answer think(String question) {
        if (kTrim(question).isEmpty()) return null;                         // question.isBlank()
        String q = normalize(question);
        q = trimChars(q.replaceAll("^(请问|问一下|我想知道|告诉我)", ""), ",，。");

        Matcher m;

        // 0. 关系链:"小明的爸爸的爸爸是谁"
        m = find(pChain, q);
        if (m != null) {
            String head = normEntity(m.group(1));
            ArrayList<String> relWords = new ArrayList<String>();           // split('的').filter { it.isNotEmpty() }
            for (String w : m.group(2).split("的", -1)) {
                if (!w.isEmpty()) relWords.add(w);
            }
            ArrayList<String> rels = new ArrayList<String>();
            for (String w : relWords) rels.add(canonRel(w));                // .map { canonRel(it) }
            boolean noneNull = true;
            for (String r : rels) {
                if (r == null) noneNull = false;                            // rels.none { it == null }
            }
            if (!head.isEmpty() && !rels.isEmpty() && noneNull) {
                ArrayList<String> nonNull = new ArrayList<String>();        // rels.filterNotNull()
                for (String r : rels) {
                    if (r != null) nonNull.add(r);
                }
                Answer chain = answerChain(head, nonNull);
                if (chain != null) return chain;
            }
        }

        // 1. 数量问句
        m = find(pCount, q);
        if (m == null) m = find(pCount2, q);                                // pCount.find(q) ?: pCount2.find(q)
        if (m != null) {
            String ent = normEntity(m.group(1));
            String unit = m.group(2);
            String noun = unit.replaceAll("^(?:几|多少)?[个只条张本头匹件位名门辆架朵把颗]?", "");
            for (String rel : new String[]{"有", "长"}) {
                for (String v : lookup(ent, rel)) {
                    if (v.contains(unit) || v.endsWith(unit) || (!noun.isEmpty() && v.contains(noun))) {
                        return new Answer(ent + "有" + v + "。", "直接事实", "attr");
                    }
                }
            }
            for (String rel : new String[]{"有", "长"}) {
                for (String v : lookup(ent, rel)) {
                    boolean hasMeasure = false;
                    for (int i = 0; i < v.length(); i++) {
                        if ("颗条只个张座把位".indexOf(v.charAt(i)) >= 0) {  // v.any { "颗条..." .contains(it) }
                            hasMeasure = true;
                            break;
                        }
                    }
                    if (hasMeasure) return new Answer(ent + "有" + v + "。", "直接事实", "attr");
                }
            }
        }

        // 2. 吃喝 / 喜欢 / 会什么
        m = find(pFood, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            List<String> vals = lookup(ent, "喜欢");
            for (String v : vals) {                                         // firstOrNull { ... }?.let { return ... }
                if (!v.isEmpty() && "吃喝啃".indexOf(v.charAt(0)) >= 0) {
                    return new Answer(ent + "喜欢" + v + "。", "直接事实", "attr");
                }
            }
        }
        m = find(pLike, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            List<String> vals = lookup(ent, "喜欢");
            if (!vals.isEmpty()) {
                return new Answer(ent + "喜欢" + join(vals, "、") + "。", "直接事实", "attr");
            }
        }
        m = find(pCan, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            List<String> vals = lookup(ent, "会");
            if (!vals.isEmpty()) {
                return new Answer(ent + "会" + join(vals, "、") + "。", "直接事实", "attr");
            }
        }

        // 3. 教学句识别(必须先排除疑问句)
        if (!isQuestion(q)) {
            Triple tr = toTriple(q);
            if (tr != null) {
                facts.add(new Fact(tr.a, tr.b, tr.c, "taught"));
                getOrPutList(bySubject, tr.a).add(new Fact(tr.a, tr.b, tr.c, "taught"));
                getOrPutList(byObject, tr.c).add(new Fact(tr.a, tr.b, tr.c, "taught"));
                return new Answer("记住啦:" + tr.a + "的" + tr.b + "是" + tr.c
                        + "。我会一直记着。", "事实入库", "remember");
            }
        }

        // 4. 值类问句(必须早于是非句)
        m = find(pValue, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            for (String rel : new String[]{"数值", "值", "是"}) {
                List<String> vals = lookup(ent, rel);
                if (!vals.isEmpty()) {
                    return new Answer(ent + "是" + join(vals, "、") + "。", "直接事实", "value");
                }
            }
            Answer d = describe(ent);
            if (d != null) return d;                                        // describe(ent)?.let { return it }
        }

        // 5. 谁发明了X(反查)
        if (q.startsWith("谁") || q.startsWith("是哪位")) {
            m = find(pWhoDid, q);
            if (m != null) {
                String rel = stripTail(m.group(1));
                String obj = stripTail(m.group(2));
                LinkedHashSet<String> cands = new LinkedHashSet<String>();
                cands.addAll(relCandidates(rel));
                cands.addAll(Arrays.asList("发明", "发现", "提出", "改进", "创立", "创作", "编写", "建立"));
                for (String r : cands) {
                    List<String> holders = reverse(obj, r);
                    if (!holders.isEmpty()) {
                        return new Answer("是" + join(holders, "、") + r + obj + " 的。", "反查事实", "reverse");
                    }
                }
            }
        }

        // 6.5 裸属性问句:没有"的"的说法 —— "氧原子序数 / 山东省简称 / 美国代码"
        //
        // pAttr 要求有"的"(X的Y是多少),所以"氧原子序数"这种紧凑说法匹配不上。
        // 实测这个漏了会直接答不出(真机自检发现:"氧原子序数" 应答 8)。
        // 做法:剥掉问句尾巴,然后从右往左尝试把结尾切成本库已知的关系。
        {
            String bare = stripTail(q);
            if (bare.length() >= 3 && !bare.contains("的")) {
                // 长关系优先,避免"序数"抢先匹配掉"原子序数"
                ArrayList<String> rels = new ArrayList<String>(relations);
                Collections.sort(rels, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        return b.length() - a.length();
                    }
                });
                for (String rel : rels) {
                    if (rel.length() < 2 || !bare.endsWith(rel)) continue;
                    String ent = bare.substring(0, bare.length() - rel.length()).trim();
                    if (ent.isEmpty() || ent.length() > 12) continue;
                    String e2 = normEntity(ent);
                    if (e2 == null || e2.isEmpty()) continue;
                    List<String> vals = lookup(e2, rel);
                    if (!vals.isEmpty()) {
                        return new Answer(e2 + "的" + rel + "是" + join(vals, "、") + "。",
                                "直接事实", "attr");
                    }
                }
            }
        }

        // 6. X的Y是什么
        m = find(pAttr, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            String attrRaw = m.group(2);
            if (attrRaw.startsWith("的")) attrRaw = attrRaw.substring(1);   // removePrefix("的")
            String attr = stripTail(attrRaw);
            String canon = canonRel(attr);
            if (canon == null && relations.contains(attr)) canon = attr;    // ?: attr.takeIf { relations.contains(it) }
            if (canon != null) {
                List<String> vals = lookup(ent, canon);
                if (!vals.isEmpty()) {
                    return new Answer(ent + "的" + attr + "是" + join(vals, "、") + "。", "直接事实", "attr");
                }
                List<Map.Entry<String, String>> trs = transitive(ent, canon);
                if (!trs.isEmpty()) {                                       // transitive(...).firstOrNull()?.let { (v, how) -> }
                    Map.Entry<String, String> first = trs.get(0);
                    return new Answer("推出来的:" + ent + "的" + attr + "是" + first.getKey() + "。",
                            first.getValue(), "infer");
                }
            }
            // 属性部分匹配("卡卡的尾巴呢")
            if (!attr.isEmpty()) {
                Fact best = null;
                for (Fact f : factsOf(bySubject, ent)) {
                    if (skipReverse.contains(f.rel) || isaRel(f.rel)) continue;
                    if (f.o.contains(attr) && (best == null || f.o.length() < best.o.length())) best = f;
                }
                if (best == null) {
                    for (String cls : isaChain(ent)) {
                        for (Fact f : factsOf(bySubject, cls)) {
                            if (skipReverse.contains(f.rel) || isaRel(f.rel)) continue;
                            if (f.o.contains(attr) && (best == null || f.o.length() < best.o.length())) best = f;
                        }
                    }
                }
                if (best != null) return new Answer(ent + best.o + "。", "属性部分匹配", "attr");
            }
        }

        // 7. X是什么 / 什么是X / X是谁
        for (Pattern p : new Pattern[]{pWhatIs, pWhatIs2}) {
            m = find(p, q);
            if (m != null) {
                String ent = normEntity(m.group(1));
                Answer d = describe(ent);
                if (d != null) return d;
            }
        }
        m = find(pWhoIs, q);
        if (m != null) {
            Answer d = describe(normEntity(m.group(1)));                    // pWhoIs.find(q)?.let { describe(...)?.let { return it } }
            if (d != null) return d;
        }

        // 8. 位置类
        for (Pattern p : new Pattern[]{pWhere, pWhere2}) {
            m = find(p, q);
            if (m != null) {
                String ent = normEntity(m.group(1));
                for (String rel : new String[]{"住在", "位于"}) {
                    List<String> vals = lookup(ent, rel);
                    if (!vals.isEmpty()) {
                        String verb = "住在".equals(rel) ? "住在" : "位于";
                        return new Answer(ent + verb + vals.get(0) + "。", "直接事实", "where");
                    }
                }
            }
        }
        m = find(pRelWhere, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            for (String rel : relCandidates(m.group(2))) {
                List<String> vals = lookup(ent, rel);
                if (!vals.isEmpty()) {
                    return new Answer(ent + "的" + rel + "是" + join(vals, "、") + "。", "直接事实", "attr");
                }
            }
        }

        // 9. 度量问句
        m = find(pMeasure, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            String rel = measureRel.get(m.group(2));
            if (rel != null) {
                List<String> vals = lookup(ent, rel);
                if (!vals.isEmpty()) {
                    return new Answer(ent + "的" + rel + "是" + join(vals, "、") + "。", "直接事实", "measure");
                }
            }
        }

        // 10. 时间类关系问句
        m = find(pRelWhen, q);
        if (m != null) {
            String joined = m.group(1) + m.group(2);
            Map.Entry<String, String> split = splitEntityRel(joined);
            String ent = split.getKey();                                    // val (ent, rawRel) = splitEntityRel(joined)
            String rawRel = split.getValue();
            if (!ent.isEmpty()) {
                LinkedHashSet<String> cands = new LinkedHashSet<String>();
                cands.addAll(relCandidates(rawRel));
                cands.addAll(Arrays.asList("建立于", "灭亡于", "发生于", "结束于", "通航于", "举办于"));
                for (String r : cands) {
                    List<String> vals = lookup(ent, r);
                    if (!vals.isEmpty()) {
                        return new Answer(ent + r + join(vals, "、") + "。", "直接事实", "when");
                    }
                }
            }
        }

        // 11. 换算
        m = find(pConvert, q);
        if (m != null) {
            String ent = normEntity(m.group(1));
            String unit = stripTail(m.group(2));
            if (!ent.isEmpty() && !unit.isEmpty()) {
                for (String v : lookup(ent, "是")) {
                    if (v.contains(unit)) {
                        Integer n = countUnit(v, unit);
                        if (n != null) {
                            return new Answer(ent + "是" + n + unit + "。",
                                    "由「" + ent + "是" + v + "」换算", "convert");
                        }
                        return new Answer(ent + "是" + v + "。", "直接事实", "convert");
                    }
                }
            }
        }

        // 12. 是非问句
        //
        // 【顺序很重要】先试**因果/属性推理**,再走直接查库的 yes/no。
        // 否则会踩这个坑:"毕昇是发明家吗" 走到下面
        // `if (!forward(a).isEmpty() || ...) return "不是,毕昇不是发明家"`
        // 直接返回否定 —— 而库里"毕昇 发明 活字印刷术"明明能推出他是发明家。
        Answer causalYes = causalYesNo(q);
        if (causalYes != null) return causalYes;

        if (find(Pattern.compile("(吗|么|嘛)[？?]*$"), q) != null) {          // containsMatchIn(q)
            m = find(pYesNo, q);
            if (m != null) {
                String a = normEntity(m.group(1));
                String b = stripTail(m.group(2));
                if (!a.isEmpty() && !b.isEmpty()) {
                    if (forward(a, "是").contains(b)) {
                        return new Answer("对," + a + "是" + b + "。", "直接事实", "yesno");
                    }
                    if (isaChain(a).contains(b)) {
                        return new Answer("对," + a + "是" + b + "(推出来的)。", "分类继承", "infer");
                    }
                    for (String rel : new String[]{"会", "可以", "能"}) {
                        // 否定优先:先查 lookupNeg(企鹅不会飞 要盖过 鸟会飞)，再查 lookup
                        List<String> negs = lookupNeg(a, rel);
                        boolean negHit = false;
                        for (String v : negs) {                             // negs.any { it.contains(b) || b.contains(it) }
                            if (v.contains(b) || b.contains(v)) {
                                negHit = true;
                                break;
                            }
                        }
                        if (negHit) {
                            return new Answer("不," + a + "不会" + b + "。这个问题换一个吧。", "否定事实", "yesno");
                        }
                        List<String> pos = lookup(a, rel);
                        boolean posHit = false;
                        for (String v : pos) {
                            if (v.contains(b) || b.contains(v)) {
                                posHit = true;
                                break;
                            }
                        }
                        if (posHit) return new Answer("对," + a + "会" + b + "。", "直接事实", "yesno");
                    }
                    List<Fact> fa = bySubject.get(a);                       // bySubject[a]?.isNotEmpty() == true
                    List<Fact> fb = bySubject.get(b);
                    if ((fa != null && !fa.isEmpty()) || (fb != null && !fb.isEmpty())) {
                        return new Answer("不是," + a + "不是" + b + "。", "事实库中无此关系", "yesno");
                    }
                }
            }
            m = find(pYesNoAdj, q);
            if (m != null) {
                String ent = normEntity(m.group(1));
                String adj = m.group(3);
                ArrayList<Fact> cands = new ArrayList<Fact>();
                cands.addAll(factsOf(bySubject, ent));
                for (String cls : isaChain(ent)) cands.addAll(factsOf(bySubject, cls));
                for (Fact f : cands) {
                    if (f.o.contains(adj)) {
                        return new Answer("对," + ent + f.rel + f.o + "。", "直接事实", "yesno");
                    }
                    if (contradicts(adj, f.o)) {
                        return new Answer("不," + ent + f.rel + f.o + "。", "反义事实", "yesno");
                    }
                }
            }
        }

        // 13. 两个实体的关系
        m = find(pRelBetween, q);
        if (m != null) {
            String a = normEntity(m.group(1));
            String b = normEntity(m.group(2));
            Answer cross = crossRel(a, b);
            if (cross != null) return cross;
            // isaChain(a).toSet().intersect(isaChain(b).toSet())：保持 a 侧顺序
            LinkedHashSet<String> common = new LinkedHashSet<String>(isaChain(a));
            common.retainAll(new LinkedHashSet<String>(isaChain(b)));
            if (!common.isEmpty()) {
                String first = common.iterator().next();                    // common.first()
                return new Answer(a + "和" + b + "都是" + first + "。", "分类继承", "infer");
            }
        }

        // 14. 兜底:整体能否被描述
        Answer fallback = describe(stripMeasure(q));
        if (fallback != null) return fallback;                              // describe(stripMeasure(q))?.let { return it }

        // 15. 因果/属性推理 —— 从已知事实推出**库里没有的结论**
        //     (对应"知道比尔盖茨是微软 CEO,就能推出他很出名"这类需求)
        Answer causal = causalAnswer(q);
        if (causal != null) return causal;
        return null;
    }

    // ============================================================
    // 因果 / 属性推理
    // ============================================================

    /**
     * 逆关系表:把单向事实反过来表述。
     *
     * 中文方向要小心:"由…建立/发明" 的主语是**被建立/被发明的那个东西**。
     *   东吴 建立者 孙权   -> "东吴由孙权建立"  ✅
     *                     -> "孙权由东吴建立"  ❌(第一版就是这个错)
     */
    private static final LinkedHashMap<String, String> INVERSE_RELS =
            new LinkedHashMap<String, String>();

    static {
        INVERSE_RELS.put("建立者", "%s由%s建立");
        INVERSE_RELS.put("创立", "%s由%s创立");
        INVERSE_RELS.put("发明", "%s由%s发明");
        INVERSE_RELS.put("首都", "%s是%s的首都");   // 用 %1$s=宾语, %2$s=主语
        INVERSE_RELS.put("省会", "%s是%s的省会");
    }

    /**
     * 因果蕴含规则:关系 -> (结论模板, 依据模板)。
     *
     * 【变量方向极易搞错,这里踩过一次】
     * 模板里 %1$s = 事实**主语** f.s,%2$s = 事实**宾语** f.o。
     * 以事实「东吴 建立者 孙权」为例:主语=东吴,宾语=孙权。
     * 要推出的结论是"**孙权**(宾语)是历史人物",所以模板必须是
     *     "%1$s是历史人物(创立了%2$s)"  ❌  -> "东吴是历史人物(创立了孙权)"
     *     "%2$s是历史人物(创立了%1$s)"  ✅  -> "孙权是历史人物(创立了东吴)"
     * 第一版写成了前者,真机自检输出"对,东吴是历史人物(创立了孙权)"——完全说反。
     */
    private static final LinkedHashMap<String, String[]> CAUSAL_RULES =
            new LinkedHashMap<String, String[]>();

    static {
        // 关系 -> {结论模板, 依据模板};%1$s=主语, %2$s=宾语
        CAUSAL_RULES.put("建立者", new String[]{
                "%2$s是历史人物(创立了%1$s)",
                "%1$s的建立者是%2$s,能建立政权的人必是历史人物"});
        CAUSAL_RULES.put("发明", new String[]{
                "%1$s是发明家(发明了%2$s)",
                "%1$s发明了%2$s,发明东西的人就是发明家"});
        CAUSAL_RULES.put("创立", new String[]{
                "%1$s是创始人(创立了%2$s)",
                "%1$s创立了%2$s"});
        CAUSAL_RULES.put("位于", new String[]{
                "%1$s在%2$s境内",
                "%1$s位于%2$s,所以它在%2$s境内"});
    }

    /**
     * 因果推理层。只做高置信度的几种问法,不瞎猜
     * (用户明确要求:错误答案比不回答更糟)。
     */
    private Answer causalAnswer(String q) {
        // ---- 1. "X由谁建立 / 谁建立了X / X是谁发明的" ----
        Matcher m = find(Pattern.compile("^(.+?)(?:是)?(?:由)?谁(?:建立|创立|发明|创建)(?:的)?[？?]*$"), q);
        if (m == null) {
            m = find(Pattern.compile("^谁(?:建立|创立|发明|创建)了?(.+?)[？?]*$"), q);
        }
        if (m != null) {
            String raw = m.group(1).trim();
            // 原文优先,规范化结果兜底:
            // normEntity("活字印刷术") 会截断成 "印刷术",导致查不到
            ArrayList<String> cands = new ArrayList<String>();
            if (!raw.isEmpty()) cands.add(raw);
            String norm = normEntity(raw);
            if (norm != null && !norm.isEmpty() && !cands.contains(norm)) cands.add(norm);
            for (String cand : cands) {
                for (String rel : new String[]{"建立者", "创立", "发明"}) {
                    // 方向 1:实体是主语(如 "东吴 建立者 孙权")
                    List<String> vals = lookup(cand, rel);
                    if (!vals.isEmpty()) {
                        return new Answer(inverseText(rel, cand, vals.get(0)) + "。",
                                cand + rel + vals.get(0), "infer");
                    }
                    // 方向 2:实体是**宾语** —— 关键!
                    // 库里存的是 "毕昇 发明 活字印刷术",问"活字印刷术是谁发明的"时
                    // 实体在宾语位置,forward 查不到,必须反查主语。
                    for (String subj : reverse(cand, rel)) {
                        return new Answer(inverseText(rel, cand, subj) + "。",
                                subj + rel + cand, "infer");
                    }
                }
            }
        }

        // ---- 2. "X是谁的首都/省会" ----
        m = find(Pattern.compile("^(.+?)是谁的(首都|省会)[？?]*$"), q);
        if (m != null) {
            String ent = m.group(1).trim();
            String rel = m.group(2);
            for (String subj : reverse(ent, rel)) {
                return new Answer(ent + "是" + subj + "的" + rel + "。",
                        subj + "的" + rel + "是" + ent, "infer");
            }
        }

        // ---- 3. 是非问句:"X是历史人物吗 / X是发明家吗" ----
        //     必须在直接查库的 yes/no 之前生效,否则会被
        //     "事实库中无此关系 -> 不是" 抢先否掉(实测踩过:
        //     "毕昇是发明家吗" 被答成"不是",而库里明明能推出他是发明家)。
        return causalYesNo(q);
    }

    /** 按逆关系表拼出反向说法 */
    private String inverseText(String rel, String subject, String obj) {
        String tmpl = INVERSE_RELS.get(rel);
        if (tmpl == null) return subject + rel + obj;
        try {
            return String.format(tmpl, subject, obj);
        } catch (Throwable t) {
            return subject + rel + obj;
        }
    }

    /**
     * "X是历史人物吗 / X是发明家吗" 这类**可以推理出来**的是非问句。
     * 推不出肯定结论就返回 null,让后面的逻辑照常处理。
     */
    private Answer causalYesNo(String q) {
        if (!q.matches(".*[吗么嘛][？?]*$")) return null;
        Matcher m = find(Pattern.compile("^(.+?)(?:是|算)(.+?)(?:吗|么|嘛)[？?]*$"), q);
        if (m == null) return null;
        String ent = normEntity(m.group(1));
        String claim = m.group(2).trim();
        if (ent == null || ent.isEmpty() || claim.isEmpty()) return null;

        // 方向 1:ent 是事实主语(如 "毕昇 发明 活字印刷术")
        Answer hit = causalCheckSubject(ent, claim);
        if (hit != null) return hit;

        // 方向 2:ent 是事实**宾语**(如 "东吴 建立者 孙权",孙权是宾语)
        for (Fact f : facts) {
            if (f.o != null && f.o.equals(ent)) {
                String[] rule = CAUSAL_RULES.get(f.rel);
                if (rule != null) {
                    String text = String.format(rule[0], f.s, f.o);
                    if (text.contains(claim)) {
                        return new Answer("对," + text + "。", f.s + f.rel + f.o, "infer");
                    }
                }
            }
        }
        return null;
    }

    private Answer causalCheckSubject(String ent, String claim) {
        for (Fact f : facts) {
            if (f.s == null || !f.s.equals(ent)) continue;
            String[] rule = CAUSAL_RULES.get(f.rel);
            if (rule != null) {
                String text = String.format(rule[0], f.s, f.o);
                if (text.contains(claim)) return new Answer("对," + text + "。", f.s + f.rel + f.o, "infer");
            }
        }
        // 继承链(卡卡是生物吗)
        for (String cls : isaChain(ent)) {
            if (claim.equals(cls)) {
                return new Answer("对," + ent + "是" + cls + "。", "分类继承", "infer");
            }
        }
        return null;
    }

    private Answer answerChain(String head, List<String> rels) {
        if (rels.size() == 1) {
            List<String> vals = lookup(head, rels.get(0));
            if (!vals.isEmpty()) {
                return new Answer(head + "的" + rels.get(0) + "是" + join(vals, "、") + "。", "直接事实", "attr");
            }
            return null;
        }
        ArrayList<String> cur = new ArrayList<String>();                     // var cur = listOf(head)
        cur.add(head);
        ArrayList<String> why = new ArrayList<String>();                     // 与 Kotlin 版一致：收集但结果不使用
        for (String rel : rels) {
            ArrayList<String> next = new ArrayList<String>();
            for (String node : cur) {
                for (String o : forward(node, rel)) {
                    next.add(o);
                    why.add(node + "的" + rel + "是" + o);
                }
            }
            if (next.isEmpty()) {
                ArrayList<String> inferred = new ArrayList<String>();
                for (String node : cur) {                                   // for ((v, _h) in transitive(node, rel))
                    for (Map.Entry<String, String> e : transitive(node, rel)) inferred.add(e.getKey());
                }
                if (inferred.isEmpty()) return null;
                next.addAll(inferred);
            }
            cur = distinct(next);                                           // next.distinct()
        }
        return new Answer("推出来的:" + head + "的" + join(rels, "的") + "是" + join(cur, "、") + "。",
                "关系链推理", "infer");
    }

    // ---------------- 三元组解析(教学) ----------------

    // Kotlin: relAliases.flatMap { (canon, aliases) -> aliases.map { it to canon } }.sortedByDescending { it.first.length }
    private final ArrayList<Map.Entry<String, String>> relTokens = new ArrayList<Map.Entry<String, String>>();
    {
        for (Map.Entry<String, List<String>> e : relAliases.entrySet()) {
            for (String alias : e.getValue()) {
                relTokens.add(new AbstractMap.SimpleEntry<String, String>(alias, e.getKey()));
            }
        }
        // 稳定排序 => 同长度的别名保持 flatMap 的原始顺序（与 Kotlin sortedByDescending 一致）
        Collections.sort(relTokens, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> x, Map.Entry<String, String> y) {
                return y.getKey().length() - x.getKey().length();
            }
        });
    }

    public Triple toTriple(String sentence) {
        String t = normalize(sentence);
        t = t.replaceAll("^教[你她他]?[一]?[句个]?[:：]?", "");
        // 第一轮:非"是"的关系词
        ArrayList<Map.Entry<String, String>> r1 = new ArrayList<Map.Entry<String, String>>();
        for (Map.Entry<String, String> e : relTokens) {
            if (!e.getKey().equals("是") && !e.getKey().equals("就是") && !e.getKey().equals("属于")) {
                r1.add(e);
            }
        }
        Triple first = matchTriple(t, r1);
        if (first != null) return first;
        // 第二轮:允许"是"
        ArrayList<Map.Entry<String, String>> r2 = new ArrayList<Map.Entry<String, String>>();
        for (Map.Entry<String, String> e : relTokens) {
            if (e.getKey().equals("是") || e.getKey().equals("就是") || e.getKey().equals("属于")) {
                r2.add(e);
            }
        }
        return matchTriple(t, r2);
    }

    private Triple matchTriple(String t, List<Map.Entry<String, String>> tokens) {
        for (Map.Entry<String, String> token : tokens) {
            String alias = token.getKey();
            String canon = token.getValue();
            Pattern re = Pattern.compile("^(.{1,20}?)(?:的)?" + Pattern.quote(alias) + "(.{1,30})$");
            Matcher m = find(re, t);
            if (m == null) continue;                                        // re.find(t) ?: continue
            String left = kTrim(m.group(1));
            String right = kTrim(m.group(2));
            if (left.isEmpty() || right.isEmpty() || left.equals(right)) continue;
            for (String cop : new String[]{"是", "就是", "属于"}) {
                if (right.startsWith(cop) && right.length() > cop.length()) {
                    right = kTrim(right.substring(cop.length()));
                    break;
                }
            }
            if (right.isEmpty()) continue;
            if (left.contains("的")) {
                int idx = left.lastIndexOf('的');
                String sub = kTrim(left.substring(0, idx));
                String relWord = kTrim(left.substring(idx + 1));
                for (Map.Entry<String, String> e : relTokens) {
                    if (relWord.equals(e.getKey())) return new Triple(sub, e.getValue(), right);
                }
            }
            return new Triple(left, canon, right);
        }
        return null;
    }

    // ======================= Kotlin 语义的小工具 =======================

    /** Kotlin: p.find(input)（找不到返回 null）。 */
    private static Matcher find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m : null;
    }

    /** Kotlin: bySubject.getOrPut(key) { ArrayList(2) }。 */
    /**
     * 运行时新增一条事实 —— 供"主动学习"使用。
     *
     * 为什么单独开一个方法:原来只有载入 facts.tsv 和解析教学句两条入库路径,
     * 而对话状态机学会的东西(如"比尔盖茨 是 微软公司CEO")需要直接入图,
     * 这样下一轮问"比尔盖茨是谁"就能查到。
     */
    public void addFact(String subj, String rel, String obj, String source) {
        if (subj == null || rel == null || obj == null) return;
        if (subj.isEmpty() || rel.isEmpty() || obj.isEmpty()) return;
        Fact f = new Fact(subj, rel, obj, source == null ? "" : source);
        facts.add(f);
        getOrPutList(bySubject, f.s).add(f);
        getOrPutList(byObject, f.o).add(f);
        relations.add(f.rel);
    }

    /** 是否已收录该实体(出现在主语或宾语位置) */
    /**
     * 删除一条事实(用户否定时调用)。
     *
     * 事实图有三处存着它:facts 列表 + bySubject/byObject 两个索引。
     * 只删一处会留下"幽灵知识" —— 查询时还能查到已经不成立的旧事实,
     * 于是用户说"不是"之后她照样拿它回答(用户反馈的原话就是
     * "我都说了不是了他还记着")。所以三处一起清。
     */
    public void removeFact(String subj, String rel, String obj) {
        if (subj == null || rel == null || obj == null) return;
        try {
            for (int i = facts.size() - 1; i >= 0; i--) {
                Fact f = facts.get(i);
                if (f.s.equals(subj) && f.rel.equals(rel) && f.o.equals(obj)) {
                    facts.remove(i);
                }
            }
            drop(bySubject, subj, rel, obj, true);
            drop(byObject, obj, rel, subj, false);
        } catch (Throwable ignored) {
        }
    }

    /** 从索引桶里摘掉一条;full 表示按 (s,rel,o) 比,否则按 (o,rel,s) 比 */
    private static void drop(HashMap<String, List<Fact>> idx,
                             String key, String rel, String other, boolean full) {
        List<Fact> bucket = idx.get(key);
        if (bucket == null) return;
        for (int i = bucket.size() - 1; i >= 0; i--) {
            Fact f = bucket.get(i);
            boolean match = full
                    ? (f.rel.equals(rel) && f.o.equals(other))
                    : (f.rel.equals(rel) && f.s.equals(other));
            if (match) bucket.remove(i);
        }
        if (bucket.isEmpty()) idx.remove(key);
    }

    /**
     * 所有出现过的实体(主语与宾语)。
     * 供同音词纠正建索引 —— 用户教过的生造词、人名、专有名词都在这里，
     * 它们是"最值得纠正"的词。
     */
    public java.util.List<String> allEntities() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        for (Fact f : facts) {
            if (f.s != null && f.s.length() >= 2) set.add(f.s);
            if (f.o != null && f.o.length() >= 2 && f.o.length() <= 12) set.add(f.o);
        }
        return new java.util.ArrayList<String>(set);
    }

    public boolean hasEntity(String e) {
        if (e == null || e.isEmpty()) return false;
        if (bySubject.containsKey(e)) return true;
        if (byObject.containsKey(e)) return true;
        return false;
    }

    private static List<Fact> getOrPutList(Map<String, List<Fact>> map, String key) {
        List<Fact> cur = map.get(key);
        if (cur == null) {
            cur = new ArrayList<Fact>(2);
            map.put(key, cur);
        }
        return cur;
    }

    /** Kotlin: parent.getOrPut(key) { HashSet() }。 */
    private static Set<String> getOrPutSet(Map<String, Set<String>> map, String key) {
        Set<String> cur = map.get(key);
        if (cur == null) {
            cur = new HashSet<String>();
            map.put(key, cur);
        }
        return cur;
    }

    /** Kotlin: bySubject[x] ?: emptyList()。 */
    private static List<Fact> factsOf(Map<String, List<Fact>> map, String key) {
        List<Fact> cur = map.get(key);
        return cur == null ? Collections.<Fact>emptyList() : cur;
    }

    /** Kotlin: parent[cur] ?: emptySet()。 */
    private static Set<String> orEmptySet(Map<String, Set<String>> map, String key) {
        Set<String> cur = map.get(key);
        return cur == null ? Collections.<String>emptySet() : cur;
    }

    /** Kotlin: list.joinToString(sep)。 */
    private static String join(Collection<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : items) {
            if (!first) sb.append(sep);
            sb.append(s);
            first = false;
        }
        return sb.toString();
    }

    /** Kotlin: list.distinct()（保持首次出现顺序）。 */
    private static ArrayList<String> distinct(List<String> items) {
        return new ArrayList<String>(new LinkedHashSet<String>(items));
    }

    /** Kotlin: str.trim('a', 'b', ...) —— 只去首尾列出的字符。 */
    private static String trimChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    /**
     * Kotlin 的 {@code Char.isWhitespace()}:{@code Character.isWhitespace(c)
     * || Character.isSpaceChar(c)}。
     * 不能用 Java 的 String.trim()：它只去 {@code <= 0x20} 的字符，全角空格
     * U+3000、不换行空格 U+00A0 都去不掉，而 Kotlin 版能去掉。
     * （与同目录 KikiBot.ktTrim 同一套语义。）
     */
    private static boolean isKtWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Kotlin 的 {@code String.trim()}。 */
    private static String kTrim(String s) {
        if (s == null) return "";
        int start = 0;
        int end = s.length();
        while (start < end && isKtWhitespace(s.charAt(start))) start++;
        while (end > start && isKtWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }
}
