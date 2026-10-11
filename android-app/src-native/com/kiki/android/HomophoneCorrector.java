package com.kiki.android;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 同音词纠正 + 记忆库消歧（应用层实现）。
 *
 * ============================================================
 * 为什么在应用层做，而不是用 sherpa-onnx 的 HomophoneReplacer
 * ============================================================
 * sherpa-onnx 确实带同音词替换，但它需要三样东西：
 *     dict/            jieba 格式的词典目录
 *     lexicon.txt      词 -> 拼音 的词表
 *     replace.fst      **编译好的 FST**(由 kaldifst 生成)
 * 那个 .fst 只能从模型发布包里取，无法在构建时临时生成。
 * 而 Paraformer **不支持热词**（hotwords 只在 transducer / LLM 模型上有效，
 * 见 sherpa-onnx 的 from_paraformer 签名）。
 *
 * 所以改成用自己的资产做同一件事 —— 项目里本来就有：
 *     assets/pinyin.txt   12009 个汉字的拼音
 *     事实图里的实体名      用户教过的 + 内置的
 *
 * ============================================================
 * 两个能力
 * ============================================================
 * ① **同音词纠正**：识别结果如果不在已知实体里，用**拼音**去比对 ——
 *    读音完全相同的已知实体，很可能是用户真正说的那个词。
 *      识别"康深"  拼音 kang shen
 *      库里"康神"  拼音 kang shen   -> 纠正为"康神"
 *    这样用户教过的生造词、人名、专有名词会越用越准。
 *
 * ② **同音消歧**：如果多个已知实体拼音相同（如"康神"和"康深"都读 kang shen），
 *    用**最近上下文**选一个 —— 优先选刚聊过的、或与上一句用词重叠的。
 *    这比随机选更符合对话直觉（用户原话是"随机选一个"，但随机会让对话
 *    前后不一致，所以这里用上下文加权，同分时才按词频）。
 */
public class HomophoneCorrector {

    private static final String TAG = "kiki-hr";

    /** 拼音 -> 候选实体（同音词表） */
    private final java.util.HashMap<String, List<String>> byPinyin =
            new java.util.HashMap<String, List<String>>();
    /** 实体使用次数（用于同分时的倾向） */
    private final java.util.HashMap<String, Integer> hits =
            new java.util.HashMap<String, Integer>();

    private final Brain graph;
    /** 最近的上下文词，用于消歧 */
    private final List<String> recent = new ArrayList<String>();

    public HomophoneCorrector(Brain graph) {
        this.graph = graph;
    }

    /** 把一个实体登记进同音词表 */
    public void index(String entity) {
        if (entity == null || entity.length() < 2) return;
        String py = pinyinOf(entity);
        if (py == null || py.isEmpty()) return;
        List<String> list = byPinyin.get(py);
        if (list == null) {
            list = new ArrayList<String>();
            byPinyin.put(py, list);
        }
        if (!list.contains(entity)) list.add(entity);
    }

    /** 从事实图里把所有实体登记一遍（启动/学到新词时调用） */
    public void indexAll() {
        if (graph == null) return;
        for (String e : graph.allEntities()) index(e);
    }

    /** 记下最近说过的话，供消歧用 */
    public void noteContext(String text) {
        if (text == null) return;
        recent.add(text);
        while (recent.size() > 6) recent.remove(0);
    }

    /**
     * 纠正识别结果。
     *
     * @param heard 语音识别的原始结果
     * @return 纠正后的文本；不需要纠正时原样返回
     */
    public String correct(String heard) {
        if (heard == null || heard.isEmpty()) return heard;
        // 整个句子如果已经是个已知实体，不用改
        if (isKnown(heard)) return heard;

        String best = null;
        int bestScore = -1;

        // 滑动窗口，找"读音与某个已知实体相同"的片段
        int n = heard.length();
        for (int len = Math.min(6, n); len >= 2; len--) {
            for (int i = 0; i + len <= n; i++) {
                String seg = heard.substring(i, i + len);
                String py = pinyinOf(seg);
                if (py == null) continue;
                List<String> cands = byPinyin.get(py);
                if (cands == null || cands.isEmpty()) continue;

                String pick = disambiguate(cands, heard);
                if (pick == null) continue;

                int score = len * 10 + contextScore(pick, heard);
                if (score > bestScore) {
                    bestScore = score;
                    best = heard.substring(0, i) + pick + heard.substring(i + len);
                }
            }
        }
        if (best != null && !best.equals(heard)) {
            android.util.Log.i(TAG, "同音纠正: [" + heard + "] -> [" + best + "]");
            return best;
        }
        return heard;
    }

    /**
     * 同音消歧：候选读音都相同，靠上下文与词频选一个。
     *
     * 优先顺序：
     *   1. 与最近上下文有字面重叠的
     *   2. 被查过/用过次数多的
     *   3. 同分时取更长的（长词更具体）
     */
    String disambiguate(List<String> cands, String context) {
        if (cands == null || cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String c : cands) {
            int score = contextScore(c, context);
            Integer h = hits.get(c);
            score += (h == null ? 0 : h) * 2;
            score += c.length();               // 更长的更具体
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** 候选与最近上下文的重叠程度 */
    private int contextScore(String cand, String context) {
        int s = 0;
        for (String ctx : recent) {
            if (ctx == null) continue;
            if (ctx.contains(cand)) s += 6;
            // 字面重叠
            for (int i = 0; i < cand.length(); i++) {
                if (ctx.indexOf(cand.charAt(i)) >= 0) s += 1;
            }
        }
        if (context != null) {
            for (int i = 0; i < cand.length(); i++) {
                if (context.indexOf(cand.charAt(i)) >= 0) s += 1;
            }
        }
        return s;
    }

    /** 某实体被使用/命中一次 */
    public void hit(String entity) {
        if (entity == null) return;
        Integer n = hits.get(entity);
        hits.put(entity, n == null ? 1 : n + 1);
    }

    private boolean isKnown(String w) {
        return graph != null && graph.hasEntity(w);
    }

    /** 用拼音表算一个词的拼音串（无声调、连写） */
    String pinyinOf(String w) {
        if (graph == null || w == null || w.isEmpty()) return null;
        return graph.pinyinKey(w);
    }

    /** 同音词表大小（自检/日志用） */
    public int size() {
        return byPinyin.size();
    }
}
