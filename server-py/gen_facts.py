# -*- coding: utf-8 -*-
"""确定性事实生成器:只从**本机真实数据**推导事实,零幻觉。

数据来源(三条腿,都可核查):
    1. 词法层 jieba dict.txt 的词性标注
       地名 ns 17,706 / 人名 nr 5,941 / 专名 nz 10,441 / 机构 nt 4,713
       -> 生成"某词是地名/人名/专名/机构"这类**词典事实**
    2. .NET 区域数据(cultures_zh.json,由 PowerShell 从 RegionInfo 导出)
       -> 244 个国家/地区的中文名与货币
    3. 手工整理的中国省级行政区(公开稳定的行政区划)

为什么这样设计
--------------
kiki 卡片说"信息数据库有 7G"。真上量级必须靠**导入真实数据**,而不是让模型
凭记忆写(容易记错)。本脚本负责"能从真实数据推出来的那部分",
模型撰写的部分走 facts_*.tsv 单独标注来源,两者在库里用 source 字段区分。

用法:
    python gen_facts.py                    # 生成全部
    python gen_facts.py --only lexicon     # 只生成词法事实
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_LEXICON = os.path.join(HERE, "facts_lexicon.tsv")
OUT_REGIONS = os.path.join(HERE, "facts_regions.tsv")
OUT_PROVINCES = os.path.join(HERE, "facts_provinces.tsv")
CULTURES_JSON = os.path.join(HERE, "cultures_zh.json")

# ---- 中国省级行政区(公开稳定的行政区划;省会为省政府所在地) ----
PROVINCES = [
    ("北京市", "北京", "华北", "京"), ("天津市", "天津", "华北", "津"),
    ("河北省", "石家庄", "华北", "冀"), ("山西省", "太原", "华北", "晋"),
    ("内蒙古自治区", "呼和浩特", "华北", "蒙"),
    ("辽宁省", "沈阳", "东北", "辽"), ("吉林省", "长春", "东北", "吉"),
    ("黑龙江省", "哈尔滨", "东北", "黑"),
    ("上海市", "上海", "华东", "沪"), ("江苏省", "南京", "华东", "苏"),
    ("浙江省", "杭州", "华东", "浙"), ("安徽省", "合肥", "华东", "皖"),
    ("福建省", "福州", "华东", "闽"), ("江西省", "南昌", "华东", "赣"),
    ("山东省", "济南", "华东", "鲁"),
    ("河南省", "郑州", "华中", "豫"), ("湖北省", "武汉", "华中", "鄂"),
    ("湖南省", "长沙", "华中", "湘"),
    ("广东省", "广州", "华南", "粤"), ("广西壮族自治区", "南宁", "华南", "桂"),
    ("海南省", "海口", "华南", "琼"),
    ("重庆市", "重庆", "西南", "渝"), ("四川省", "成都", "西南", "川"),
    ("贵州省", "贵阳", "西南", "黔"), ("云南省", "昆明", "西南", "滇"),
    ("西藏自治区", "拉萨", "西南", "藏"),
    ("陕西省", "西安", "西北", "陕"), ("甘肃省", "兰州", "西北", "甘"),
    ("青海省", "西宁", "西北", "青"), ("宁夏回族自治区", "银川", "西北", "宁"),
    ("新疆维吾尔自治区", "乌鲁木齐", "西北", "新"),
    ("台湾省", "台北", "华东", "台"),
    ("香港特别行政区", "香港", "华南", "港"),
    ("澳门特别行政区", "澳门", "华南", "澳"),
]

# 词性 -> 事实里用的类别名
POS_TO_CATEGORY = [
    ("ns", "地名"),
    ("nr", "人名"),
    ("nrt", "人名"),
    ("nz", "专名"),
    ("nt", "机构"),
]

# 词法层噪音过滤
# 实测:jieba 的 ns 标注并不干净 —— "一加仑""一厘""一堆堆"都被标成了地名。
# 直接全量入库会把库变成垃圾场,所以做三层过滤:
#   1) 长度 2-6(单字歧义太大,超长的多半是短语)
#   2) 含数字/数量词/量词 -> 丢(通常是把度量单位误标成地名)
#   3) 含常见虚词/标点 -> 丢
LEX_NOISE_CHARS = set(
    "一二三四五六七八九十百千万亿零两"
    "个只条张本头匹件位名门辆架朵把支根颗粒滴片块份次遍趟回"
    "的了着过吗呢啊吧呀嘛哦哈啦哟呗么和与及或等很太更最又再"
    "加减乘除等于大于小于百分之"
)


def _lex_ok(word):
    """判断词法层的一个词是否值得入库。"""
    if not word:
        return False
    n = len(word)
    if n < 2 or n > 6:
        return False
    if any(ch in LEX_NOISE_CHARS for ch in word):
        return False
    return True


def gen_lexicon():
    """从词法层生成"某词属于某类"的词典事实。

    诚实说明:这些是**词典类目事实**(该词在中文里被标注为地名/人名/…),
    不是百科条目。它们在库里 source='lexicon',与百科类事实区分得清清楚楚。
    价值:让引擎"认识"上万实体(抗错字、支持同音匹配、扩展实体识别范围)。
    """
    import lexicon
    lx = lexicon.get_lexicon()
    lines = ["# 由 gen_facts.py 从 jieba dict.txt 的词性标注生成(词典类目事实)",
             "# 来源标记: lexicon —— 表示'该词在中文词法里被标注为某类实体'",
             "# 主语\t关系\t宾语"]
    total = 0
    for pos, category in POS_TO_CATEGORY:
        words = lx.all_of(pos)
        for w in words:
            if not _lex_ok(w):
                continue
            lines.append("%s\t是\t%s" % (w, category))
            total += 1
    with io.open(OUT_LEXICON, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("词法事实: %d 条 -> %s" % (total, os.path.basename(OUT_LEXICON)))
    return total


def gen_regions():
    """从 .NET 区域数据生成国家/地区事实。"""
    if not os.path.exists(CULTURES_JSON):
        print("缺少 cultures_zh.json(先用 PowerShell 从 RegionInfo 导出),跳过")
        return 0
    with io.open(CULTURES_JSON, encoding="utf-8") as f:
        data = json.load(f)
    lines = ["# 由 gen_facts.py 从 .NET RegionInfo 生成(zh-CN 显示名)",
             "# 来源标记: dotnet-region",
             "# 主语\t关系\t宾语"]
    n = 0
    seen = set()
    for item in data:
        code, disp = item.get("code"), (item.get("disp") or "").strip()
        if not code or not disp or disp in seen:
            continue
        seen.add(disp)
        cur = (item.get("cur") or "").strip()
        lines.append("%s\t是\t国家或地区" % disp)
        lines.append("%s\t代码\t%s" % (disp, code))
        if cur:
            lines.append("%s\t货币代码\t%s" % (disp, cur))
        n += 3 if cur else 2
    with io.open(OUT_REGIONS, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("国家/地区事实: %d 条(%d 个地区) -> %s" % (n, len(seen), os.path.basename(OUT_REGIONS)))
    return n


def gen_provinces():
    """从手工整理的省级行政区表生成事实。"""
    lines = ["# 由 gen_facts.py 生成:中国省级行政区(公开稳定的行政区划)",
             "# 来源标记: handcrafted-admin",
             "# 主语\t关系\t宾语"]
    n = 0
    for name, capital, region, abbr in PROVINCES:
        lines.append("%s\t是\t中国省级行政区" % name)
        lines.append("%s\t省会\t%s" % (name, capital))
        lines.append("%s\t位于\t%s" % (name, region))
        lines.append("%s\t简称\t%s" % (name, abbr))
        # 反向句也写一条,验证引擎的自动翻转
        lines.append("%s\t是\t%s的省会" % (capital, name))
        n += 5
    with io.open(OUT_PROVINCES, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("省级行政区事实: %d 条(%d 个) -> %s" % (n, len(PROVINCES), os.path.basename(OUT_PROVINCES)))
    return n


def main():
    only = None
    if "--only" in sys.argv:
        i = sys.argv.index("--only")
        if i + 1 < len(sys.argv):
            only = sys.argv[i + 1]
    total = 0
    if only in (None, "lexicon"):
        total += gen_lexicon()
    if only in (None, "regions"):
        total += gen_regions()
    if only in (None, "provinces"):
        total += gen_provinces()
    print("-" * 50)
    print("确定性生成合计: %d 条" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
