plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marisa.kiki"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marisa.kiki"
        minSdk = 24            // Android 7.0:覆盖几乎所有在用机型,TTS/识别 API 都齐全
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // 自用包:不开混淆,避免把资源和反射用到的地方搞坏
            isMinifyEnabled = false
            // 用 debug 签名,方便直接安装(自用不需要正式密钥)
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // assets 里有 TSV/TXT 数据,禁止压缩以便直接读取
    androidResources {
        noCompress += listOf("tsv", "txt")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
}
