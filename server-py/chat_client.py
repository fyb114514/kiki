# -*- coding: utf-8 -*-
"""在终端里和kiki聊天(本地单机版 · 带语音开关)。

玩法(完全复刻 2011 年那套"调教"):
    直接说话        -> 提问,命中的话她回答你
    关键词`回答     -> 教她一句话(反引号分隔);教完立刻生效,也能立即参与推理
    teach           -> 进入教学模式,接下来每行都按 关键词`回答 解析
    talk <条件>`<关键词>`<回答>
                    -> 教"看人下菜"的回答(对象判断),条件见 help
    status          -> 看她现在会多少条
    hint            -> 随机偷看一条她已经记住的内容
    forget <回答>   -> 忘掉某条(要输入完整回答原文)
    /voice on|off   -> 开关语音(能出声时念回答)
    /who            -> 看自己的匿名身份 uid
    help / quit

用法:
    先确保后端在跑:python -m local_service
    再开一个终端:  python chat_client.py
"""
import json
import os
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:3000"
IP = "127.0.0.1"          # 本机自用,固定 IP
UID_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "local_uid.txt")
TEACH_PROMPT = "教学模式(输入 关键词`回答 ;空行或 exit 退出教学)> "

HELP = """可用输入:
  <任意话>              提问
  <关键词>`<回答>       教她一句话(教完立刻生效)
  talk <条件>`<关键词>`<回答>
                        教"看人下菜"的回答,条件可选:
                          all                    所有人(默认)
                          user:<uid>             只对某个 uid
                          favor:low|medium|high  只对好感低/中/高的人
                          time:dawn|day|dusk|night  只在早晨/白天/傍晚/深夜
  teach                 进入教学模式
  status                她会多少条
  hint                  随机看她记住的一条内容
  forget <回答原文>     让她忘掉该条
  /voice on|off         开关语音(念出她的回答)
  /voice test           试播一句,检查语音能力
  /who                  显示本机匿名身份(uid)
  help / quit
"""


def load_uid():
    """匿名身份:首次运行生成并落盘,之后固定。

    为什么要固定?上游的"对象判断"按 uid 区分人 —— 同一个 uid 才能积累,
    否则每次重启都变成陌生人,条件化回答永远命中不了。
    """
    try:
        if os.path.exists(UID_FILE):
            u = open(UID_FILE, encoding="utf-8").read().strip()
            if u:
                return u
    except OSError:
        pass
    u = uuid.uuid4().hex[:16]
    try:
        with open(UID_FILE, "w", encoding="utf-8") as f:
            f.write(u)
    except OSError:
        pass
    return u


def post(path, **fields):
    body = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body)
    with urllib.request.urlopen(req, timeout=25) as resp:
        return json.loads(resp.read().decode("utf-8"))


def say(text):
    print("kiki > %s" % text)


class Voice:
    """语音开关的薄封装:能出声就出声,不能就静默降级并说清原因。"""

    def __init__(self, enabled=False):
        self.enabled = enabled
        self.info = None
        self._mod = None
        self.status = "未检测"

    def _load(self):
        if self._mod is None:
            try:
                import voice as v
                self._mod = v
                self.info = v.available()
            except Exception as e:
                self._mod = False
                self.status = "语音模块不可用: %s" % e
        return self._mod

    def describe(self):
        m = self._load()
        if not m:
            return self.status
        i = self.info or {}
        return " | ".join([
            "TTS=%s" % (",".join(i.get("tts") or []) or "无"),
            "中文TTS=%s" % ("有" if i.get("chinese_tts") else "无"),
            "识别=%s" % (",".join(i.get("asr") or []) or "无"),
        ])

    def speak(self, text):
        if not self.enabled:
            return
        m = self._load()
        if not m:
            print("        (语音不可用: %s)" % self.status)
            return
        r = m.say(text)
        if not r["ok"]:
            print("        (没念出来: %s)" % r["msg"])

    def test(self):
        m = self._load()
        if not m:
            print("        语音模块加载失败:", self.status)
            return
        print("        能力: %s" % self.describe())
        r = m.say("你好呀,我是kiki")
        print("        试播: ok=%s played=%s msg=%s" % (r["ok"], r["played"], r["msg"]))


