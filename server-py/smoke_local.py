# -*- coding: utf-8 -*-
"""端到端冒烟测试:确认本地服务"教学立即生效"。

用法:python smoke_local.py
"""
import json
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:3000"
IP = "10.9.9.9"  # 固定测试 IP,避免打乱限流


def post(path, **fields):
    body = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    print("== 开服前知识条数 ==")
    print(post("/Status"))

    # 1. 教一条全新的知识(关键词 / 回答)
    print("\n== 教学:卡卡`卡卡是一只猫 ==")
    print(post("/Add", ip=IP, keyword="卡卡", answer="卡卡是一只猫"))

    # 2. 立刻追问(不等待审核)——这正是视频里那个"明白了"的桥段
    print("\n== 追问:卡卡是什么 ==")
    print(post("/Reply", ip=IP, keyword="卡卡是什么"))

    # 3. 精确匹配
    print("\n== 精确追问:卡卡 ==")
    print(post("/Reply", ip=IP, keyword="卡卡"))

    # 4. 预置人设
    print("\n== 提问:你是谁 ==")
    print(post("/Reply", ip=IP, keyword="你是谁"))

    # 5. 未命中兜底
    print("\n== 提问:量子力学 (预期未命中) ==")
    print(post("/Reply", ip=IP, keyword="量子力学"))

    # 6. 最终状态
    print("\n== 最终知识条数 ==")
    print(post("/Status"))
    print("\n== 开场白 ==")
    print(post("/Greeting"))


if __name__ == "__main__":
    main()
