# -*- coding: utf-8 -*-
"""验证"对象判断"(flag):同一句话对不同人/不同时段给不同回答。

这是 kiki 卡片上"上下文理解"里最像"认人"的那部分。
上游早已实现,只是默认关闭;local_service.py 现在默认开启。
"""
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime

BASE = "http://127.0.0.1:3000"
IP = "10.4.4.4"
UID_A = "tester_aaa"
UID_B = "tester_bbb"


def post(path, **f):
    body = urllib.parse.urlencode(f).encode("utf-8")
    return json.loads(urllib.request.urlopen(
        urllib.request.Request(BASE + path, data=body), timeout=20).read().decode("utf-8"))


def add(keyword, answer, flag="all", uid=UID_A):
    r = post("/Add", ip=IP, keyword=keyword, answer=answer, uid=uid, flag=flag)
    print("  教[%s] %s -> %s   => %s" % (flag, keyword, answer, r.get("code")))
    time.sleep(0.4)


def ask(keyword, uid):
    r = post("/Reply", ip=IP, keyword=keyword, uid=uid)
    d = r.get("data") or {}
    return d.get("answer") if isinstance(d, dict) else d


def main():
    now = datetime.now()
    hour = now.hour
    if 5 <= hour < 8:
        window = "dawn"
    elif 8 <= hour < 18:
        window = "day"
    elif 18 <= hour < 22:
        window = "dusk"
    else:
        window = "night"
    print("当前时间 %s,所属时段 flag = time:%s" % (now.strftime("%H:%M"), window))
    print()

    print("== 教学 ==")
    add("晚安", "晚安~做个好梦,梦里也要有星星哦")                      # 通用
    add("晚安", "这么晚还不睡?熬夜会变丑的,快去睡!", flag="time:" + window)  # 只在本时段
    add("晚安", "是你啊,今晚也谢谢你陪我聊天", flag="user:" + UID_A)     # 只对 A
    print()

    print("== 提问(同 IP,不同身份) ==")
    a = ask("晚安", UID_A)
    b = ask("晚安", UID_B)
    print("  A(%s) 听到: %s" % (UID_A, a))
    print("  B(%s) 听到: %s" % (UID_B, b))

    ok = True
    if a != "是你啊,今晚也谢谢你陪我聊天":
        print("  !! A 应该听到 user 定向回答"); ok = False
    if b == "是你啊,今晚也谢谢你陪我聊天":
        print("  !! B 不该听到 A 的定向回答(泄密了)"); ok = False
    if b != "这么晚还不睡?熬夜会变丑的,快去睡!":
        print("  !! B 应该命中时段回答"); ok = False
    print()
    print("结果:", "PASS —— 定向回答只给该给的人,时段回答按当前时间生效" if ok else "FAIL")


if __name__ == "__main__":
    main()
