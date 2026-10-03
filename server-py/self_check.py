# -*- coding: utf-8 -*-
"""三件套一键自检:逻辑引擎 / 知识库规模 / 语音能力。

装完中文语音包之后,先跑这个脚本确认全部能力,再启动聊天。

用法:
    cd D:\\nbjj\\dsh\\marisa-revive\\server-py
    ..\\.venv\\Scripts\\python self_check.py
"""
import io
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

SEP = "=" * 62


def line(title):
    print("\n" + SEP)
    print(title)
    print(SEP)


def check_lexicon():
    line("① 逻辑引擎 · 词法层")
    try:
        import lexicon
        lx = lexicon.get_lexicon()
        print("  词条总数 : %d" % lx.size())
        kinds = lx.kinds()
        for name in ("地名", "人名", "专名", "机构", "名词"):
            if name in kinds:
                print("  %-8s : %d" % (name, kinds[name]))
        print("  拼音表   : %d 字" % len(lexicon._load_pinyin()))
        print("  同音纠错 : 零梦->%s | 艾丽斯->%s"
              % (lx.suggest("零梦")[:1] or ["(无)"], lx.suggest("艾丽斯")[:1] or ["(无)"]))
        return True
    except Exception as e:
        print("  !! 词法层不可用: %s" % e)
        return False


def check_brain():
    line("② 逻辑引擎 · 推理能力")
    try:
        import brain
        b = brain.get_brain()
        cases = [
            ("卡卡是什么", "分类继承"),
            ("卡卡有几条腿", "属性继承"),
            ("小明的爷爷是谁", "传递推理"),
            ("小红和小明是什么关系", "亲属路径"),
            ("企鹅会飞吗", "否定判定"),
            ("乌龟走得快吗", "反义判定"),
            ("kiki住在哪", "同音纠错"),
            ("中国的首都是哪里", "导入关系"),
            ("一小时是几分钟", "换算"),
        ]
        ok = 0
        for q, tag in cases:
            r = b.think(q, "selfcheck")
            got = (r or {}).get("answer")
            if got:
                ok += 1
                print("  [OK] %-8s %s" % (tag, got[:38]))
            else:
                print("  [!!] %-8s %s -> 答不出" % (tag, q))
        print("  --> %d/%d 项通过" % (ok, len(cases)))
        return ok == len(cases)
    except Exception as e:
        print("  !! 引擎不可用: %s" % e)
        return False


def check_facts():
    line("③ 知识库规模")
    try:
        c = sqlite3.connect(os.path.join(HERE, "kiki_brain.db"))
        total = c.execute("SELECT COUNT(*) FROM facts").fetchone()[0]
        print("  事实库总计: %d 条" % total)
        for src, n in c.execute(
            "SELECT source, COUNT(*) FROM facts GROUP BY source ORDER BY COUNT(*) DESC"
        ):
            print("    %-26s %d" % (src, n))
        c2 = sqlite3.connect(os.path.join(HERE, "webmarisa.db"))
        print("  记忆库(调教): %d 条"
              % c2.execute("SELECT COUNT(*) FROM memorise").fetchone()[0])
        return True
    except Exception as e:
        print("  !! 知识库读取失败: %s" % e)
        return False


def check_voice():
    line("④ 语音能力")
    try:
        import voice
        info = voice.available()
        print("  TTS 语音 : %s" % (", ".join(info["tts"]) or "无"))
        print("  中文 TTS : %s" % ("有" if info["chinese_tts"] else "无"))
        print("  识别引擎 : %s" % (", ".join(info["asr"]) or "无"))
        if not info["chinese_tts"]:
            print("\n  >> 要能用中文说话,请先安装语音包:")
            print("     设置 -> 时间和语言 -> 语音 -> 添加语音「中文(简体,中国)」")
        r = voice.say("你好呀,我是kiki")
        print("\n  合成+播放: ok=%s played=%s" % (r["ok"], r["played"]))
        print("  说明: %s" % r["msg"][:110])
        return bool(r["ok"])
    except Exception as e:
        print("  !! 语音模块异常: %s" % e)
        return False


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    print(SEP)
    print(" kiki · 本地单机版 三件套自检")
    print(SEP)

    results = {
        "词法层": check_lexicon(),
        "逻辑引擎": check_brain(),
        "知识库": check_facts(),
        "语音": check_voice(),
    }

    line("汇总")
    for k, v in results.items():
        print("  %-8s : %s" % (k, "通过" if v else "未通过/受限"))
    print("\n  启动聊天:  ..\\.venv\\Scripts\\python chat_client.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
