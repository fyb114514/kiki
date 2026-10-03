# -*- coding: utf-8 -*-
"""本地单机旁路:让教学立即生效(跳过"先审后玩"的 AI 审核队列)。

为什么需要它
------------
上游 `service.MemoriseService.add()` 把教学内容写成 `review_status="pending"`,
**不写入内存索引**,要靠每小时的后台任务调 DeepSeek 审核通过后才生效。
本机没有配置 `DEEPSEEK_API_KEY`,审核任务会直接跳过,于是"教什么都不会生效"。

本模块只在本地单机场景使用:把 `add()` 换成"入库即 approved + 立刻进索引"的版本,
行为与"审核已全部通过"完全一致,不改动上游任何文件。

用法
----
    # 起服务(替代 python -m uvicorn main:app)
    python -m local_service

    # 单独灌预置问答
    python -m local_service --seed
"""
import os
import random
import sys
from datetime import datetime

_PATCHED = False
_REPLY_PATCHED = False

# 虚词表 + 实义词抽取(质量闸用)
# 实测发现"你笨吗"与"你今天吃饭了吗"的重合度高达 0.67,因为候选里只有"你/吗"
# 两个虚词、而它们恰好都在问题里 —— 虚词污染了相似度,必须剔除后再比。
STOPWORDS = set(
    "你 我 他 她 它 的 了 吗 呢 啊 吧 呀 嘛 哦 是 有 会 在 和 与 就 都 也 很 太 真 "
    "这 那 个 什么 怎么 为什么 多少 几".split()
)


def content_tokens(tokens):
    """只保留实义词(去掉虚词与空白)。"""
    return [t for t in (tokens or []) if t and t not in STOPWORDS]


def patch_reply_quality(enable=True):
    """(已并入 patch_brain_reply,保留此函数仅为兼容旧调用)"""
    return None


def patch_brain_reply(enable=True):
    """把 kiki 逻辑引擎接进 reply 链路,并加上质量闸。

    最终链路(每个环节的职责都清楚):
        1. 精确匹配(raw_keyword 完全相同)  -> 上游记忆库,保留"教什么背什么"的调教手感
        2. kiki 逻辑引擎                    -> 事实/推理,能给出确定答案就直接返回
        3. 模糊匹配(上游) + **质量闸**      -> 候选与输入分词重合度不足则判未命中
        4. 兜底话术

    为什么需要质量闸
    ----------------
    上游模糊匹配(默认阈值 0.4)会把"你吃饭了吗"匹配到"你笨吗"。
    **答错比答不上更糟**,所以模糊候选必须"问什么答什么"才放行。
    引擎的事实性回答在第 2 步已返回,不受此闸影响。
    """
    global _REPLY_PATCHED
    import brain
    import config
    import models
    import service

    original = service.MemoriseService.reply
    if not enable or _REPLY_PATCHED:
        return original

    MIN_OVERLAP = 0.5   # 模糊候选:其实义词至少一半要出现在用户输入里

    def reply(self, ip, keyword, uid=""):
        kw = (keyword or "").strip()
        if kw:
            # 1. 精确匹配优先,交给上游(调教玩法的手感来源)
            try:
                exact = [e for e in self.index.all_entries()
                         if e.raw_keyword and e.raw_keyword == kw]
            except Exception:
                exact = []
            if not exact:
                # 2. 精确匹配没中,先让逻辑引擎试着推理出确切答案
                try:
                    key = (uid or "").strip() or (ip or "")
                    ans = brain.ask(kw, key)
                except Exception:
                    ans = None
                if ans:
                    return {"code": 200, "data": {"answer": ans["answer"]}}
        # 3. 其余情况沿用上游链路(模糊命中 / 工具 / 未命中话术)
        result = original(self, ip, keyword, uid)

        # 3.4 greeting 兜底:上游的问候语条目关键词为空,reply 的匹配逻辑**看不到它们**
        #     (它们只被 /Greeting 接口当开场白用)。所以用户主动说"你好"反而答不出。
        #     这里用问题本身与问候语回答文本的实义词重合度补上这一步。
        if result.get("code") == 10001 and kw:
            try:
                qtokens = content_tokens(service.cut_keyword(kw))
                if qtokens:
                    for entry in self.index.all_entries():
                        if (entry.category or "") != "greeting":
                            continue
                        atokens = content_tokens(service.cut_keyword(entry.answer or ""))
                        if any(t in qtokens for t in atokens):
                            return {"code": 200, "data": {"answer": entry.answer}}
            except Exception:
                pass

        # 3.5 质量闸:实义词覆盖不足就判未命中(答非所问比答不上更糟)
        if result.get("code") == 200 and kw:
            try:
                qtokens = content_tokens(service.cut_keyword(kw))
                if qtokens:
                    best = 0.0
                    for entry in self.index.all_entries():
                        if entry.tokens:
                            etokens = content_tokens(entry.tokens)
                            if not etokens:
                                continue
                            # 候选实义词里有多少能在问题实义词中找到
                            hit = sum(1 for t in etokens if t in qtokens)
                            ratio = hit / float(len(etokens))
                        elif (entry.category or "") == "greeting":
                            # greeting 条目关键词是空的(只当开场白用),但用户明确
                            # 说了问候语时应该能命中 —— 用回答文本里的实义词判断
                            atokens = content_tokens(service.cut_keyword(entry.answer or ""))
                            ratio = 1.0 if any(t in qtokens for t in atokens) else 0.0
                        else:
                            continue
                        if ratio > best:
                            best = ratio
                    if best < MIN_OVERLAP:
                        return {"code": 10001, "data": {"answer": service.NOT_FOUND_ANSWER}}
            except Exception:
                pass  # 质量检查失败不影响正常回复
        return result

    service.MemoriseService.reply = reply
    _REPLY_PATCHED = True
    return original


