# -*- coding: utf-8 -*-
"""kiki 逻辑与记忆引擎 —— 本地、离线、可推理的对话内核。

设计目标(对标 2011 年 kiki 卡片上自述的能力):
    逻辑推理      -> 传递推理(爸爸的爸爸=爷爷)+ 分类继承(卡卡是猫,猫是动物)
    多音节分辨    -> 同音/近音归一化(语音输入常见错字)
    中英文混合分辨 -> 中英混排分词 + 数字/单位归一化
    上下文理解    -> 代词(他/她/它/这个)消解 + 追问补主语
    主动记忆      -> 教过的事实落库持久化,下次启动仍在
    语言总结      -> 把"X是猫、猫是动物"压成一句可复述的总结

与 web-marisa 的关系:
    上游回复链路(精确匹配 -> 工具 -> 关键词重合)保持不变;
    本引擎作为最后一道兜底,只有当上游答不出(code=10001)时接手。
    所以它是"加一层脑子",不是替换。
"""
import json
import os
import re
import sqlite3

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kiki_brain.db")

# ---- 关系词法 ----
# 一个"关系"在中文里有很多说法,都归一到同一个规范关系名。
REL_ALIASES = {
    "是": ["是", "就是", "属于"],
    "有": ["有", "拥有", "具有"],
    "喜欢": ["喜欢", "最爱", "钟爱", "爱好"],
    "讨厌": ["讨厌", "不喜欢", "厌恶", "害怕"],
    "朋友": ["朋友", "好友", "好朋友", "死党"],
    "爸爸": ["爸爸", "父亲", "爸", "老爹", "爹"],
    "妈妈": ["妈妈", "母亲", "妈", "娘", "老妈"],
    "儿子": ["儿子", "孩子", "儿子"],
    "女儿": ["女儿"],
    "爷爷": ["爷爷", "祖父"],
    "奶奶": ["奶奶", "祖母"],
    "哥哥": ["哥哥", "兄长"],
    "弟弟": ["弟弟"],
    "姐姐": ["姐姐"],
    "妹妹": ["妹妹"],
    "妻子": ["妻子", "老婆", "爱人"],
    "丈夫": ["丈夫", "老公"],
    "住在": ["住在", "生活在", "位于", "在"],
    "会": ["会", "能", "可以", "能够"],
}
# 反向关系(问 B 的某个"上位关系"时,用 A 的顺向关系回答)
INVERSE_REL = {
    "爸爸": "儿子", "妈妈": "儿子", "儿子": "爸爸", "女儿": "爸爸",
    "爷爷": "孙子", "奶奶": "孙子", "哥哥": "弟弟", "弟弟": "哥哥",
    "姐姐": "妹妹", "妹妹": "姐姐", "丈夫": "妻子", "妻子": "丈夫",
}
# 传递链:(A -r1-> B) + (B -r2-> C)  =>  (A -结果-> C)
# 这就是卡片上说的"逻辑推理":不需要预先教过"爷爷",也能自己推出来。
TRANSITIVE_RULES = [
    ("爸爸", "爸爸", "爷爷", "爸爸的爸爸是爷爷"),
    ("妈妈", "妈妈", "外婆", "妈妈的妈妈是外婆"),
    ("爸爸", "妈妈", "奶奶", "爸爸的妈妈是奶奶"),
    ("妈妈", "爸爸", "外公", "妈妈的爸爸是外公"),
    ("爸爸", "妻子", "妈妈", "爸爸的妻子是妈妈"),
    ("妈妈", "丈夫", "爸爸", "妈妈的丈夫是爸爸"),
    ("儿子", "儿子", "孙子", "儿子的儿子是孙子"),
    ("朋友", "朋友", "朋友", "朋友的朋友也是朋友"),
]
# 反义关系:问"谁讨厌X"时可以用"喜欢"的反义回答
ANTONYM_REL = {"喜欢": "讨厌", "讨厌": "喜欢", "朋友": "敌人"}
# 分类继承:猫是动物 -> 卡卡是猫 -> 卡卡是动物(可多级)
INHERIT_REL = "是"
# 定义类问句里,"一个X"中的量词要忽略
MEASURE_WORDS = "个只条本张的头匹座朵把件位名门辆架"
# 中英混排 + 多音节:给常见英文/音译词做归一化
NORMALIZE_MAP = {
    "siri": "siri", "android": "安卓", "app": "应用", "ai": "人工智能",
    "cpu": "处理器", "gpu": "显卡", "pc": "电脑", "wifi": "无线网络",
    "ok": "好", "yes": "是", "no": "否",
}
# 近音/错字归一化(语音识别与输入法常见结果)
PHONETIC_FIX = {
    "卡卡西": "卡卡", "卡卡": "卡卡",
    "零梦": "", "林梦": "",
    "爱丽斯": "",
    "波利": "博丽",
}
PRONOUNS = ("他", "她", "它", "祂", "他们", "她们", "它们", "这个人", "那个", "这", "那")

# 关系链中每个环节的归一化词典(比 REL_ALIASES 更宽,专门给 "A的B的C" 用)
REL_WORDS = {
    "爸爸": "爸爸", "父亲": "爸爸", "爹": "爸爸",
    "妈妈": "妈妈", "母亲": "妈妈", "娘": "妈妈",
    "爷爷": "爷爷", "祖父": "爷爷", "奶奶": "奶奶", "祖母": "奶奶",
    "哥哥": "哥哥", "弟弟": "弟弟", "姐姐": "姐姐", "妹妹": "妹妹",
    "儿子": "儿子", "女儿": "女儿", "孩子": "儿子",
    "丈夫": "丈夫", "老公": "丈夫", "妻子": "妻子", "老婆": "妻子",
    "朋友": "朋友", "好友": "朋友",
}

# 不能当成"分类"处理的关系(否则会生成"北京的一种"这种病句)
NON_ISA_RELATIONS = {
    "首都", "省会", "符号", "人口", "面积", "沸点", "冰点", "光速",
    "住在", "位于", "绕着", "有", "会", "喜欢", "妻子", "丈夫", "朋友",
}


def _is_isa(rel):
    """该关系是否表示"属于某类别"(可作分类继承用)。"""
    return rel in ("是", "属于") 
INVERSE_DESC = {
    "是": "的一种",
    "有": "属于",
    "喜欢": "被喜欢",
    "会": "的能力属于",
    "住在": "的居民包括",
    "朋友": "的朋友",
    "爸爸": "的儿子/女儿",
    "妈妈": "的儿子/女儿",
    "儿子": "的父亲/母亲",
    "女儿": "的父亲/母亲",
    "妻子": "的丈夫",
    "丈夫": "的妻子",
}
# 无法自然反述的关系直接跳过
SKIP_REVERSE = {"颜色", "叫声", "口头禅", "首都", "绕着", "在", "走得", "需要"}
WORD_TO_REL = {
    "喜欢": "喜欢", "爱": "喜欢", "最爱": "喜欢", "爱好": "喜欢",
    "颜色": "颜色", "叫声": "叫声", "口头禅": "口头禅", "出名": None,
    "数值": "数值", "内容": "内容", "高度": "高度", "长度": "长度",
    "面积": "面积", "人口": "人口", "重量": "重量", "温度": "温度",
}
# 反问句式:"谁发明了电话" 事实存的是 "贝尔 发明 电话" —— 方向相反,要反查
P_WHO_DID = re.compile(r"^(?:谁|是)?(.{1,6}?)(?:了|的)(.{1,20}?)[呢？?]*$")
# 度量问句:"长江有多长" -> 查"长度"关系;"珠峰有多高" -> 查"高度"
MEASURE_REL = {
    "多长": "长度", "多高": "高度", "多大": "面积", "多重": "重量",
    "多久": "时长", "多远": "距离", "多深": "深度", "多宽": "宽度",
    "多厚": "厚度",
}
P_MEASURE = re.compile(r"(.{1,20}?)有多(长|高|大|重|久|远|深|宽|厚)[呢？?]*$")
# "光速是多少 / 圆周率是多少" —— 值类问句(必须在 P_YESNO 之前处理,否则被切成 (光速, 多少))
P_VALUE = re.compile(r"(.{1,20}?)(?:是|等于)(?:多少|几|多大|什么值)[呢？?]*$")
# "地球有几颗卫星 / X有多少个Y" —— 数量问句(允许多字量词与名词)
P_COUNT2 = re.compile(r"(.{1,20}?)(?:一共)?(?:有|长了)(?:几|多少)(.{1,10})$")
# "谁发明了电话" —— 关系 + 了 + 宾语
P_WHO_DID = re.compile(r"^(?:谁|是哪位|是哪个人)(.{2,6}?)(?:了|的)?(.{1,20}?)[呢？?]*$")

