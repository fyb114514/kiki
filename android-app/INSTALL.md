# kiki · Android 版 安装与使用

## 交付物

| 项 | 值 |
|---|---|
| 文件 | `build\MarisaKiki.apk` |
| 应用名 | **kiki** |
| 大小 | 45.18 MB(内置语音模型) |
| 包名 | `com.marisa.kiki` |
| 版本 | 1.0 (versionCode 1) |
| 兼容 | Android 7.0 (API 24) ~ Android 16 (API 36) |
| 签名 | v2 + v3 方案,自签(自用) |
| 原生库 | `lib/arm64-v8a/libvosk.so`、`lib/arm64-v8a/libjnidispatch.so` |
| 内置模型 | `assets/model-cn.zip`(41.86 MB,Vosk 中文小模型) |

**装上就能用,不需要任何额外步骤。** 语音模型已打进 APK,
首次启动自动解压到应用私有目录(约 0.7 秒),之后每次直接加载。

---

## 一、安装

```powershell
$adb = 'D:\nbjj\dsh\android-sdk\platform-tools\adb.exe'
& $adb install -r "D:\nbjj\dsh\marisa-revive\android-app\build\MarisaKiki.apk"
```
或把 APK 传到手机点击安装(允许"未知来源")。

**首次启动**日志:
```
I/kiki-vosk: 首次运行:开始从 assets 解压语音模型…
I/kiki-vosk: 模型解压完成,耗时 758 ms
I/kiki-vosk: 模型加载完成,耗时 434 ms
```
**之后每次启动**直接加载,不再解压。

---

## 二、语音交互

底部深灰区域支持**两种手势**,界面提示写的是「长按说话 / 点一下开始」:

| 手势 | 行为 |
|---|---|
| **长按** | 按下开始录,松手立即停(最可靠,原版行为) |
| **点一下** | 点一下开始录,说完自动停(静音判定收尾);想立刻结束就**再点一下** |

录制中区域变绿并显示「● 正在录音…」。

> 踩过的坑:曾经短按也去调 `startRecording()`,于是第二次点击时 `recording` 已是 true、
> 直接返回,而松手时又因按住时间 < 400ms 不调 `stopRecording()` —— **再也停不下来**。
> 用户反馈原话:"长按正常,但是点击只有第一次可选"。
> 现在的规则:**只要正在录音,下一次触摸无论多短都算"停止"**。

---

## 三、为什么不用系统语音识别

这台手机(Redmi K70E / Android 16)上**系统语音识别用不了**。证据链:

```
I/kiki-speech: createRecognizer 结果=SpeechRecognizerProxy
I/kiki-speech: onReadyForSpeech 就绪
I/AsrService:  onStopListening: mUseLocal=false     ← 小米走的是【在线】识别
D/AsrService:  error code: 50010004                 ← 小米服务器拒绝
I/kiki-speech: onError 触发 code=1 (ERROR_NETWORK)
```

设备上**只有小米在线识别引擎**一个识别服务,它没有离线模型,对第三方调用固定
返回 `50010004`;关梯子、换网络都无效,系统里也没有第二个识别服务可换。
所以改为在 App 内集成 **Vosk 离线引擎**(Apache 2.0) + 中文小模型。

---

## 四、语音识别调试实录(重要)

用户反馈"语音识别效果奇差"。**结论:模型完全没问题,是录音链路的问题。**

### 客观基准:同一个模型在干净音频上 100% 准确

`server-py/asr_bench.py` 用 TTS 合成 10 句中文,喂给**同一个** Vosk 模型:

```
[OK] 原文: 比尔盖茨是谁      识别: 比尔盖茨是谁       字错率 0%
[OK] 原文: 东吴是谁建立的     识别: 东吴是谁建立的      字错率 0%
[OK] 原文: 卡卡是一只猫      识别: 卡卡是一只猫       字错率 0%
完全正确: 10/10     平均字错率: 0.0%
```

**所以"换更大模型"是错的方向** —— 42MB 小模型本身够用。任何"识别不准"的怀疑,
先用这个脚本测模型上限,避免走弯路。

### 修掉的四个录音链路问题

