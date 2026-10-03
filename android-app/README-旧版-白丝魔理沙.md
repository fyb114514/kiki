# 白丝魔理沙 · Android 版（kiki 引擎）

把 PC 版验证过的推理引擎与知识库搬进一个**离线、可安装的 Android 应用**。
这是"可用的 .apk"那条线的交付物。

## 它是什么

一个聊天 App：打开她先说一句开场白，你说话她回答，用 `关键词`回答 可以教她说话。
语音用 **Android 系统自带的 TTS 与语音识别**——这一点比 PC 版强：Windows 那台机器
只有英文语音、识别引擎为 0，而手机通常自带中文离线语音。

## 知识库（打包进 APK，共约 490 KB）

| 资源 | 内容 | 大小 |
|---|---|---|
| `facts.tsv` | 事实库 **1,807 条**（元素/国家/省级行政区/历史/常识） | 89 KB |
| `pinyin.txt` | 拼音表 **12,009 字**（同音纠错，即"多音节分辨"） | 95 KB |
| `words.txt` | 分词词表 **40,155 词**（最长匹配分词，质量闸依赖它） | 300 KB |
| `qa.tsv` | 调教问答 45 条 | 4 KB |
| `greeting.txt` | 开场白 15 条 | 1 KB |
| `relations.txt` | 关系词 66 个（动态关系问句） | 1 KB |

重新生成：`cd ../server-py && ..\.venv\Scripts\python export_android_assets.py`

## 回答链路（与 PC 版 local_service.py 一致）

```
1. 精确匹配(教过的原话)  -> 记忆库
2. kiki 逻辑引擎          -> 事实/推理,给出确切答案
3. greeting 兜底          -> 问候语
4. 模糊匹配 + 质量闸      -> 实义词覆盖不足就诚实说不会
5. 兜底话术
```

**质量闸**是关键：上游模糊匹配会把"你吃饭了吗"匹配到"你笨吗"（因为候选里只有
"你/吗"两个虚词而它们恰好都在问题里）。**答错比答不上更糟**，所以先剔虚词再判
实义词覆盖度，不足就宁可说不会。

## 引擎能力（从 PC 版移植，算法一一对应）

| 能力 | 例子 |
|---|---|
| 分类继承（链式） | `卡卡是什么` → 卡卡是猫、哺乳动物、动物、生物 |
| 属性继承 | `卡卡有几条腿` → 四条腿（库里只写了猫） |
| 传递推理 | `小明的爷爷是谁` → 现场推出张建国 |
| 亲属路径（家谱树 LCA） | `小红和小明是什么关系` → 堂表兄弟姐妹 |
| 否定优先 | `企鹅会飞吗` → 不会（压过"鸟会飞"） |
| 反义判定 | `乌龟走得快吗` → 走得很慢 |
| 拼音同音 | `魔里沙`/`艾丽斯`/`零梦` 都能认对 |
| 动态关系问句 | `氧原子序数`/`山东省简称`/`李白代表作`/`美国代码` |
| 值类与度量 | `光速是多少`、`长江有多长`、`中国的面积是多少` |
| 反查 | `谁发明了电话` → 贝尔 |
| 换算 | `一小时是几分钟` → 60 |
| 教学 | `卡卡`卡卡是一只猫` 教完立刻能推理 |

## 构建

### 需要下载（约 460 MB，链接已核实）

| 组件 | 链接 | 放到 |
|---|---|---|
| Android 命令行工具 | `https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip` | `D:\nbjj\dsh\android-tools\cmdline-tools.zip` |
| Gradle 8.14.5 | `https://services.gradle.org/distributions/gradle-8.14.5-bin.zip` | `D:\nbjj\dsh\android-tools\gradle.zip` |
| JDK 21 (Temurin) | `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip` | `D:\nbjj\dsh\android-tools\jdk21.zip` |

**版本说明**：Gradle **8.7 已被官方归档**（下载会 404），所以用 8.14.5。
Android Studio 自带的 jbr 是 **JDK 25**，AGP 8.x 只支持 17–21，所以单独下 JDK 21。

### 构建步骤（工具链就位后）

```powershell
$env:JAVA_HOME = "D:\nbjj\dsh\android-tools\jdk21"
$env:ANDROID_HOME = "D:\nbjj\dsh\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

# 1. 装 SDK 组件(platform-35 / build-tools / platform-tools)
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 2. 构建 release(用 debug 签名,可直接安装)
cd D:\nbjj\dsh\marisa-revive\android-app
& "D:\nbjj\dsh\android-tools\gradle\gradle-8.14.5\bin\gradle.bat" assembleRelease

# 产物
# app\build\outputs\apk\release\app-release.apk
```

## 目录结构

```
android-app/
├── build.gradle.kts              顶层:只声明插件版本
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/…              Gradle wrapper 配置
└── app/
    ├── build.gradle.kts          AGP 8.13.2 / compileSdk 35 / minSdk 24
    └── src/main/
        ├── AndroidManifest.xml   录音+网际权限;声明 TTS/识别服务查询
        ├── assets/               知识库(tsv/txt)
        ├── java/com/marisa/kiki/
        │   ├── Answer.kt         回答数据类
        │   ├── Brain.kt          kiki 逻辑引擎(移植自 brain.py)
        │   ├── MarisaBot.kt      对话层:记忆库+引擎+质量闸+分词
        │   ├── SpeechManager.kt  Android 原生 TTS + 语音识别
        │   └── MainActivity.kt   聊天界面
        └── res/                  布局/主题/图标
```

## 已知风险（如实说明）

1. **我没能在这台机器上编译过**——Android 工具链（约 2 GB）在本会话里下载不下来
   （大文件传输反复中断）。所以代码是"写好但未验证编译"的状态。
   工具链就位后我会立即构建并修编译错误。
2. 界面只有最简的聊天视图，没有立绘、没有指令面板（PC 版有）。
3. 运行时新教的内容只存在内存里，**重启 App 会丢**（PC 版是落库持久化）。
   后续可加 SQLite 持久化。
