# kiki v1.0 — 首次发布

一个**完全离线运行**的中文语音助手 Android 应用。

## 下载

见下方 **Assets** 里的 `MarisaKiki.apk`（429 MB）。

## 功能

- **语音识别** —— Vosk 离线中文模型，内置，无需联网
- **对话生成** —— Qwen2.5-0.5B-Instruct（int4 量化 ONNX），内置
- **事实问答** —— 本地知识库 + 规则推理引擎（1795 条事实）
- **语音合成** —— 调用系统 TTS 引擎
- **完全离线** —— 不依赖任何云服务

## 实测效果

```
事实类（走本地知识库，约 1ms）
  你是谁        →  我是kiki，一个语音助手，住在你的手机里。
  卡卡是什么     →  卡卡是猫、哺乳动物、动物、生物。我记性很好的。
  东吴是谁建立的  →  东吴由孙权建立。

闲聊类（走对话模型，约 3 秒）
  你好          →  你好，Kiki！有什么问题或者需要帮助的吗？
  我好累啊       →  给你点休息时间吧。
```

## 安装要求

| 要求 | 值 |
|---|---|
| CPU 架构 | **仅 arm64-v8a** |
| 系统版本 | Android 7.0+（API 24） |
| 内存 | 建议 4 GB+（实测峰值约 1.8 GB） |
| 可用存储 | 约 1.2 GB |

**首次启动**会把 460 MB 模型从 APK 释放到私有目录（约 2.5 秒），之后直接复用。

## 已知限制

- 只支持 **arm64**，32 位机型装不上
- 语音合成依赖**系统 TTS 引擎**。若系统未设默认引擎
  （`settings get secure tts_default_synth` 返回 `null`），需先安装中文语音引擎
- 小米 TTS 实测为**在线合成**（`onlyOffline=false`），无网络时可能不出声
- APK 体积较大（429 MB），因为对话模型（460 MB）直接内置

## 技术说明

- **零编译集成**：用 ONNX Runtime 官方 Android 预编译库，不需要 NDK
- **手写 Qwen 分词器**：ONNX Runtime 不带分词，字节级 BPE 为纯 Java 实现，
  与 HuggingFace 结果 13/13 一致
- **构建工具链**：不用 Gradle / Android Studio / androidx，
  纯 `aapt2 → javac → d8 → zipalign → apksigner`

详细的踩坑记录（量化档位缺陷、SIGSEGV 生命周期问题、asset 反斜杠等）见仓库内
[android-app/README.md](https://github.com/fyb114514/kiki/blob/main/android-app/README.md)。

## 关于 2011 年的同名作品

本项目是对 **2011 年那个同名作品 kiki** 的一次**独立复现尝试**。

那个项目在 2011 年就做到了本地处理 + 主动记忆 + 逻辑推理，
需要 8G 存储卡（7G 信息库 + 1G 记忆库），在当时引起过不小的关注
（B 站 20 万播放、多个大吧转发、《现代快报》报道），
但**最终没有正式发布**，作者至今未知。

完整的追查记录见 [docs/kiki原作者追查档案.md](https://github.com/fyb114514/kiki/blob/main/docs/kiki%E5%8E%9F%E4%BD%9C%E8%80%85%E8%BF%BD%E6%9F%A5%E6%A1%A3%E6%A1%88.md)。

## 校验

```
SHA256: 1539CD0739AF4E9D0D585340CA68E4275AC2647B10E96AF41B70B50FEA93483B
大小:   429.1 MB (449,940,118 字节)
```

## 许可

代码仅供学习研究。内置的 Qwen2.5-0.5B 与 Vosk 中文模型各自遵循原作者的许可协议。