def do_reply(text, uid, voice):
    r = post("/Reply", ip=IP, keyword=text, uid=uid)
    code = r.get("code")
    data = r.get("data")
    ans = data.get("answer", "") if isinstance(data, dict) else str(data)
    say(ans)
    voice.speak(ans)
    if code == 10001:
        print("        (没答上来。你可以教她: %s`你的回答)" % text)


def do_add(keyword, answer, uid, flag="all"):
    body = {"ip": IP, "keyword": keyword, "answer": answer, "uid": uid}
    if flag and flag != "all":
        body["flag"] = flag
    r = post("/Add", **body)
    if r.get("code") == 200:
        extra = "" if flag == "all" else " [条件 %s]" % flag
        print("        [已记住%s] %s -> %s" % (extra, keyword, answer))
    else:
        print("        [没记住] %s" % r.get("data"))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    uid = load_uid()
    voice = Voice(enabled=False)

    print("=" * 62)
    print(" kiki · 本地单机版(kiki 逻辑引擎)")
    print(" 提问直接说话;教她用  关键词`回答")
    print(" 输入 help 看全部指令,/voice test 检查语音能力")
    print("=" * 62)

    try:
        r = post("/Greeting")
        if r.get("code") == 200 and isinstance(r.get("data"), dict):
            say(r["data"].get("answer", ""))
    except urllib.error.URLError:
        print("!! 连不上本地后端 http://127.0.0.1:3000")
        print("!! 请先在另一个终端运行:  python -m local_service")
        return 1

    teaching = False
    try:
        while True:
            try:
                prompt = TEACH_PROMPT if teaching else "你 > "
                line = input(prompt).strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break

            if not line:
                if teaching:
                    teaching = False
                    print("        [已退出教学模式]")
                continue

            low = line.lower()
            # ---- 斜杠命令 ----
            if low.startswith("/voice"):
                arg = low[len("/voice"):].strip()
                if arg == "on":
                    voice.enabled = True
                    print("        [语音已开] 能力: %s" % voice.describe())
                elif arg == "off":
                    voice.enabled = False
                    print("        [语音已关]")
                elif arg == "test":
                    voice.test()
                else:
                    print("        用法: /voice on|off|test (当前 %s)"
                          % ("开" if voice.enabled else "关"))
                continue
            if low in ("/who", "who"):
                print("        你的匿名身份 uid = %s" % uid)
                continue
            if low == "help":
                print(HELP)
                continue

            if low in ("quit", "exit", "q") and not teaching:
                break
            if teaching and low in ("exit", "quit"):
                teaching = False
                print("        [已退出教学模式]")
                continue
            if low == "status":
                r = post("/Status")
                print("        [她记住了 %s 条]" % r.get("data"))
                continue
            if low == "hint":
                r = post("/Hint")
                d = r.get("data") if isinstance(r.get("data"), dict) else {}
                if r.get("code") == 200:
                    print("        [线索] %s -> %s" % (d.get("keyword", ""), d.get("answer", "")))
                else:
                    print("        [她还没记住什么内容]")
                continue
            if low.startswith("forget"):
                ans = line[len("forget"):].strip()
                if not ans:
                    print("        用法: forget <完整回答原文>")
                    continue
                r = post("/Forget", answer=ans, uid=uid)
                print("        [忘掉了]" if r.get("code") == 200 else "        [执行了]")
                continue
            if low == "teach":
                teaching = True
                print("        [进入教学模式] 每行输入 关键词`回答,空行退出")
                continue

            # ---- talk:带"对象判断"的教学 ----
            if low.startswith("talk"):
                rest = line[len("talk"):].strip()
                parts = rest.split("`")
                if len(parts) != 3:
                    print("        用法: talk <条件>`<关键词>`<回答>")
                    print("        例:   talk time:night`晚安`这么晚还不睡?熬夜会变丑的")
                    continue
                flag, kw, ans = (p.strip() for p in parts)
                if not kw or not ans:
                    print("        关键词和回答都不能空")
                    continue
                do_add(kw, ans, uid, flag)
                continue

            # 反引号 -> 教学;否则 -> 提问
            if "`" in line:
                kw, _, ans = line.partition("`")
                kw, ans = kw.strip(), ans.strip()
                if not kw or not ans:
                    print("        教学格式: 关键词`回答")
                    continue
                do_add(kw, ans, uid)
            else:
                try:
                    do_reply(line, uid, voice)
                except urllib.error.URLError:
                    print("        !! 后端没在跑,先执行: python -m local_service")
    finally:
        print("再见啦,下次再来找我玩哦!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
