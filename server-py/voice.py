# -*- coding: utf-8 -*-
"""语音壳:让kiki"能出声、能听你说话"。

本机现实(已探测):
    TTS   ✅ 有 Microsoft Zira(en-US),可离线合成到 wav —— 但**念中文会哑**
    ASR   ❌ 注册表里 0 个识别引擎,没装中文语音包 -> 现在无法开麦识别
    麦克风 ✅ 硬件在(Realtek / NVIDIA HD Audio)

所以本模块的设计原则是**能力探测 + 优雅降级**,而不是假装能用:
    speak(text)         把回答念出来(用的是本机 SAPI 语音)
    available()         返回当前语音能力,便于界面提示
    listen()            录音并识别;无识别引擎时明确报错,不静默失败
    voice_chat_loop()   语音对话主循环(识别 -> 引擎 -> 合成)

补上中文能力的方法(免费、离线):
    Windows 设置 -> 时间和语言 -> 语音 -> 添加语音"中文(简体,中国)"
装完后 `available()` 会显示中文 TTS;识别引擎也会出现,`listen()` 即可用。
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def _ps(script):
    """在 Windows PowerShell 里跑一段脚本并返回输出。

    注意:PowerShell 5.1 管道传中文会按 GBK 误解码,所以脚本一律用
    -EncodedCommand 以 UTF-16LE 传入,避免中文变问号。
    """
    import base64
    enc = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", enc],
            capture_output=True, timeout=120,
        )
        return (out.stdout or b"").decode("utf-8", "replace"), (out.stderr or b"").decode("utf-8", "replace")
    except FileNotFoundError:
        return "", "找不到 powershell"
    except subprocess.TimeoutExpired:
        return "", "语音操作超时"


def _readable_error(raw):
    """把 PowerShell 的 CLIXML 错误摘要成一行人话。

    PowerShell 非交互执行时错误会以 CLIXML 序列化到 stderr,
    直接展示给用户就是一坨 XML,所以这里抽出 <S S="Error"> 里的文本。
    """
    raw = raw or ""
    if "<Objs" not in raw and "CLIXML" not in raw:
        return raw.strip()
    import re as _re
    msgs = _re.findall(r'<S S="Error">(.*?)</S>', raw, _re.S)
    out = []
    for m in msgs[:2]:
        t = (m.replace("_x000D_", "").replace("_x000A_", " ").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", '"').replace("&amp;", "&"))
        out.append(t.strip())
    return " | ".join(x for x in out if x) or raw.strip()[:200]


def _has_chinese(text):
    return any("\u4e00" <= ch <= "\u9fff" for ch in (text or ""))


def available():
    """探测语音能力 -> {"tts": [...], "asr": [...], "chinese_tts": bool}。

    分两次探测并用 try/catch 包住:没有识别引擎时 SetInputToDefaultAudioDevice
    会抛 NullReference,不该让整个探测失败。
    """
    script = (
        "$ErrorActionPreference='SilentlyContinue';"
        "Add-Type -AssemblyName System.Speech;"
        "$v = @();"
        "try { $s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
        "  $v = @($s.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name + '|' + $_.VoiceInfo.Culture });"
        "  $s.Dispose() } catch {};"
        "Write-Output ('TTS:' + ($v -join ','));"
        "$r = @();"
        "try { $r = @([System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() |"
        "  ForEach-Object { $_.Id + '|' + $_.Culture }) } catch {};"
        "Write-Output ('ASR:' + ($r -join ','));"
        "exit 0"
    )
    out, err = _ps(script)
    info = {"tts": [], "asr": [], "chinese_tts": False, "error": err.strip()}
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("TTS:"):
            items = [x for x in line[4:].split(",") if x]
            info["tts"] = items
            info["chinese_tts"] = any("zh" in x.lower() or "Chinese" in x for x in items)
        elif line.startswith("ASR:"):
            info["asr"] = [x for x in line[4:].split(",") if x]
    # CLIXML 噪音不算真正的错误信息
    if "<Objs" in info["error"] or "CLIXML" in info["error"]:
        info["error"] = ""
    return info


def speak(text, out_wav=None):
    """把 text 念出来(本机 SAPI)。返回 {"ok":bool, "wav":路径|None, "msg":str}。

    - out_wav 非空时只合成到文件,不播放(便于检查/留存)。
    - 中文文本 + 只有英文语音时,会如实告知"念不出来",而不是静默失败。
    """
    text = (text or "").strip()
    if not text:
        return {"ok": False, "wav": None, "msg": "没有内容可念"}
    info = available()
    # 中文 + 没有中文语音 -> 直接说清楚,SAPI 会抛错且生成 0 字节
    if _has_chinese(text) and not info["chinese_tts"]:
        return {
            "ok": False, "wav": None,
            "msg": "本机没有中文语音,念不出中文。请先安装:"
                   "设置 -> 时间和语言 -> 语音 -> 添加语音「中文(简体,中国)」",
        }
    wav = out_wav or os.path.join(tempfile.gettempdir(), "marisa_say.wav")
    safe = text.replace("'", "''")
    wav_ps = wav.replace("'", "''")
    script = (
        "$ErrorActionPreference='Stop';"
        "Add-Type -AssemblyName System.Speech;"
        "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
        "try {"
        "  $s.SetOutputToWaveFile('%s');"
        "  $s.Speak('%s');"
        "  $s.SetOutputToNull();"          # 必须先收尾,否则 wav 头部不落盘 -> 0 KB
        "  Write-Output 'OK'"
        "} finally { $s.Dispose() }"
        % (wav_ps, safe)
    )
    out, err = _ps(script)
    ok = "OK" in out and os.path.exists(wav) and os.path.getsize(wav) > 1024
    msg = ("已合成 %d KB" % (os.path.getsize(wav) // 1024)) if ok else (_readable_error(err) or "合成失败")
    # 中文能探测到语音、却仍合成失败时,几乎都是"进程拿不到 SAPI 权限"
    # (实测:在受限沙箱里运行会报"系统上未安装语音",而普通终端里正常)
    if not ok and info["tts"] and "未安装语音" in msg:
        msg += "(已装语音却拿不到权限:可能运行在受限环境里;请在普通终端重试)"
    return {"ok": ok, "wav": wav if ok else None, "msg": msg}


def listen(seconds=5, wav_path=None):
    """录音并识别。无识别引擎时**明确失败**,不做假的"听到了"。

    返回 {"ok":bool, "text":str, "msg":str}
    """
    info = available()
    if not info["asr"]:
        return {
            "ok": False, "text": "",
            "msg": "本机没有语音识别引擎,无法开麦。请先安装中文语音包:"
                   "设置 -> 时间和语言 -> 语音 -> 添加语音「中文(简体,中国)」",
        }
    wav = wav_path or os.path.join(tempfile.gettempdir(), "marisa_mic.wav")
    script = (
        "Add-Type -AssemblyName System.Speech;"
        # 用默认输入设备录到 wav(固定秒数,避免一直等静音)
        "$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,"
        "[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,"
        "[System.Speech.AudioFormat.AudioChannel]::Mono);"
        "$rec = New-Object System.Speech.Recognition.SpeechRecognitionEngine;"
        "$rec.SetInputToDefaultAudioDevice();"
        "$rec.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar));"
        "$r = $rec.Recognize([TimeSpan]::FromSeconds(%d));"
        "if ($r) { Write-Output ('TEXT:' + $r.Text) } else { Write-Output 'TEXT:' }"
        % int(seconds)
    )
    out, err = _ps(script)
    text = ""
    for line in out.splitlines():
        if line.startswith("TEXT:"):
            text = line[5:].strip()
    if not text:
        return {"ok": False, "text": "", "msg": _readable_error(err) or "没听清,再说一次?"}
    return {"ok": True, "text": text, "msg": "识别成功"}


def play(wav_path):
    """播放一个 wav(Windows 用 winsound,异步不阻塞对话)。

    说明:沙箱里被测出过 SAPI 写盘/播放被拒;真实终端里应该可用。
    这里只负责"尝试播放",失败返回 False,由调用方决定是否提示。
    """
    if not wav_path or not os.path.exists(wav_path):
        return False
    try:
        import winsound
        winsound.PlaySound(wav_path, winsound.SND_FILENAME | winsound.SND_ASYNC)
        return True
    except Exception:
        return False


def say(text):
    """一句话完成"合成 + 播放",失败时返回原因(不抛异常)。

    返回 {"ok":bool, "wav":路径|None, "played":bool, "msg":str}
    """
    r = speak(text)
    if not r["ok"]:
        return {"ok": False, "wav": None, "played": False, "msg": r["msg"]}
    played = play(r["wav"])
    msg = r["msg"] if played else (r["msg"] + "(合成成功但播放失败)")
    return {"ok": True, "wav": r["wav"], "played": played, "msg": msg}


def voice_chat_loop(ask_fn, say_prefix="kiki > "):
    """语音对话主循环:说完 -> 识别 -> 交给引擎 -> 念出回答。

    ask_fn(text) -> 回答字符串(由调用方接 HTTP /Reply 或直接问引擎)。
    """
    info = available()
    print("语音能力: TTS=%s | 中文TTS=%s | ASR=%s" % (
        info["tts"] or "无", info["chinese_tts"], info["asr"] or "无"))
    if not info["asr"]:
        print("!! 无法开麦:", listen()["msg"])
        print("!! 你可以先用键盘输入,回答仍会用语音念出来。")
    while True:
        try:
            text = ""
            if info["asr"]:
                r = listen()
                if not r["ok"]:
                    print("   (%s)" % r["msg"])
                    continue
                text = r["text"]
                print("你(语音) >", text)
            else:
                text = input("你 > ").strip()
                if not text or text.lower() in ("quit", "exit"):
                    break
            answer = ask_fn(text)
            print(say_prefix, answer)
            res = speak(answer)
            if not res["ok"]:
                print("   (念不出来: %s)" % res["msg"])
        except (KeyboardInterrupt, EOFError):
            print()
            break


def main():
    """自检:打印语音能力 + 合成一段样例 wav。"""
    info = available()
    print("语音能力自检")
    print("  TTS 语音 :", info["tts"] or "无")
    print("  中文 TTS :", "有" if info["chinese_tts"] else "无(中文发音会不准)")
    print("  识别引擎 :", info["asr"] or "无(无法开麦)")
    if info.get("error"):
        print("  备注     :", info["error"])
    r = speak("你好呀,我是kiki")
    print("  合成测试 :", r["msg"])
    if r["wav"]:
        print("  wav 文件 :", r["wav"], "(%d KB)" % (os.path.getsize(r["wav"]) // 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
