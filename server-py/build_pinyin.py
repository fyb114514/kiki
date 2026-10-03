# -*- coding: utf-8 -*-
"""构建拼音表:从 Unihan_Readings.txt 抽取 kMandarin,生成 pinyin_cache.py。

这是**可复现的构建步骤**,不是一次性命令。数据流向:

    Unihan.zip (Unicode 官方读音数据, 约 8MB)
        └─ Unihan_Readings.txt  里的 kMandarin 字段
             └─ 只保留词法层里出现过的汉字(约 2 万字)
                  └─ pinyin_cache.py  {汉字: "pin1yin1"}(声调最后一位,便于比对)

为什么需要拼音
--------------
2011 年 kiki 卡片写着"多音节分辨":语音识别最容易错的就是同音字
(kiki / kiki / kiki)。靠手写规则表只能覆盖几十个词,
有拼音表之后就能对**任意词**做同音/近音匹配。

用法:
    python build_pinyin.py                 # 自动找 %TEMP%\\unihan_x\\Unihan_Readings.txt
    python build_pinyin.py <Unihan_Readings.txt 路径>
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_FILE = os.path.join(HERE, "pinyin_cache.py")


def find_unihan():
    """找 Unihan_Readings.txt:显式参数 > 临时目录常见位置。"""
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        return sys.argv[1]
    for cand in (
        os.path.join(os.environ.get("TEMP", ""), "unihan_x", "Unihan_Readings.txt"),
        os.path.join(os.environ.get("TMP", ""), "unihan_x", "Unihan_Readings.txt"),
        os.path.join(HERE, "Unihan_Readings.txt"),
    ):
        if cand and os.path.exists(cand):
            return cand
    return None


def parse_unihan(path, keep_chars=None):
    """解析 kMandarin 字段 -> {汉字: "pin1yin1"}。

    一个汉字可能有多个读音(如「行」xing2/hang2),全部收下,用空格分隔。
    keep_chars 非空时只保留这些字(取决于词法层覆盖范围)。
    """
    out = {}
    line_re = re.compile(r"^U\+([0-9A-F]+)\s+(kMandarin|kXHC1983)\s+(.+)$")
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.startswith("U+"):
                continue
            m = line_re.match(line.rstrip("\n"))
            if not m:
                continue
            code, field, value = int(m.group(1), 16), m.group(2), m.group(3).strip()
            if field != "kMandarin":
                continue
            try:
                ch = chr(code)
            except ValueError:
                continue
            if keep_chars is not None and ch not in keep_chars:
                continue
            # kMandarin 形如 "mó" 或 "mó xíng";要把声调符号转成数字后缀
            readings = []
            for r in value.split():
                r = r.strip()
                if not r:
                    continue
                readings.append(_tone_to_digit(r))
            if readings and ch not in out:
                out[ch] = " ".join(readings)
    return out


# 带声调符号的元音 -> (无符号元音, 声调数字)
_TONE_MAP = {
    "ā": ("a", 1), "á": ("a", 2), "ǎ": ("a", 3), "à": ("a", 4),
    "ē": ("e", 1), "é": ("e", 2), "ě": ("e", 3), "è": ("e", 4),
    "ī": ("i", 1), "í": ("i", 2), "ǐ": ("i", 3), "ì": ("i", 4),
    "ō": ("o", 1), "ó": ("o", 2), "ǒ": ("o", 3), "ò": ("o", 4),
    "ū": ("u", 1), "ú": ("u", 2), "ǔ": ("u", 3), "ù": ("u", 4),
    "ǖ": ("v", 1), "ǘ": ("v", 2), "ǚ": ("v", 3), "ǜ": ("v", 4), "ü": ("v", 0),
    "ń": ("n", 2), "ň": ("n", 3), "ǹ": ("n", 4), "ḿ": ("m", 2),
}
_PLAIN = {"a": "a", "e": "e", "i": "i", "o": "o", "u": "u", "v": "v", "n": "n", "m": "m"}


def _tone_to_digit(reading):
    """mó -> mo2;mā -> ma1;轻声 -> 无数字后缀。"""
    tone = 0
    letters = []
    for ch in reading.lower():
        if ch in _TONE_MAP:
            plain, t = _TONE_MAP[ch]
            letters.append(plain)
            if t:
                tone = t
        elif ch in _PLAIN or ch.isalpha():
            letters.append(ch)
    base = "".join(letters)
    return "%s%d" % (base, tone) if tone else base


def main():
    path = find_unihan()
    if not path:
        print("找不到 Unihan_Readings.txt。请先下载解压:")
        print("  https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip")
        return 1
    print("读音数据:", path)

    # 只保留词法层里出现过的字,避免生成几万条无关数据
    try:
        import lexicon
        lx = lexicon.get_lexicon()
        keep = set()
        for w in lx._words:
            keep.update(w)
        print("词法层覆盖汉字数:", len(keep))
    except Exception as e:
        print("未能加载词法层(%s),改为全量导出" % e)
        keep = None

    table = parse_unihan(path, keep)
    print("生成拼音条目:", len(table))
    with open(CACHE_FILE, "w", encoding="utf-8") as f:
        f.write("# -*- coding: utf-8 -*-\n")
        f.write('"""自动生成的拼音表(来自 Unicode Unihan kMandarin),请勿手改。\\n\\n')
        f.write("重新生成: python build_pinyin.py <Unihan_Readings.txt>\\n")
        f.write('"""\n')
        f.write("PINYIN = %r\n" % (table,))
    print("已写入:", CACHE_FILE, "(%d KB)" % (os.path.getsize(CACHE_FILE) // 1024))
    # 抽检
    for ch in "kiki北京中国卡":
        print("   %s -> %s" % (ch, table.get(ch, "(无)")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
