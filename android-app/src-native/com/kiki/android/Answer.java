package com.kiki.android;

/** 引擎返回的一条回答。 */
public class Answer {
    /** 回答文本 */
    public final String text;
    /** 推理依据(便于调试/展示"为什么这么答") */
    public final String why;
    /** 类型:attr/infer/describe/convert/reverse/yesno/remember/rel/where/value/when/measure */
    public final String kind;

    public Answer(String text, String why, String kind) {
        this.text = text == null ? "" : text;
        this.why = why == null ? "" : why;
        this.kind = kind == null ? "" : kind;
    }

    public Answer(String text) {
        this(text, "", "");
    }

    @Override
    public String toString() {
        return text;
    }
}
