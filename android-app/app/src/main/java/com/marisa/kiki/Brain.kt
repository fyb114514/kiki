package com.marisa.kiki

import android.content.res.AssetManager

/**
 * kiki 逻辑引擎（Android / Kotlin 版）
 *
 * 这是 PC 版 brain.py + lexicon.py 的移植，算法一一对应，只做了语言层面的改写：
 *   - 事实库：TSV 载入内存 + 正查/反查索引（PC 版用 SQLite，这里数据量小，内存更快）
 *   - 推理：分类继承（链式）、传递规则、亲属路径（家谱树 LCA）、否定优先、反义对
 *   - 语言：中英混排归一、近音错字表、拼音同音匹配（多音节分辨）
 *   - 输出：按关系类型选择句式，避免"水的在是100摄氏度"这类病句
 *
 * 设计原则：**引擎只负责"给出确切答案"，答不出就返回 null**，
 * 由上层决定是走模糊匹配、质量闸还是兜底话术。
 */
class KikiBrain(assets: AssetManager) {

    // ---------------- 数据 ----------------
    data class Fact(val s: String, val rel: String, val o: String, val source: String)

    private val facts = ArrayList<Fact>(2048)
    private val bySubject = HashMap<String, MutableList<Fact>>()
    private val byObject = HashMap<String, MutableList<Fact>>()
    private val relations = LinkedHashSet<String>()
    private val pinyin = HashMap<Char, String>()      // 汉字 -> 无声调拼音
    private val pinyinIndex = HashMap<String, String>() // 无声调拼音串 -> 实体名(惰性构建)

    /** 事实库条数 */
    val factCount: Int get() = facts.size
    val pinyinCount: Int get() = pinyin.size
    val relationCount: Int get() = relations.size

    init {
        loadFacts(assets)
        loadPinyin(assets)
        loadRelations(assets)
    }