| # | 问题 | 根因与修法 |
|---|---|---|
| 1 | **音频源选错** | 原用 `VOICE_RECOGNITION`,它加**降噪+AGC**,破坏 Vosk 依赖的声学特征。改为 `MIC` → `VOICE_RECOGNITION` → `UNPROCESSED` 依次尝试(实测 `UNPROCESSED` 在这台机器上底噪 ~1600、人声峰值仅 ~1200,信噪比 < 1,反而最差) |
| 2 | **松手立刻掐断** | 快速松手时 Vosk 拿不到尾部音频,`FinalResult` 为空 → "没听清"。现在松手后**再录 500ms** 收尾 |
| 3 | **底噪校准被污染** | 原用开头几帧的**中位数**,可能正好采到"按下瞬间的碰触噪声"(实测底噪=5135 而峰值=2248,话被当静音)。改为取**观察到的最小值** |
| 4 | **"一直不停"** | 原判据是"最后一次超过门槛的时间",环境里偶尔一声响动就**清零计时**,永远到不了静音判停(录音一路跑到 8 秒上限)。改为**滑动窗口比例判定**:窗口内"有语音帧占比 ≤ 15%"才算静音,短促响动不再重置计时 |

另外把静音判停从 1.5s 调到 2.8s(换气时不被切断)、单次录音上限 15s 收到 8s
(避免吞入大量杂音把结果变乱码)、门槛上限压到 2000(避免小声说话被判静音)。

### 诊断日志(排障靠它)

```
I/kiki-vosk: 音频源: MIC
I/kiki-vosk: 底噪估计(最小值): 86
I/kiki-vosk: 本次录音: 底噪=86 峰值=7843 信噪比=90.2 门槛=500 时长=1779ms 超时收尾=false 是否说过话=true
I/kiki-vosk: 识别结果: [你好]
I/kiki-vosk: 录音线程结束,耗时 1779 ms,busy 已释放
```

判读:**信噪比**是关键 —— 大于 10 基本都能识别对;接近 1 说明麦克风没收到人声。

### 识别准确率的真实边界(实测,别猜)

用户反馈:"语速稍快一点或者是多音字就分不出来了"。把真实录音取下来逐项验证过:

**① 音频本身是好的** —— 用真实录音分析:
```
采样率 16000 Hz, 时长 3.07 秒
整体 RMS 2403, 峰值 15507, 削波样本 0 个
底噪(25%)409  vs  人声(75%)3030   ->  信噪比 7.4 倍
```
没有削波、信噪比够,所以**不是录音链路的问题**(链路问题前面已经修完了)。

**② 换大模型没用** —— 同一段真实录音("水在一百摄氏度沸腾"),两个模型都错,而且错得不同:
```
用户原话:  水在一百摄氏度沸腾
small 42M: 水灾 一百 舒适度 都 沸腾     (在→灾, 摄氏度→舒适度)
BIG  1.5G: 水 在 一百四十 都 沸腾       (摄氏→四十)
```
用 TTS 合成音频做三档语速对比(16 句 × 3 档),结果**大模型反而更差**:
```
模型            常速    稍快    很快
small(42M)     0.7%    1.5%    1.5%
BIG(1.5G)      1.6%    1.6%    4.2%
```
而且大模型解压后要 **2.6GB**。结论:**不该换大模型**,已删除(释放 4.2GB)。

**③ 拼音纠错也走不通** —— 思路是"汉字可能听错,但拼音往往是对的",实测:
```
识别: 水灾一百舒适度都沸腾   正确: 水在一百摄氏度沸腾   拼音相似度 70%
识别: 水在一百四十都沸腾     正确: 水在一百摄氏度沸腾   拼音相似度 78%
识别: 东吴是谁建立的        正确: 东吴是谁建立的      拼音相似度 100%
识别: 你好(无关)           最好仅 33%
```
看着可行,但**候选池里没有正确说法**时(数据库存的是 `水 在 100摄氏度沸腾`,
阿拉伯数字,与"一百"拼不上),相似度只有 0.50,达不到安全阈值;
而把阈值降到 0.6 又会把 `北京是谁的首都` **误纠**成 `北京是中国的首都`。
**帮倒忙比不纠更糟,所以这条实现已撤掉**(避免留死代码)。

**④ 词表偏置走不通** —— 试过用知识库词表做 `setGrammar` 动态词表:
```
普通识别    : 水灾 一百 舒适度 都 沸腾
词表偏置识别: 北京 摄氏度 北京          <- 更糟
WARNING: Ignoring word missing in vocabulary: '水在一百摄氏度沸腾'
```
中文模型里多字词不在其词表内会被忽略,结果输出乱码。

### 所以真正的结论

**42MB 小模型的声学能力就是上限**,这类"声学混淆"(在/灾、摄氏度/舒适度、摄氏/四十)
是它的固有局限,调代码解决不了。要明显提升,必须**换识别引擎**:

