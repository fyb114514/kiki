package com.kiki.android;

import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * 对话大脑:规则检索 + 预训练对话模型。
 *
 * 回答链路(两层,职责分明)
 * ------------------------
 *   1. **本地知识检索/推理**({@link KikiBot} → Brain)
 *      事实类问题("卡卡是什么""东吴是谁建立的""小明的爷爷是谁")必须准确,
 *      所以由规则引擎作答,不允许模型改写。
 *   2. **预训练对话模型**({@link LlmEngine}, Qwen2.5-0.5B / ONNX Runtime)
 *      闲聊、问候、没有明确事实可查的问题交给它生成。
 *      实测输出:"我好累啊" -> "给你点休息时间吧。"
 *   3. 两者都不会 -> 反问用户(原版行为),用户答完就学会,下次就会了。
 *
 * 关于曾经存在的手写字符级模型
 * ----------------------------
 * 早期这里挂过一个纯 Java 手写的字符级语言模型(NeuralLM,24 层不到的
 * 前馈网络 + 手写反向传播)。它在 21k 语料上能把损失降到 1.69,
 * 但**生成的是这种串**:
 *     黑果我生吉家壳是别什么地是什么是黑什么区国地心么轼人地级买累低镆镄么炼腺备用首诗
 * 每个字都合法、整句毫无意义 —— 字符级小模型学的是"字与字的搭配",不是语言。
 * 换成预训练 Transformer 后输出连贯,所以 **NeuralLM 已整体删除**,
 * 连带它那套语料/权重的落盘逻辑(省掉每次首启约 30 秒的无用训练)。
 */
public class ChatBrain {

    private static final String TAG = "kiki-chat";

    private final Context context;
    private final KikiBot retriever;

    /** 预训练对话模型(由上层在后台载入完成后注入)。为空则退回规则引擎。 */
    private volatile LlmEngine llm;

    public interface Status {
        void onStatus(String message);
    }

    public ChatBrain(Context context, KikiBot retriever) {
        this.context = context;
        this.retriever = retriever;
    }

    /** 由上层在后台载入完成后注入 */
    public void setLlm(LlmEngine e) {
        this.llm = e;
        Log.i(TAG, e != null ? "对话模型已接入" : "对话模型不可用");
    }

    public boolean hasLlm() {
        return llm != null && llm.isReady();
    }

    /**
     * 角色设定 —— 这一段直接决定她的说话风格。
     * 模型是通用的 Qwen,靠这段提示词她才"是 Kiki"而不是通用助手。
     */
    public static final String SYSTEM_PROMPT =
            "你是Kiki，一个语音助手。回答要简短、口语化，一到两句话就好。"
            + "不要用书面语，不要长篇大论，不要用markdown格式。";

    /**
     * 初始化。现在没有本地训练要做,所以只需要通知上层"可以开始载模型了"。
     * 保留这个接口是为了让 {@code MainActivity} 的调用序列不变。
     */
    public void init(final Status cb) {
        if (cb != null) cb.onStatus("准备载入对话模型…");
    }

    /** 异步回调:保持与旧接口一致(回答可能涉及模型生成,不能在主线程等) */
    public interface ReplyCallback {
        void onReply(Answer answer, String learnedFrom);
    }

    /**
     * 回答 —— 检索优先,其次对话模型,都不会就反问用户。
     */
    public void replyAsync(final String input, final ReplyCallback cb) {
        final String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            cb.onReply(new Answer("嗯?", "空输入", "miss"), null);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. 本地知识检索(只信确定性答案,事实类问题不能被模型改写)
                    Answer a = retriever.reply(text);
                    if (isDefinitive(a)) {
                        cb.onReply(a, null);
                        return;
                    }

                    // 2. 预训练对话模型 —— 这才是"会说话"的那部分
                    if (llm != null && llm.isReady()) {
                        String gen = llm.generate(SYSTEM_PROMPT, text, 64);
                        if (gen != null && gen.trim().length() > 0) {
                            remember(text, gen);
                            cb.onReply(new Answer(gen.trim(), "对话模型生成", "llm"), null);
                            return;
                        }
                    }

                    // 3. 都不会 -> 反问用户
                    cb.onReply(askUser(text), null);
                } catch (Throwable t) {
                    Log.e(TAG, "回答失败: " + t);
                    cb.onReply(new Answer("唔…我想岔了,再说一次?", "异常", "miss"), null);
                }
            }
        }, "kiki-reply").start();
    }

    /**
     * 是不是"确定性"答案 —— 决定要不要让对话模型接手。
     *
     * 允许放行的:精确匹配、本地记忆、以及推理/属性/关系等确定性结果。
     * 一律不让过的:模糊匹配、问候语、以及兜底话术 ——
     * 它们多半是"沾边就答",实测问"比尔盖茨是谁"曾被模糊匹配成自我介绍。
     */
    private static boolean isDefinitive(Answer a) {
        if (a == null) return false;
        String k = a.kind == null ? "" : a.kind;
        if (k.isEmpty() || "miss".equals(k) || "ask".equals(k)
                || "fuzzy".equals(k) || "greeting".equals(k)) {
            return false;
        }
        // exact 是我们自己的精确匹配标记,保留
        if ("exact".equals(k)) return true;
        String t = a.text == null ? "" : a.text.trim();
        if (t.length() < 2) return false;
        if (t.contains("不懂你在说") || t.contains("教教我吧")) return false;
        return true;
    }

    /** 网上也查不到时:反问用户(原版最核心的行为) */
    private Answer askUser(String text) {
        return new Answer("唔…这个我暂时不知道,你教教我好不好?", "未知(等待用户教)", "ask");
    }

    /**
     * 记住一次对话。
     *
     * 注意:原来是写进"字符级模型的训练语料"并触发增量训练,那套已随
     * NeuralLM 一起删除。这里只保留会话级的记录点(将来若要接入
     * "本地记忆库"可以在这里落盘),不再做任何无用的训练。
     */
    public void remember(String user, String ai) {
        // 预留:历史上有语料落盘,随字符级模型一并移除
    }

    /** 教一条知识:进检索库(精确命中),下次问就能答上 */
    public void teach(String keyword, String answer) {
        retriever.teach(keyword, answer);
    }

    /** 同步版(兼容旧调用,内部仍走异步) */
    public Answer reply(String input) {
        final Answer[] holder = new Answer[1];
        final Object lock = new Object();
        replyAsync(input, new ReplyCallback() {
            @Override
            public void onReply(Answer answer, String learnedFrom) {
                synchronized (lock) {
                    holder[0] = answer;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            if (holder[0] == null) {
                try {
                    lock.wait(30000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return holder[0] != null ? holder[0] : new Answer("(还在想…)", "超时", "miss");
    }

    /** 事实库里有多少条(界面/日志用) */
    public int factCount() {
        return retriever.getFactCount();
    }
}
