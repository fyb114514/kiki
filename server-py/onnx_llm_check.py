# -*- coding: utf-8 -*-
"""
在 PC 上验证 ONNX 量化版 Qwen2.5-0.5B 能否正确生成 —— 作为 Java 移植的参考实现。

为什么先做这个
--------------
模型有 51 个输入 / 49 个输出(24 层 KV 缓存),Java 侧要手写生成循环。
先在 Python 里把接口跑通、拿到**参考输出**,Java 移植时就能逐项对照,
避免集成完才发现量化模型有问题或接口理解错了。
"""
import json
import os
import time

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

BASE = r"D:\nbjj\dsh\android-tools\onnx-llm"
MODEL = os.path.join(BASE, "onnx", "model_q4f16.onnx")

print("== 载入 tokenizer 与模型 ==")
tok = AutoTokenizer.from_pretrained(BASE, trust_remote_code=False)
sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
print("  ONNX Runtime:", ort.__version__)

in_names = [i.name for i in sess.get_inputs()]
out_names = [o.name for o in sess.get_outputs()]
N_LAYER = sum(1 for n in in_names if n.endswith(".key"))
print("  输入 %d 个, 输出 %d 个, 层数 %d" % (len(in_names), len(out_names), N_LAYER))

cfg = json.load(open(os.path.join(BASE, "config.json"), encoding="utf-8"))
EOS = cfg["eos_token_id"]
print("  eos_token_id =", EOS)


def build_inputs(ids, past=None):
    """历史 KV 为 None 时是 prefill(整段),否则是 decode(单 token)"""
    n = len(ids)
    past_len = 0 if past is None else past[0][0].shape[2]
    feed = {
        "input_ids": np.array([ids], dtype=np.int64),
        "attention_mask": np.ones((1, past_len + n), dtype=np.int64),
        "position_ids": np.arange(past_len, past_len + n, dtype=np.int64).reshape(1, n),
    }
    for i in range(N_LAYER):
        if past is None:
            feed["past_key_values.%d.key" % i] = np.zeros((1, 2, 0, 64), dtype=np.float32)
            feed["past_key_values.%d.value" % i] = np.zeros((1, 2, 0, 64), dtype=np.float32)
        else:
            feed["past_key_values.%d.key" % i] = past[i][0]
            feed["past_key_values.%d.value" % i] = past[i][1]
    return feed


def run(ids, past=None):
    outs = sess.run(None, build_inputs(ids, past))
    od = dict(zip(out_names, outs))
    logits = od["logits"]
    new_past = []
    for i in range(N_LAYER):
        new_past.append((od["present.%d.key" % i], od["present.%d.value" % i]))
    return logits, new_past


def generate(messages, max_new=40):
    text = tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    ids = tok(text, return_tensors=None)["input_ids"]
    print("  提示词 token 数: %d" % len(ids))
    print("  提示词原文: %s" % repr(text[:150]))

    t0 = time.time()
    logits, past = run(ids)
    t_prefill = time.time() - t0

    out_ids = []
    t0 = time.time()
    for _ in range(max_new):
        nxt = int(np.argmax(logits[0, -1]))
        if nxt == EOS:
            break
        out_ids.append(nxt)
        logits, past = run([nxt], past)
    t_gen = time.time() - t0
    txt = tok.decode(out_ids, skip_special_tokens=True)
    print("  prefill %.2fs, 生成 %d 字用 %.2fs (%.1f 字/秒)"
          % (t_prefill, len(out_ids), t_gen, len(out_ids) / max(0.01, t_gen)))
    return txt


SYS = "你叫kiki,活泼可爱的中文助手,回答简短口语化,不超过两句话。"
print("\n== 生成测试(int8 量化模型) ==")
for q in ["你好", "你是谁", "比尔盖茨是谁", "我好累啊"]:
    print("\n  问: %s" % q)
    msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": q}]
    print("  kiki: %s" % generate(msgs))