def patch(auto_approve=True):
    """把 service.MemoriseService.add 替换为"教学即生效"版本(幂等)。

    auto_approve=True  : 新教学内容直接 review_status="approved" 并加入内存索引
    auto_approve=False : 恢复上游行为(pending,等审核)
    返回被替换前的原函数。
    """
    global _PATCHED
    import config
    import models
    import service
    # 本地默认开启对象判断(让 talk 教学的条件生效)
    os.environ.setdefault("FEATURE_FLAG", "on")
    # 模糊匹配阈值:上游默认 0.4/0.3/0.2 偏松,实测会把
    # "你吃饭了吗"匹配到"你笨吗"、"你会唱歌吗"匹配到"你会什么"(答错比答不上更糟)。
    # 适度收紧到 0.5;更激进的"事实性回答不受影响"由 patch_reply_quality 负责。
    service.CATEGORY_THRESHOLDS.update({
        "word": 0.5, "sentence": 0.5, "syntax": 0.5,
        "logic": 0.4, "greeting": 0.5, "": 0.5,
    })

    original = service.MemoriseService.add
    if not auto_approve or _PATCHED:
        return original

    def add(self, ip, keyword, answer, category="auto", uid="", flag="all"):
        """上游 add() 的等价实现,唯一差别:落库状态为 approved 并立即进索引。"""
        # 深夜催睡:凌晨 3:50 ~ 6:00 不接受教学(与上游一致)
        if service.in_sleep_window():
            return {"code": 400, "data": service.SLEEP_ANSWER}
        # 限流与防注入检查(与上游一致)
        if not self._check_add_rate(ip or ""):
            return {"code": 429, "data": "教学太频繁了,休息一下吧~"}
        if not self._check_global_add_rate():
            return {"code": 429, "data": "教学太频繁了,休息一下吧~"}
        ok_ip, _until = self._check_ip_ban(ip or "")
        if not ok_ip:
            return {"code": 429, "data": "教学太频繁了,休息一下吧~"}
        if config.is_enabled("MAID") and uid and self._is_blocked(uid):
            return {"code": 400, "data": "你被禁止教学了"}
        cat = (category or "auto").strip().lower()
        if cat not in service.TEACH_CATEGORIES:
            return {"code": 400, "data": "教学分类不合法,可选:word/sentence/syntax/logic/greeting/auto"}
        is_greeting = cat == "greeting"
        kw = (keyword or "").strip()
        ans = (answer or "").strip()
        if not kw and not is_greeting:
            return {"code": 400, "data": "参数不合法:关键词不能为空"}
        if len(kw) > service.KEYWORD_MAX_LEN:
            return {"code": 400, "data": "参数不合法:关键词长度不能超过%d" % service.KEYWORD_MAX_LEN}
        if not ans:
            return {"code": 400, "data": "参数不合法:回答不能为空"}
        if len(ans) > service.ANSWER_MAX_LEN:
            return {"code": 400, "data": "参数不合法:回答长度不能超过%d" % service.ANSWER_MAX_LEN}
        flag_val = "all"
        if config.is_enabled("FLAG"):
            flag_val = (flag or "all").strip().lstrip("@")
            if not self._valid_flag(flag_val):
                return {"code": 400, "data": "参数不合法:flag 只能是 all / user:<uid> / favor:high|medium|low / time:dawn|day|dusk|night"}
        if ans in self.blacklist:
            self._record_reject(ip or "")
            return {"code": 400, "data": "这个回答好像不太妙,kiki拒绝记住它~"}
        if service.match_banned(ans):
            try:
                session = service.SessionLocal()
                session.add(models.Blacklist(answer=ans, created_at=datetime.now()))
                session.commit()
                self.blacklist.add(ans)
                session.close()
            except Exception:
                pass
            self._record_reject(ip or "")
            return {"code": 400, "data": "这个回答好像不太妙,kiki拒绝记住它~"}
        ok_fp, _fp = self._check_answer_fp(ip or "", ans)
        if not ok_fp:
            self._record_reject(ip or "")
            return {"code": 400, "data": "这个回答已经有很多人教过啦,kiki不重复记~"}
        tokens = service.cut_keyword(kw) or [kw]
        resolved_cat = service.detect_category(kw, tokens) if cat == "auto" else cat
        # auto 把"晚安/你好"这类词判成 greeting,而 greeting 语义是**开场白**
        # (存库时清空关键词、不参与问答)。可一旦用户给了条件(flag != all),
        # 他要的显然是"条件化回答"而不是开场白,所以这里强制归为 word,
        # 否则 talk time:night`晚安`... 会被静默丢掉,永远问不出来。
        want_flag = (flag or "all").strip().lstrip("@") not in ("", "all")
        if resolved_cat == "greeting" and want_flag:
            resolved_cat = "word"
        stored_tokens = tokens
        if resolved_cat == "greeting":
            # greeting 是单条问候语,不参与问答匹配(与上游一致)
            tokens = []
            stored_tokens = []
            kw = ""
        else:
            for entry in self.index.all_entries():
                existing = entry.tokens
                if tokens and existing and set(tokens) <= set(existing):
                    stored_tokens = list(dict.fromkeys(existing + tokens))
                    break
        session = service.SessionLocal()
        try:
            now = datetime.now()
            row = models.Memorise(
                ip=ip or "",
                keyword=",".join(stored_tokens),
                answer=ans,
                raw_keyword=kw,
                hit_count=0,
                created_at=now,
                updated_at=now,
                review_status="approved",          # ← 与上游唯一差别:直接过审
                category=resolved_cat,
                flag=flag_val if config.is_enabled("FLAG") else "all",
                uid=(uid or "").strip() if config.is_enabled("MAID") else None,
            )
            session.add(row)
            session.commit()
            session.refresh(row)
            with self._rate_lock:
                self._ip_ok[ip or ""] += 1
            if config.is_enabled("FAVOR"):
                self._touch_favor(uid, ip, teach=1)
            # 立即进内存索引:这正是审核通过时 review_pending() 做的事
            self.index.add(service.MemoryEntry(
                row.memory_id, stored_tokens, ans, kw, 0, "approved", resolved_cat,
                row.flag or "all",
            ))
            self._resolve_misses(row, session)
            # 同步喂给 kiki 逻辑引擎:把"关键词`回答"这种教学也变成可推理的事实
            try:
                import brain as _brain
                _brain.get_brain().teach_pair(kw, ans)
            except Exception:
                pass
            return {
                "code": 200,
                "data": {
                    "ip": ip or "",
                    "keyword": ",".join(stored_tokens),
                    "answer": ans,
                    "category": resolved_cat,
                },
            }
        finally:
            session.close()

    service.MemoriseService.add = add
    _PATCHED = True
    return original


