# -*- coding: utf-8 -*-
"""覆盖面审计:用一批日常问题打真实 HTTP 链路,量化"答不上来"的比例。

用法: python audit_coverage.py            # 打运行中的服务
      python audit_coverage.py --direct   # 绕过 HTTP,直接问引擎
"""
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:3000"
IP = "10.2.2.2"

# 覆盖各类问法:常识 / 元素 / 地理 / 历史 / 换算 / 推理 / 闲聊
QUESTIONS = [
    # 推测能答的(库里有的事实)
    "中国的首都是哪里", "江苏省的省会是哪里", "山东省简称", "氧的符号是什么",
    "铁的原子序数是多少", "秦朝建立于哪一年", "李白代表作", "美国的代码是什么",
    "卡卡有几条腿", "小明的爷爷是谁", "企鹅会飞吗", "一小时是几分钟",
    "巴西的货币代码是什么", "氢是什么", "孔子是什么",
    # 日常闲聊(库里大概没有)
    "你好", "你是谁", "今天天气怎么样", "你吃饭了吗", "你喜欢什么",
    "你会唱歌吗", "你几岁了", "你在干什么", "讲个笑话", "我很难过",
    # 常识(库里有 / 没有各半)
    "太阳是什么", "月亮是什么", "地球有几颗卫星", "水的沸点是多少", "光速是多少",
    "猫是什么", "狗有几条腿", "苹果是什么", "北京是什么", "日本的首都是哪里",
    # 更偏的常识(可能没有)
    "珠穆朗玛峰有多高", "长江有多长", "中国的面积是多少", "美国有多少人口",
    "什么是光合作用", "什么是勾股定理", "圆周率是多少", "谁发明了电话",
    # 推理类
    "什么是猫", "狗狗有几条腿", "kiki住在哪", "小红和小明是什么关系",
    "张伟的妈妈是谁", "乌龟走得快吗", "猫会捉老鼠吗",
]


def post(path, **fields):
    body = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body)
    with urllib.request.urlopen(req, timeout=25) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    direct = "--direct" in sys.argv
    brain = None
    if direct:
        import brain as brain_mod
        brain = brain_mod.get_brain()

    ok, miss = [], []
    for q in QUESTIONS:
        try:
            if direct:
                r = brain.think(q, "audit")
                ans = (r or {}).get("answer")
                code = 200 if ans else 10001
            else:
                resp = post("/Reply", ip=IP, keyword=q)
                code = resp.get("code")
                d = resp.get("data") or {}
                ans = d.get("answer") if isinstance(d, dict) else str(d)
        except Exception as e:
            code, ans = -1, "EXC:%s" % e
        if code == 200:
            ok.append((q, ans))
        else:
            miss.append((q, ans))

    total = len(QUESTIONS)
    print("=" * 60)
    print("覆盖面审计:%d 题,命中 %d,未命中 %d,命中率 %.0f%%"
          % (total, len(ok), len(miss), 100.0 * len(ok) / total))
    print("=" * 60)
    print("\n--- 答上了 ---")
    for q, a in ok:
        print("  %-22s %s" % (q, (a or "")[:40]))
    print("\n--- 答不上 ---")
    for q, a in miss:
        print("  %-22s %s" % (q, (a or "")[:40]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
