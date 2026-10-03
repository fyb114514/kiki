package com.marisa.kiki

import android.content.res.AssetManager

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
 */
class MarisaBot(assets: AssetManager) {

    private val brain = KikiBrain(assets)

    /** 调教问答:(关键词, 回答, 分类) */
    private val qa = ArrayList<Triple<String, String, String>>()

    /** 开场白(greeting 类条目,上游存成没有关键词的一句话) */
    private val greetings = ArrayList<String>()

    /** 运行时新教的内容(本次会话内有效;持久化由上层负责) */
    private val taught = ArrayList<Pair<String, String>>()

    val notFoundAnswer = "唔嗯...不懂你在说什么呢...教教我吧~"
    val factCount: Int get() = brain.factCount
    val qaCount: Int get() = qa.size
    val pinyinCount: Int get() = brain.pinyinCount

    init {
        loadQa(assets)
        loadGreetings(assets)
        loadWords(assets)
    }

    private fun loadQa(assets: AssetManager) {
        assets.open("qa.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val p = line.split('\t')
                if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                    qa.add(Triple(p[0].trim(), p[1].trim(), if (p.size > 2) p[2].trim() else ""))
                }
            }
        }
    }

    private fun loadGreetings(assets: AssetManager) {
        try {
            assets.open("greeting.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) if (line.isNotBlank()) greetings.add(line.trim())
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- 分词(基于词表的最长匹配) ----------------
    private val words = HashSet<String>(65536)
    private var maxWordLen = 1

    private fun loadWords(assets: AssetManager) {
        try {
            assets.open("words.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val w = line.trim()
                    if (w.length >= 2) {
                        words.add(w)
                        if (w.length > maxWordLen) maxWordLen = w.length
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private val stopwords = setOf(
        "你", "我", "他", "她", "它", "的", "了", "吗", "呢", "啊", "吧", "呀", "嘛", "哦",
        "是", "有", "会", "在", "和", "与", "就", "都", "也", "很", "太", "真",
        "这", "那", "个", "什么", "怎么", "为什么", "多少", "几"
    )

    /** 去掉虚词,只留实义词 */
    fun contentTokens(text: String): List<String> =
        segment(text).filter { it.isNotEmpty() && !stopwords.contains(it) }

    /**
     * 最长匹配分词:优先取词表里最长的词,匹配不到才退化为单字。
     *
     * 为什么必须这样切:质量闸靠"实义词覆盖度"识别答非所问,而按单字切会让
     * "你笨吗"与"你今天吃饭了吗"都退化成"你/吗",重合度被虚词污染
     * (这正是 Windows 版最初踩的坑)。有了词表就能切出"今天/吃饭"这样的实义词。
     */
    private fun segment(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        val t = text.trim()
        while (i < t.length) {
            var matched = false
            val maxLen = minOf(maxWordLen, t.length - i)
            var len = maxLen
            while (len >= 2) {
                if (words.contains(t.substring(i, i + len))) {
                    out.add(t.substring(i, i + len))
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                out.add(t[i].toString())
                i++
            }
        }
        return out
    }

    /** 相似度:候选实义词里有多少出现在输入实义词中(与 PC 版一致) */
    private fun overlapRatio(candidate: String, inputTokens: Set<String>): Double {
        val ct = contentTokens(candidate)
        if (ct.isEmpty()) return 0.0
        val hit = ct.count { inputTokens.contains(it) }
        return hit.toDouble() / ct.size
    }

    // ---------------- 主入口 ----------------
    fun reply(input: String): Answer {
        val raw = input.trim()
        if (raw.isEmpty()) return Answer(notFoundAnswer, "空输入", "miss")

        // 1. 精确匹配(教过的原话)
        taught.firstOrNull { it.first == raw }?.let { return Answer(it.second, "精确匹配", "exact") }
        qa.firstOrNull { it.first == raw }?.let { return Answer(it.second, "精确匹配", "exact") }

        // 2. 逻辑引擎(事实/推理)
        brain.think(raw)?.let { return it }

        val qTokens = contentTokens(raw).toSet()

        // 3. greeting 兜底(问候语条目没有关键词,匹配逻辑看不到它们)
        if (qTokens.isNotEmpty()) {
            for (g in greetings) {
                if (contentTokens(g).any { qTokens.contains(it) }) {
                    return Answer(g, "问候语", "greeting")
                }
            }
        }

        // 4. 模糊匹配 + 质量闸
        var best = 0.0
        var bestAns: String? = null
        for ((kw, ans, _cat) in qa) {
            val r = overlapRatio(kw, qTokens)
            if (r > best) { best = r; bestAns = ans }
        }
        for ((kw, ans) in taught) {
            val r = overlapRatio(kw, qTokens)
            if (r > best) { best = r; bestAns = ans }
        }
        if (best >= MIN_OVERLAP && bestAns != null) return Answer(bestAns, "模糊匹配(重合度 $best)", "fuzzy")

        // 5. 兜底
        return Answer(notFoundAnswer, "未命中", "miss")
    }

    /** 教学:存进运行时记忆(上层可持久化) */
    fun teach(keyword: String, answer: String): Boolean {
        val kw = keyword.trim()
        val ans = answer.trim()
        if (kw.isEmpty() || ans.isEmpty()) return false
        taught.removeAll { it.first == kw }
        taught.add(kw to ans)
        return true
    }

    /** 随机开场白(用户打开 App 时她主动说的第一句) */
    fun randomGreeting(): String? = greetings.randomOrNull()

    private companion object {
        const val MIN_OVERLAP = 0.5
    }
}