| 候选 | 说明 | 状态 |
|---|---|---|
| `csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` | 中英双语流式,**专为端上设计** | 未尝试 |
| `csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28` | Paraformer,中文最强开源方案之一 | 未尝试 |
| `FunAudioLLM/SenseVoiceSmall` | 阿里 SenseVoice,中文准确率高 | 未尝试 |

以上在 **hf-mirror.com** 上都能下(实测下载速度 12~22 MB/s;
而 alphacephei 只有 20 KB/s,1.3GB 要 18 小时 —— 所以要用国内镜像)。

换引擎是**另一个工程**:要集成 sherpa-onnx 的 JNI 原生库(不是改几行代码),
并且它的模型格式与 Vosk 完全不同。

### 排障用录音

`OfflineRecognizer` 会把每次录音存到
`/sdcard/Android/data/com.marisa.kiki/files/last_recording.wav`(16kHz 单声道 PCM),
取出来就能在 PC 上用不同模型离线对比:
```powershell
& $adb pull /sdcard/Android/data/com.marisa.kiki/files/last_recording.wav .
```
至今保留的一份真实样本在 `android-tools\asrbench_real\real.wav`,
`server-py/asr_bench.py` 与 `asr_compare.py` 是对应的基准脚本。

---

## 五、因果 / 属性推理(本地引擎的核心能力)

用户最初要的能力:**"知道比尔盖茨是微软(一家大公司)的 CEO 后,能够推断出他很出名"** ——
这类结论**不在事实库里**,必须从已有事实 + 规则推出来。

> **方向说明**:曾经尝试过"不会就上网搜、贴原话",结果它退化成一个**搜索引擎**
> (用户原话:"怎么变成搜索引擎了")。搜网页贴原话不叫 AI —— 既不理解、也不学习、更不推理。
> 所以联网那条路已**彻底移除**,改为把本地推理做深。

实现:PC 侧 `server-py/causal.py`,Android 侧 `Brain.java` 的 `causalAnswer` / `causalYesNo`。
规则写成**数据表**,不是散落在 if/else 里。

### 三类推理

| 类型 | 例子 |
|---|---|
| **属性继承** | 卡卡是猫,猫是哺乳动物 → 卡卡是哺乳动物 → 动物 → 生物(四级链) |
| **逆关系** | 东吴 建立者 孙权 → **东吴由孙权建立**;毕昇 发明 活字印刷术 → **活字印刷术由毕昇发明** |
| **因果蕴含** | 东吴 建立者 孙权 → **孙权是历史人物**;毕昇 发明 X → **毕昇是发明家** |

### 真机自检结果(20/20 通过)

`EngineSelfTest.java` 在启动时直接跑推理用例,结果见 logcat tag `kiki-engine`:

```
[OK] 东吴是谁建立的      -> 东吴由孙权建立。
[OK] 活字印刷术是谁发明的  -> 活字印刷术由毕昇发明。      ← 反查宾语
[OK] 北京是谁的首都      -> 北京是中国的首都。
[OK] 昆明是谁的省会      -> 昆明是云南省的省会。
[OK] 孙权是历史人物吗     -> 对,孙权是历史人物(创立了东吴)。  ← 因果蕴含
[OK] 毕昇是发明家吗      -> 对,毕昇是发明家(发明了活字印刷术)。
[OK] 卡卡是什么         -> 卡卡是猫、哺乳动物、动物、生物。
[OK] 氧原子序数         -> 氧的原子序数是8。
[OK] 小明的爷爷是谁      -> 推出来的:小明的爷爷是张建国。
=== 引擎自检: 20/20 通过 ===
```

### 修掉的四个真 bug(都是自检抓出来的)

1. **因果规则模板变量用反** —— `孙权是历史人物吗` 被答成
   "对,**东吴**是历史人物(创立了**孙权**)",完全说反。
   模板里 `%1$s` 是事实主语(东吴)、`%2$s` 是宾语(孙权),而"历史人物"应该是**宾语**。
2. **反问待答机制吞掉用户提问** ⚠️ —— 一旦反问("氧原子序数 是什么？"),
   **下一个问题会被当成答案静默学走**(`谁发明了电话` → "明白了")。
   修法:待答前先判断 `looksLikeQuestion`,是问句就不当作答案。
3. **逆关系"由…建立"方向错** —— 第一版写成"孙权由东吴建立"。
   中文里"由…建立"的主语是**被建立的东西**,应为"东吴由孙权建立"。
4. **裸属性问句漏了** —— `氧原子序数`(没有"的")匹配不上要求有"的"的正则。
   补了"从右往左把结尾切成本库已知关系"的分支。

---

## 六、界面与交互(还原 2011 原版)

