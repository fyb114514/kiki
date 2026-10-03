package com.marisa.kiki;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地持久化("记忆数据库")。
 *
 * 对应原作者的说法:**kiki 是本地+网络,kiki 学到的东西会保存到本地,
 * 久而久之形成各种用户偏好下的 kiki**。
 *   —— 一个漫画家用的 kiki 会懂更多绘画/动漫,一个追星的人用的 kiki 会懂更多娱乐圈。
 *
 * 所以这里做两件事:
 *   1. 把"教过的问答"持久化(重启 App 不丢)
 *   2. 按**使用频率**给知识排序:同一个关键词被问得越多,越优先命中
 *      —— 这就是"用户偏好"的雏形:用得多的话题自然占了更大的权重
 *
 * 用 Android 内置的 SQLite(android.database.sqlite),零第三方依赖。
 */
public class Store {

    private static final String DB_NAME = "kiki.db";
    private static final int DB_VERSION = 1;

    private final SQLiteDatabase db;

    public Store(Context context) {
        db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS learned ("
                + "keyword TEXT PRIMARY KEY,"
                + "answer TEXT NOT NULL,"
                + "hits INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS topic ("  // 话题偏好统计
                + "word TEXT PRIMARY KEY,"
                + "count INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL)");
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

    /** 记住某条被命中了(用于"用户偏好"加权) */
    public void hit(String keyword) {
        try {
            db.execSQL("UPDATE learned SET hits = hits + 1, updated_at = ? WHERE keyword = ?",
                    new Object[]{System.currentTimeMillis(), keyword});
        } catch (Exception ignored) {
        }
    }

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