def seed(ips=40):
    """把 seed_qa.py 的 39 条人设问答灌进本地库(走打补丁后的 add,直接生效)。"""
    import service
    # seed_qa.py 位于项目根(server-py 的上一级),不在默认模块搜索路径里,显式补上
    _here = os.path.dirname(os.path.abspath(__file__))
    _root = os.path.dirname(_here)
    if _root not in sys.path:
        sys.path.insert(0, _root)
    import seed_qa

    patch(True)
    svc = service.MemoriseService()
    svc.reload()  # 从库重建索引(含此前已 approved 的数据)
    ok = fail = 0
    for i, (kw, ans) in enumerate(seed_qa.QA):
        ip = "192.168.%d.%d" % (random.randint(0, 254), random.randint(1, 254))
        r = svc.add(ip, kw, ans)
        if r.get("code") == 200:
            ok += 1
        else:
            fail += 1
            print("FAIL[%d] %s -> %s" % (i, kw, r))
    print("灌入完成:成功 %d,失败 %d,当前知识条数 %d" % (ok, fail, svc.status()["data"]))


def main():
    # 对象判断(P4)默认开启:让"同一句话对不同人/不同时段给不同回答"生效,
    # 对应 kiki 卡片上说的"上下文理解"。chat_client 用 talk 指令教这类回答。
    os.environ.setdefault("FEATURE_FLAG", "on")
    if "--no-flag" in sys.argv:
        os.environ["FEATURE_FLAG"] = "off"
    if "--seed" in sys.argv:
        seed()
        return
    patch(True)
    patch_brain_reply(True)
    import uvicorn
    import config
    import brain
    print("[local] 教学即生效(无审核队列) + kiki 逻辑引擎已接入,监听 127.0.0.1:%d" % config.http_port())
    print("[local] 事实库:%d 条(webmarisa 词库与逻辑引擎各管一半)" % brain.get_brain().store.count())
    print("[local] 对象判断(flag):%s" % ("开启" if config.is_enabled("FLAG") else "关闭"))
    uvicorn.run("main:app", host="127.0.0.1", port=config.http_port(), log_level="info")


if __name__ == "__main__":
    main()
