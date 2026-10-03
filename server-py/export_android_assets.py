# -*- coding: utf-8 -*-
"""把本地知识库导出成 Android App 能直接读的资源。

产出(全部 UTF-8 无 BOM,尽量紧凑):
    android-assets/facts.tsv      事实库:主语<TAB>关系<TAB>宾语<TAB>来源
    android-assets/qa.tsv         调教问答:关键词<TAB>回答<TAB>分类
    android-assets/greeting.txt   开场白逐行一条(greeting 类条目)
    android-assets/pinyin.txt     拼音表:汉字<TAB>无声调拼音(每行一个)

设计说明
--------
- **不用 SQLite**:事实只有 1,700 多行,启动时一次性读进内存(约 60 KB),
  比 SQL 查询更快,也省掉移植 SQL 的出错风险 —— 这正是本地版验证过的方式。
- 拼音只导出**无声调**形式:语音识别错的是同音字,声调不可靠,比对时也就
  不需要声调(与 PC 版 lexicon._strip_tone 的行为一致)。
- 导出的关系词表会在 App 里重建,用于"动态关系问句"(氧原子序数 / 山东省简称)。

用法:
    python export_android_assets.py            # 输出到 ../android-app/app/src/main/assets
    python export_android_assets.py <输出目录>
"""
import io
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.abspath(os.path.join(HERE, "..", "android-app", "app", "src", "main", "assets"))

# 客户端质量闸用的虚词表(与 local_service.py 的 STOPWORDS 保持一致)
STOPWORDS = set(
    "你 我 他 她 它 的 了 吗 呢 啊 吧 呀 嘛 哦 是 有 会 在 和 与 就 都 也 很 太 真 "
    "这 那 个 什么 怎么 为什么 多少 几".split()
)


# 人格名:屏幕上的角色自称。用户要求显示 kiki(2011 年那个项目的名字),
# 而不再是「kiki」——素材来源是kiki,但呈现为 kiki。
PERSONA = "kiki"


# 名称替换:素材/知识库来自「kiki」,但屏幕上的角色自称 kiki。
# 长名优先替换,避免「kiki」被「kiki」先吃掉一半。
NAME_MAP = [
    ("kiki", PERSONA),
    ("kiki", PERSONA),
    ("kiki", PERSONA),
    ("kiki", PERSONA),
]


def rename(text):
    """把所有自称替换成 PERSONA。"""
    t = text or ""
    for a, b in NAME_MAP:
        t = t.replace(a, b)
    # 「kiki」-> kiki 会得到「我叫kiki,kiki!」,清掉重复
    t = t.replace(PERSONA + "," + PERSONA, PERSONA)
    t = t.replace(PERSONA + "、" + PERSONA, PERSONA)
    return t


def ensure_dir(path):
    if not os.path.isdir(path):
        os.makedirs(path)


def export_facts(out_dir):
    """导出事实库(facts 表)。

    会过滤掉"引擎自动记忆"时误拆出来的垃圾事实:
    例如把"今天也是好天气呢"拆成 (今天,好天气,呢)。
    判据:关系词里含日语假名/语气助词,或关系词长度超过 6 —— 都不可能是真关系。
    """
    src = os.path.join(HERE, "kiki_brain.db")
    if not os.path.exists(src):
        print("!! 找不到 kiki_brain.db")
        return 0
    conn = sqlite3.connect(src)
    rows = conn.execute("SELECT s, rel, o, COALESCE(source,'') FROM facts").fetchall()
    conn.close()
    path = os.path.join(out_dir, "facts.tsv")
    kept = dropped = 0
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        for s, rel, o, src_tag in rows:
            # 制表符/换行会破坏格式,做一次清洗(事实里不该有,防御性处理)
            s2 = (s or "").replace("\t", " ").replace("\n", " ").strip()
            r2 = (rel or "").replace("\t", " ").replace("\n", " ").strip()
            o2 = (o or "").replace("\t", " ").replace("\n", " ").strip()
            if not (s2 and r2 and o2):
                dropped += 1
                continue
            if not _rel_ok(r2):
                dropped += 1
                continue
            # 自称改成 PERSONA(素材是kiki,呈现为 kiki)
            s2, o2 = rename(s2), rename(o2)
            f.write("%s\t%s\t%s\t%s\n" % (s2, r2, o2, src_tag))
            kept += 1
    print("事实   : 保留 %d 条(丢弃垃圾 %d 条) -> %s" % (kept, dropped, os.path.basename(path)))
    return kept


# 明显不是关系词的情况:语气助词结尾、含假名、过长
REL_TAIL_NOISE = "呢啊吧呀嘛哦哈啦哟呗么了"
KANA = set("ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねの"
           "はばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをん"
           "ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノ"
           "ハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴ")


def _rel_ok(rel):
    """判断一个"关系词"是否可信。"""
    if not rel or len(rel) > 6:
        return False
    if rel[-1] in REL_TAIL_NOISE:
        return False
    if any(ch in KANA for ch in rel):
        return False
    return True