# ---- 意图识别模式(全部 re.search,不依赖字符串开头,避免漏匹配) ----
P_COUNT = re.compile(r"(.+?)(?:一共)?(?:有|长)几(?:个|只|条|张|本|头|匹|件|位|名|门|辆|架|朵|把)?(.)[呢？?]*$")
# 词形差异(叠字/后缀)归一:狗狗->狗,小黄狗->狗
ENTITY_SUFFIX = ("狗狗", "猫猫", "鸟鸟", "车车")
P_FOOD = re.compile(r"(.+?)(?:最)?(?:喜欢|爱)(?:吃|喝|玩)(?:什么|啥)[呢？?]*$")
P_LIKE = re.compile(r"(.+?)(?:最)?喜欢什么[呢？?]*$")
P_CAN = re.compile(r"(.+?)(?:会|能|可以)(?:什么|啥|干什么|做什么)[呢？?]*$")
# attr 的后缀要求非可选:否则 "小明的爸爸的爸爸是谁" 会被切错
# 问句尾巴(是多少/是什么…)允许被剥掉,这样"铁的原子序数是多少"也能命中
P_ATTR = re.compile(r"(.+?)的([^的是什么谁哪]+?)(?:是多少|是几|是什么|是啥|是哪个|是谁|叫什么|叫啥|呢|？|\?)$")
P_WHATIS = re.compile(r"(.+?)(?:是|属于)(?:什么|啥|哪种|哪类|什么样的)(?:东西|动物|人|植物)?[呢？?]*$")
P_WHATIS2 = re.compile(r"^什么是(.+?)[呢？?]*$")
P_WHOIS = re.compile(r"(.+?)(?:是|叫)(?:谁|啥人|什么人)[呢？?]*$")
P_WHERE = re.compile(r"(.+?)(?:住在|生活在|位于)(?:哪里|哪儿|什么地方|哪)?[呢？?]*$")
P_WHERE2 = re.compile(r"(.+?)(?:在|去)(?:哪|哪里|哪儿)[呢？?]*$")
# "中国的首都是哪里 / 法国的首都是哪个城市" —— 关系词 + 哪里
P_REL_WHERE = re.compile(r"(.+?)(?:的)?([^的是什么谁哪]{1,8}?)是(?:哪里|哪儿|哪个城市|什么地方)[呢？?]*$")
# "法国首都" —— 关系词直接跟在实体后面(口语省略"的")
# 关系词清单**从事实库动态构建**(见 _rel_bare_pattern),所以导入任何新关系都自动可查。
# 这里只列几个内置的短关系兜底,避免事实库为空时完全失效。
P_REL_BARE_BUILTIN = re.compile(r"^(.{1,20}?)(首都|省会|简称|符号|人口|面积|代表作|代码)$")
_ent_pat_cache = {"pattern": None, "stamp": None}


def _entity_pattern():
    """构建"已知实体"的正则(来自事实库,最长优先,带缓存)。

    为什么需要它:`_norm_entity("秦")` 会把"秦"当成可能是"秦始皇陵…"的简写
    (拼音/包含匹配),于是"秦朝建立于哪一年"被切成 ('秦','朝建立于')。
    正确做法是**先**用事实库里的已知实体做最长匹配,再谈纠错。
    """
    names = sorted(_load_entities(), key=len, reverse=True)
    stamp = tuple(names[:200]) + (len(names),)
    if _ent_pat_cache["stamp"] == stamp and _ent_pat_cache["pattern"] is not None:
        return _ent_pat_cache["pattern"]
    if not names:
        pat = re.compile(r"(.{1,20}?)")
    else:
        pat = re.compile(r"(%s)" % "|".join(re.escape(n) for n in names[:4000]))
    _ent_pat_cache["pattern"] = pat
    _ent_pat_cache["stamp"] = stamp
    return pat


def _load_entities():
    """事实库里出现过的实体名(主语+宾语)。"""
    try:
        store = BrainStore()
        with store._conn() as c:
            rows = c.execute("SELECT DISTINCT s FROM facts UNION SELECT DISTINCT o FROM facts").fetchall()
        return [r[0] for r in rows if r[0]]
    except Exception:
        return []


def invalidate_entity_pattern():
    _ent_pat_cache["pattern"] = None
    _ent_pat_cache["stamp"] = None


_rel_bare_cache = {"pattern": None, "stamp": None}


def split_entity_rel(text, rels):
    """把 "秦朝建立于" 切成 (实体, 关系)。

    策略:对每个关系词,在文本里找它的位置;要求**关系词之前的部分恰好是已知实体**
    (最长优先)。找不到就回退到"最长已知实体前缀 + 剩余部分当关系词"。
    """
    t = (text or "").strip()
    if not t:
        return t, ""
    known = set(_load_entities())
    # 1) 已知实体前缀(最长优先)
    try:
        m = _entity_pattern().match(t)
        if m and m.group(1) in known and len(m.group(1)) < len(t):
            return m.group(1), t[len(m.group(1)):]
    except Exception:
        pass
    # 2) 关系词位置切分:关系词前是已知实体
    for rel in sorted(rels or [], key=len, reverse=True):
        idx = t.find(rel)
        if idx > 0:
            head = t[:idx]
            if head in known:
                return head, rel
    # 3) 兜底:按"最长已知实体"切,没有就用整串当实体
    best = ""
    for name in known:
        if t.startswith(name) and len(name) > len(best):
            best = name
    if best and len(best) < len(t):
        return best, t[len(best):]
    return t, ""


def _rel_bare_pattern():
    """构建"实体+关系词"裸问句的正则(关系词来自事实库,带缓存)。

    例:氧原子序数 / 山东省简称 / 美国代码 / 李白代表作
    长关系词优先,避免"序数"抢先匹配掉"原子序数"。
    """
    rels = sorted(_load_store_relations(), key=len, reverse=True)
    stamp = tuple(rels)
    if _rel_bare_cache["stamp"] == stamp and _rel_bare_cache["pattern"] is not None:
        return _rel_bare_cache["pattern"]
    words = [r for r in rels if len(r) >= 2 and r not in ("是", "属于")]
    if not words:
        pat = P_REL_BARE_BUILTIN
    else:
        pat = re.compile(r"^(.{1,20}?)(%s)$" % "|".join(re.escape(w) for w in words))
    _rel_bare_cache["pattern"] = pat
    _rel_bare_cache["stamp"] = stamp
    return pat


def invalidate_rel_bare():
    _rel_bare_cache["pattern"] = None
    _rel_bare_cache["stamp"] = None
# "一小时是几分钟 / 一米是多少厘米" —— 换算问句
P_CONVERT = re.compile(r"^(.{1,20}?)是(?:多少|几)(.{1,6}?)[呢？?]*$")
# "秦朝建立于哪一年" —— 历史类:关系词 + 哪一年/什么时候
P_REL_WHEN = re.compile(r"(.{1,20}?)(?:的)?([^的是什么谁哪]{1,9}?)(?:是哪一年|是哪年|是哪一天|是什么时候|是何时|哪一年|哪年|什么时候|何时)[呢？?]*$")


def _count_unit(value, unit):
    """从"六十分钟"里数出"分钟"的个数 -> 60;"二十四小时"->24;数不出返回 None。

    支持中文数字与阿拉伯数字,并容忍"零/一百"这类写法。
    """
    if not value or not unit:
        return None
    idx = value.find(unit)
    if idx <= 0:
        return None
    prefix = value[:idx]
    m = re.search(r"(\d+(?:\.\d+)?)$", prefix)
    if m:
        try:
            return float(m.group(1))
        except ValueError:
            return None
    return _cn_number(prefix)


_CN_DIGITS = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
              "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
_CN_UNITS = {"十": 10, "百": 100, "千": 1000, "万": 10000, "亿": 100000000}


def _cn_number(text):
    """中文数字 -> 数值:六十 -> 60,二十四 -> 24,一百 -> 100。失败返回 None。"""
    if not text:
        return None
    total, section, num = 0, 0, 0
    seen = False
    for ch in text:
        if ch in _CN_DIGITS:
            num = _CN_DIGITS[ch]
            seen = True
        elif ch in _CN_UNITS:
            unit = _CN_UNITS[ch]
            if unit >= 10000:
                section = (section + num) * unit
                total += section
                section, num = 0, 0
            else:
                if num == 0:
                    num = 1  # "十五" = 15
                section += num * unit
                num = 0
            seen = True
        else:
            return None
    if not seen:
        return None
    return total + section + num
P_YESNO = re.compile(r"(.+?)(?:是不是|是|会|能|可以|属于)([^吗么嘛？?]+?)(?:吗|么|嘛|吧)?[？?]*$")
P_RELBETWEEN = re.compile(r"(.+?)(?:和|跟|与)([^是]+?)(?:是)?(?:什么)?关系[呢？?]*$")

# 疑问句尾巴上的语气词,抓实体时要剥掉(避免把 "卡卡呢" 当成实体)
TAIL_WORDS = "呢啊呀哦吧嘛"
LY_TAILS = ("怎么样", "如何")

# 反义对:自定义事实里出现相反描述时不能误判为"对"
ANTONYM_PAIRS = [
    ("快", "慢"), ("大", "小"), ("多", "少"), ("高", "矮"), ("高", "低"),
    ("长", "短"), ("胖", "瘦"), ("冷", "热"), ("新", "旧"), ("早", "晚"),
    ("会", "不会"), ("有", "没有"), ("喜欢", "讨厌"),
]


def contradicts(claim, fact):
    """claim 与事实 fact 是否语义相反(只查显式反义对,避免过度推断)。"""
    c, f = (claim or ""), (fact or "")
    for x, y in ANTONYM_PAIRS:
        if (x in c and y in f) or (y in c and x in f):
            return True
    return False


DOWN_REL = {
    "爸爸": "子女", "妈妈": "子女", "爷爷": "孙辈", "奶奶": "孙辈",
    "哥哥": "弟弟/妹妹", "弟弟": "哥哥/姐姐", "姐姐": "弟弟/妹妹", "妹妹": "哥哥/姐姐",
    "丈夫": "妻子", "妻子": "丈夫",
}


def down_rel_name(parent_rel):
    """父->子 方向上的称谓(用于把上跳边反向描述)。"""
    return DOWN_REL.get(parent_rel, "子女")


# 亲属称谓表:按路径的(上跳数, 下跳数)映射关系名
KIN_TERMS = {
    (1, 0): "父母", (2, 0): "祖父母", (3, 0): "曾祖父母",
    (0, 1): "子女", (0, 2): "孙辈",
    (1, 1): "兄弟姐妹", (2, 1): "叔伯/舅舅/姑姑/姨", (1, 2): "侄辈",
    (2, 2): "堂表兄弟姐妹", (3, 1): "曾祖辈的兄弟姐妹",
}


