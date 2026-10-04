package com.kiki.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地存储层。
 *
 * 三张表对应三类数据:
 *   learned —— 用户教过的问答对("问什么答什么")
 *   fact    —— 参与**推理**的事实三元组(主语/关系/宾语)
 *   topic   —— 话题偏好统计(她眼里的你常聊什么)
 *   profile —— 说话风格等偏好(特性 2:用户偏好)
 *
 * ============================================================
 * 特性 2 之一:**新知识覆盖旧知识**
 * ============================================================
 * 原来的 fact 表只有 (s, rel, o),同一条三元组重复写入就是幂等,
 * 但**回答不了"同一个关系有两个不同宾语"怎么办** ——
 * 例如先说"水的沸点是 100 度",后来学到"水的沸点是 90 度",
 * 两条都在表里,查询时就不知道信哪个。
 *
 * 现在引入:
 *   source    来源,决定优先级(见 {@link #priorityOf})
 *   priority  显式优先级(冗余存一份,便于排序)
 *   created_at / updated_at  时间戳(同优先级时新的胜出)
 *   superseded 是否已被更新的知识取代
 *   hits      被命中次数(反映"这条知识有多常用")
 *
 * 判定规则({@link #factKey}):同 (s,rel) 下,
 * **优先级高者胜;优先级相同则时间新者胜**;败者标记 superseded=1。
 * 这样"新学习的知识会覆盖原来的知识"就成立,同时低优先级的
 * 网络知识**不会**覆盖用户亲口教的东西。
 */
public class Store {

    private static final String TAG = "kiki-store";

    private static final String DB_NAME = "kiki.db";
    /** v2:fact 表新增 source/priority/timestamps/superseded/hits;新增 profile 表 */
    private static final int DB_VERSION = 2;

    // ---------------- 事实来源与其优先级 ----------------
    /** 用户亲口教的:最高优先,永远不被覆盖 */
    public static final String SRC_TAUGHT = "taught";
    /** 从用户纠正中自学习(如"不对,应该是…") */
    public static final String SRC_CORRECTED = "corrected";
    /** 联网学到的 */
    public static final String SRC_NET = "net";
    /** 内置知识库 */
    public static final String SRC_BUILTIN = "builtin";
    /** 推理推导出来的(最弱:只是推论,可以被任何来源推翻) */
    public static final String SRC_INFERRED = "inferred";

    /** 来源优先级:数值越大越可信 */
    public static int priorityOf(String source) {
        if (source == null) return 10;
        if (SRC_TAUGHT.equals(source)) return 100;
        if (SRC_CORRECTED.equals(source)) return 80;
        if (SRC_NET.equals(source)) return 50;
        if (SRC_BUILTIN.equals(source)) return 30;
        if (SRC_INFERRED.equals(source)) return 10;
        return 20;
    }

    private final SQLiteDatabase db;

    public Store(Context context) {
        db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS learned ("
                + "keyword TEXT PRIMARY KEY,"
                + "answer TEXT NOT NULL,"
                + "hits INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        // 主动学习得到的事实(主语/关系/宾语)。与 learned(问答对)分开存:
        // learned 是"问什么答什么",fact 是"参与推理的知识"。
        db.execSQL("CREATE TABLE IF NOT EXISTS fact ("
                + "s TEXT NOT NULL,"
                + "rel TEXT NOT NULL,"
                + "o TEXT NOT NULL,"
                + "source TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "PRIMARY KEY(s, rel, o))");
        db.execSQL("CREATE TABLE IF NOT EXISTS topic ("  // 话题偏好统计
                + "word TEXT PRIMARY KEY,"
                + "count INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL)");
        upgrade();
    }

    /** 把 v1 的 fact 表升级到 v2(加列),并建 profile 表。可重复执行。 */
    private void upgrade() {
        addColumnIfMissing("fact", "priority", "INTEGER NOT NULL DEFAULT 10");
        addColumnIfMissing("fact", "updated_at", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("fact", "superseded", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("fact", "hits", "INTEGER NOT NULL DEFAULT 0");
        // v1 已有的行:按来源补上优先级和时间戳,避免它们排序时全排在最后
        try {
            db.execSQL("UPDATE fact SET priority = CASE source "
                    + "WHEN 'taught' THEN 100 WHEN 'corrected' THEN 80 "
                    + "WHEN 'net' THEN 50 WHEN 'builtin' THEN 30 "
                    + "WHEN 'inferred' THEN 10 ELSE 20 END "
                    + "WHERE priority = 10 AND (source IS NULL OR source <> 'inferred')");
            db.execSQL("UPDATE fact SET updated_at = created_at WHERE updated_at = 0");
        } catch (Exception ignored) {
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS pref ("
                + "aspect TEXT NOT NULL,"
                + "value TEXT NOT NULL,"
                + "count INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL,"
                + "PRIMARY KEY(aspect, value))");
    }

    private void addColumnIfMissing(String table, String col, String decl) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                if (col.equalsIgnoreCase(c.getString(1))) return;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + decl);
            Log.i(TAG, "已加列 " + table + "." + col);
        } catch (Exception e) {
            Log.w(TAG, "加列失败 " + table + "." + col + ": " + e);
        }
    }

    /** 一条已学知识 */
    public static class Learned {
        public final String keyword;
        public final String answer;
        public final int hits;

        public Learned(String keyword, String answer, int hits) {
            this.keyword = keyword;
            this.answer = answer;
            this.hits = hits;
        }
    }

    /** 读出全部已学知识(按命中次数降序,常用的在前) */
    public List<Learned> loadAll() {
        List<Learned> out = new ArrayList<Learned>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT keyword, answer, hits FROM learned ORDER BY hits DESC, updated_at DESC", null);
            while (c.moveToNext()) {
                out.add(new Learned(c.getString(0), c.getString(1), c.getInt(2)));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 教一条知识(同关键词覆盖) */
    public void teach(String keyword, String answer) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("keyword", keyword);
        v.put("answer", answer);
        v.put("created_at", now);
        v.put("updated_at", now);
        try {
            // 已存在则只更新回答,保留命中计数
            int updated = db.update("learned", v, "keyword=?", new String[]{keyword});
            if (updated == 0) {
                db.insert("learned", null, v);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 学到一条事实(主语/关系/宾语)。
     * 主动学习闭环里"比尔盖茨 是 微软公司CEO"就存这里。
     *
     * 默认来源是 taught(用户亲口教的,最高优先)。
     */
    public void learnFact(String subj, String rel, String obj) {
        learnFact(subj, rel, obj, SRC_TAUGHT);
    }

    /**
     * 学到一条事实,并指定来源。
     *
     * 【特性 2 · 新知识覆盖旧知识】写入时会:
     *   1. 计算本条优先级;
     *   2. 把**同 (s,rel) 但优先级更低**的旧事实标记 superseded=1;
     *   3. 如果旧事实优先级更高,则本条一进来就是 superseded —— 即"网络知识
     *      不会覆盖用户教过的知识",这条规则很重要,否则联网久了会把用户
     *      亲口说的东西冲掉。
     *
     * @return true 表示这条知识是"生效"的(没被更可信的知识压住)
     */
    public boolean learnFact(String subj, String rel, String obj, String source) {
        if (subj == null || rel == null || obj == null) return false;
        if (subj.isEmpty() || rel.isEmpty() || obj.isEmpty()) return false;
        if (source == null) source = SRC_TAUGHT;
        final int prio = priorityOf(source);
        final long now = System.currentTimeMillis();

        ContentValues v = new ContentValues();
        v.put("s", subj);
        v.put("rel", rel);
        v.put("o", obj);
        v.put("source", source);
        v.put("priority", prio);
        v.put("created_at", now);
        v.put("updated_at", now);
        v.put("superseded", 0);
        try {
            db.insertWithOnConflict("fact", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.w(TAG, "写事实失败: " + e);
            return false;
        }

        // 看看这个 (s,rel) 下面有没有优先级更高的旧事实
        int best = bestPriority(subj, rel);
        boolean active = prio >= best;

        if (active) {
            // 本条胜出:把同 (s,rel) 里**不如它**的都删掉。
            //
            // 判据分两种(特性 2 自检暴露过这里漏了第二种):
            //   1. 优先级更低                   -> 一定被覆盖
            //   2. 优先级相同但**更旧或同时**     -> 也被覆盖
            // 只写第一种时,"同来源下新学的覆盖旧的"不成立:
            // 旧的那条会留在表里,loadFacts 会同时返回新旧两条,查询时不知道信谁。
            //
            // 【第 2 条为什么用 <= 而不是 <】
            //   两次写入可能落在**同一毫秒**(机器快,或测试连续写入),
            //   updated_at 完全相等。若用 <,旧行不满足"更旧"就删不掉;
            //   而当新旧的**宾语不同**时,INSERT OR REPLACE 也替换不掉它
            //   (主键含宾语)——于是新旧并存,loadFacts 先返回旧的那条。
            //   特性 2 自检的"同来源下新知识覆盖旧知识"就是这么假失败的。
            // 【第 2 条为什么用 <= 而不是 <】
            //   两次写入可能落在**同一毫秒**(机器快,或测试连续写入),
            //   updated_at 完全相等。若用 <,旧行不满足"更旧"就删不掉;
            //   而当新旧的**宾语不同**时,INSERT OR REPLACE 也替换不掉它
            //   (主键含宾语)——于是新旧并存,loadFacts 先返回旧的那条。
            //   语义上"本条正在写入,就应该赢",所以用 <=。
            //
            // 【必须排除本条(o <> ?)】
            //   本条是**刚 INSERT 进去的**,它的 updated_at 正好等于 now,
            //   满足 updated_at<=now。若不排除,这条 DELETE 会把**自己删掉**,
            //   于是"写入后查不到"(特性 2 自检立刻从 11/12 掉到 6/12)。
            try {
                int n = db.delete("fact",
                        "s=? AND rel=? AND o<>? AND (priority<? OR (priority=? AND updated_at<=?))",
                        new String[]{subj, rel, obj, String.valueOf(prio),
                                     String.valueOf(prio), String.valueOf(now)});
                if (n > 0) {
                    Log.i(TAG, "新知识覆盖旧知识: " + subj + "|" + rel + " <- [" + obj
                            + "] (" + source + "),取代了 " + n + " 条旧事实");
                }
            } catch (Exception ignored) {
            }
        } else {
            // 有更可信的旧知识,本条只做记录,不参与回答
            try {
                db.execSQL("UPDATE fact SET superseded=1 WHERE s=? AND rel=? AND o=?",
                        new Object[]{subj, rel, obj});
            } catch (Exception ignored) {
            }
            Log.i(TAG, "新知识被更可信的旧知识压住: " + subj + "|" + rel + " <- [" + obj
                    + "] (" + source + ", " + prio + ")");
        }
        return active;
    }

    /** 同 (s,rel) 下最高的优先级;没有则返回 -1 */
    public int bestPriority(String subj, String rel) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT MAX(priority) FROM fact WHERE s=? AND rel=? AND superseded=0",
                    new String[]{subj, rel});
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** 读出全部**生效**的自学事实(启动时灌进事实图) */
    public List<String[]> loadFacts() {
        List<String[]> out = new ArrayList<String[]>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT s, rel, o FROM fact WHERE superseded=0 "
                    + "ORDER BY priority DESC, updated_at DESC", null);
            while (c.moveToNext()) {
                out.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 忘掉一条具体事实(用户否定时调用) */
    public void forgetFact(String s, String rel, String o) {
        if (s == null || rel == null || o == null) return;
        try {
            db.delete("fact", "s=? AND rel=? AND o=?", new String[]{s, rel, o});
        } catch (Exception ignored) {
        }
    }

    /** 删除某个主语的全部事实(自检清理用;也可用于"忘掉"功能) */
    public void deleteSubject(String subj) {
        if (subj == null || subj.isEmpty()) return;
        try {
            db.delete("fact", "s=?", new String[]{subj});
        } catch (Exception ignored) {
        }
    }

    /** 被取代的知识条数(用于展示"她更新过多少认知") */
    public int supersededCount() {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM fact WHERE superseded=1", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /** 按关键词查已学问答(精确优先,其次包含) */
    public String findLearned(String keyword) {
        if (keyword == null || keyword.isEmpty()) return null;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT answer FROM learned WHERE keyword = ? LIMIT 1",
                    new String[]{keyword});
            if (c.moveToNext()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        c = null;
        try {
            c = db.rawQuery("SELECT answer FROM learned WHERE ? LIKE '%' || keyword || '%' "
                    + "ORDER BY hits DESC LIMIT 1", new String[]{keyword});
            if (c.moveToNext()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 记住某条被命中了(用于"用户偏好"加权) */
    public void hit(String keyword) {
        try {
            db.execSQL("UPDATE learned SET hits = hits + 1, updated_at = ? WHERE keyword = ?",
                    new Object[]{System.currentTimeMillis(), keyword});
        } catch (Exception ignored) {
        }
    }

    /** 事实被命中时也计数 —— 常用的知识会在冲突时更"稳" */
    public void hitFact(String subj, String rel) {
        try {
            db.execSQL("UPDATE fact SET hits = hits + 1 WHERE s=? AND rel=? AND superseded=0",
                    new Object[]{subj, rel});
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // 用户偏好(特性 2)
    // ============================================================

    /** 统计一个话题词出现的次数(偏好画像) */
    public void countTopic(String word) {
        if (word == null || word.length() < 2) return;
        try {
            db.execSQL("INSERT OR IGNORE INTO topic(word, count, updated_at) VALUES(?, 0, ?)",
                    new Object[]{word, System.currentTimeMillis()});
            db.execSQL("UPDATE topic SET count = count + 1, updated_at = ? WHERE word = ?",
                    new Object[]{System.currentTimeMillis(), word});
        } catch (Exception ignored) {
        }
    }

    /** 取最关注的话题(前 n 个),用于展示"她眼里的你" */
    public List<String> topTopics(int n) {
        List<String> out = new ArrayList<String>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT word FROM topic WHERE count >= 2 ORDER BY count DESC LIMIT ?",
                    new String[]{String.valueOf(n)});
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 记录一条偏好。
     *
     * @param aspect 偏好类别,如 "style"(说话风格)、"topic"(常聊话题)、"habit"(习惯)
     * @param value  取值,如 "简洁"、"科技"、"爱问为什么"
     * 同 (aspect,value) 累加计数,用于选出"最主要的偏好"。
     */
    public void countProfile(String aspect, String value) {
        if (aspect == null || value == null || value.isEmpty()) return;
        long now = System.currentTimeMillis();
        try {
            ContentValues v = new ContentValues();
            v.put("aspect", aspect);
            v.put("value", value);
            v.put("count", 1);
            v.put("updated_at", now);
            db.insertWithOnConflict("pref", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("UPDATE pref SET count = count + 1, updated_at = ? "
                    + "WHERE aspect = ? AND value = ?", new Object[]{now, aspect, value});
        } catch (Exception ignored) {
        }
    }

    /** 取某个偏好类别下计数最高的取值;没有则 null */
    public String topProfile(String aspect) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT value FROM pref WHERE aspect = ? "
                    + "ORDER BY count DESC, updated_at DESC LIMIT 1", new String[]{aspect});
            if (c.moveToNext()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 取某个偏好类别下计数最高的若干个取值 */
    public List<String> topProfiles(String aspect, int n) {
        List<String> out = new ArrayList<String>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT value FROM pref WHERE aspect = ? AND count >= 2 "
                    + "ORDER BY count DESC LIMIT ?", new String[]{aspect, String.valueOf(n)});
            while (c.moveToNext()) out.add(c.getString(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public int learnedCount() {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM learned", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    public void close() {
        try {
            db.close();
        } catch (Exception ignored) {
        }
    }
}