| 视觉特征 | 实现 |
|---|---|
| 聊天框是**一长条** | 每条消息整行满宽、**半透明**条纹 |
| **AI 回复行白色半透明** | `stripe_a` `#D9FFFFFF`,深色字 |
| 用户提问行 | 深色半透明 `#8C14365E`,白字 + 阴影 |
| **背景图直接显示** | 背景图**不做任何改色**(没有暗化遮罩),透过半透明条纹可见 |
| AI 靠左、用户靠右 | 靠对齐区分,不用气泡 |
| 行高 | 上下内边距各 18dp |
| 状态栏/标题栏 | 深色状态栏融进背景,去掉标题栏(原来那两条"白边") |
| 字号 | 消息 21sp、系统提示 15sp |

---

## 七、反问式学习(原版最核心的玩法)

```
你 : 比尔盖茨是谁
kiki: 他是谁？            ← 反问,同时记住"比尔盖茨"这个待学主语
你 : 微软公司CEO
kiki: 明白了               ← 学会并持久化到本地
```

`MarisaBot.askBack()` 剥掉疑问尾巴得到主语,按疑问词生成反问句,
记下待学主语(60 秒内有效),用户下一句话就是答案。

---

## 八、从源码重新构建

**零下载构建**:不用 Gradle / AGP / androidx,手工链路
`aapt2 → javac → d8 → zipalign → apksigner`,只用 JDK 21 + build-tools 35 + platform-35。

```powershell
powershell -ExecutionPolicy Bypass -File "D:\nbjj\dsh\marisa-revive\android-app\build_apk.ps1"
```

改知识库后重新导出 assets:
```powershell
cd D:\nbjj\dsh\marisa-revive\server-py
..\.venv\Scripts\python export_android_assets.py
```

### 构建依赖

| 文件 | 用途 |
|---|---|
| `android-tools\vosk\vosk-android.aar` | 取 `jni/arm64-v8a/libvosk.so` + `classes.jar` |
| `android-tools\vosk\jna.aar` | 取 `jni/arm64-v8a/libjnidispatch.so` + `classes.jar` |
| `android-app\app\src\main\assets\model-cn.zip` | Vosk 中文小模型(会被打进 APK) |

### 构建时踩过的坑(改脚本前务必知道)

1. **Vosk/JNA 的 `classes.jar` 必须作为 d8 的「输入」**,不能只放 `--classpath`。
   放 classpath 只是"能解析",类不会进 dex → 运行时报
   `NoClassDefFoundError: Failed resolution of: Lorg/vosk/Model;`
2. **`-source 8 -target 8`** 是必须的。JDK 21 配 `-bootclasspath android.jar` 时,
   `-source 17` 会报"目标 17 不再支持 --boot-class-path"。
3. **颜色值必须写 6 位 `#RRGGBB`**。写成 8 位时 Android 按 `#AARRGGBB` 解析,
   `#08182A` 会被当成 alpha=0x08 的近全透明色 → 文字直接看不见。
4. **`FrameLayout` 上 XML 的 `background` 在部分 ROM 会被忽略**,
   底部说话区的颜色改为在代码里 `setBackgroundColor` 设置。
