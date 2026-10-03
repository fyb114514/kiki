# -*- coding: utf-8 -*-
"""
Vosk 中文小模型的客观准确率基准测试。

为什么做这个
------------
用户反馈"语音识别效果奇差"。要修就得先分清原因:
  (a) 我的录音代码有问题(采样率/音频源/缓冲)
  (b) 模型本身能力有限(vosk-model-small-cn-0.22 只有 42MB)
如果 (b),再怎么改代码也没用,只能换更大的模型。

做法:用 TTS 合成已知句子,重采样成 16kHz 单声道 PCM,喂给**同一个模型**,
     算字错误率。这样排除了麦克风、环境噪声、录音链路的所有干扰 ——
     得到的是模型在"理想音频"下的上限。

工具选型上踩的坑(记录备查)
--------------------------
1. Windows SAPI(System.Speech)在 Python 子进程里**写不出音频**:
   能 SelectVoice 成功、能建文件,但大小恒为 0 字节。
   (直接跑 PowerShell 却正常 —— 沙箱里的 SAPI 受限。)
2. 于是改用 edge-tts(微软在线神经网络语音,免密钥),能稳定生成 mp3。
3. Vosk 只吃 16kHz 单声道 PCM,所以要把 24kHz 的 mp3 重采样。
   本机没有 ffmpeg,改用 soundfile 读 + numpy 线性插值重采样。
"""
import asyncio
import json
import os
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = r"D:\nbjj\dsh\android-tools\asrbench"
MODEL = os.path.join(r"D:\nbjj\dsh\android-tools\vosk\model", "vosk-model-small-cn-0.22")

VOICE = "zh-CN-XiaoxiaoNeural"

SENTENCES = [
    "你好",
    "今天天气怎么样",
    "你叫什么名字",
    "我喜欢吃苹果",
    "明天会下雨吗",
    "东吴是谁建立的",
    "北京是谁的首都",
    "比尔盖茨是谁",
    "卡卡是一只猫",
    "谢谢你",
]

TARGET_SR = 16000


def synth(text, path_mp3):
    """edge-tts 合成"""
    import edge_tts

    async def go():
        c = edge_tts.Communicate(text, VOICE)
        await c.save(path_mp3)

    asyncio.run(go())
    return os.path.exists(path_mp3) and os.path.getsize(path_mp3) > 0


def to_wav16k(path_mp3, path_wav):
    """mp3 -> 16kHz 单声道 16bit PCM wav"""
    import soundfile as sf

    data, sr = sf.read(path_mp3)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != TARGET_SR:
        n = int(round(len(data) * TARGET_SR / float(sr)))
        idx = np.linspace(0, len(data) - 1, n)
        data = np.interp(idx, np.arange(len(data)), data)
    pcm = np.clip(data, -1.0, 1.0)
    pcm = (pcm * 32767).astype("<i2")
    with wave.open(path_wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(TARGET_SR)
        w.writeframes(pcm.tobytes())
    return True


def recognize(wav_path):
    """用 vosk 识别"""
    from vosk import Model, KaldiRecognizer, SetLogLevel

    SetLogLevel(-1)
    wf = wave.open(wav_path, "rb")
    rec = KaldiRecognizer(Model(MODEL), wf.getframerate())
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
    """字错误率(编辑距离)"""
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
    print("模型: %s" % os.path.basename(MODEL))
    print("TTS : edge-tts / %s" % VOICE)
    print("=" * 68)
    total = 0.0
    exact = 0
    n = 0
    for i, s in enumerate(SENTENCES):
        mp3 = os.path.join(WORK, "s%02d.mp3" % i)
        wav = os.path.join(WORK, "s%02d.wav" % i)
        if not synth(s, mp3):
            print("  [合成失败] %s" % s)
            continue
        to_wav16k(mp3, wav)
        got = recognize(wav)
        c = cer(s, got)
        total += c
        n += 1
        if got == s:
            exact += 1
        print("  [%s] 原文: %s" % ("OK " if got == s else "!! ", s))
        print("        识别: %s   (字错率 %.0f%%)" % (got or "(空)", c * 100))
    print("=" * 68)
    print("完全正确: %d/%d" % (exact, n))
    print("平均字错率: %.1f%%" % (total / max(1, n) * 100))
    print()
    print("判读:理想音频下字错率仍高 => 模型能力问题(要换大模型)")
    print("      理想音频下字错率低 => 录音链路问题(改代码能解决)")


if __name__ == "__main__":
    main()
