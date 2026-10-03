# kiki — 本地离线语音助手（Android）

一个**完全离线运行**的中文语音助手。听你说话、陪你聊天、记住你教的东西。
模型和语音引擎全部内置，**不需要联网，不依赖任何云服务**。

[![Release](https://img.shields.io/badge/release-v1.0-blue)](https://github.com/fyb114514/kiki/releases/tag/v1.0)

> 本项目是对 2011 年那个同名作品 **kiki** 的一次**独立复现**。
> 原项目从未公开发布，作者至今未知 —— 追查记录见
> [kiki 原作者追查档案](docs/kiki原作者追查档案.md)。

---

## 下载

**[⬇ kiki-v1.0.apk（429 MB）](https://github.com/fyb114514/kiki/releases/download/v1.0/kiki-v1.0.apk)**

```
SHA256: C9A619014D1E8D83042000A5D5C57FCD8E9FACCAFD2EEBBCA750262DCEFFC228
大小:   429.1 MB (449,940,114 字节)
包名:   com.kiki.android
```

### 安装要求

| 要求 | 值 | 原因 |
|---|---|---|
| **CPU 架构** | **仅 arm64-v8a** | 原生库只编了 arm64 |
| **系统版本** | Android 7.0+（API 24） | minSdk |
| **内存** | 建议 4 GB+ | 实测峰值约 1.8 GB 原生内存 |
| **可用存储** | 约 1.2 GB | APK 429MB + 首次释放模型 460MB + 语音模型 65MB |

**首次启动**会把 460MB 模型从 APK 释放到私有目录（约 2.5 秒），之后直接复用。

---

## 这是什么

| 能力 | 实现方式 |
|---|---|
| **语音识别** | Vosk 离线中文模型（42 MB，内置在 APK 里） |
| **对话生成** | Qwen2.5-0.5B-Instruct（int4 量化 ONNX，460 MB，内置） |
| **事实问答** | 本地知识库 + 规则推理引擎（1795 条事实） |
| **语音合成** | 调用系统 TTS 引擎（可离线） |
| **联网** | ❌ 完全不需要 |

回答走**两层链路**：

1. **本地知识检索 / 因果推理** —— 事实类问题（"卡卡是什么""东吴是谁建立的"）
   必须准确，由规则引擎作答，不允许模型改写
2. **预训练对话模型** —— 闲聊、问候、没有明确事实可查的问题交给它生成

两者都不会 → **反问用户**（原版行为），用户答完就学会，下次就会了。

### 实测效果

```
事实类（走知识库，约 1ms）
  你是谁        →  我是kiki，一个语音助手，住在你的手机里。
  卡卡是什么     →  卡卡是猫、哺乳动物、动物、生物。我记性很好的。
  东吴是谁建立的  →  东吴由孙权建立。

闲聊类（走模型，约 3 秒）
  你好          →  你好，Kiki！有什么问题或者需要帮助的吗？
  我好累啊       →  给你点休息时间吧。
```

---

## 技术要点

### 为什么用 ONNX Runtime 而不是 llama.cpp

Android 有官方预编译的 `onnxruntime-android`（`libonnxruntime.so` + Java API），
**零编译**即可集成。llama.cpp 需要 NDK 自行编译 arm64 原生库，成本高得多。

### 手写 Qwen 分词器的踩坑记录

ONNX Runtime 只提供算子，**不带分词**。所以字节级 BPE 是**纯 Java 手写**的
（[QwenTokenizer.java](android-app/src-native/com/kiki/android/QwenTokenizer.java)）。

最容易错的是**预分词正则**。第一版误用了 Python 的 `\w`（它**包含汉字**），
导致中文被整段当成一个词，与官方不符（13 个测试用例只对 3 个）。
正确写法（取自 `tokenizer.json` 的 `pre_tokenizer.pattern`）：

```
(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}|
 ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+
```

改对之后，与 HuggingFace 分词结果 **13/13 完全一致**（含中英混排、数字、标点）。

### 量化档位的坑（重要）

官方 `model_int8.onnx` **量化有缺陷**，输出重复胡话：

```
问: 你是谁  →  Assistant: 你这是什么问题？(重复 6 次)
```

换 **`model_q4f16.onnx` 才正确**，而且**快一倍**（17 字/秒 vs 9.6）。
本项目内置的是 q4f16。

### 一个会崩溃的坑：OrtSession.Result 的生命周期

`session.run()` 返回的 `Result` 对象**持有 KV 缓存的原生内存**。
如果 `Result` 被 GC 回收，那些张量就成了**悬空指针**，
下一轮再喂给 `session.run` 就会段错误：

```
Fatal signal 11 (SIGSEGV) in libonnxruntime.so
  Java_ai_onnxruntime_OrtSession_run
```

正确做法：**持有 `Result` 直到下一步推理完成**，再 close 它。
见 [LlmEngine.java](android-app/src-native/com/kiki/android/LlmEngine.java)。

### asset 路径的反斜杠

aapt2 打包时会把 `assets/llm/model.onnx` 存成 `assets/llm\model.onnx`
（**反斜杠**）。而 `AssetManager` 对分隔符的兼容性因版本而异。
解决办法：**两种都试**，哪种能开用哪种。

---

## 构建

**不用 Gradle，不用 Android Studio，不用 androidx。**
纯手工工具链：`aapt2 → javac → d8 → zipalign → apksigner`。

### 需要

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | `javac` 需加 `-source 8 -target 8` + `-bootclasspath android.jar` |
| Android SDK | build-tools 35.0.0 + platform-35 | — |
| ONNX Runtime | `onnxruntime-android:1.20.0` | Maven Central 取 AAR（26.6 MB） |
| Vosk | `vosk-android` + `jna` | 同上 |

### 构建命令

```powershell
powershell -ExecutionPolicy Bypass -File android-app\build_apk.ps1
```

> ⚠️ Vosk / JNA / ONNX Runtime 的 `classes.jar` 必须作为 **d8 的输入**，
> 不能放 `--classpath`，否则运行时报 `NoClassDefFoundError`。

> 注：构建需要 `assets/llm/model.onnx`（460MB）等大文件。
> 它们因体积原因**未纳入版本控制**，详见文末的「技术踩坑记录」。

---

## 目录结构

```
android-app/
  src-native/com/kiki/android/      Java 源码（构建时直接编译，无 Gradle）
    MainActivity.java              界面（全宽条纹消息行 + 底部说话区）
    LlmEngine.java                 ONNX 推理 + KV 缓存生成循环
    LlmBootstrap.java              模型释放与载入
    QwenTokenizer.java             手写 Qwen 字节级 BPE
    ChatBrain.java                 回答链路编排
    Brain.java                     规则推理引擎（因果/属性/分类继承）
    KikiBot.java                   检索层
    OfflineRecognizer.java         Vosk 语音识别
    SpeechManager.java             TTS
    ModelInstaller.java            语音模型释放
    EngineSelfTest.java            20 条推理自检
  app/src/main/                    资源与清单
  build_apk.ps1                    构建脚本
  README.md                        构建细节与完整踩坑记录
docs/
  kiki原作者追查档案.md             2011 年原项目的追查记录
  release-v1.0.md                  v1.0 发布说明
  寻找-kiki原作者.md                寻人帖
```

---

## 界面

还原 2011 年原版的风格：

- **全宽条纹消息行** —— AI 回复白底半透明 + 深色字；用户消息深底半透明 + 白字
- **底部灰色说话区** —— 长按说话（或点一下开始、再点一下结束）
- **无打字输入框** —— 原版是纯语音
- **启动时无任何提示文字**

---

## 已知限制

- 只支持 **arm64**，32 位老机型装不上
- 语音合成依赖**系统 TTS 引擎**。若系统没设默认引擎
  （`settings get secure tts_default_synth` 返回 `null`），需先装一个中文语音引擎
- 小米 TTS 实测是**在线合成**（`onlyOffline=false`），没网时可能不出声
- 对话模型只有 0.5B 参数，复杂问题会答得比较浅

---

## 关于 2011 年的同名作品

2011 年有个小团队做的东西也叫 **kiki**：本地运行、会主动记忆、有逻辑推理，
需要 8G 存储卡（7G 信息库 + 1G 记忆库），中英文混说也能听懂。
它在当时引起过不小的关注（B 站 20 万播放、多个大吧转发、《现代快报》报道），
但**最终没有正式发布**，作者至今未知。

完整的追查过程（原始优酷/土豆视频 ID、传播时间线、10 位关键人物、14 条已排除通路）
见 [docs/kiki原作者追查档案.md](docs/kiki原作者追查档案.md)。

**本仓库与那个项目没有代码关系**，只是同名 + 同一目标的一次独立尝试。

---

## 许可

本项目代码仅供学习研究。

**注意**：`assets/` 内含第三方模型（Qwen2.5-0.5B、Vosk 中文模型），
它们各自遵循原作者的许可协议，请自行确认使用范围。