5. **`FrameLayout` 按声明顺序绘制**:暗化遮罩必须声明在背景图之后、前景之前。
6. **解压模型时要把 zip 条目名的 `\` 规范成 `/`**。
   .NET 的 `ZipFile.CreateFromDirectory` 用 Windows 风格 `vosk-model-small-cn-0.22\am\final.mdl`
   写条目名,而 `ZipInputStream` 不保证规范化 → 会解出字面量文件名 `am\final.mdl`,
   自检失败、每次启动都重新解压。这个问题实际发生过。
7. **模型用分离式解码图**:官方 `vosk-model-small-cn-0.22` 是
   `graph/Gr.fst` + `graph/HCLr.fst`,**没有** `graph/HCLG.fst`。自检逻辑必须两种布局都接受。

---

## 九、已知限制(如实列出)

1. **安装包 45MB**(语音模型内置的代价)。用户要求"不要在乎包体大小",所以刻意这么做。
2. **界面没有立绘小人**。原版底部有个角色立绘,需要一张**透明背景**的角色图才能加。
3. **知识库只有 1,810 条事实**,且很多是"货币代码/原子序数/符号"这类结构化数据,
   日常常识覆盖很薄。所以**问她没学过的东西,她会反问**——这是设计行为(原版就是
   不会就问用户),不是故障。要变聪明必须继续喂知识。
4. **不做联网**。曾经实现过"不会就搜网页贴原话",但那样它只是个搜索引擎,
   不是 AI(用户明确指出过),所以已移除。
5. **神经语言模型答不了问题**。`NeuralLM.java` 是纯 Java 手写的字符级模型,
   用 21k 语料(人格种子 + 内置知识库)训练,损失能降到 1.69;
   但实测它生成的是
   `黑果我生吉家壳是别什么地是什么是黑什么区国地心么轼人地级买镆镄么炼腺备用首诗`
   这类字串 —— **每个字都合法、整句毫无意义**。字符级小模型学的是"字与字的搭配",
   不是"回答问题"。所以回答路径上不用它。
   **要真正会说话,必须换成预训练 Transformer(真 LLM)** —— 那是另一条路线。
6. 用的是 Vosk「小模型」,在**干净音频**下基准测试字错率 0%,但真实环境会受噪声影响。
7. 卸载 App 会连解压出来的语音模型一起删掉 —— 重装后首次启动会再解压一次(约 0.7 秒)。

---

## 十、调试探针(排障用)

| 探针 | logcat tag | 作用 |
|---|---|---|
| `EngineSelfTest.java` | `kiki-engine` | 启动时跑 20 条推理用例,输出 `[OK]/[!!]` 与通过率 |
| `OfflineRecognizer` | `kiki-vosk` | 音频源、底噪、信噪比、识别结果 |
| `NeuralLM` / `ChatBrain` | `kiki-lm` | 语言模型训练进度与损失 |
| `SelfTest.java` | `kiki-selftest` | 一次性联网探针(联网已移除,保留作历史参考) |

```powershell
$adb = 'D:\nbjj\dsh\android-sdk\platform-tools\adb.exe'
& $adb logcat -d -v brief | Select-String 'kiki-engine'   # 推理回归
& $adb logcat -d -v brief | Select-String 'kiki-vosk'     # 语音链路
& $adb logcat -d -v brief | Select-String 'kiki-lm'       # 模型训练
```

语音识别基准(PC 侧,测模型上限):
```powershell
cd D:\nbjj\dsh\marisa-revive\server-py
..\.venv\Scripts\python asr_bench.py
```

---

## 十一、TTS(语音合成)能用在别的手机上吗

**能 —— TTS 是 Android 框架的标准 API,不是小米专属。**

用的是 `android.speech.tts.TextToSpeech`(API 1 就有)。任何 Android 手机都有这个接口,
具体出声靠**设备自己的 TTS 引擎**:

| 机型 | 自带引擎 | 中文 | 离线 |
|---|---|---|---|
| 小米/MIUI | 小爱语音引擎 | ✅ | ❌ 需联网(实测 `onlyOffline=false`) |
| Google 系 | Google 语音服务 | ✅ | ✅ 可下离线包 |
| 三星 | Samsung TTS | ✅ | ✅ |
| 华为 | 华为语音引擎 | ✅ | 部分 |
| 原生 AOSP | 常无引擎 | — | 需自行装 |

### 这台手机上的坑(已修)

用户手机 `settings get secure tts_default_synth` 原本是 **`null`** ——
**系统没设默认合成引擎**,所以 `TextToSpeech` 初始化返回失败,一直不出声。
把小米引擎设上之后就正常了:

```powershell
& $adb shell settings put secure tts_default_synth com.xiaomi.mibrain.speech
```

设完后实测日志(引擎逐条确认合成并播放):
```
E/TtsService: onSynthesizeText Start: text=你好，我是kiki，语音合成测试。
I/EngineCapabilityUtils: SpeechSynthesizer onPlayStart=16000
I/EngineCapabilityUtils: SpeechSynthesizer onPlayFinish
```
App 侧:
```
I/kiki-tts: TTS 初始化: 可用=true 支持中文=true
```

### 代码侧的两处改进

1. TTS 初始化回调原本是**空的**(`// 念不出来时静默`),导致"到底成没成"完全看不出来。
   现在会打日志,并在失败时明确提示用户:
   - 没引擎 -> "本机没有可用的语音合成引擎,回答只会显示文字"
   - 不支持中文 -> "语音引擎不支持中文,建议装中文语音包"
2. `speak()` 加了返回值与异常日志(`kiki-tts` tag),便于排障。

### 所以给别的手机时

- **不需要**任何小米组件,App 用的是标准 API
- 如果对方手机不出声,先查它的默认引擎:
  `设置 → 系统 → 语言和输入 → 文字转语音(TTS)输出`
  装一个中文引擎即可(讯飞语记、Google 语音服务等)
- App 现在会**自己提示**这个问题,不再静默失败