# -*- coding: utf-8 -*-
"""词法层:用本机已有的 jieba 词典(349,046 行,带词性标注)给引擎加"词感"。

为什么需要它
------------
2011 年 kiki 卡片上写着"多音节分辨"。逻辑引擎原本只认得事实库里 161 条手工基座里的
实体,一旦用户提到别的词就完全没反应。jieba 自带的 dict.txt 是一份真实的中文词表:

    AT&T 3 nz          # nz = 其他专名
    北京 1234 ns       # ns = 地名
    kiki 5 nr        # nr = 人名

词条格式是「词 词频 词性」。本模块把它按词性归类加载,提供:

    exists(word)       这个词是不是中文里真实存在的词(抗错字/抗乱答)
    kind_of(word)      它是什么类型(地名/人名/专名/普通词)
    all_of(kind)       某个类型的全部词
    suggest(word)      拼错/听错时的近似词建议(基于拼音)

注意:这是**词表**,不是知识库。它让引擎"认识词",但不提供事实。
事实仍然只能来自事实库(seed/taught/import),这一点不做任何混淆。
"""
import os
import re
import threading

# 词性 -> 可读类型(只挑和"实体识别"相关的)
POS_KIND = {
    "ns": "地名", "nr": "人名", "nrt": "人名", "nz": "专名",
    "nt": "机构", "n": "名词", "v": "动词", "a": "形容词",
    "m": "数词", "mq": "量词", "t": "时间", "f": "方位",
}
# 引擎关心的"实体类"词性(用于实体识别)
ENTITY_POS = ("ns", "nr", "nrt", "nz", "nt")

_DICT_CANDIDATES = [
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".venv", "Lib", "site-packages", "jieba", "dict.txt"),
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "jieba_dict.txt"),
]
CACHE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lexicon_cache.py")


