# -*- coding: utf-8 -*-
"""自检 facts_elements.tsv:编码/字段/覆盖度/计数。"""
import io, os, collections

HERE = os.path.dirname(os.path.abspath(__file__))
p = os.path.join(HERE, "facts_elements.tsv")
raw = open(p, "rb").read()
text = raw.decode("utf-8")

print("== 1. 编码 ==")
print("前 3 字节:", raw[:3], "-> 有 BOM:", raw.startswith(b"\xef\xbb\xbf"))
print("UTF-8 可解码:", True, " 含 CR:", b"\r" in raw, " 以 LF 结尾:", raw.endswith(b"\n"))

rows = [l.split("\t") for l in text.split("\n") if l.strip() and not l.startswith("#")]
print("\n== 2. 行/字段 ==")
print("有效行数:", len(rows))
print("字段数不足 3 的行:", sum(1 for r in rows if len(r) < 3))
print("字段数不是 3 的行:", sum(1 for r in rows if len(r) != 3))

# 权威参考表:(原子序数, 符号, 中文名)
REF = [
    (1,"H","氢"),(2,"He","氦"),(3,"Li","锂"),(4,"Be","铍"),(5,"B","硼"),(6,"C","碳"),
    (7,"N","氮"),(8,"O","氧"),(9,"F","氟"),(10,"Ne","氖"),(11,"Na","钠"),(12,"Mg","镁"),
    (13,"Al","铝"),(14,"Si","硅"),(15,"P","磷"),(16,"S","硫"),(17,"Cl","氯"),(18,"Ar","氩"),
    (19,"K","钾"),(20,"Ca","钙"),(21,"Sc","钪"),(22,"Ti","钛"),(23,"V","钒"),(24,"Cr","铬"),
    (25,"Mn","锰"),(26,"Fe","铁"),(27,"Co","钴"),(28,"Ni","镍"),(29,"Cu","铜"),(30,"Zn","锌"),
    (31,"Ga","镓"),(32,"Ge","锗"),(33,"As","砷"),(34,"Se","硒"),(35,"Br","溴"),(36,"Kr","氪"),
    (37,"Rb","铷"),(38,"Sr","锶"),(39,"Y","钇"),(40,"Zr","锆"),(41,"Nb","铌"),(42,"Mo","钼"),
    (43,"Tc","锝"),(44,"Ru","钌"),(45,"Rh","铑"),(46,"Pd","钯"),(47,"Ag","银"),(48,"Cd","镉"),
    (49,"In","铟"),(50,"Sn","锡"),(51,"Sb","锑"),(52,"Te","碲"),(53,"I","碘"),(54,"Xe","氙"),
    (55,"Cs","铯"),(56,"Ba","钡"),(57,"La","镧"),(58,"Ce","铈"),(59,"Pr","镨"),(60,"Nd","钕"),
    (61,"Pm","钷"),(62,"Sm","钐"),(63,"Eu","铕"),(64,"Gd","钆"),(65,"Tb","铽"),(66,"Dy","镝"),
    (67,"Ho","钬"),(68,"Er","铒"),(69,"Tm","铥"),(70,"Yb","镱"),(71,"Lu","镥"),(72,"Hf","铪"),
    (73,"Ta","钽"),(74,"W","钨"),(75,"Re","铼"),(76,"Os","锇"),(77,"Ir","铱"),(78,"Pt","铂"),
    (79,"Au","金"),(80,"Hg","汞"),(81,"Tl","铊"),(82,"Pb","铅"),(83,"Bi","铋"),(84,"Po","钋"),
    (85,"At","砹"),(86,"Rn","氡"),(87,"Fr","钫"),(88,"Ra","镭"),(89,"Ac","锕"),(90,"Th","钍"),
    (91,"Pa","镤"),(92,"U","铀"),(93,"Np","镎"),(94,"Pu","钚"),(95,"Am","镅"),(96,"Cm","锔"),
    (97,"Bk","锫"),(98,"Cf","锎"),(99,"Es","锿"),(100,"Fm","镄"),(101,"Md","钔"),(102,"No","锘"),
    (103,"Lr","铹"),(104,"Rf","𬬻"),(105,"Db","𬭊"),(106,"Sg","𬭳"),(107,"Bh","𬭛"),(108,"Hs","𬭶"),
    (109,"Mt","鿏"),(110,"Ds","𫟼"),(111,"Rg","𬬭"),(112,"Cn","鿔"),(113,"Nh","鿭"),(114,"Fl","𫓧"),
    (115,"Mc","镆"),(116,"Lv","𫟷"),(117,"Ts","鿬"),(118,"Og","鿫"),
]
NAMES = [c for _, _, c in REF]
NAME = set(NAMES)

EL_REL = ("符号", "原子序数", "化学元素")
el_rows = [r for r in rows if r[0] in NAME and (
    r[1] in ("符号", "原子序数") or (r[1] == "是" and r[2] == "化学元素"))]
common = [r for r in rows if not (r[0] in NAME and (
    r[1] in ("符号", "原子序数") or (r[1] == "是" and r[2] == "化学元素")))]

print("\n== 3. 元素覆盖 ==")
print("参考表元素个数:", len(REF), " 去重中文名:", len(NAME), " 去重符号:", len(set(s for _, s, _ in REF)))
print("文件中出现的元素主语个数:", len(set(r[0] for r in el_rows)))

missing = [c for c in NAMES if c not in set(r[0] for r in el_rows)]
print("有元素名但缺事实的元素:", missing)

by_name = collections.defaultdict(lambda: collections.defaultdict(list))
for r in el_rows:
    by_name[r[0]][r[1]].append(r[2])

bad_sym = bad_z = bad_is = 0
for z, s, c in REF:
    by = by_name[c]
    if by.get("符号") != [s]:
        bad_sym += 1; print("  符号错:", c, by.get("符号"), "应为", [s])
    if by.get("原子序数") != [str(z)]:
        bad_z += 1; print("  序号错:", c, by.get("原子序数"), "应为", [str(z)])
    if "化学元素" not in by.get("是", []):
        bad_is += 1; print("  缺/错 是化学元素:", c, by.get("是"))
print("符号错误数:", bad_sym, " 原子序数错误数:", bad_z, " 是化学元素错误数:", bad_is)

cnt = collections.Counter(r[0] for r in el_rows)
print("每元素至少 3 条:", all(cnt[c] >= 3 for c in NAMES), " 最少条数:", min(cnt.values()))
print("\n== 4. 计数 ==")
print("元素事实条数:", len(el_rows), "(应为 118*3=%d)" % (118 * 3))
print("常识条数:", len(common), " 常识主语个数:", len(set(r[0] for r in common)))
print("总有效行数:", len(rows))
print("重复三元组:", [k for k, v in collections.Counter(tuple(r) for r in rows).items() if v > 1])
print("\n== 5. 跳过条目 ==")
for l in text.split("\n"):
    if l.startswith("# 跳过"):
        print("  ", l)