    private fun loadFacts(assets: AssetManager) {
        assets.open("facts.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val p = line.split('\t')
                if (p.size < 3) continue
                val f = Fact(p[0].trim(), p[1].trim(), p[2].trim(),
                    if (p.size > 3) p[3].trim() else "")
                if (f.s.isEmpty() || f.rel.isEmpty() || f.o.isEmpty()) continue
                facts.add(f)
                bySubject.getOrPut(f.s) { ArrayList(2) }.add(f)
                byObject.getOrPut(f.o) { ArrayList(2) }.add(f)
            }
        }
    }

    private fun loadPinyin(assets: AssetManager) {
        assets.open("pinyin.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val i = line.indexOf('\t')
                if (i <= 0) continue
                val ch = line.substring(0, i)
                val py = line.substring(i + 1).trim()
                if (ch.length == 1 && py.isNotEmpty()) pinyin[ch[0]] = py
            }
        }
    }

    private fun loadRelations(assets: AssetManager) {
        try {
            assets.open("relations.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val r = line.trim()
                    if (r.isNotEmpty()) relations.add(r)
                }
            }
        } catch (_: Exception) {
            // 关系词清单缺失不影响主流程(动态关系问句会退化)
        }
    }

    // ---------------- 查询 ----------------
    fun forward(s: String, rel: String? = null): List<String> =
        bySubject[s]?.filter { rel == null || it.rel == rel }?.map { it.o } ?: emptyList()

    fun reverse(o: String, rel: String? = null): List<String> =
        byObject[o]?.filter { rel == null || it.rel == rel }?.map { it.s } ?: emptyList()

    fun entityNames(): Set<String> = bySubject.keys

    // ---------------- 文本归一化 ----------------
    private val normalizeMap = mapOf(
        "marisa" to "魔理沙", "reimu" to "灵梦", "alice" to "爱丽丝",
        "sakuya" to "咲夜", "android" to "安卓", "ai" to "人工智能", "pc" to "电脑"
    )
    private val phoneticFix = mapOf(
        "卡卡西" to "卡卡", "魔里沙" to "魔理沙", "魔理纱" to "魔理沙", "摸你傻" to "魔理沙",
        "零梦" to "灵梦", "林梦" to "灵梦", "爱丽斯" to "爱丽丝", "波利灵梦" to "博丽灵梦"
    )

    fun normalize(text: String): String {
        var t = text.trim().replace(Regex("[\\s\\u3000]+"), "")
        var low = t.lowercase()
        for ((en, zh) in normalizeMap) if (en != zh) low = low.replace(en, zh)
        t = low
        for ((wrong, right) in phoneticFix) if (wrong != right) t = t.replace(wrong, right)
        // 全角 -> 半角
        val sb = StringBuilder(t.length)
        for (c in t) {
            sb.append(if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c)
        }
        return sb.toString().trim('。', '！', '？', '!', '?', '~', '～', ',', '，', '、', ' ')
    }

    /** 疑难句尾语气词剥离 */
    private val tailWords = "呢啊呀哦吧嘛"
    fun stripTail(text: String): String {
        var t = text.trim()
        while (t.isNotEmpty() && (tailWords.contains(t.last()) ||
                    "？?！!。，,".contains(t.last()))) t = t.dropLast(1)
        for (tail in listOf("怎么样", "如何")) if (t.endsWith(tail)) t = t.dropLast(tail.length)
        return t.trim()
    }

    // ---------------- 拼音（多音节分辨） ----------------
    fun pinyinKey(word: String): String? {
        if (word.isEmpty()) return null
        val sb = StringBuilder()
        for (c in word) sb.append(pinyin[c] ?: return null)
        return sb.toString()
    }

    private fun ensurePinyinIndex() {
        if (pinyinIndex.isNotEmpty()) return
        for (e in entityNames()) {
            val k = pinyinKey(e) ?: continue
            pinyinIndex.putIfAbsent(k, e)
        }
    }

    /** 同音实体:魔里沙 -> 魔理沙 */
    fun homophoneEntity(word: String): String? {
        if (word.length < 2) return null
        ensurePinyinIndex()
        return pinyinIndex[pinyinKey(word) ?: return null]
    }

    // ---------------- 实体归一 ----------------
    fun fuzzyFind(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        phoneticFix[t]?.let { return it }
        if (bySubject.containsKey(t)) return t
        // 与事实库实体做包含匹配
        for (e in entityNames()) if (e != t && e.length > 1 && t.contains(e)) return e
        for (e in entityNames()) if (e != t && e.length > 1 && e.contains(t)) return e
        // 拼音同音
        homophoneEntity(t)?.let { return it }
        // 叠字归一
        for (dup in listOf("狗狗", "猫猫", "鸟鸟", "车车")) {
            if (t == dup) return fuzzyFind(dup.substring(0, 1))
        }
        return t
    }

    fun normEntity(raw: String): String = fuzzyFind(stripMeasure(stripTail(raw)))

    private val measureChars = "个只条张本头匹件位名门辆架朵把颗"
    fun stripMeasure(text: String): String {
        val t = text.trim()
        if (t.length < 2) return t
        // "一个猫" -> 去掉"一个";"张伟" 不能动(张不是量词误判)
        if ("一这那".contains(t[0]) && t.length >= 3 && measureChars.contains(t[1])) return t.drop(2)
        return t
    }

    // ---------------- 关系词 ----------------
    private val relAliases: Map<String, List<String>> = mapOf(
        "是" to listOf("是", "就是", "属于"),
        "有" to listOf("有", "拥有", "具有"),
        "喜欢" to listOf("喜欢", "最爱", "爱"),
        "会" to listOf("会", "能", "可以"),
        "住在" to listOf("住在", "生活在", "位于"),
        "朋友" to listOf("朋友", "好友"),
        "爸爸" to listOf("爸爸", "父亲", "爹"),
        "妈妈" to listOf("妈妈", "母亲", "娘"),
        "爷爷" to listOf("爷爷", "祖父"),
        "奶奶" to listOf("奶奶", "祖母"),
        "哥哥" to listOf("哥哥", "兄长"),
        "弟弟" to listOf("弟弟"),
        "姐姐" to listOf("姐姐"),
        "妹妹" to listOf("妹妹"),
        "妻子" to listOf("妻子", "老婆"),
        "丈夫" to listOf("丈夫", "老公"),
        "儿子" to listOf("儿子"),
        "女儿" to listOf("女儿")
    )
    private val measureRel = mapOf(
        "长" to "长度", "高" to "高度", "大" to "面积", "重" to "重量",
        "久" to "时长", "远" to "距离", "深" to "深度", "宽" to "宽度", "厚" to "厚度"
    )
    private val nonIsa = setOf(
        "首都", "省会", "符号", "人口", "面积", "沸点", "光速", "住在", "位于",
        "绕着", "有", "会", "喜欢", "妻子", "丈夫", "朋友"
    )

    private fun isaRel(rel: String) = rel == "是" || rel == "属于"

    fun canonRel(word: String): String? {
        val w = stripTail(word)
        if (w.isEmpty()) return null
        for ((canon, aliases) in relAliases) if (w == canon || aliases.contains(w)) return canon
        return if (relations.contains(w)) w else null
    }

    private fun relCandidates(raw: String): List<String> {
        val w = stripTail(raw)
        if (w.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        canonRel(w)?.let { out.add(it) }
        if (relations.contains(w)) out.add(w)
        if (w.length >= 2) for (k in relations) if (k.length > w.length && k.endsWith(w)) out.add(k)
        return out.toList()
    }

    // ---------------- 关系链 / 继承 ----------------
    private fun isaChain(entity: String, depth: Int = 0, seen: MutableSet<String> = HashSet()): List<String> {
        if (depth > 6 || !seen.add(entity)) return emptyList()
        val out = ArrayList<String>()
        for (o in forward(entity, "是")) {
            if (seen.contains(o)) continue
            out.add(o)
            out.addAll(isaChain(o, depth + 1, seen))
        }
        return out
    }

    /** 查关系:直接事实 -> 分类继承 */
    private fun lookup(entity: String, rel: String): List<String> {
        val direct = forward(entity, rel)
        if (direct.isNotEmpty()) return direct
        for (cls in isaChain(entity)) {
            val got = forward(cls, rel)
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    /** 查否定关系:不会/没有/不喜欢,支持继承(企鹅不会飞 要盖过 鸟会飞) */
    private fun lookupNeg(entity: String, rel: String): List<String> {
        val neg = when (rel) {
            "会", "能", "可以" -> "不$rel"
            "有" -> "没有"
            else -> "不$rel"
        }
        for (t in listOf(entity) + isaChain(entity)) {
            val got = forward(t, neg)
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    private val transitive = listOf(
        Triple("爸爸", "爸爸", "爷爷"),
        Triple("妈妈", "妈妈", "外婆"),
        Triple("爸爸", "妈妈", "奶奶"),
        Triple("妈妈", "爸爸", "外公"),
        Triple("儿子", "儿子", "孙子"),
        Triple("朋友", "朋友", "朋友")
    )

    private fun transitive(a: String, rel: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((r1, r2, outRel) in transitive) {
            if (r1 != rel && outRel != rel) continue
            for (mid in forward(a, r1)) {
                if (mid == a) continue
                for (c in forward(mid, r2)) {
                    out.add(c to "$a的$r1是$mid,$mid的$r2是$c")
                }
            }
        }
        return out
    }

    // ---------------- 亲属路径（家谱树 LCA） ----------------
    private val parentRels = setOf("爸爸", "妈妈")
    private val childRels = setOf("儿子", "女儿")
    private val spouseRels = setOf("妻子", "丈夫")

    private class Kin(
        val parent: HashMap<String, MutableSet<String>>,
        val child: HashMap<String, MutableSet<String>>,
        val spouse: HashMap<String, MutableSet<String>>,
        val relName: HashMap<String, String>
    )

    private fun kinEdges(): Kin {
        val parent = HashMap<String, MutableSet<String>>()
        val child = HashMap<String, MutableSet<String>>()
        val spouse = HashMap<String, MutableSet<String>>()
        val relName = HashMap<String, String>()
        for (f in facts) {
            when {
                parentRels.contains(f.rel) -> {
                    parent.getOrPut(f.s) { HashSet() }.add(f.o)
                    child.getOrPut(f.o) { HashSet() }.add(f.s)
                    relName["${f.s}|${f.o}"] = f.rel
                }
                childRels.contains(f.rel) -> {
                    child.getOrPut(f.s) { HashSet() }.add(f.o)
                    parent.getOrPut(f.o) { HashSet() }.add(f.s)
                    relName["${f.o}|${f.s}"] = f.rel
                }
                spouseRels.contains(f.rel) -> {
                    spouse.getOrPut(f.s) { HashSet() }.add(f.o)
                    spouse.getOrPut(f.o) { HashSet() }.add(f.s)
                    relName["${f.s}|${f.o}"] = f.rel
                }
            }
        }
        return Kin(parent, child, spouse, relName)
    }

    private fun ancestorIndex(node: String, parent: Map<String, MutableSet<String>>, maxUp: Int = 4): HashMap<String, Int> {
        val idx = HashMap<String, Int>()
        idx[node] = 0
        var frontier = listOf(node)
        for (d in 1..maxUp) {
            val next = ArrayList<String>()
            for (cur in frontier) for (p in parent[cur] ?: emptySet()) {
                if (!idx.containsKey(p)) { idx[p] = d; next.add(p) }
            }
            if (next.isEmpty()) break
            frontier = next
        }
        return idx
    }

    /** 路径元素:方向/主语/宾语/关系名 */
    private data class Edge(val dir: String, val a: String, val b: String, val rel: String)

    private fun kinPath(a: String, b: String, maxUp: Int = 4): List<Edge>? {
        if (a == b) return emptyList()
        val kin = kinEdges()
        // 同父母即兄弟姐妹
        for (p in kin.parent[a] ?: emptySet()) {
            if (b != a && (kin.child[p] ?: emptySet()).contains(b)) {
                return listOf(Edge("down", a, b, "兄弟姐妹"))
            }
        }
        (kin.spouse[a] ?: emptySet()).firstOrNull { it == b }?.let {
            return listOf(Edge("spouse", a, b, kin.relName["$a|$b"] ?: "配偶"))
        }
        val ia = ancestorIndex(a, kin.parent, maxUp)
        val ib = ancestorIndex(b, kin.parent, maxUp)
        val common = ia.keys.intersect(ib.keys)
        if (common.isNotEmpty()) {
            val ancestor = common.minByOrNull { (ia[it] ?: 99) + (ib[it] ?: 99) }!!
            val up = upChain(a, ancestor, ia, kin)
            val down = upChain(b, ancestor, ib, kin)
            if (up != null && down != null) {
                val downPath = ArrayList<Edge>()
                for (e in down.reversed()) {
                    // 反向成"父->子"方向,用子女称谓
                    val pr = kin.relName["${e.a}|${e.b}"] ?: kin.relName["${e.b}|${e.a}"] ?: ""
                    downPath.add(Edge("down", e.b, e.a, downRelName(pr)))
                }
                return up + downPath
            }
        }
        // 配偶一侧有血缘
        for (sp in kin.spouse[a] ?: emptySet()) {
            val sub = kinPath(sp, b, maxUp)
            if (sub != null) return listOf(Edge("spouse", a, sp, kin.relName["$a|$sp"] ?: "配偶")) + sub
        }
        return null
    }

    private fun upChain(start: String, target: String, dist: Map<String, Int>, kin: Kin, limit: Int = 8): List<Edge>? {
        if (start == target) return emptyList()
        val queue = ArrayDeque<Pair<String, List<Edge>>>()
        queue.add(start to emptyList())
        val seen = HashSet<String>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val (cur, chain) = queue.removeFirst()
            if (chain.size >= limit) continue
            for (p in kin.parent[cur] ?: emptySet()) {
                if (!dist.containsKey(p) || seen.contains(p)) continue
                val nc = chain + Edge("up", cur, p, kin.relName["$cur|$p"] ?: "爸爸/妈妈")
                if (p == target) return nc
                seen.add(p)
                queue.add(p to nc)
            }
        }
        return null
    }

    private val downRel = mapOf(
        "爸爸" to "子女", "妈妈" to "子女", "爷爷" to "孙辈", "奶奶" to "孙辈",
        "哥哥" to "弟弟/妹妹", "弟弟" to "哥哥/姐姐", "姐姐" to "弟弟/妹妹", "妹妹" to "哥哥/姐姐",
        "丈夫" to "妻子", "妻子" to "丈夫"
    )
    private fun downRelName(parentRel: String) = downRel[parentRel] ?: "子女"

    private val kinTerms = mapOf(
        (1 to 0) to "父母", (2 to 0) to "祖父母", (3 to 0) to "曾祖父母",
        (0 to 1) to "子女", (0 to 2) to "孙辈",
        (1 to 1) to "兄弟姐妹", (2 to 1) to "叔伯/舅舅/姑姑/姨", (1 to 2) to "侄辈",
        (2 to 2) to "堂表兄弟姐妹", (3 to 1) to "曾祖辈的兄弟姐妹"
    )

    private fun labelPath(path: List<Edge>): Pair<String?, String> {
        val ups = path.count { it.dir == "up" }
        val downs = path.count { it.dir == "down" }
        val spouses = path.any { it.dir == "spouse" }
        val rels = path.map { it.rel }.toSet()
        var term: String? = if (rels.any { it == "兄弟姐妹" || it == "弟弟/妹妹" || it == "哥哥/姐姐" })
            "兄弟姐妹" else kinTerms[ups to downs]
        if (spouses && term == null) term = "姻亲"
        val detail = path.joinToString(" -> ") { "${it.a}的${it.rel}是${it.b}" }
        return term to detail
    }

    private fun directRel(a: String, b: String): Answer? {
        for (f in bySubject[a] ?: emptyList()) {
            if (f.o == b && !isaRel(f.rel)) return Answer("${f.s}的${f.rel}是${f.o}。", "直接事实", "rel")
        }
        for (f in bySubject[b] ?: emptyList()) {
            if (f.o == a && !isaRel(f.rel)) return Answer("${f.s}的${f.rel}是${f.o}。", "直接事实", "rel")
        }
        return null
    }

    private fun crossRel(a: String, b: String): Answer? {
        directRel(a, b)?.let { return it }
        kinPath(a, b)?.let { path ->
            val (term, detail) = labelPath(path)
            if (term != null) {
                return if (term == "兄弟姐妹" || term == "堂表兄弟姐妹" || term == "父母" || term == "子女")
                    Answer("$a和$b是$term。", detail, "infer")
                else Answer("$a是$b的$term($detail)。", detail, "infer")
            }
            return Answer("$a和$b的关系是:$detail。", detail, "infer")
        }
        // 共享中间实体
        for (f1 in bySubject[a] ?: emptyList()) {
            for (f2 in bySubject[b] ?: emptyList()) {
                if (f1.o == f2.o && !isaRel(f1.rel) && !isaRel(f2.rel)) {
                    return Answer("$a的${f1.rel}和$b的${f2.rel}是同一个人(${f1.o}),所以他们是亲戚关系。",
                        "${a}的${f1.rel}=${f1.o}", "infer")
                }
            }
        }
        return null
    }

    // ---------------- 句式 ----------------
    private fun phrase(entity: String, rel: String, obj: String): String {
        val r = rel.trim()
        val o = obj.trim()
        if (r.isEmpty()) return entity + o
        if (listOf("有", "长", "会", "能", "可以", "喜欢", "讨厌", "需要", "没有", "不会").contains(r))
            return "$entity$r$o"
        if (isaRel(r)) return "$entity是$o"
        if (r == "绕着") return "$entity绕着$o"
        if (r == "走得" || r == "跑得") return "$entity$r$o"
        if (r == "在") return "$entity在$o"
        if (r == "住在" || r == "位于") return "$entity$r$o"
        if (r.endsWith("于")) return "$entity$r$o"
        return "$entity的$r是$o"
    }

    private val skipReverse = setOf("颜色", "叫声", "口头禅", "首都", "绕着", "在", "走得", "需要")

    private fun describe(entity: String): Answer? {
        if (entity.isEmpty()) return null
        val classes = isaChain(entity)
        val ownFacts = (bySubject[entity] ?: emptyList()).filter { isaRel(it.rel) }
        val attrFacts = (bySubject[entity] ?: emptyList()).filter { !isaRel(it.rel) }
        val rev = byObject[entity] ?: emptyList()
        if (classes.isEmpty() && ownFacts.isEmpty() && attrFacts.isEmpty() && rev.isEmpty()) return null

        val parts = ArrayList<String>(6)
        val merged = LinkedHashSet<String>()
        ownFacts.forEach { merged.add(it.o) }
        classes.forEach { merged.add(it) }
        if (merged.isNotEmpty()) parts.add("$entity是" + merged.take(4).joinToString("、"))
        for (f in attrFacts.take(4)) parts.add(phrase(entity, f.rel, f.o))
        for (f in rev) {
            if (skipReverse.contains(f.rel) || !isaRel(f.rel)) continue
            val inv = when (f.rel) {
                "是" -> "的一种"; "有" -> "属于"; "喜欢" -> "被喜欢"; "会" -> "的能力属于"
                "住在" -> "的居民包括"; "朋友" -> "的朋友"
                else -> null
            }
            if (inv != null) { parts.add("$entity是${f.s}$inv"); break }
        }
        if (parts.isEmpty()) return null
        return Answer(parts.joinToString(";") + "。本天才记得清清楚楚だぜ!", "分类继承+属性检索", "describe")
    }

    // ---------------- 计数与换算 ----------------
    private val cnDigits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val cnUnits = mapOf('十' to 10, '百' to 100, '千' to 1000)

    fun cnNumber(text: String): Int? {
        if (text.isEmpty()) return null
        var total = 0; var section = 0; var num = 0; var seen = false
        for (ch in text) {
            when {
                cnDigits.containsKey(ch) -> { num = cnDigits[ch]!!; seen = true }
                cnUnits.containsKey(ch) -> {
                    val unit = cnUnits[ch]!!
                    if (num == 0) num = 1
                    section += num * unit; num = 0; seen = true
                }
                else -> return null
            }
        }
        if (!seen) return null
        return total + section + num
    }

    private fun countUnit(value: String, unit: String): Int? {
        val idx = value.indexOf(unit)
        if (idx <= 0) return null
        val prefix = value.substring(0, idx)
        Regex("(\\d+(?:\\.\\d+)?)$").find(prefix)?.let { return it.groupValues[1].toDouble().toInt() }
        return cnNumber(prefix)
    }

    // ---------------- 意图模式 ----------------
    private val pCount = Regex("(.+?)(?:一共)?(?:有|长)几(?:个|只|条|张|本|头|匹|件|位|名|门|辆|架|朵|把|颗)?(.)[呢？?]*$")
    private val pCount2 = Regex("(.+?)(?:一共)?(?:有|长了)(?:几|多少)(.{1,10})$")
    private val pFood = Regex("(.+?)(?:最)?(?:喜欢|爱)(?:吃|喝|玩)(?:什么|啥)[呢？?]*$")
    private val pLike = Regex("(.+?)(?:最)?喜欢什么[呢？?]*$")
    private val pCan = Regex("(.+?)(?:会|能|可以)(?:什么|啥|干什么|做什么)[呢？?]*$")
    private val pAttr = Regex("(.+?)的([^的是什么谁哪]+?)(?:是多少|是几|是什么|是啥|是哪个|是谁|叫什么|叫啥|呢|？|\\?)$")
    private val pWhatIs = Regex("(.+?)(?:是|属于)(?:什么|啥|哪种|哪类|什么样的)(?:东西|动物|人|植物)?[呢？?]*$")
    private val pWhatIs2 = Regex("^什么是(.+?)[呢？?]*$")
    private val pWhoIs = Regex("(.+?)(?:是|叫)(?:谁|啥人|什么人)[呢？?]*$")
    private val pWhere = Regex("(.+?)(?:住在|生活在|位于)(?:哪里|哪儿|什么地方|哪)?[呢？?]*$")
    private val pWhere2 = Regex("(.+?)(?:在|去)(?:哪|哪里|哪儿)[呢？?]*$")
    private val pYesNo = Regex("(.+?)(?:是不是|是|会|能|可以|属于)([^吗么嘛？?]+?)(?:吗|么|嘛|吧)?[？?]*$")
    private val pRelBetween = Regex("(.+?)(?:和|跟|与)([^是]+?)(?:是)?(?:什么)?关系[呢？?]*$")
    private val pValue = Regex("(.{1,20}?)(?:是|等于)(?:多少|几|多大|什么值)[呢？?]*$")
    private val pMeasure = Regex("(.{1,20}?)有多(长|高|大|重|久|远|深|宽|厚)[呢？?]*$")
    private val pWhoDid = Regex("^(?:谁|是哪位|是哪个人)(.{2,6}?)(?:了|的)?(.{1,20}?)[呢？?]*$")
    private val pRelWhen = Regex("(.{1,20}?)(?:的)?([^的是什么谁哪]{1,9}?)(?:是哪一年|是哪年|是哪一天|是什么时候|是何时|哪一年|哪年|什么时候|何时)[呢？?]*$")
    private val pRelWhere = Regex("(.+?)(?:的)?([^的是什么谁哪]{1,8}?)是(?:哪里|哪儿|哪个城市|什么地方)[呢？?]*$")
    private val pConvert = Regex("^(.{1,20}?)是(?:多少|几)(.{1,6}?)[呢？?]*$")
    private val pYesNoAdj = Regex("(.+?)(走得|跑得|飞得|游得|长得)?([快慢大小多少高矮长短胖瘦冷热新旧早晚])(?:吗|么|嘛)[？?]*$")
    private val pChain = Regex("^(.+?)的((?:[^的是什么谁哪]+?的)*[^的是什么谁哪]+?)(?:是|叫)(?:谁|什么|啥|哪个)(?:呢|？|\\?)?$")

    private val questionMarkers = listOf(
        "什么", "谁", "哪", "吗", "呢", "几", "多少", "多长", "多高", "多大", "多重",
        "多久", "多远", "怎么样", "如何", "为什么", "是不是", "有没有", "能不能",
        "会不会", "可不可以", "请问", "?", "？"
    )
    private fun isQuestion(t: String) = questionMarkers.any { t.contains(it) }

    private val antonymPairs = listOf(
        "快" to "慢", "大" to "小", "多" to "少", "高" to "矮", "高" to "低",
        "长" to "短", "胖" to "瘦", "冷" to "热", "新" to "旧", "早" to "晚",
        "会" to "不会", "有" to "没有", "喜欢" to "讨厌"
    )
    private fun contradicts(claim: String, fact: String): Boolean {
        for ((x, y) in antonymPairs) {
            if ((claim.contains(x) && fact.contains(y)) || (claim.contains(y) && fact.contains(x))) return true
        }
        return false
    }

    /** 已知实体最长匹配:解决 "秦朝建立于哪一年" 被切成 (秦, 朝建立于) */
    private fun splitEntityRel(text: String): Pair<String, String> {
        val t = text.trim()
        if (t.isEmpty()) return t to ""
        val known = entityNames()
        var best = ""
        for (name in known) if (t.startsWith(name) && name.length > best.length) best = name
        if (best.isNotEmpty() && best.length < t.length) return best to t.substring(best.length)
        for (rel in relations.sortedByDescending { it.length }) {
            val idx = t.indexOf(rel)
            if (idx > 0) {
                val head = t.substring(0, idx)
                if (known.contains(head)) return head to rel
            }
        }
        return t to ""
    }

    // ---------------- 主入口 ----------------
    fun think(question: String): Answer? {
        if (question.isBlank()) return null
        var q = normalize(question)
        q = q.replace(Regex("^(请问|问一下|我想知道|告诉我)"), "").trim(',', '，', '。')

        // 0. 关系链:"小明的爸爸的爸爸是谁"
        pChain.find(q)?.let { m ->
            val head = normEntity(m.groupValues[1])
            val relWords = m.groupValues[2].split('的').filter { it.isNotEmpty() }
            val rels = relWords.map { canonRel(it) }
            if (head.isNotEmpty() && rels.isNotEmpty() && rels.none { it == null }) {
                answerChain(head, rels.filterNotNull())?.let { return it }
            }
        }

        // 1. 数量问句
        (pCount.find(q) ?: pCount2.find(q))?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val unit = m.groupValues[2]
            val noun = unit.replace(Regex("^(?:几|多少)?[个只条张本头匹件位名门辆架朵把颗]?"), "")
            for (rel in listOf("有", "长")) {
                for (v in lookup(ent, rel)) {
                    if (v.contains(unit) || v.endsWith(unit) || (noun.isNotEmpty() && v.contains(noun)))
                        return Answer("$ent有$v。", "直接事实", "attr")
                }
            }
            for (rel in listOf("有", "长")) {
                for (v in lookup(ent, rel)) {
                    if (v.any { "颗条只个张座把位".contains(it) })
                        return Answer("$ent有$v。", "直接事实", "attr")
                }
            }
        }

        // 2. 吃喝 / 喜欢 / 会什么
        pFood.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val vals = lookup(ent, "喜欢")
            vals.firstOrNull { it.isNotEmpty() && "吃喝啃".contains(it[0]) }?.let {
                return Answer("$ent喜欢$it。", "直接事实", "attr")
            }
        }
        pLike.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val vals = lookup(ent, "喜欢")
            if (vals.isNotEmpty()) return Answer("$ent喜欢${vals.joinToString("、")}。", "直接事实", "attr")
        }
        pCan.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val vals = lookup(ent, "会")
            if (vals.isNotEmpty()) return Answer("$ent会${vals.joinToString("、")}。", "直接事实", "attr")
        }

        // 3. 教学句识别(必须先排除疑问句)
        if (!isQuestion(q)) {
            toTriple(q)?.let { (s, r, o) ->
                facts.add(Fact(s, r, o, "taught"))
                bySubject.getOrPut(s) { ArrayList() }.add(Fact(s, r, o, "taught"))
                byObject.getOrPut(o) { ArrayList() }.add(Fact(s, r, o, "taught"))
                return Answer("记住啦:${s}的${r}是${o}。本天才的记忆力可是很好的だぜ!", "事实入库", "remember")
            }
        }

        // 4. 值类问句(必须早于是非句)
        pValue.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            for (rel in listOf("数值", "值", "是")) {
                val vals = lookup(ent, rel)
                if (vals.isNotEmpty()) return Answer("$ent是${vals.joinToString("、")}。", "直接事实", "value")
            }
            describe(ent)?.let { return it }
        }

        // 5. 谁发明了X(反查)
        if (q.startsWith("谁") || q.startsWith("是哪位")) {
            pWhoDid.find(q)?.let { m ->
                val rel = stripTail(m.groupValues[1])
                val obj = stripTail(m.groupValues[2])
                val cands = LinkedHashSet<String>()
                cands.addAll(relCandidates(rel))
                cands.addAll(listOf("发明", "发现", "提出", "改进", "创立", "创作", "编写", "建立"))
                for (r in cands) {
                    val holders = reverse(obj, r)
                    if (holders.isNotEmpty()) return Answer("是${holders.joinToString("、")}$r$obj 的。", "反查事实", "reverse")
                }
            }
        }

        // 6. X的Y是什么
        pAttr.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val attr = stripTail(m.groupValues[2].removePrefix("的"))
            val canon = canonRel(attr) ?: attr.takeIf { relations.contains(it) }
            if (canon != null) {
                val vals = lookup(ent, canon)
                if (vals.isNotEmpty()) return Answer("$ent的$attr是${vals.joinToString("、")}。", "直接事实", "attr")
                transitive(ent, canon).firstOrNull()?.let { (v, how) ->
                    return Answer("推出来的:$ent的$attr是$v。", how, "infer")
                }
            }
            // 属性部分匹配("卡卡的尾巴呢")
            if (attr.isNotEmpty()) {
                var best: Fact? = null
                for (f in (bySubject[ent] ?: emptyList())) {
                    if (skipReverse.contains(f.rel) || isaRel(f.rel)) continue
                    if (f.o.contains(attr) && (best == null || f.o.length < best!!.o.length)) best = f
                }
                if (best == null) {
                    for (cls in isaChain(ent)) for (f in (bySubject[cls] ?: emptyList())) {
                        if (skipReverse.contains(f.rel) || isaRel(f.rel)) continue
                        if (f.o.contains(attr) && (best == null || f.o.length < best!!.o.length)) best = f
                    }
                }
                best?.let { return Answer("$ent${it.o}。", "属性部分匹配", "attr") }
            }
        }

        // 7. X是什么 / 什么是X / X是谁
        for (p in listOf(pWhatIs, pWhatIs2)) {
            p.find(q)?.let { m ->
                val ent = normEntity(m.groupValues[1])
                describe(ent)?.let { return it }
            }
        }
        pWhoIs.find(q)?.let { m -> describe(normEntity(m.groupValues[1]))?.let { return it } }

        // 8. 位置类
        for (p in listOf(pWhere, pWhere2)) {
            p.find(q)?.let { m ->
                val ent = normEntity(m.groupValues[1])
                for (rel in listOf("住在", "位于")) {
                    val vals = lookup(ent, rel)
                    if (vals.isNotEmpty()) {
                        val verb = if (rel == "住在") "住在" else "位于"
                        return Answer("$ent$verb${vals[0]}。", "直接事实", "where")
                    }
                }
            }
        }
        pRelWhere.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            for (rel in relCandidates(m.groupValues[2])) {
                val vals = lookup(ent, rel)
                if (vals.isNotEmpty()) return Answer("$ent的$rel是${vals.joinToString("、")}。", "直接事实", "attr")
            }
        }

        // 9. 度量问句
        pMeasure.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val rel = measureRel[m.groupValues[2]]
            if (rel != null) {
                val vals = lookup(ent, rel)
                if (vals.isNotEmpty()) return Answer("$ent的$rel是${vals.joinToString("、")}。", "直接事实", "measure")
            }
        }

        // 10. 时间类关系问句
        pRelWhen.find(q)?.let { m ->
            val joined = m.groupValues[1] + m.groupValues[2]
            val (ent, rawRel) = splitEntityRel(joined)
            if (ent.isNotEmpty()) {
                val cands = LinkedHashSet<String>()
                cands.addAll(relCandidates(rawRel))
                cands.addAll(listOf("建立于", "灭亡于", "发生于", "结束于", "通航于", "举办于"))
                for (r in cands) {
                    val vals = lookup(ent, r)
                    if (vals.isNotEmpty()) return Answer("$ent$r${vals.joinToString("、")}。", "直接事实", "when")
                }
            }
        }

        // 11. 换算
        pConvert.find(q)?.let { m ->
            val ent = normEntity(m.groupValues[1])
            val unit = stripTail(m.groupValues[2])
            if (ent.isNotEmpty() && unit.isNotEmpty()) {
                for (v in lookup(ent, "是")) {
                    if (v.contains(unit)) {
                        val n = countUnit(v, unit)
                        if (n != null) return Answer("$ent是$n$unit。", "由「$ent是$v」换算", "convert")
                        return Answer("$ent是$v。", "直接事实", "convert")
                    }
                }
            }
        }

        // 12. 是非问句
        if (Regex("(吗|么|嘛)[？?]*$").containsMatchIn(q)) {
            pYesNo.find(q)?.let { m ->
                val a = normEntity(m.groupValues[1])
                val b = stripTail(m.groupValues[2])
                if (a.isNotEmpty() && b.isNotEmpty()) {
                    if (forward(a, "是").contains(b)) return Answer("对,$a是$b。", "直接事实", "yesno")
                    if (isaChain(a).contains(b)) return Answer("对,$a是$b(推出来的)。", "分类继承", "infer")
                    for (rel in listOf("会", "可以", "能")) {
                        val negs = lookupNeg(a, rel)
                        if (negs.any { it.contains(b) || b.contains(it) })
                            return Answer("不,$a不会$b。别为难本天才だぜ~", "否定事实", "yesno")
                        val pos = lookup(a, rel)
                        if (pos.any { it.contains(b) || b.contains(it) })
                            return Answer("对,$a会$b。", "直接事实", "yesno")
                    }
                    if ((bySubject[a]?.isNotEmpty() == true) || (bySubject[b]?.isNotEmpty() == true))
                        return Answer("不是,$a不是$b。", "事实库中无此关系", "yesno")
                }
            }
            pYesNoAdj.find(q)?.let { m ->
                val ent = normEntity(m.groupValues[1])
                val adj = m.groupValues[3]
                val cands = ArrayList<Fact>()
                cands.addAll(bySubject[ent] ?: emptyList())
                for (cls in isaChain(ent)) cands.addAll(bySubject[cls] ?: emptyList())
                for (f in cands) {
                    if (f.o.contains(adj)) return Answer("对,$ent${f.rel}${f.o}。", "直接事实", "yesno")
                    if (contradicts(adj, f.o)) return Answer("不,$ent${f.rel}${f.o}。", "反义事实", "yesno")
                }
            }
        }

        // 13. 两个实体的关系
        pRelBetween.find(q)?.let { m ->
            val a = normEntity(m.groupValues[1])
            val b = normEntity(m.groupValues[2])
            crossRel(a, b)?.let { return it }
            val common = isaChain(a).toSet().intersect(isaChain(b).toSet())
            if (common.isNotEmpty()) return Answer("$a和$b都是${common.first()}。", "分类继承", "infer")
        }

        // 14. 兜底:整体能否被描述
        describe(stripMeasure(q))?.let { return it }
        return null
    }

    private fun answerChain(head: String, rels: List<String>): Answer? {
        if (rels.size == 1) {
            val vals = lookup(head, rels[0])
            if (vals.isNotEmpty()) return Answer("$head的${rels[0]}是${vals.joinToString("、")}。", "直接事实", "attr")
            return null
        }
        var cur = listOf(head)
        val why = ArrayList<String>()
        for (rel in rels) {
            val next = ArrayList<String>()
            for (node in cur) {
                for (o in forward(node, rel)) {
                    next.add(o); why.add("${node}的${rel}是$o")
                }
            }
            if (next.isEmpty()) {
                val inferred = ArrayList<String>()
                for (node in cur) for ((v, _h) in transitive(node, rel)) inferred.add(v)
                if (inferred.isEmpty()) return null
                next.addAll(inferred)
            }
            cur = next.distinct()
        }
        return Answer("推出来的:$head的${rels.joinToString("的")}是${cur.joinToString("、")}。",
            "关系链推理", "infer")
    }

    // ---------------- 三元组解析(教学) ----------------
    private val relTokens: List<Pair<String, String>> = relAliases
        .flatMap { (canon, aliases) -> aliases.map { it to canon } }
        .sortedByDescending { it.first.length }

    fun toTriple(sentence: String): Triple<String, String, String>? {
        var t = normalize(sentence)
        t = t.replace(Regex("^教[你她他]?[一]?[句个]?[:：]?"), "")
        // 第一轮:非"是"的关系词
        val r1 = relTokens.filter { it.first != "是" && it.first != "就是" && it.first != "属于" }
        matchTriple(t, r1)?.let { return it }
        // 第二轮:允许"是"
        val r2 = relTokens.filter { it.first == "是" || it.first == "就是" || it.first == "属于" }
        return matchTriple(t, r2)
    }

    private fun matchTriple(t: String, tokens: List<Pair<String, String>>): Triple<String, String, String>? {
        for ((alias, canon) in tokens) {
            val re = Regex("^(.{1,20}?)(?:的)?" + Regex.escape(alias) + "(.{1,30})$")
            val m = re.find(t) ?: continue
            var left = m.groupValues[1].trim()
            var right = m.groupValues[2].trim()
            if (left.isEmpty() || right.isEmpty() || left == right) continue
            for (cop in listOf("是", "就是", "属于")) {
                if (right.startsWith(cop) && right.length > cop.length) {
                    right = right.substring(cop.length).trim(); break
                }
            }
            if (right.isEmpty()) continue
            if (left.contains("的")) {
                val idx = left.lastIndexOf('的')
                val sub = left.substring(0, idx).trim()
                val relWord = left.substring(idx + 1).trim()
                for ((a2, c2) in relTokens) if (relWord == a2) return Triple(sub, c2, right)
            }
            return Triple(left, canon, right)
        }
        return null
    }

    fun Answer(text: String, why: String, kind: String) = com.marisa.kiki.Answer(text, why, kind)
}