class Lexicon:
    """jieba 词典的词法视图(惰性加载 + 磁盘缓存)。"""

    def __init__(self, dict_path=None):
        self._lock = threading.Lock()
        self._words = {}          # 词 -> 词性
        self._by_kind = {}        # 词性 -> [词, ...]
        self._loaded = False
        self._dict_path = dict_path or self._find_dict()

    @staticmethod
    def _find_dict():
        for p in _DICT_CANDIDATES:
            p = os.path.abspath(p)
            if os.path.exists(p):
                return p
        for root in ("C:/Users",):
            pass
        return None

    # ---- 加载 ----
    def load(self):
        """加载词典(优先磁盘缓存,否则解析 dict.txt 并写缓存)。"""
        with self._lock:
            if self._loaded:
                return self
            if self._try_cache():
                self._loaded = True
                return self
            self._parse()
            self._write_cache()
            self._loaded = True
            return self

    def _parse(self):
        if not self._dict_path or not os.path.exists(self._dict_path):
            return
        words, by_kind = {}, {}
        with open(self._dict_path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.rstrip("\n").split(" ")
                if len(parts) < 3:
                    continue
                w, _freq, pos = parts[0], parts[1], parts[2]
                if not w:
                    continue
                words[w] = pos
                if pos in ENTITY_POS or pos in POS_KIND:
                    by_kind.setdefault(pos, []).append(w)
        self._words, self._by_kind = words, by_kind

    def _try_cache(self):
        """读磁盘缓存(避免每次启动都解析 349k 行)。"""
        if not os.path.exists(CACHE_FILE):
            return False
        try:
            ns = {}
            exec(compile(open(CACHE_FILE, encoding="utf-8").read(), CACHE_FILE, "exec"), ns)
            words = ns.get("WORDS") or {}
            if not words:
                return False
            self._words = words
            self._by_kind = ns.get("BY_KIND") or {}
            return True
        except Exception:
            return False

    def _write_cache(self):
        try:
            with open(CACHE_FILE, "w", encoding="utf-8") as f:
                f.write("# -*- coding: utf-8 -*-\n")
                f.write('"""自动生成的词法缓存(来自 jieba dict.txt),请勿手改。"""\n')
                f.write("WORDS = %r\n" % (self._words,))
                f.write("BY_KIND = %r\n" % (self._by_kind,))
        except Exception:
            pass  # 缓存写失败不影响功能

    # ---- 查询 ----
    def exists(self, word):
        self.load()
        return (word or "") in self._words

    def pos(self, word):
        self.load()
        return self._words.get(word or "")

    def kind_of(self, word):
        p = self.pos(word)
        return POS_KIND.get(p) if p else None

    def is_entity(self, word):
        """是不是"实体类"的词(地名/人名/专名/机构)。"""
        return self.pos(word) in ENTITY_POS

    def all_of(self, pos):
        self.load()
        return list(self._by_kind.get(pos, ()))

    def size(self):
        self.load()
        return len(self._words)

    def kinds(self):
        self.load()
        return {POS_KIND.get(k, k): len(v) for k, v in self._by_kind.items()}

    # ---- 近似建议(抗听错/打错) ----
    def pinyin_of(self, word):
        """词 -> 拼音(带声调数字,多音字取第一个读音)。查不到的字用'?'占位。"""
        self.load()
        table = _load_pinyin()
        out = []
        for ch in (word or ""):
            py = table.get(ch)
            if not py:
                out.append("?")
                continue
            out.append(py.split(" ")[0])  # 多音字取首选
        return out

    @staticmethod
    def _strip_tone(syllable):
        """mo2 -> mo;声调无关比对用。"""
        return re.sub(r"\d+$", "", syllable or "")

    def homophones(self, word, limit=5):
        """找同音词(声调无关),比如 kiki -> kiki。

        实现:把词法层按"无声调拼音串"建一次倒排索引(惰性、只建一次)。
        """
        self.load()
        if not word or word in self._words:
            return []
        self._ensure_pinyin_index()
        key = "".join(self._strip_tone(s) for s in self.pinyin_of(word))
        if "?" in key:
            return []
        return self._py_index.get(key, [])[:limit]

    def _ensure_pinyin_index(self):
        if getattr(self, "_py_index", None) is not None:
            return
        table = _load_pinyin()
        idx = {}
        # 只为"词长 2-6"的词建索引,控制内存(短词最常用于实体/名字)
        for w in self._words:
            if not (2 <= len(w) <= 6):
                continue
            key = "".join(self._strip_tone(table.get(ch, "?").split(" ")[0]) for ch in w)
            if "?" in key:
                continue
            idx.setdefault(key, []).append(w)
        self._py_index = idx

    def suggest(self, word, limit=5):
        """给一个词找近似候选,按可信度排序:

        1. 同音词(kiki -> kiki)——语音识别最典型的错误
        2. 互为子串
        3. 同长度且只差一个字(听错一个音节)
        """
        self.load()
        w = (word or "").strip()
        if not w or w in self._words:
            return []
        out = []
        for cand in self.homophones(w, limit):
            if cand not in out:
                out.append(cand)
        py_w = "".join(self._strip_tone(s) for s in self.pinyin_of(w))
        for cand in self._words:
            if len(out) >= limit:
                break
            if cand in out or len(cand) < 2:
                continue
            if cand in w or w in cand:
                out.append(cand)
                continue
            if abs(len(cand) - len(w)) <= 1:
                py_c = "".join(self._strip_tone(s) for s in self.pinyin_of(cand))
                if "?" not in py_w and py_c == py_w:
                    out.append(cand)
                elif len(cand) == len(w) and sum(1 for a, b in zip(cand, w) if a != b) == 1:
                    out.append(cand)
            if len(out) >= limit * 4:
                break
        return out[:limit]


_default = None
_lock = threading.Lock()
_pinyin_cache = None


def _load_pinyin():
    """加载拼音表(pinyin_cache.py,由 build_pinyin.py 从 Unihan 生成)。"""
    global _pinyin_cache
    if _pinyin_cache is None:
        try:
            from pinyin_cache import PINYIN
            _pinyin_cache = PINYIN
        except Exception:
            _pinyin_cache = {}
    return _pinyin_cache


def get_lexicon():
    """全局单例(首次调用会解析词典,约 1-2 秒;之后走缓存)。"""
    global _default
    with _lock:
        if _default is None:
            _default = Lexicon().load()
    return _default


def main():
    """自检:打印词表规模与各类实体数量。"""
    lx = get_lexicon()
    print("词典文件:", lx._dict_path)
    print("词条总数:", lx.size())
    k = lx.kinds()
    print("按词性统计:")
    for name, n in sorted(k.items(), key=lambda x: -x[1]):
        print("   %-6s %d" % (name, n))
    print("抽检:")
    for w in ("kiki", "北京", "中国", "卡卡", "kiki", "喵喵喵喵"):
        print("   %-8s exists=%-5s kind=%s" % (w, lx.exists(w), lx.kind_of(w)))
    print("拼音(kiki):", lx.pinyin_of("kiki"))
    for w in ("kiki", "kiki", "北京", ""):
        print("   近似建议(%-6s) -> %s" % (w, lx.suggest(w)))


if __name__ == "__main__":
    main()