def strip_tail(text):
    """剥掉实体尾部的语气词/问号,避免 "卡卡呢" 被当成实体。"""
    t = (text or "").strip()
    while t and t[-1] in TAIL_WORDS + "？?！!。，,":
        t = t[:-1]
    for tail in LY_TAILS:
        if t.endswith(tail):
            t = t[: -len(tail)]
    return t.strip()


# ============================ 存储层 ============================
class BrainStore:
    """事实库(持久化,= 卡片上说的"记忆数据库")。"""

    def __init__(self, path=DB_PATH):
        self.path = path
        self._init_db()

    def _conn(self):
        c = sqlite3.connect(self.path)
        c.row_factory = sqlite3.Row
        return c

    def _init_db(self):
        with self._conn() as c:
            c.execute(
                """CREATE TABLE IF NOT EXISTS facts(
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       s TEXT NOT NULL, rel TEXT NOT NULL, o TEXT NOT NULL,
                       source TEXT DEFAULT 'taught', created_at TEXT, UNIQUE(s, rel, o))"""
            )
            c.execute(
                """CREATE TABLE IF NOT EXISTS rules(
                       name TEXT PRIMARY KEY, expr TEXT, descr TEXT)"""
            )
            c.execute("CREATE INDEX IF NOT EXISTS idx_facts_s ON facts(s)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_facts_o ON facts(o)")

    def add(self, s, rel, o, source="taught"):
        with self._conn() as c:
            c.execute(
                "INSERT OR IGNORE INTO facts(s,rel,o,source,created_at) VALUES(?,?,?,?,datetime('now'))",
                (s, rel, o, source),
            )

    def add_many(self, triples, source="seed"):
        with self._conn() as c:
            c.executemany(
                "INSERT OR IGNORE INTO facts(s,rel,o,source,created_at) VALUES(?,?,?,?,datetime('now'))",
                [(s, r, o, source) for s, r, o in triples],
            )

    def forward(self, s, rel=None):
        """查 s 的所有/指定关系。统一返回 [(关系, 宾语), ...]。"""
        with self._conn() as c:
            if rel:
                rows = c.execute("SELECT rel,o FROM facts WHERE s=? AND rel=?", (s, rel)).fetchall()
            else:
                rows = c.execute("SELECT rel,o FROM facts WHERE s=?", (s,)).fetchall()
            return [(r["rel"], r["o"]) for r in rows] if rows else []

    def reverse(self, o, rel=None):
        """反查:谁和 o 有某关系。统一返回 [(主语, 关系), ...]。"""
        with self._conn() as c:
            if rel:
                rows = c.execute("SELECT s,rel FROM facts WHERE o=? AND rel=?", (o, rel)).fetchall()
            else:
                rows = c.execute("SELECT s,rel FROM facts WHERE o=?", (o,)).fetchall()
            return [(r["s"], r["rel"]) for r in rows] if rows else []

    def count(self):
        with self._conn() as c:
            return c.execute("SELECT COUNT(*) FROM facts").fetchone()[0]

    def all_facts(self):
        with self._conn() as c:
            return [(r["s"], r["rel"], r["o"]) for r in c.execute("SELECT s,rel,o FROM facts")]

    def forget(self, s=None, rel=None, o=None):
        q, args = "DELETE FROM facts WHERE 1=1", []
        if s:
            q += " AND s=?"; args.append(s)
        if rel:
            q += " AND rel=?"; args.append(rel)
        if o:
            q += " AND o=?"; args.append(o)
        with self._conn() as c:
            cur = c.execute(q, args)
            return cur.rowcount


# ============================ 文本处理 ============================
# 疑问句特征:出现这些就绝不当成"教学句"记住
QUESTION_MARKERS = (
    "什么", "谁", "哪", "吗", "呢", "几", "多少", "多长", "多高", "多大", "多重",
    "多久", "多远", "怎么样", "如何", "为什么", "是不是", "有没有", "能不能",
    "会不会", "可不可以", "请问", "?", "？",
)


def _is_question(text):
    """判断一句话是不是疑问句(用于避免把提问误当教学记住)。"""
    t = text or ""
    return any(m in t for m in QUESTION_MARKERS)


def normalize(text):
    """中英混排 + 近音错字 + 数字/标点归一化。"""
    t = (text or "").strip()
    t = re.sub(r"[\s\u3000]+", "", t)
    # 中英混排:英文词统一小写,再按词典换成中文(子串替换,不依赖 \b 边界)
    low = t.lower()
    for en, zh in NORMALIZE_MAP.items():
        if en != zh and en in low:
            low = low.replace(en, zh)
    t = low
    # 近音/错字归一化(语音识别与输入法常见结果)
    for wrong, right in PHONETIC_FIX.items():
        if wrong in t and wrong != right:
            t = t.replace(wrong, right)
    # 全角数字/英文 -> 半角
    t = "".join(chr(ord(ch) - 0xFEE0) if 0xFF01 <= ord(ch) <= 0xFF5E else ch for ch in t)
    return t.strip("。！？!?~～,，、 ")


# 预展开的别名表:按别名长度倒序,保证 "妈妈" 不会被 "爸爸" 抢先匹配。
# (bug 教训:原实现按关系遍历,("张伟","妈妈","李秀兰") 会被误判成 爸爸=李秀兰)
_REL_TOKENS = sorted(
    [(alias, canon) for canon, aliases in REL_ALIASES.items() for alias in aliases],
    key=lambda x: -len(x[0]),
)
# 取别名首字做快速候选筛选(可选优化),同时保证"是"这类短别名最后才试
_REL_ONLY = sorted(_REL_TOKENS, key=lambda x: (-len(x[0]), x[0] != "是"))


def _first_chars():
    return {a[0] for a, _ in _REL_TOKENS}


_REL_FIRST = _first_chars()


def to_triple(sentence):
    """把一句中文拆成 (主语, 关系, 宾语);拆不出返回 None。

    支持:卡卡是猫 / 卡卡喜欢吃鱼 / 小明的爸爸是张三 / 猫是动物
    """
    t = normalize(sentence)
    t = re.sub(r"^教[你她他]?[一]?[句个]?[:：]?", "", t)
    # 1) 先整体匹配:"小明的爸爸是张三" 要整体命中,否则会拆成 (小明, 爸爸, 是张三)
    tri = _match_triple(t)
    if tri:
        return _normalize_triple(tri)
    # 2) 再把开头的"是"去掉重试:"小明是猫" 这类系表句(少了"是"的别名边界)
    t2 = re.sub(r"^是", "", t)
    if t2 != t:
        tri = _match_triple(t2)
        if tri:
            return _normalize_triple(tri)
    return None


# 反向表达:宾语形如"<主体>的首都 / 的爸爸 / 的符号"时,翻转成 (主体, 关系, 主语)
_REV_OBJ = re.compile(r"^(.+?)的([^的]{1,6})$")


def _normalize_triple(tri):
    """把反向句规整成正向三元组,这样语料不必正反各写一遍。

        北京是中国的首都   ->  (中国, 首都, 北京)
        小明是小红的爸爸   ->  (小红, 爸爸, 小明)
        铁是金属           ->  原样返回(宾语不是"X的Y"结构)

    这里不能依赖 _REL_TOKENS(会被"爸"这种短别名抢先匹配),
    而是直接按"的"切分宾语:最后一个"的"右边就是关系词。
    """
    s, rel, o = tri
    if rel not in ("是", "就是", "属于"):
        return tri
    if "的" not in o:
        return tri
    subject, _, relword = o.rpartition("的")
    subject, relword = subject.strip(), relword.strip()
    if not subject or not relword or subject == s:
        return tri
    # 关系词要能归一(首都/爸爸/妈妈/符号…)
    canon = None
    for a, c in _REL_TOKENS:
        if relword == a:
            canon = c
            break
    if canon is None:
        canon = relword  # 新关系词(如"符号""首都")直接沿用,引擎按字面存
    return (subject, canon, s)


def _match_triple(t):
    """返回 (主语, 关系, 宾语);不匹配返回 None。

    三轮匹配,顺序从"最像陈述句"到"最像系表句":
      第 1 轮:主语侧是"X的Y"结构 -> 主语取 X、关系取 Y
              "小明的爸爸是张三" -> ('小明','爸爸','张三')
              "中国的首都是北京" -> ('中国','首都','北京')
      第 2 轮:普通关系词匹配(排除"是")
              "猫喜欢吃鱼" -> ('猫','喜欢','吃鱼')
      第 3 轮:只有前两轮都没中,才把"是/就是/属于"当关系词
              "卡卡是猫" -> ('卡卡','是','猫')
    宾语若是"<主体>的<关系>"(如"北京是中国的首都"),由 _normalize_triple 翻转。
    """
    rounds = [
        _SUBJ_DE_PATTERNS,
        [(a, c) for a, c in _REL_TOKENS if a not in ("是", "就是", "属于")],
        [(a, c) for a, c in _REL_TOKENS if a in ("是", "就是", "属于")],
    ]
    for idx, tokens in enumerate(rounds):
        for alias, canon in tokens:
            if idx == 0:
                m = alias  # 第 1 轮里 alias 位置放的是预编译正则
                g = m.match(t)
                if not g:
                    continue
                sub, relword, right = g.group(1).strip(), g.group(2).strip(), g.group(3).strip()
                if not sub or not right or not relword:
                    continue
                # 关系词归一
                c2 = None
                for a2, cc in _REL_TOKENS:
                    if relword == a2:
                        c2 = cc
                        break
                return (sub, c2 or relword, _strip_copula(right))
            pat = re.compile(r"^(.{1,20}?)(?:的)?" + re.escape(alias) + r"(.{1,30})$")
            m = pat.match(t)
            if not m:
                continue
            left, right = m.group(1).strip(), m.group(2).strip()
            if not left or not right or left == right:
                continue
            right = _strip_copula(right)
            if not right:
                continue
            if "的" in left:
                sub, _, relword = left.rpartition("的")
                if sub and relword:
                    for a2, c2 in _REL_TOKENS:
                        if relword == a2:
                            return (sub.strip(), c2, right)
            return (left, canon, right)
    return None


