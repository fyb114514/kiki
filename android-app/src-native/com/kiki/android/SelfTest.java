package com.kiki.android;

import android.content.Context;
import android.util.Log;

/**
 * 首次启动自检 —— 验证"自我进化闭环"真的通了。
 *
 * 为什么需要它:我(开发者)无法对着手机说话,而模拟长按只会得到"没听清",
 * 永远走不到"上网学"那条分支。所以这里在**首次启动**时主动问一个
 * 知识库里肯定没有的问题,把整条链路跑一遍:
 *
 *     本地检索(必然未命中) -> 上网搜 -> 抽最相关原话 -> 塞进语料 -> 增量重训
 *
 * 结果写进日志(tag: kiki-selftest),只跑一次(用 SharedPreferences 记标记)。
 * 这不是给用户看的功能,是给开发/排障用的探针。
 */
public class SelfTest {

    private static final String TAG = "kiki-selftest";
    private static final String PREF = "kiki_selftest";
    private static final String KEY_DONE = "web_learn_done";

    /** 用一个知识库里没有、但网上一定有答案的问题 */
    private static final String QUESTION = "比尔盖茨是谁";

    public static void runOnceIfNeeded(final Context context, final ChatBrain brain) {
        try {
            final android.content.SharedPreferences sp =
                    context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if (sp.getBoolean(KEY_DONE, false)) {
                Log.i(TAG, "自检已跑过,跳过");
                return;
            }
            Log.i(TAG, "=== 开始自我进化自检,问题:" + QUESTION + " ===");
            brain.replyAsync(QUESTION, new ChatBrain.ReplyCallback() {
                @Override
                public void onReply(final Answer answer, final String learnedFrom) {
                    Log.i(TAG, "自检结果: kind=" + answer.kind
                            + " 来源=" + (learnedFrom == null ? "本地" : learnedFrom));
                    Log.i(TAG, "自检回答: " + answer.text);
                    boolean ok = "web".equals(answer.kind);
                    Log.i(TAG, ok ? "=== 自检通过:上网学到了东西 ==="
                                  : "=== 自检未走到联网分支(可能网络不通) ===");
                    sp.edit().putBoolean(KEY_DONE, true).apply();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "自检异常: " + t);
        }
    }
}