def export_qa(out_dir):
    """导出调教问答(webmarisa.db 的 memorise 表)。greeting 类单独成文件。"""
    src = os.path.join(HERE, "webmarisa.db")
    if not os.path.exists(src):
        print("!! 找不到 webmarisa.db")
        return 0, 0
    conn = sqlite3.connect(src)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT keyword, raw_keyword, answer, "
        "COALESCE(category,'') AS category, "
        "COALESCE(review_status,'approved') AS review_status "
        "FROM memorise"
    ).fetchall()
    conn.close()

    qa_path = os.path.join(out_dir, "qa.tsv")
    greet_path = os.path.join(out_dir, "greeting.txt")
    n_qa = n_greet = 0
    with io.open(qa_path, "w", encoding="utf-8", newline="\n") as f, \
            io.open(greet_path, "w", encoding="utf-8", newline="\n") as g:
        for row in rows:
            ans = (row["answer"] or "").replace("\t", " ").replace("\n", " ").strip()
            # 自称统一改成 PERSONA(素材是kiki,呈现为 kiki)
            ans = rename(ans)
            if not ans:
                continue
            cat = (row["category"] or "").strip()
            raw = (row["raw_keyword"] or "").strip()
            # greeting 条目关键词为空:它们只当开场白(与 PC 版语义一致)
            if cat == "greeting" or (not raw and not (row["keyword"] or "").strip()):
                g.write(rename(ans) + "\n")
                n_greet += 1
                continue
            kw = raw or (row["keyword"] or "").replace(",", "").strip()
            if not kw:
                continue
            f.write("%s\t%s\t%s\n" % (kw.replace("\t", " "), ans, cat))
            n_qa += 1
    print("问答   : %d 条 -> %s" % (n_qa, os.path.basename(qa_path)))
    print("开场白 : %d 条 -> %s" % (n_greet, os.path.basename(greet_path)))
    return n_qa, n_greet


def export_pinyin(out_dir):
    """导出拼音表(无声调)。"""
    try:
        from pinyin_cache import PINYIN
    except Exception as e:
        print("!! 无法导入 pinyin_cache: %s" % e)
        return 0
    path = os.path.join(out_dir, "pinyin.txt")
    n = 0
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        for ch, py in PINYIN.items():
            first = (py or "").split(" ")[0]
            plain = re.sub(r"\d+$", "", first)   # 去声调:mo2 -> mo
            if not ch or not plain or plain == "?":
                continue
            f.write("%s\t%s\n" % (ch, plain))
            n += 1
    print("拼音   : %d 字 -> %s" % (n, os.path.basename(path)))
    return n


def export_relations(out_dir):
    """导出关系词清单(供 App 端动态关系问句使用)。"""
    src = os.path.join(HERE, "kiki_brain.db")
    if not os.path.exists(src):
        return 0
    conn = sqlite3.connect(src)
    rels = [r[0] for r in conn.execute("SELECT DISTINCT rel FROM facts WHERE rel IS NOT NULL")]
    conn.close()
    rels = sorted({r.strip() for r in rels if r and r.strip() and _rel_ok(r.strip())},
                  key=len, reverse=True)
    path = os.path.join(out_dir, "relations.txt")
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        for r in rels:
            f.write(r + "\n")
    print("关系词 : %d 个 -> %s" % (len(rels), os.path.basename(path)))
    return len(rels)


def export_dict(out_dir, max_words=40000):
    """导出分词词表,供 App 做最长匹配分词。

    为什么必须带词表
    ----------------
    质量闸靠"实义词覆盖度"判断答非所问,而这件事**依赖分词**:
    按单字切的话,"你笨吗"和"你今天吃饭了吗"都会切出"你/吗",
    重合度又会被虚词污染(这正是 PC 版踩过的坑)。
    带一份词表做最长匹配,才能切出"今天/吃饭"这样的实义词。

    只挑长度 2-3 的词并按词频降序取前 N 个,兼顾覆盖与体积。
    """
    import lexicon
    lx = lexicon.get_lexicon()
    dict_path = lx._dict_path
    if not dict_path or not os.path.exists(dict_path):
        print("!! 找不到 jieba 词典,跳过分词词表")
        return 0
    rows = []
    with io.open(dict_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            p = line.rstrip("\n").split(" ")
            if len(p) < 3:
                continue
            w = p[0]
            if len(w) < 2 or len(w) > 3:
                continue
            if not w.isalpha() and not all('\u4e00' <= c <= '\u9fff' for c in w):
                continue
            try:
                freq = int(p[1])
            except ValueError:
                continue
            rows.append((freq, w))
    rows.sort(reverse=True)
    words = []
    seen = set()
    for _freq, w in rows:
        if w in seen:
            continue
        seen.add(w)
        words.append(w)
        if len(words) >= max_words:
            break
    # 事实库实体也加进去(它们最可能在问句里出现)
    try:
        conn = sqlite3.connect(os.path.join(HERE, "kiki_brain.db"))
        for (s,) in conn.execute("SELECT DISTINCT s FROM facts"):
            if s and 2 <= len(s) <= 4 and s not in seen:
                seen.add(s)
                words.append(s)
        conn.close()
    except Exception:
        pass
    path = os.path.join(out_dir, "words.txt")
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        for w in words:
            f.write(w + "\n")
    print("词表   : %d 词 -> %s" % (len(words), os.path.basename(path)))
    return len(words)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    ensure_dir(out_dir)
    print("输出目录: %s" % out_dir)
    print("-" * 56)
    export_facts(out_dir)
    export_qa(out_dir)
    export_pinyin(out_dir)
    export_relations(out_dir)
    export_dict(out_dir)
    print("-" * 56)
    total = 0
    for name in sorted(os.listdir(out_dir)):
        p = os.path.join(out_dir, name)
        size = os.path.getsize(p)
        total += size
        print("  %-16s %8.1f KB" % (name, size / 1024.0))
    print("  合计 %.1f KB" % (total / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
