// 顶层构建文件:只声明插件版本,具体配置在 app/build.gradle.kts
//
// 版本组合说明(2026-10 核实可用):
//   Gradle 8.14.5 + AGP 8.13.2 + JDK 21
//   —— AGP 8.x 官方支持 JDK 17-21;Android Studio 自带的 jbr 是 JDK 25,太新,不用它。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
