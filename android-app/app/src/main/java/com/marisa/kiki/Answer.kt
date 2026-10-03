package com.marisa.kiki

/** 引擎返回的一条回答。 */
data class Answer(
    /** 回答文本 */
    val text: String,
    /** 推理依据(便于调试/展示"为什么这么答") */
    val why: String = "",
    /** 类型:attr/infer/describe/convert/reverse/yesno/remember/rel/where/value/when/measure */
    val kind: String = ""
)