def _strip_copula(right):
    """剥掉宾语开头的系动词:"是张三" -> "张三"。"""
    for cop in ("是", "就是", "属于"):
        if right.startswith(cop) and len(right) > len(cop):
            return right[len(cop):].strip()
    return right


# 第 1 轮用:主语侧"X的Y 是/为 Z"结构,预编译(关系词位置先占位,统一用别名轮处理)
_SUBJ_DE_PATTERNS = [
    (re.compile(r"^(.{1,20}?)的(.{1,6}?)(?:是|就是|属于|为)(.{1,30})$"), None),
]


# ============================ 推理引擎 ============================
class KikiBrain:
    """事实库 + 推理 + 上下文。reply 链路把它当最后一道兜底。"""

    def __init__(self, store=None):
        self.store = store or BrainStore()
        self.session = {}  # key(uid/ip) -> {"last": 实体, "history": [...]}

    # ---------- 记忆 ----------
    def remember(self, sentence, source="taught"):
        """主动记忆:把一句话拆成事实存下来;拆不出返回 None。"""
        tri = to_triple(sentence)
        if not tri:
            return None
        self.store.add(tri[0], tri[1], tri[2], source)
        self.invalidate_entity_cache()  # 有新实体了,拼音/实体索引要重建
        invalidate_store_relations()    # 可能有新关系词
        return tri

    def teach_pair(self, keyword, answer):
        """上游"关键词`回答"的教学也喂给事实库(如果能拆成三元组)。"""
        tri = to_triple(answer) or to_triple(keyword)
        if tri:
            self.store.add(tri[0], tri[1], tri[2], "taught")
            self.invalidate_entity_cache()
            invalidate_store_relations()

    # ---------- 上下文 ----------
    def _ctx(self, key):
        return self.session.setdefault(key, {"last": None, "history": []})

    def _resolve(self, text, key):
        """代词消解 + 追问补主语。"""
        c = self._ctx(key)
        t = normalize(text)
        for p in PRONOUNS:
            if p in t and c["last"]:
                t = t.replace(p, c["last"])
        return t, c

    # ---------- 分类继承 ----------
    def _isa_chain(self, entity, depth=0, seen=None):
        """返回实体的所有类别(含多级):卡卡 -> 猫 -> 动物 -> 生物。"""
        seen = seen or set()
        if entity in seen or depth > 6:
            return []
        seen.add(entity)
        out = []
        for rel, obj in self.store.forward(entity, INHERIT_REL):
            if obj in seen:
                continue
            out.append(obj)
            out.extend(self._isa_chain(obj, depth + 1, seen))
        return out

    def _lookup(self, entity, rel):
        """查关系:直接事实 -> 分类继承 -> (上层类别) 事实。"""
        direct = [o for r, o in self.store.forward(entity, rel)]
        if direct:
            return direct, None
        # 继承:实体所属类别上查同一关系
        for cls in self._isa_chain(entity):
            got = [o for r, o in self.store.forward(cls, rel)]
            if got:
                return got, "%s属于%s,%s%s" % (entity, cls, cls, rel)
        return [], None

    def _lookup_neg(self, entity, rel):
        """查"否定"关系(不会/没有/不喜欢),支持分类继承。

        2011 年 kiki 演示里最容易被抓的破绽就是否定句,
        所以这里让"企鹅不会飞"能盖过"鸟会飞"。
        """
        neg = "不" + rel if rel in ("会", "能", "可以") else ("没有" if rel == "有" else "不" + rel)
        for target in (entity,) + tuple(self._isa_chain(entity)):
            got = [o for r, o in self.store.forward(target, neg)]
            if got:
                return got, target
        return [], None


    # ---------- 传递推理 ----------
    def _transitive(self, a, rel):
        """按 TRANSITIVE_RULES 推两级。"""
        results = []
        for r1, r2, out_rel, why in TRANSITIVE_RULES:
            if r1 != rel and out_rel != rel:
                continue
            mids = [o for r, o in self.store.forward(a, r1)]
            for mid in mids:
                for r, c in self.store.forward(mid, r2):
                    if mid == a:
                        continue
                    results.append((c, "%s的%s是%s,%s的%s是%s => %s的%s是%s" %
                                    (a, r1, mid, mid, r2, c, a, out_rel, c)))
        return results

    # ---------- 主入口 ----------
    def think(self, question, key="local"):
        """返回 {"answer":str, "why":str, "kind":str} 或 None(答不出)。"""
        if not question:
            return None
        q, ctx = self._resolve(question, key)
        q = re.sub(r"^(请问|问一下|我想知道|告诉我)", "", q).strip("，,。")

        # 0. 关系链提问:"X的Y的Z是谁/是什么" —— kiki 卡片上"逻辑推理"的核心演示
        chain = self._parse_chain_question(q)
        if chain:
            ans = self._answer_chain(chain, ctx)
            if ans:
                return ans

        # 1. 数量问句:卡卡有几条腿 / 地球有几颗卫星
        m = P_COUNT.search(q) or P_COUNT2.search(q)
        if m:
            ent, unit = self._norm_entity(m.group(1)), m.group(2)
            # 去掉量词,留下名词(几颗卫星 -> 卫星)
            noun = re.sub(r"^(?:几|多少)?[个只条张本头匹件位名门辆架朵把颗颗颗]?", "", unit)
            for cand_rel in ("有", "长"):
                vals, why = self._lookup(ent, cand_rel)
                for v in vals:
                    if unit in v or v.endswith(unit) or (noun and noun in v):
                        return {"answer": "%s有%s。" % (ent, v), "why": why or "直接事实",
                                "kind": "attr"}
            # 单位没直接命中时:只要"有"类事实里出现量词就答(地球有一颗天然卫星)
            for cand_rel in ("有", "长"):
                vals, why = self._lookup(ent, cand_rel)
                for v in vals:
                    if any(ch in v for ch in ("颗", "条", "只", "个", "张", "座", "把", "位")):
                        return {"answer": "%s有%s。" % (ent, v), "why": why or "直接事实",
                                "kind": "attr"}
            nums, why = self._lookup(ent, "有")
            if nums:
                return {"answer": "%s有%s。" % (ent, nums[0]), "why": why or "直接事实", "kind": "attr"}

        # 2. "X最喜欢吃什么"(必须在通用 attr 之前)
        m = P_FOOD.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            vals, why = self._lookup(ent, "喜欢")
            eaten = [v for v in vals if v[0] in "吃喝啃"]
            if eaten:
                ctx["last"] = ent
                return {"answer": "%s喜欢%s。" % (ent, eaten[0]),
                        "why": why or "直接事实", "kind": "attr"}

        # 2.5 "X喜欢什么"
        m = P_LIKE.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            vals, why = self._lookup(ent, "喜欢")
            if vals:
                ctx["last"] = ent
                return {"answer": "%s喜欢%s。" % (ent, "、".join(vals)),
                        "why": why or "直接事实", "kind": "attr"}

        # 2.6 "X会什么 / X能做什么"
        m = P_CAN.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            vals, why = self._lookup(ent, "会")
            if vals:
                ctx["last"] = ent
                return {"answer": "%s会%s。" % (ent, "、".join(vals)),
                        "why": why or "直接事实", "kind": "attr"}

        # 3. 教学句识别:这句本身能拆成三元组就顺手记住
        #    注意:必须**先排除疑问句**,否则"长江有多长"会被静默当成教学记住
        #    (实测踩到的坑:用户提问却得到"记住啦:长江的有是多长")
        tri = to_triple(q)
        if tri and not _is_question(q):
            self.remember(q)
            ctx["last"] = tri[0]
            ctx["history"].append(q)
            return {"answer": "记住啦:%s的%s是%s。我会一直记着。" % tri,
                    "why": "事实入库", "kind": "remember"}

        # 4. "X的Y是什么/是谁/叫什么"
        m = P_ATTR.search(q)
        if m:
            ent, attr = self._norm_entity(m.group(1)), strip_tail(m.group(2).lstrip("的"))
            canon = self._canon_rel(attr) or WORD_TO_REL.get(attr)
            if canon:
                vals, why = self._lookup(ent, canon)
                if vals:
                    ctx["last"] = ent
                    return {"answer": "%s的%s是%s。" % (ent, attr, "、".join(vals)),
                            "why": why or "直接事实", "kind": "attr"}
                inferred = self._transitive(ent, canon)
                if inferred:
                    ctx["last"] = ent
                    v, how = inferred[0]
                    return {"answer": "推出来的:%s的%s是%s。" % (ent, attr, v), "why": how,
                            "kind": "infer"}
            # canon 为空(如"卡卡的尾巴呢"):用属性名做部分匹配,顺序敏感
            if attr:
                best = None
                for r, o in self.store.forward(ent):
                    if r in SKIP_REVERSE or r == "是":
                        continue
                    if attr in o and (best is None or len(o) < len(best[1])):
                        best = (r, o)
                if best is None:
                    for cls in self._isa_chain(ent):
                        for r, o in self.store.forward(cls):
                            if r in SKIP_REVERSE or r == "是":
                                continue
                            if attr in o and (best is None or len(o) < len(best[1])):
                                best = (r, o)
                if best:
                    ctx["last"] = ent
                    return {"answer": "%s%s。" % (ent, best[1]), "why": "属性部分匹配",
                            "kind": "attr"}
                # "卡卡呢" 这类没带属性的追问:直接描述整个实体
                if not self._canon_rel(attr) and len(attr) <= 4:
                    ans = self._describe(ent, ctx)
                    if ans:
                        return ans

        # 5. "X是什么" / "什么是X"
        for pat in (P_WHATIS, P_WHATIS2):
            m = pat.search(q)
            if m:
                ent = self._norm_entity(m.group(1))
                if ent:
                    ans = self._describe(ent, ctx)
                    if ans:
                        return ans

        # 6. "X是谁"
        m = P_WHOIS.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            ans = self._describe(ent, ctx)
            if ans:
                return ans

        # 7. "X住在哪里" / "X在哪" / "X位于哪里"
        for pat in (P_WHERE, P_WHERE2):
            m = pat.search(q)
            if m:
                ent = self._norm_entity(m.group(1))
                # 依次尝试:住在 -> 位于(导入的地理事实常用"位于")
                for rel in ("住在", "位于"):
                    vals, why = self._lookup(ent, rel)
                    if vals:
                        ctx["last"] = ent
                        verb = "住在" if rel == "住在" else "位于"
                        return {"answer": "%s%s%s。" % (ent, verb, vals[0]),
                                "why": why or "直接事实", "kind": "where"}

        # 7.5 "X的首都是哪里 / X的符号是什么" —— 关系词 + 哪里/哪儿
        m = P_REL_WHERE.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            rel = self._canon_rel(strip_tail(m.group(2)))
            if ent and rel:
                vals, why = self._lookup(ent, rel)
                if vals:
                    ctx["last"] = ent
                    return {"answer": "%s的%s是%s。" % (ent, rel, "、".join(vals)),
                            "why": why or "直接事实", "kind": "attr"}

        # 7.6 "法国首都 / 山东省简称 / 美国代码" —— 省略"的"的口语问法
        #     关系词优先查内置别名;查不到再看"事实库里出现过的关系词",
        #     这样以后导入任何新关系(人口/面积/成立时间…)都自动可查,无需改代码。
        m = _rel_bare_pattern().search(q)
        if m:
            ent, raw_rel = self._norm_entity(m.group(1)), m.group(2)
            for rel in self._rel_candidates(raw_rel):
                vals, why = self._lookup(ent, rel)
                if vals:
                    ctx["last"] = ent
                    return {"answer": "%s的%s是%s。" % (ent, rel, "、".join(vals)),
                            "why": why or "直接事实", "kind": "attr"}

        # 7.4 "光速是多少 / 圆周率是多少" —— 值类问句
        #     必须排在 P_YESNO 之前:否则"光速是多少"会被是非句切成 (光速, 多少)
        m = P_VALUE.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            if ent:
                for rel in ("数值", "值", "是"):
                    vals, why = self._lookup(ent, rel)
                    if vals:
                        ctx["last"] = ent
                        return {"answer": "%s是%s。" % (ent, "、".join(vals)),
                                "why": why or "直接事实", "kind": "value"}
                ans = self._describe(ent, ctx)
                if ans:
                    return ans

        # 7.45 "谁发明了电话 / 谁提出了万有引力" —— 主语是"谁",要反查事实库
        if q.startswith("谁") or q.startswith("是哪位") or q.startswith("是哪个人"):
            m = P_WHO_DID.match(q)
            if m:
                rel, obj = strip_tail(m.group(1)), strip_tail(m.group(2))
                cands = self._rel_candidates(rel)
                for extra in ("发明", "发现", "提出", "改进", "创立", "创作", "编写", "建立"):
                    if extra not in cands:
                        cands.append(extra)
                for r in cands:
                    holders = [s for s, _r in self.store.reverse(obj, r)]
                    if holders:
                        ctx["last"] = obj
                        return {"answer": "是%s%s%s的。" % ("、".join(holders), r, obj),
                                "why": "反查事实:%s%s%s" % (holders[0], r, obj), "kind": "reverse"}

        # 7.5 "X的数值/内容是什么" —— 短关系直接查
        m = re.match(r"(.{1,20}?)(?:的)?(数值|内容|公式|定义)是(?:多少|什么|啥)[呢？?]*$", q)
        if m:
            ent = self._norm_entity(m.group(1))
            for rel in (m.group(2),):
                vals, why = self._lookup(ent, rel)
                if vals:
                    ctx["last"] = ent
                    return {"answer": "%s的%s是%s。" % (ent, rel, "、".join(vals)),
                            "why": why or "直接事实", "kind": "attr"}

        # 7.55 "长江有多长 / 珠峰有多高" —— 度量问句
        m = P_MEASURE.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            rel = MEASURE_REL.get("多" + m.group(2))
            if ent and rel:
                vals, why = self._lookup(ent, rel)
                if vals:
                    ctx["last"] = ent
                    return {"answer": "%s的%s是%s。" % (ent, rel, "、".join(vals)),
                            "why": why or "直接事实", "kind": "measure"}

        # 7.8 "秦朝建立于哪一年 / 唐朝灭亡于何时" —— 时间类关系问句
        m = P_REL_WHEN.search(q)
        if m:
            # 用"已知实体最长匹配"切分,避免 秦 | 朝建立于 这种切错
            joined = (m.group(1) or "") + (m.group(2) or "")
            ent, raw_rel = split_entity_rel(joined, _load_store_relations())
            if ent:
                cands = self._rel_candidates(raw_rel) if raw_rel else []
                for extra in ("建立于", "灭亡于", "发生于", "结束于", "通航于", "举办于"):
                    if extra not in cands:
                        cands.append(extra)
                for rel in cands:
                    vals, why = self._lookup(ent, rel)
                    if vals:
                        ctx["last"] = ent
                        return {"answer": "%s%s%s。" % (ent, rel, "、".join(vals)),
                                "why": why or "直接事实", "kind": "when"}

        # 7.7 换算问句:"一小时是几分钟 / 一米是多少厘米"
        #     事实库存的是"一小时=六十分钟",这里算出 60 再按单位归一
        m = P_CONVERT.search(q)
        if m:
            ent = self._norm_entity(m.group(1))
            unit = strip_tail(m.group(2))
            if ent and unit:
                vals, why = self._lookup(ent, "是")
                for v in vals:
                    if unit in v:
                        n = _count_unit(v, unit)
                        if n is None:
                            return {"answer": "%s是%s。" % (ent, v), "why": "直接事实",
                                    "kind": "convert"}
                        pretty = ("%d" % n) if float(n).is_integer() else ("%g" % n)
                        return {"answer": "%s是%s%s。" % (ent, pretty, unit),
                                "why": "由「%s是%s」换算" % (ent, v), "kind": "convert"}
        # 8. 是非问句:卡卡是动物吗 / 企鹅会飞吗 / 猫会捉老鼠吗
        #
        # 【顺序很重要】先试**因果/属性推理**,再走直接查库的 yes/no。
        # 否则会踩这个坑:"毕昇是发明家吗" 走到下面
        # `if self.store.forward(a): return "不是,毕昇不是发明家"`
        # 直接返回否定 —— 而库里"毕昇 发明 活字印刷术"明明能推出他是发明家。
        causal_hit = self._causal_yesno(q)
        if causal_hit:
            return causal_hit

        m = P_YESNO.search(q) if re.search(r"(吗|么|嘛)[？?]*$", q) else None
        if m:
            a = self._norm_entity(m.group(1))
            b = strip_tail(m.group(2))
            if a and b:
                # 先按 "是/属于" 判定
                if b in [o for r, o in self.store.forward(a, "是")]:
                    return {"answer": "对,%s是%s。" % (a, b), "why": "直接事实", "kind": "yesno"}
                if b in self._isa_chain(a):
                    return {"answer": "对,%s是%s(推出来的)。" % (a, b),
                            "why": "%s的类别链里有%s" % (a, b), "kind": "infer"}
                if a in [o for r, o in self.store.forward(b, "是")]:
                    return {"answer": "不,%s不是%s;反过来了,%s是%s。" % (a, b, b, a),
                            "why": "反向事实", "kind": "yesno"}
                # 再按 "会/能/可以" 这类能力关系判定:否定优先("企鹅不会飞"要盖过"鸟会飞")
                for rel in ("会", "可以", "能"):
                    negs, holder = self._lookup_neg(a, rel)
                    for v in negs:
                        if b and (b in v or v in b):
                            return {"answer": "不,%s不会%s。这个问题换一个吧。" % (a, b),
                                    "why": "%s的否定事实:%s%s" % (holder, holder, v), "kind": "yesno"}
                    abilities, why = self._lookup(a, rel)
                    for v in abilities:
                        if b and (b in v or v in b):
                            return {"answer": "对,%s会%s。" % (a, b), "why": why or "直接事实",
                                    "kind": "yesno"}
                if self.store.forward(a) or self.store.forward(b):
                    return {"answer": "不是,%s不是%s。" % (a, b), "why": "事实库中无此关系",
                            "kind": "yesno"}

        # 8.5 "X走得快吗 / X跑得快吗" 这类形容词是非问句
        m = re.search(r"(.+?)(走得|跑得|飞得|游得|长得)?([快慢大小多少高矮长短胖瘦冷热新旧早晚])(?:吗|么|嘛)[？?]*$", q)
        if m:
            ent, verb, adj = self._norm_entity(m.group(1)), (m.group(2) or ""), m.group(3)
            for r, o in list(self.store.forward(ent)) + [
                (rr, oo) for cls in self._isa_chain(ent) for rr, oo in self.store.forward(cls)
            ]:
                if adj in o:
                    return {"answer": "对,%s%s%s。" % (ent, r, o), "why": "直接事实",
                            "kind": "yesno"}
                if contradicts(adj, o):
                    return {"answer": "不,%s%s%s。" % (ent, r, o), "why": "反义事实:%s" % o,
                            "kind": "yesno"}

        # 9. 两个实体的关系
        m = P_RELBETWEEN.search(q)
        if m:
            a, b = self._norm_entity(m.group(1)), self._norm_entity(m.group(2))
            direct = self._direct_rel(a, b)
            if direct:
                return direct
            # 跨链:两人没有直接关系,但共享某个亲属/中间实体(A的X == B的Y)
            cross = self._cross_rel(a, b)
            if cross:
                return cross
            # 共同类别
            sa, sb = set(self._isa_chain(a)), set(self._isa_chain(b))
            common = sa & sb
            if common:
                c = list(common)[0]
                return {"answer": "%s和%s都是%s。" % (a, b, c), "why": "分类继承", "kind": "infer"}

        # 8. 兜底:实体是否能被描述
        ans = self._describe(self._strip_measure(q), ctx)
        if ans:
            return ans

        # 8.9 因果/属性推理:从已知事实推出**新结论**(不在库里,但能推出来)
        ans = self._causal_answer(q)
        if ans:
            return ans
        return None

    def _causal_answer(self, q):
        """因果推理层 —— 对应"知道比尔盖茨是微软 CEO,就能推出他很出名"这类需求。

        直接事实查不到时,用 causal.py 的规则对**候选事实**做一次推理,
        看推出的结论是否能回答当前问题。只做高置信度的几种问法,不瞎猜
        (用户明确要求:错误答案比不回答更糟)。

        支持的问法:
            X是谁建立的 / X由谁建立    <- 逆关系
            X是谁发明的
            X是谁的首都 / 省会
            X是历史人物吗 / X是发明家吗 <- 因果蕴含
        """
        try:
            import causal
        except Exception:
            return None

        # ---- 1. "X由谁建立 / 谁建立了X / X是谁建立的" ----
        # 正则说明:结尾允许"的"(是谁建立的),所以用 (?:的)? 而不是把"的"吃进实体名
        def _try_whose(text):
            """原文优先,规范化结果兜底。

            为什么要两个都试:_norm_entity("活字印刷术") 会截断成 "印刷术"
            (因为"活字印刷术"不在实体表里,它贪心匹配了子串),导致查不到
            "印刷术 发明 ..."。而原文 "活字印刷术" 在事实里是精确存在的。
            """
            cands = []
            raw = text.strip()
            if raw:
                cands.append(raw)
            norm = self._norm_entity(text)
            if norm and norm not in cands:
                cands.append(norm)
            for cand in cands:
                for rel, tmpl in (("建立者", "%s由%s建立"), ("创立", "%s由%s创立"),
                                  ("发明", "%s由%s发明")):
                    # 方向 1:实体是主语(如 "东吴 建立者 孙权")
                    vals, why = self._lookup(cand, rel)
                    if vals:
                        return {"answer": tmpl % (cand, vals[0]) + "。",
                                "why": why or ("%s%s%s" % (cand, rel, vals[0])),
                                "kind": "infer"}
                    # 方向 2:实体是**宾语** —— 关键!
                    # 库里存的是 "毕昇 发明 活字印刷术",问"活字印刷术是谁发明的"时
                    # 实体在宾语位置,forward 查不到,必须反查主语。
                    for subj in self._subjects_with(rel, cand):
                        return {"answer": tmpl % (cand, subj) + "。",
                                "why": "%s%s%s" % (subj, rel, cand), "kind": "infer"}
            return None

        m = re.search(r"^(.+?)(?:是)?(?:由)?谁(?:建立|创立|发明|创建)(?:的)?[？?]*$", q)
        if m:
            hit = _try_whose(m.group(1))
            if hit:
                return hit
        # "谁建立了X"
        m = re.search(r"^谁(?:建立|创立|发明|创建)了?(.+?)[？?]*$", q)
        if m:
            hit = _try_whose(m.group(1))
            if hit:
                return hit

        # ---- 2. "X是谁的首都/省会" ----
        m = re.search(r"^(.+?)是谁的(首都|省会)[？?]*$", q)
        if m:
            raw_ent = m.group(1).strip()
            rel = m.group(2)
            for cand in ([raw_ent] + ([self._norm_entity(m.group(1))] if self._norm_entity(m.group(1)) != raw_ent else [])):
                for subj in self._subjects_with(rel, cand):
                    return {"answer": "%s是%s的%s。" % (cand, subj, rel),
                            "why": "%s的%s是%s" % (subj, rel, cand), "kind": "infer"}
        return None

    def _causal_yesno(self, q):
        """"X是历史人物吗 / X是发明家吗" 这类**可以推理出来**的是非问句。

        必须在直接查库的 yes/no 之前调用,否则会被"库里没有 -> 不是"抢先否掉。
        只对能推出肯定结论的情况返回;推不出就返回 None,让后面的逻辑照常处理。
        """
        if not re.search(r"(吗|么|嘛)[？?]*$", q):
            return None
        m = re.search(r"^(.+?)(?:是|算)(.+?)(?:吗|么|嘛)[？?]*$", q)
        if not m:
            return None
        try:
            import causal
        except Exception:
            return None
        ent, claim = self._norm_entity(m.group(1)), m.group(2).strip()
        if not ent or not claim:
            return None

        def _check(entity):
            """对某个实体的正向事实做因果推理,看能否支持 claim"""
            try:
                for rel, o in self.store.forward(entity):
                    for concl_rel, text, why in causal.derive(self._causal_store(), entity, rel, o):
                        if claim in text:
                            return {"answer": "对,%s。" % text, "why": why, "kind": "infer"}
            except Exception:
                pass
            try:
                for cls in self._isa_chain(entity):
                    if claim == cls:
                        return {"answer": "对,%s是%s。" % (entity, cls), "why": "分类继承",
                                "kind": "infer"}
            except Exception:
                pass
            return None

        # 方向 1:entity 是事实的**主语**(如 "毕昇 发明 活字印刷术")
        hit = _check(ent)
        if hit:
            return hit

        # 方向 2:entity 是事实的**宾语** —— 这一条很容易漏!
        # 库里存的是 "东吴 建立者 孙权",孙权是宾语,forward("孙权") 是空的。
        # 所以要把"谁建立了X"这类事实反过来推:"东吴建立者孙权" => "孙权是历史人物"。
        try:
            for subj, rel, obj in self.store.all_facts():
                if obj == ent:
                    for concl_rel, text, why in causal.derive(self._causal_store(), subj, rel, obj):
                        if claim in text:
                            return {"answer": "对,%s。" % text, "why": why, "kind": "infer"}
        except Exception:
            pass
        return None

    def _subjects_with(self, rel, obj):
        """反查:哪些实体的 rel 关系值等于 obj(用于首都/省会这类)"""
        out = []
        try:
            # 注意方法名是 all_facts()(实现里叫法,别想当然写 all_triples)
            for s, r, o in self.store.all_facts():
                if r == rel and o == obj:
                    out.append(s)
        except Exception:
            pass
        return out

    def _causal_store(self):
        """给 causal.derive 用的适配器:提供 isa_parents"""
        brain = self

        class _Adapter:
            def isa_parents(self, e):
                vals, _ = brain._lookup(e, "是")
                return [v for v in vals if len(v) <= 8][:3]

        return _Adapter()

    def _fuzzy_find(self, text):
        """实体名做一次宽松匹配,尽量认到事实库里的规范实体。

        顺序很重要(从"最可信"到"最宽松"):
          0. 拼写变体表(kiki->kiki)
          1. 本身就在事实库里
          2. 事实库实体出现在输入里(北京城 -> 北京)
          3. 输入出现在事实库实体里
          4. 拼音同音匹配(kiki -> kiki)**这是 kiki 卡片说的"多音节分辨"**
          5. 叠字归一(狗狗 -> 狗)
          6. 词表里含该子串的词
        """
        t = (text or "").strip()
        if not t:
            return t
        # 0. 手写变体表优先(覆盖拼音表里没有的词)
        if t in PHONETIC_FIX:
            return PHONETIC_FIX[t]
        # 1. 直接命中
        if self.store.forward(t):
            return t
        # 2/3. 与事实库实体做包含匹配
        entities = self._entity_names()
        for e in entities:
            if e and e != t and e in t:
                return e
        for e in entities:
            if e and e != t and t in e:
                return e
        # 4. 拼音同音匹配(核心)
        cand = self._homophone_entity(t)
        if cand:
            return cand
        # 5. 叠字归一
        for dup in ("狗狗", "猫猫", "鸟鸟", "车车", "鱼鱼"):
            if t == dup:
                if self.store.forward(dup[0]):
                    return dup[0]
                t = dup[0]
                break
        # 6. 词表子串兜底
        if self.store.forward(t):
            return t
        for s, rel, o in self.store.all_facts():
            if t and t in s:
                return s
        for s, rel, o in self.store.all_facts():
            if t and t in o:
                return o
        return t

    # ---------- 实体识别:用本机词表 + 拼音(多音节分辨) ----------
    def _entity_names(self):
        """事实库里出现过的实体名(主语+宾语),带小缓存。"""
        if getattr(self, "_ent_cache", None) is not None:
            return self._ent_cache
        names = set()
        for s, _r, o in self.store.all_facts():
            if s:
                names.add(s)
            if o:
                names.add(o)
        self._ent_cache = sorted(names, key=len, reverse=True)
        return self._ent_cache

    def _pinyin_key(self, word):
        """词 -> 无声调拼音串(用于同音比对)。查不到的字返回 None。"""
        try:
            import lexicon
            lx = lexicon.get_lexicon()
            parts = [lx._strip_tone(s) for s in lx.pinyin_of(word)]
            if not parts or "?" in parts:
                return None
            return "".join(parts)
        except Exception:
            return None

    def _homophone_entity(self, text):
        """在事实库实体里找与输入同音的那个:kiki -> kiki。"""
        if len(text) < 2:
            return None
        if getattr(self, "_py_map", None) is None:
            m = {}
            for e in self._entity_names():
                k = self._pinyin_key(e)
                if k:
                    m.setdefault(k, e)
            self._py_map = m
        k = self._pinyin_key(text)
        if not k:
            return None
        return self._py_map.get(k)

    def invalidate_entity_cache(self):
        """教了新事实后调用,让实体/拼音索引重建。"""
        self._ent_cache = None
        self._py_map = None

    # ---------- 亲属关系路径推理 ----------
    # 把各种亲属称谓归一成两种原始边:parent(指向父母)与 spouse
    PARENT_RELS = {"爸爸", "妈妈"}
    CHILD_RELS = {"儿子", "女儿"}
    SPOUSE_RELS = {"妻子", "丈夫"}

    def _kin_edges(self):
        """构建亲属图。

        返回 (parent, child, spouse, relname):
            parent[子] = {父, 母}          child[父/母] = {子}
            spouse[A] = {B}                 relname[(s,o)] = 原始关系名(爸爸/妈妈/妻子…)
        """
        parent, child, spouse, relname = {}, {}, {}, {}
        for s, rel, o in self.store.all_facts():
            if rel in self.PARENT_RELS:
                parent.setdefault(s, set()).add(o)
                child.setdefault(o, set()).add(s)
                relname[(s, o)] = rel
            elif rel in self.CHILD_RELS:
                child.setdefault(s, set()).add(o)
                parent.setdefault(o, set()).add(s)
                relname[(o, s)] = rel
            elif rel in self.SPOUSE_RELS:
                spouse.setdefault(s, set()).add(o)
                spouse.setdefault(o, set()).add(s)
                relname[(s, o)] = rel
        return parent, child, spouse, relname

    def _ancestors(self, node, parent, relname, max_up=4):
        """返回 node 的所有祖先:辈分 -> [(祖先, 到它的关系链)]。

        用"向上走"建树(上跳优先),同辈分只保留最先遇到的那条,避免组合爆炸。
        """
        levels = {0: [(node, [])]}
        frontier = [node]
        seen = {node}
        for d in range(1, max_up + 1):
            nxt, nxt_paths = [], []
            for cur in frontier:
                for p in parent.get(cur, ()):
                    if p in seen:
                        continue
                    seen.add(p)
                    nxt.append(p)
                    nxt_paths.append(("up", cur, p, relname.get((cur, p), "爸爸/妈妈")))
            if not nxt:
                break
            levels[d] = [(p, [e]) for p, e in zip(nxt, nxt_paths)]
            frontier = nxt
        return levels

    def _ancestor_index(self, node, parent, relname, max_up=4):
        """祖孙索引:节点 -> 最少上跳数(便于找最近共同祖先)。"""
        idx = {node: 0}
        frontier = [node]
        for d in range(1, max_up + 1):
            nxt = []
            for cur in frontier:
                for p in parent.get(cur, ()):
                    if p not in idx:
                        idx[p] = d
                        nxt.append(p)
            if not nxt:
                break
            frontier = nxt
        return idx

    def _kin_path(self, a, b, max_depth=4):
        """找 A 到 B 的亲属关系路径:先上到最近共同祖先,再下到 B。

        这才是亲属推理的正确解法(等价于家谱树的 LCA)。
        之前用朴素 BFS + 方向锁,会出现"李秀兰的妈妈是张伟"这种荒谬路径。
        """
        parent, child, spouse, relname = self._kin_edges()
        if a == b:
            return []
        # 1. 同父母即为兄弟姐妹
        a_parents = parent.get(a, set())
        for p in a_parents:
            if b != a and b in child.get(p, set()):
                return [("down", a, b, "兄弟姐妹")]
        if b in spouse.get(a, ()):
            return [("spouse", a, b, relname.get((a, b), "配偶"))]
        # 2. 上到共同祖先,再下到 B
        ia = self._ancestor_index(a, parent, relname, max_depth)
        ib = self._ancestor_index(b, parent, relname, max_depth)
        common = set(ia) & set(ib)
        if common:
            ancestor = min(common, key=lambda n: ia[n] + ib[n])
            def _up_chain(start, target, dist, limit=8):
                """从 start 沿 parent 边走到 target。

                必须传入"起点自己的"祖先距离表 dist:
                bug 教训——闭包里误用了另一个人的表,导致小明的爸爸被当成无关节点过滤掉。
                """
                if start == target:
                    return []
                from collections import deque
                q = deque([(start, [])])
                seen = {start}
                while q:
                    cur, chain = q.popleft()
                    if len(chain) >= limit:
                        continue
                    for p in parent.get(cur, ()):
                        if p not in dist or p in seen:
                            continue
                        new_chain = chain + [("up", cur, p, relname.get((cur, p), "爸爸/妈妈"))]
                        if p == target:
                            return new_chain
                        seen.add(p)
                        q.append((p, new_chain))
                return None

            up_path = _up_chain(a, ancestor, ia)
            down_chain = _up_chain(b, ancestor, ib)
            if up_path is not None and down_chain is not None:
                # 下链要反转成"父 -> 子"方向,并把关系名换成通顺的称谓
                # (bug 教训:直接复用 up 边会生成"李秀兰的妈妈是张伟"这种反了的说法)
                down_path = []
                for _d, mid, nxt, _rel in reversed(down_chain):
                    # down_chain 的边是 (子 -> 父),反向成 (父 -> 子) 并用子女称谓:
                    # 读起来是 "长辈的子女是晚辈"
                    parent_rel = relname.get((mid, nxt), "") or relname.get((nxt, mid), "")
                    down_path.append(("down", nxt, mid, down_rel_name(parent_rel)))
                return up_path + down_path
        # 3. 跨配偶:A 的配偶与 B 有血缘
        for sp in spouse.get(a, ()):
            sub = self._kin_path(sp, b, max_depth)
            if sub is not None:
                return [("spouse", a, sp, relname.get((a, sp), "配偶"))] + sub
        return None

    def _label_path(self, path):
        """把路径翻译成人话 + 给出称谓。"""
        if not path:
            return None
        ups = [p for p in path if p[0] == "up"]
        downs = [p for p in path if p[0] == "down"]
        spouses = [p for p in path if p[0] == "spouse"]
        # 路径里直接标了"兄弟姐妹"时,不要再按(上跳,下跳)数量去套普通称谓
        # (bug 教训:一步直达的兄弟关系会落进 (0,1)->"子女")
        rels = {p[3] for p in path}
        if rels & {"兄弟姐妹", "弟弟/妹妹", "哥哥/姐姐"}:
            term = "兄弟姐妹"
        else:
            term = KIN_TERMS.get((len(ups), len(downs)))
        detail = " -> ".join("%s的%s是%s" % (mid, rel, nxt) for _d, mid, nxt, rel in path)
        if spouses:
            term = term or "姻亲"
        return term, detail

    def _norm_entity(self, name):
        """统一入口:把用户说的实体名归一到事实库里的规范名。

        所有分支取到实体名后都应该过这一道,否则会出现
        "艾丽斯住在哪"答不出、而"艾丽斯是什么"能答的诡异不一致。
        """
        n = strip_tail(self._strip_measure(name))
        if not n:
            return n
        return self._fuzzy_find(n)

    def _rel_candidates(self, raw):
        """把用户说的关系词变成一组"可尝试的关系名"。

        这是让**导入的新关系自动可查**的关键:
          1. 内置别名归一(首都/爸爸/符号…)
          2. 字面就是事实库里的关系名
          3. 用户写的词是某个已知关系的后缀("序数" -> "原子序数")
        顺序即优先级,调用方按序尝试。
        """
        w = strip_tail(raw)
        out = []
        if not w:
            return out
        canon = None
        for c, aliases in REL_ALIASES.items():
            if w == c or w in aliases:
                canon = c
                break
        if canon:
            out.append(canon)
        known = set(_load_store_relations())
        if w in known and w not in out:
            out.append(w)
        if len(w) >= 2:
            for k in known:
                if len(k) > len(w) and k.endswith(w) and k not in out:
                    out.append(k)
        return out

    # ---------- 辅助 ----------
    def _direct_rel(self, a, b):
        """A 与 B 是否有直接关系(双向查);输出统一成 "主语的关系是宾语" 的形式。

        注意方向:张伟 -妈妈-> 李秀兰 表示 "张伟的妈妈是李秀兰",
        所以反查时不能写成 "李秀兰是张伟的妈妈"(那是错的)。
        """
        for rel, obj in self.store.forward(a):
            if obj == b and rel != "是":
                return {"answer": "%s的%s是%s。" % (a, rel, b), "why": "直接事实", "kind": "rel"}
        for rel, obj in self.store.forward(b):
            if obj == a and rel != "是":
                return {"answer": "%s的%s是%s。" % (b, rel, a), "why": "直接事实", "kind": "rel"}
        return None

    def _cross_rel(self, a, b):
        """跨链关系推理:**先查直接关系**(直系亲属优先),再上家谱树找共同祖先。"""
        direct = self._direct_rel(a, b)
        if direct:
            return direct
        path = self._kin_path(a, b)
        if path:
            labelled = self._label_path(path)
            if labelled:
                term, detail = labelled
                if term:
                    if term in ("兄弟姐妹", "堂表兄弟姐妹", "父母", "子女", "祖父母", "孙辈"):
                        return {"answer": "%s和%s是%s。" % (a, b, term),
                                "why": detail, "kind": "infer"}
                    return {"answer": "%s是%s的%s(%s)。" % (a, b, term, detail),
                            "why": detail, "kind": "infer"}
                return {"answer": "%s和%s的关系是:%s。" % (a, b, detail),
                        "why": detail, "kind": "infer"}
        # 2. 共享中间实体的保守判定
        fa = [(r, o) for r, o in self.store.forward(a) if r != "是"]
        fb = [(r, o) for r, o in self.store.forward(b) if r != "是"]
        for r1, o1 in fa:
            for r2, o2 in fb:
                if o1 == o2:
                    return {"answer": "%s的%s和%s的%s是同一个人(%s),所以他们是亲戚关系。"
                                      % (a, r1, b, r2, o1),
                            "why": "%s的%s=%s,%s的%s=%s" % (a, r1, o1, b, r2, o2),
                            "kind": "infer"}
        return None

    def _parse_chain_question(self, q):
        """解析 "X的Y的Z是谁/是什么/叫什么"。返回 (X, [rel...]) 或 None。

        这是 kiki 卡片上"逻辑推理"最典型的形式:问一个**没教过**的关系,
        引擎靠关系链规则自己推出来(没教过"爷爷",也能答"小明的爷爷是谁")。
        """
        m = re.match(r"^(.+?)的((?:[^的是什么谁哪]+?的)*[^的是什么谁哪]+?)(?:是|叫)(?:谁|什么|啥|哪个)(?:呢|？|\?)?$", q)
        if not m:
            return None
        head = self._norm_entity(m.group(1))
        chain = [w for w in m.group(2).split("的") if w]
        if not head or not chain:
            return None
        # 每个环节必须是已知关系词,否则不是关系链问句(避免把"电脑的处理器是什么"误判)
        rels = []
        for w in chain:
            canon = REL_WORDS.get(w) or self._canon_rel(w)
            if not canon:
                return None
            rels.append(canon)
        return head, rels

    def _answer_chain(self, chain, ctx=None):
        """回答关系链问句:先查直接事实,再做传递推理。"""
        head, rels = chain
        # 单环节:直接查(含分类继承)
        if len(rels) == 1:
            vals, why = self._lookup(head, rels[0])
            if vals:
                if ctx is not None:
                    ctx["last"] = head
                return {"answer": "%s的%s是%s。" % (head, rels[0], "、".join(vals)),
                        "why": why or "直接事实", "kind": "attr"}
            return None
        # 多环节:逐跳推理
        cur = [head]
        why = []
        for rel in rels:
            nxt = []
            for node in cur:
                got = [o for r, o in self.store.forward(node, rel)]
                for o in got:
                    nxt.append(o)
                    why.append("%s的%s是%s" % (node, rel, o))
            if not nxt:
                # 这一跳断了,尝试用传递规则补
                inferred = []
                for node in cur:
                    inferred.extend(self._transitive(node, rel))
                if not inferred:
                    return None
                nxt = [v for v, _h in inferred]
                why.extend([h for _v, h in inferred])
            cur = list(dict.fromkeys(nxt))
        if ctx is not None:
            ctx["last"] = head
        out = "、".join(cur)
        return {"answer": "推出来的:%s的%s是%s。" % (head, "的".join(rels), out),
                "why": " -> ".join(why[:6]) or "关系链推理", "kind": "infer"}

    @staticmethod
    def _strip_measure(text):
        """去掉前面的量词:一个猫 -> 猫。

        bug 教训:量词表里有"张",而 `[一这那]?` 是可选的,
        于是 张伟 -> 伟、张三 -> 三,所有姓张的人都会被砍掉首字。
        所以只在量词后面还有内容、且量词不是人名首字时才剥:
        简化为"仅当量词前有 一/这/那 或量词后长度>=2 时才剥"。
        """
        t = (text or "").strip()
        m = re.match(r"^(一|这|那)?[" + MEASURE_WORDS + r"](.+)$", t)
        if not m:
            return t
        prefix, rest = m.group(1), m.group(2)
        # 没有指示代词前缀时,只有当量词明显是"数量词"才剥:
        # "个猫""只鸟"这类量词不会是中文姓氏,而"张/头/位/名"可能撞姓氏,故要求 rest 长度>=2
        if prefix:
            return rest
        if len(rest) >= 2 and t[0] in "个只条本朵把件辆架匹":
            return rest
        return t

    @staticmethod
    def _canon_rel(word):
        """关系词归一。

        除了内置别名词典,还**动态纳入事实库里出现过的关系词**——
        这样导入新语料(如"首都""符号""沸点")后无需改代码即可查询,
        这是"扩充知识库"能自动生效的关键。
        """
        w = (word or "").strip()
        if not w:
            return None
        for canon, aliases in REL_ALIASES.items():
            if w == canon or w in aliases:
                return canon
        # 动态关系词:事实库里真实存在的关系名
        if w in _load_store_relations():
            return w
        return None

    @staticmethod
    def _phrase(entity, rel, obj):
        """把 (实体, 关系, 宾语) 说成一句通顺的中文。

        不同关系要用不同句式,否则会生成"水的在是100摄氏度沸腾"这种病句。
        句式表覆盖常见关系;表里没有的用"的X是Y"兜底。
        """
        r = (rel or "").strip()
        o = (obj or "").strip()
        if not r:
            return "%s%s" % (entity, o)
        # 动宾型:直接接宾语
        for verb in ("有", "长", "会", "能", "可以", "喜欢", "讨厌", "需要", "没有", "不会"):
            if r == verb:
                return "%s%s%s" % (entity, verb, o)
        # "是/属于":表语
        if r in ("是", "属于"):
            return "%s是%s" % (entity, o)
        # 位置/方向型:把它们说成完整句
        if r in ("住在", "位于", "落在", "绕着", "在", "走得", "跑得"):
            if r == "绕着":
                return "%s绕着%s" % (entity, o)
            if r in ("走得", "跑得"):
                return "%s%s%s" % (entity, r, o)
            if r == "在":
                return "%s在%s" % (entity, o)
            return "%s%s%s" % (entity, r, o)
        # 时间/数值型:"建立于公元前221年" -> "建立于公元前221年"
        if r.endswith("于") or r in ("发生于", "结束于", "通航于", "举办于"):
            return "%s%s%s" % (entity, r, o)
        # 名词属性型:首都/符号/代码/简称/原子序数/面积/人口/沸点/代表作…
        return "%s的%s是%s" % (entity, r, o)

    def _describe(self, entity, ctx=None):
        """生成一个实体的描述(分类继承 + 属性 + 总结)。

        关键:只有"是/属于"才是类别关系(可继承),其它关系一律按字面陈述,
        否则会生成"北京的一种"这种病句(导入的"首都"关系不是类别)。
        """
        if not entity:
            return None
        classes = self._isa_chain(entity)
        own = [(r, o) for r, o in self.store.forward(entity) if _is_isa(r)]
        attrs = [(r, o) for r, o in self.store.forward(entity) if not _is_isa(r)]
        rev = self.store.reverse(entity)
        if not classes and not own and not attrs and not rev:
            return None
        parts = []
        # 分类链与"自身是"合并去重:避免"卡卡是猫;卡卡是猫、哺乳动物、动物"这种啰嗦
        own_objs = [o for _r, o in own]
        merged = list(dict.fromkeys(own_objs + [c for c in classes if c not in own_objs]))
        if merged:
            parts.append("%s是%s" % (entity, "、".join(merged[:4])))
        for r, o in attrs[:4]:
            parts.append(self._phrase(entity, r, o))
        # 反查:必须能换成通顺的反向称谓,否则宁可不写(避免"猫是卡卡的是"这种病句)
        for holder, rel in rev:
            if rel in SKIP_REVERSE or not _is_isa(rel):
                continue
            inv = INVERSE_DESC.get(rel)
            if not inv:
                continue
            parts.append("%s是%s%s" % (entity, holder, inv))
            break
        if not parts:
            return None
        summary = ";".join(parts)
        if ctx is not None:
            ctx["last"] = entity
            ctx["history"].append(entity)
        return {"answer": "%s。我记性很好的。" % summary,
                "why": "分类继承+属性检索", "kind": "describe"}

    def stats(self):
        return {"facts": self.store.count(), "sessions": len(self.session)}


# 便利函数:给上游 service 兜底用
_default = None
_store_relations = None


def _load_store_relations():
    """事实库里出现过的全部关系词(带缓存),供"动态关系词"识别用。"""
    global _store_relations
    if _store_relations is None:
        try:
            store = BrainStore()
            with store._conn() as c:
                rows = c.execute("SELECT DISTINCT rel FROM facts").fetchall()
            _store_relations = {r["rel"] for r in rows if r["rel"]}
        except Exception:
            _store_relations = set()
    return _store_relations


def invalidate_store_relations():
    """导入/教学后调用,让动态关系词缓存失效。"""
    global _store_relations
    _store_relations = None


def get_brain():
    global _default
    if _default is None:
        _default = KikiBrain()
    return _default


def ask(question, key="local"):
    return get_brain().think(question, key)
