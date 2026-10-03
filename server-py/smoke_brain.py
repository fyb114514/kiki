# -*- coding: utf-8 -*-
"""端到端验证:kiki 逻辑引擎 + 记忆库联动(走 HTTP 层,和聊天客户端同一条链路)。"""
import json
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:3000"
IP = "10.7.7.7"


def post(path, **fields):
    body = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ask(text):
    r = post("/Reply", ip=IP, keyword=text)
    d = r.get("data")
    ans = d.get("answer") if isinstance(d, dict) else d
    print("你 > %s" % text)
    print("kiki > %s" % ans)
    print()


def main():
    print("== 1. 记忆库(上游关键词检索)仍正常 ==")
    for q in ("你是谁", "kiki喜欢什么"):
        ask(q)

    print("== 2. 逻辑引擎:分类继承 + 属性继承(卡卡是视频里的猫) ==")
    for q in ("卡卡是什么", "卡卡有几条腿", "卡卡喜欢吃什么"):
        ask(q)

    print("== 3. 逻辑引擎:多步推理(没教过'爷爷'也要能答) ==")
    for q in ("小明的爸爸的爸爸是谁", "小明的爷爷是谁"):
        ask(q)

    print("== 4. 逻辑引擎:否定与反义(2011 年最容易翻车的地方) ==")
    for q in ("企鹅会飞吗", "乌龟走得快吗"):
        ask(q)

    print("== 5. 主动记忆:现场教一句,立刻能推 ==")
    print("你 > 小红`小红的爸爸是王强")
    print("kiki > %s" % post("/Add", ip=IP, keyword="小红", answer="小红的爸爸是王强").get("data"))
    print()
    ask("小红的爸爸是谁")

    print("== 6. 中英混排 + 近音错字 ==")
    for q in ("Marisa喜欢什么", "kiki住在哪"):
        ask(q)

    print("== 7. 知识条数 ==")
    print(post("/Status"))


if __name__ == "__main__":
    main()
