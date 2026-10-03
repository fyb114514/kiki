package com.kiki.android;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 联网学习(特性 2):从中文维基百科抓实体简介,抽出事实入库。
 *
 * 为什么用维基而不用百度百科
 * --------------------------
 *   1. 维基有**干净的 JSON API**(action=query&prop=extracts),
 *      不用解析整页 HTML,抓回来的就是纯文本;
 *   2. 百度百科页面结构经常变,而且有反爬,离线可用性差。
 * 两者都抓不到时就老实说"不知道",不编 —— 这是原版 kiki 的性格。
 *
 * 抽出来的事实来源标为 {@link Store#SRC_NET},优先级 50,
 * **低于用户亲口教的 100** —— 所以网络知识不会冲掉用户教过的东西。
 *
 * 网络访问一律在后台线程进行(见 {@link #learnAsync}),
 * 主线程绝不碰 IO;抓不到就静默放弃,不影响对话。
 */
public class NetworkLearner {

    private static final String TAG = "kiki-net";

    /** 维基百科中文 API */
    private static final String WIKI_API =
            "https://zh.wikipedia.org/w/api.php?action=query&format=json&prop=extracts"
            + "&explaintext=1&exintro=1&redirects=1&titles=";
    /**
     * 百度百科摘要 API —— **兜底**。
     *
     * 为什么需要兜底:实测这台设备能解析 zh.wikipedia.org 的域名,
     * 但中国大陆网络下维基经常整站不可达。百度百科的开放 API 返回
     * 干净的 JSON(desc / abstract / card),大陆可达性稳定得多。
     * 两个源都拿不到时才说"我不知道" —— 不编。
     */
    private static final String BAIKE_API =
            "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi"
            + "?scope=103&format=json&appid=379020&bk_length=600&bk_key=";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    /** 单次最多学几条,避免一次灌太多噪声 */
    private static final int MAX_FACTS = 8;

    private final Store store;

    public NetworkLearner(Store store) {
        this.store = store;
    }

    /** 学习结果 */
    public static class Result {
        public boolean ok;
        public String title;
        public String summary;
        public int factCount;
        public String message;   // 给用户看的一句话

        Result(boolean ok, String title, String summary, int factCount, String message) {
            this.ok = ok; this.title = title; this.summary = summary;
            this.factCount = factCount; this.message = message;
        }
    }

    /** 回调(在主线程外调用,调用方自己 post 到 UI) */
    public interface Callback {
        void onLearned(Result r);
    }

    /** 后台线程学习一个词条;不会抛异常,失败也回调 ok=false */
    public void learnAsync(final String keyword, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Result r;
                try {
                    r = learn(keyword);
                } catch (Throwable t) {
                    Log.w(TAG, "联网学习异常: " + t);
                    r = new Result(false, keyword, null, 0, "联网学习失败");
                }
                if (cb != null) cb.onLearned(r);
            }
        }, "kiki-net").start();
    }

    /** 同步学习(内部;调用方负责放在后台线程) */
    public Result learn(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new Result(false, keyword, null, 0, "没有可查的词");
        }
        final String kw = keyword.trim();

        // 1. 先试维基(内容质量最好)
        Result r = learnFromWiki(kw);
        if (r.ok && r.factCount > 0) return r;

        // 2. 维基不行就试百度百科(大陆可达性更好)
        Result b = learnFromBaike(kw);
        if (b.ok && b.factCount > 0) return b;

        // 3. 两个源都不行 —— 老实说不知道,把决定权交回用户(反问)
        Log.i(TAG, "两个源都没学到: " + kw);
        return new Result(false, kw, null, 0, "我不认识" + kw + "，你教教我好不好？");
    }

    /** 从中文维基学 */
    private Result learnFromWiki(String kw) {
        String json = httpGet(WIKI_API + URLEncoder.encode(kw));
        if (json == null) {
            Log.i(TAG, "维基抓取失败(可能不可达): " + kw);
            return new Result(false, kw, null, 0, null);
        }
        String title = null;
        String extract = null;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject pages = root.getJSONObject("query").getJSONObject("pages");
            java.util.Iterator<String> it = pages.keys();
            if (it.hasNext()) {
                JSONObject page = pages.getJSONObject(it.next());
                title = page.optString("title", kw);
                extract = page.optString("extract", null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析维基返回失败: " + t);
        }
        if (extract == null || extract.trim().isEmpty()) {
            Log.i(TAG, "维基没有这个词条: " + kw);
            return new Result(false, kw, null, 0, null);
        }
        String summary = firstSentences(extract, 2);
        int n = extractFacts(title == null ? kw : title, summary);
        Log.i(TAG, "维基学到 " + kw + " -> " + n + " 条: " + summary);
        return new Result(n > 0, title == null ? kw : title, summary, n, summary);
    }

    /**
     * 从百度百科学(兜底)。
     * 返回的 JSON 里 abstract 就是简介,card 里是属性表(中文名/外文名/…)。
     */
    private Result learnFromBaike(String kw) {
        String json = httpGet(BAIKE_API + URLEncoder.encode(kw));
        if (json == null) {
            Log.i(TAG, "百度百科抓取失败: " + kw);
            return new Result(false, kw, null, 0, null);
        }
        String title = kw;
        String summary = null;
        try {
            JSONObject root = new JSONObject(json);
            title = root.optString("key", kw);
            summary = root.optString("abstract", null);
            if (summary == null || summary.trim().isEmpty()) {
                summary = root.optString("desc", null);
            }
            // card 里的属性对(如"外文名: Quantum mechanics")也当事实存下来
            JSONArray card = root.optJSONArray("card");
            if (card != null) {
                for (int i = 0; i < card.length(); i++) {
                    JSONObject c = card.optJSONObject(i);
                    if (c == null) continue;
                    String name = c.optString("name", null);
                    JSONArray vals = c.optJSONArray("value");
                    if (name == null || vals == null || vals.length() == 0) continue;
                    String v = stripHtml(vals.optString(0, ""));
                    if (v.isEmpty() || v.length() > 30) continue;
                    store.learnFact(title, name, v, Store.SRC_NET);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析百度百科失败: " + t);
        }
        if (summary == null || summary.trim().isEmpty()) {
            Log.i(TAG, "百度百科没有摘要: " + kw);
            return new Result(false, kw, null, 0, null);
        }
        String s = firstSentences(summary, 2);
        int n = extractFacts(title, s);
        Log.i(TAG, "百度百科学到 " + kw + " -> " + n + " 条: " + s);
        return new Result(n > 0, title, s, n, s);
    }

    /** 去掉 <a …>…</a> 之类标签,只留文字 */
    private static String stripHtml(String h) {
        if (h == null) return "";
        return h.replaceAll("<[^>]*>", "").trim();
    }

    /** 取前 n 句(维基简介开头通常就是定义句) */
    private static String firstSentences(String text, int n) {
        String t = text.replaceAll("\\s+", " ").trim();
        int end = 0, count = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                count++;
                end = i + 1;
                if (count >= n) break;
            }
        }
        String out = (end > 0) ? t.substring(0, end) : t;
        return out.length() > 220 ? out.substring(0, 220) : out;
    }

    /**
     * 从简介里抽事实三元组。
     *
     * 只做**可靠**的抽取,宁少勿错:
     *   "X是Y"            -> X 是 Y     (仅当 Y 足够短,像"物理学家"这样的定义)
     *   "X出生于Y"         -> X 出生地 Y
     *   "X位于Y"           -> X 位于 Y
     *   "X毕业于Y"         -> X 毕业院校 Y
     * 抽不出就只学"X 简介 <句子>",至少能原样复述,便于对话。
     *
     * 【实测教训】"X是Y" 这一条最早写成"取'是'后面全部内容",结果把整个
     * 定义句都吞进去了 —— 例如"比尔·盖茨，英文：Bill Gates，全名…"。
     * 现在加了长度上限,太长就不当属性,只留"简介"这一条。
     */
    private int extractFacts(String subject, String summary) {
        int n = 0;
        String s = summary;

        // 定义句:"X是…" —— 取"是"后面的部分
        int i = s.indexOf('是');
        if (i > 0 && i < s.length() - 1) {
            String rest = s.substring(i + 1).trim();
            rest = stripTail(rest);
            // 【长度上限】只把**短**的后半句当定义(如"是物理学家"),
            // 太长的多半是整句介绍,当属性读起来很怪。
            if (!rest.isEmpty() && rest.length() <= 24) {
                if (store.learnFact(subject, "是", rest, Store.SRC_NET)) n++;
            }
        }

        // 关系式抽取
        n += relation(subject, s, "出生地", "出生于");
        n += relation(subject, s, "位于", "位于");
        n += relation(subject, s, "毕业院校", "毕业于");
        n += relation(subject, s, "国籍", "国籍是");

        // 兜底:把整句简介作为"简介"事实存下来,这样能原样回答
        if (summary.length() <= 220) {
            if (store.learnFact(subject, "简介", summary, Store.SRC_NET)) n++;
        }

        return Math.min(n, MAX_FACTS);
    }

    /** 从 "X<marker>Y" 里抽 Y 作为关系值 */
    private int relation(String subject, String text, String rel, String marker) {
        int i = text.indexOf(marker);
        if (i < 0) return 0;
        String rest = text.substring(i + marker.length()).trim();
        rest = stripTail(rest);
        // 取值到第一个标点为止
        for (int k = 0; k < rest.length(); k++) {
            char c = rest.charAt(k);
            if (c == '，' || c == ',' || c == '。' || c == '、' || c == '（' || c == '(') {
                rest = rest.substring(0, k);
                break;
            }
        }
        rest = rest.trim();
        if (rest.isEmpty() || rest.length() > 30) return 0;
        return store.learnFact(subject, rel, rest, Store.SRC_NET) ? 1 : 0;
    }

    private static String stripTail(String s) {
        String r = s.trim();
        while (!r.isEmpty()) {
            char c = r.charAt(r.length() - 1);
            if (c == '。' || c == '.' || c == '，' || c == ',' || c == '！'
                    || c == '!' || c == '？' || c == '?' || c == ' ') {
                r = r.substring(0, r.length() - 1);
            } else break;
        }
        return r;
    }

    /** 极简 HTTP GET;失败返回 null */
    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // 维基要求带 User-Agent,否则可能被拒
            conn.setRequestProperty("User-Agent", "kiki-android/1.0 (offline assistant)");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }
            InputStream in = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            Log.i(TAG, "HTTP 失败: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
