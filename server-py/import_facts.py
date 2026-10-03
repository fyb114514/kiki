# -*- coding: utf-8 -*-
"""离线事实导入:把大规模三元组灌进 kiki 事实库。

这是"扩充知识库规模"的正路。当前事实库只有 161 条手工基座,
而 kiki 自述是"7G 信息库 + 1G 记忆库"。要真上量级,只能靠**导入真实语料**,
不能靠手写——所以本脚本专门负责:

    输入:一个或多个文本文件,每行一条事实,三种格式都支持
        1) 制表符三元组  主语<TAB>关系<TAB>宾语      (推荐,最清晰)
        2) 冒号句        主语:关系:宾语
        3) 自然语句      猫是动物 / 猫喜欢吃鱼 / 小明的爸爸是张三
                         (复用引擎自己的 to_triple 解析器)

    输出:写入 kiki_brain.db,并打上 source 标记(默认 "import:<文件名>"),
         便于日后区分"手工基座 / 教学 / 外部导入"三类来源。

用法:
    # 先干跑,看看能解析出多少条、有没有坏行
    python import_facts.py --dry-run facts.tsv

    # 正式导入
    python import_facts.py facts.tsv more_facts.txt

    # 查看当前事实库来源分布
    python import_facts.py --stats

可对接的真实离线语料(自行下载,本机不联网抓取):
    - 中文维基/百度百科的抽取结果(实体-关系-实体)
    - CN-DBpedia / OwnThink 等开放知识图谱的 dump
    - 自建词条表(如 国家-首都、元素-符号)
只要转成上面任一行格式即可。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def parse_line(line):
    """一行文本 -> (主语, 关系, 宾语);无法解析返回 None。"""
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    # 1) 制表符
    if "\t" in line:
        parts = [p.strip() for p in line.split("\t")]
        if len(parts) >= 3 and all(parts[:3]):
            return parts[0], parts[1], parts[2]
        return None
    # 2) 冒号
    if ":" in line or "：" in line:
        norm = line.replace("：", ":")
        parts = [p.strip() for p in norm.split(":")]
        if len(parts) >= 3 and all(parts[:3]):
            return parts[0], parts[1], parts[2]
        return None
    # 3) 自然语句,交给引擎解析
    import brain
    tri = brain.to_triple(line)
    return tri


def iter_files(paths):
    for p in paths:
        if not os.path.exists(p):
            print("  跳过(不存在): %s" % p)
            continue
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            for lineno, line in enumerate(f, 1):
                yield p, lineno, line


def stats():
    """打印事实库的来源分布。"""
    import brain
    store = brain.BrainStore()
    rows = store.all_facts()
    with_src = {}
    for s, rel, o in rows:
        with_src.setdefault("(unknown)", 0)
    # 直接查 source 列
    import sqlite3
    c = sqlite3.connect(store.path)
    print("事实库:", store.path)
    print("总条数:", store.count())
    print("按来源:")
    for src, n in c.execute("SELECT source, COUNT(*) FROM facts GROUP BY source ORDER BY COUNT(*) DESC"):
        print("   %-24s %d" % (src, n))
    print("按关系(前 15):")
    for rel, n in c.execute("SELECT rel, COUNT(*) FROM facts GROUP BY rel ORDER BY COUNT(*) DESC LIMIT 15"):
        print("   %-12s %d" % (rel, n))


def main():
    args = [a for a in sys.argv[1:]]
    if not args or "--help" in args or "-h" in args:
        print(__doc__)
        return 0
    if "--stats" in args:
        stats()
        return 0

    dry = "--dry-run" in args
    source_tag = None
    files = []
    for a in args:
        if a.startswith("--source="):
            source_tag = a.split("=", 1)[1]
        elif a.startswith("--"):
            continue
        else:
            files.append(a)
    if not files:
        print("没有给出文件。用 --help 看用法。")
        return 2

    import brain
    store = brain.BrainStore()
    before = store.count()
    added = skipped = bad = 0
    per_file = {}

    for path, lineno, line in iter_files(files):
        tri = parse_line(line)
        if not tri:
            if line.strip() and not line.strip().startswith("#"):
                bad += 1
            continue
        s, rel, o = tri
        src = source_tag or ("import:" + os.path.basename(path))
        per_file[src] = per_file.get(src, 0) + 1
        if dry:
            added += 1
            if added <= 5:
                print("  样例: %s -%s-> %s" % (s, rel, o))
            continue
        try:
            store.add(s, rel, o, src)
            added += 1
        except Exception:
            skipped += 1

    after = store.count()
    print("-" * 56)
    print("解析成功: %d 条  无法解析: %d 行  入库新增: %d 条" % (added, bad, after - before))
    for src, n in sorted(per_file.items(), key=lambda x: -x[1])[:10]:
        print("   %-28s %d" % (src, n))
    print("事实库: %d -> %d 条" % (before, after))
    if dry:
        print("(干跑模式,未写入。去掉 --dry-run 即正式导入)")
    else:
        import brain as b2
        b2.get_brain().invalidate_entity_cache()
        print("已清理实体缓存,新事实立即可用。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
