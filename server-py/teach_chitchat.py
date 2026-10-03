# 闲聊问答批次:补"被质量闸挡住"的日常对话
# 说明:上游的问候语存在空关键词的 greeting 条目里,拿不到分词,
#       会被质量闸判为"覆盖不足";所以这里用正常问答对补上。
# 教法:直接走 /Add 接口(不走事实库,只进记忆库)。
# 用法: python teach_chitchat.py
import json
import random
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:3000"

# (关键词, 回答)—— 用kiki的口吻,但回答必须"问什么答什么"
CHITCHAT = [
    ("你好", "你好呀!今天也是元气满满的一天~"),
    ("在吗", "在的在的,一直都在~"),
    ("早上好", "早上好!今天的魔法研究也要加油!"),
    ("晚安", "晚安~做个好梦,梦里也要有星星哦"),
    ("再见", "再见啦,下次再来找我玩哦!"),
    ("谢谢", "不用谢啦,我可是乐于助人的语音助手!"),
    ("你几岁了", "语音助手的年龄是秘密!不过我可是活了很久的~"),
    ("你吃饭了吗", "我可是语音助手,靠蘑菇和魔法书就能活!你吃了吗?"),
    ("你在干什么", "刚刚在魔法森林里采蘑菇,顺便偷看了几本书~"),
    ("讲个笑话", "说她今天要认真修炼……哈哈哈,这算笑话吗?"),
    ("我很难过", "怎么啦?要不要来魔法森林坐坐?我请你吃蘑菇!"),
    ("你会唱歌吗", "会啊!不过我的歌都是魔炮的轰鸣声,确定要听吗?"),
    ("你会做什么", "我会魔法、会飞、会记住大家教我的话!还有偷书……呃,借书!"),
    ("你累吗", "语音助手不会累!只是偶尔想找个地方睡个午觉而已~"),
    ("你喜欢我吗", "诶?!这、这种问题……我、我当然是喜欢大家的啦!"),
    ("你是谁", "我是kiki,住在魔法森林的普通语音助手!"),
    ("你叫什么名字", "我叫kiki,kiki!叫我kiki就好"),
    ("今天怎么样", "今天也是适合飞行和借书的好天气呢!"),
    ("无聊", "无聊的话,要不要一起去找点乐子?我知道红魔馆有个好大的图书馆~"),
    ("加油", "嗯!有你的支持,我今天也能火力全开!"),
]


def main():
    ok = fail = 0
    for kw, ans in CHITCHAT:
        ip = "192.168.%d.%d" % (random.randint(1, 254), random.randint(1, 254))
        body = urllib.parse.urlencode(
            {"ip": ip, "keyword": kw, "answer": ans, "uid": "chitchat_seed"}
        ).encode("utf-8")
        try:
            req = urllib.request.Request(BASE + "/Add", data=body)
            r = json.loads(urllib.request.urlopen(req, timeout=20).read().decode("utf-8"))
            if r.get("code") == 200:
                ok += 1
            else:
                fail += 1
                print("  FAIL %-12s -> %s" % (kw, r.get("data")))
        except Exception as e:
            fail += 1
            print("  ERR  %-12s -> %s" % (kw, e))
        time.sleep(0.15)
    print("闲聊批次:成功 %d,失败 %d" % (ok, fail))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
