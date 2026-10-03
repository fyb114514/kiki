# -*- coding: utf-8 -*-
"""
小模型 vs 大模型 的对比基准 —— 专测"语速快"和"多音字"。

背景
----
用户反馈:"语音识别不是很准,语速稍快一点或者是多音字就分不出来了。"
官方公布的字错率:
    vosk-model-small-cn-0.22        42M   SpeechIO-02 23.54%  THCHS 17.15%
    vosk-model-cn-kaldi-multicn-0.15 1.5G SpeechIO-02 17.44%  THCHS  9.56%
但官方数字是别人的测试集,这里用**同样的音频**喂两个模型,自己量。

设计
----
- 用 edge-tts 合成,并通过 rate 参数**改变语速**(+0% / +25% / +50%)。
- 句子刻意包含多音字与易混词(了/乐、行/航、还/环、长/chang/zhang…)。
- 三组条件各测一遍,分别统计字错率。
"""
import asyncio
import json
import os
import sys
import wave

import numpy as np

TARGET_SR = 16000
VOICE = "zh-CN-XiaoxiaoNeural"
WORK = r"D:\nbjj\dsh\android-tools\asrbench_cmp"

MODELS = {
    "small(42M)": r"D:\nbjj\dsh\android-tools\vosk\model\vosk-model-small-cn-0.22",
    "BIG(1.5G)": r"D:\nbjj\dsh\android-tools\vosk\big\model\vosk-model-cn-kaldi-multicn-0.15",
}

# 多音字 / 易混 / App 相关
SENTENCES = [
    "你好",
    "谢谢你",
    "我喜欢吃苹果",
    "今天天气怎么样",
    "你叫什么名字",
    "长江有多长",
    "银行在哪里",
    "我还想再玩一会儿",
    "他长得很高",
    "快乐和音乐",
    "东吴是谁建立的",
    "北京是谁的首都",
    "卡卡是一只猫",
    "比尔盖茨是谁",
    "一个小时是几分钟",
    "水在一百摄氏度沸腾",
]

RATES = [("常速", "+0%"), ("稍快", "+25%"), ("很快", "+50%")]


def synth(text, path_mp3, rate):
    import edge_tts

    async def go():
        c = edge_tts.Communicate(text, VOICE, rate=rate)
        await c.save(path_mp3)

    asyncio.run(go())
    return os.path.exists(path_mp3) and os.path.getsize(path_mp3) > 0


def to_wav16k(mp3, wav):
    import soundfile as sf

    data, sr = sf.read(mp3)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != TARGET_SR:
        n = int(round(len(data) * TARGET_SR / float(sr)))
        idx = np.linspace(0, len(data) - 1, n)
        data = np.interp(idx, np.arange(len(data)), data)
    pcm = (np.clip(data, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(TARGET_SR)
        w.writeframes(pcm.tobytes())


def recognize(model_dir, wav, model_cache={}):
    from vosk import Model, KaldiRecognizer, SetLogLevel

    SetLogLevel(-1)
    if model_dir not in model_cache:
        model_cache[model_dir] = Model(model_dir)
    m = model_cache[model_dir]
    wf = wave.open(wav, "rb")
    rec = KaldiRecognizer(m, wf.getframerate())
    out = []
    while True:
        data = wf.readframes(4000)
        if len(data) == 0:
            break
        if rec.AcceptWaveform(data):
            out.append(json.loads(rec.Result()).get("text", ""))
    out.append(json.loads(rec.FinalResult()).get("text", ""))
    wf.close()
    return "".join(x.replace(" ", "") for x in out if x)


def cer(expected, got):
    e, g = list(expected), list(got)
    prev = list(range(len(g) + 1))
    for i in range(1, len(e) + 1):
        cur = [i] + [0] * len(g)
        for j in range(1, len(g) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (e[i - 1] != g[j - 1]))
        prev = cur
    return prev[len(g)] / max(1, len(e))


def main():
    os.makedirs(WORK, exist_ok=True)
    # 先合成全部音频
    print("合成测试音频(3 种语速 × %d 句)..." % len(SENTENCES), flush=True)
    files = {}
    for ri, (rname, rate) in enumerate(RATES):
        for si, s in enumerate(SENTENCES):
            mp3 = os.path.join(WORK, "r%d_s%02d.mp3" % (ri, si))
            wav = os.path.join(WORK, "r%d_s%02d.wav" % (ri, si))
            if not os.path.exists(wav):
                if not synth(s, mp3, rate):
                    print("  合成失败:", s)
                    continue
                to_wav16k(mp3, wav)
            files[(ri, si)] = wav
    print("  完成 %d 个音频\n" % len(files), flush=True)

    results = {}
    for mname, mdir in MODELS.items():
        if not os.path.isdir(mdir):
            print("跳过 %s(模型目录不存在)" % mname)
            continue
        print("=== 模型 %s ===" % mname, flush=True)
        per_rate = {}
        for ri, (rname, rate) in enumerate(RATES):
            tot = 0.0
            n = 0
            exact = 0
            for si, s in enumerate(SENTENCES):
                wav = files.get((ri, si))
                if not wav:
                    continue
                got = recognize(mdir, wav)
                c = cer(s, got)
                tot += c
                n += 1
                if got == s:
                    exact += 1
            per_rate[rname] = (tot / max(1, n), exact, n)
            print("  %-4s 字错率 %5.1f%%   完全正确 %d/%d"
                  % (rname, tot / max(1, n) * 100, exact, n), flush=True)
        results[mname] = per_rate

    print("\n" + "=" * 60)
    print("对比汇总(字错率越低越好)")
    print("%-12s %10s %10s %10s" % ("模型", "常速", "稍快", "很快"))
    for mname, pr in results.items():
        print("%-12s %9.1f%% %9.1f%% %9.1f%%"
              % (mname, pr["常速"][0] * 100, pr["稍快"][0] * 100, pr["很快"][0] * 100))


if __name__ == "__main__":
    main()
