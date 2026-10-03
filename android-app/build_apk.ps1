﻿﻿﻿# 手工构建 APK:完全离线,不需要 Gradle / AGP / 任何依赖下载。
#
# 为什么走手工构建
# ----------------
# 走 Gradle 需要下载 AGP + Kotlin 插件 + androidx(轻松超过 500 MB),
# 而用户 VPN 流量只剩 500 MB;而且实测 sdkmanager 的下载通道在本机不通。
# 本脚本只用**已经下载好的**东西:
#     JDK 21        -> javac / keytool / jarsigner
#     build-tools 35 -> aapt2 / d8 / zipalign / apksigner
#     platform-35   -> android.jar
# 源码是纯 Java + 纯 Android 框架 API(不用 androidx),所以**零依赖**。
#
# 流程:aapt2 compile -> aapt2 link -> javac -> d8 -> 打 zip -> zipalign -> apksigner
#
# 用法:
#     powershell -ExecutionPolicy Bypass -File build_apk.ps1

$ErrorActionPreference = 'Stop'

# ---------------- 路径 ----------------
$root      = 'D:\nbjj\dsh\marisa-revive\android-app'
$tools     = 'D:\nbjj\dsh\android-tools'
$sdk       = 'D:\nbjj\dsh\android-sdk'
$jdk       = "$tools\jdk21"
$buildTools= "$sdk\build-tools\35.0.0"
$androidJar= "$sdk\platforms\android-35\android.jar"

# ---- 离线语音识别(Vosk)所需:JNA + Vosk 的 classes.jar 与原生库 ----
$voskDir   = "$tools\vosk"
$voskJar   = "$voskDir\aar\classes.jar"          # org.vosk.* 的 Java API
$jnaJar    = "$voskDir\jna-aar\classes.jar"      # com.sun.jna.* (Vosk 依赖它)
$voskSoDir = "$voskDir\aar\jni\arm64-v8a"       # libvosk.so
$jnaSoDir  = "$voskDir\jna-aar\jni\arm64-v8a"   # libjnidispatch.so

# ---- 本地对话模型(ONNX Runtime)----
$ortDir    = "$tools\onnxruntime\aar"
$ortJar    = "$ortDir\classes.jar"                # ai.onnxruntime.*
$ortSoDir  = "$ortDir\jni\arm64-v8a"             # libonnxruntime*.so

# d8.bat / apksigner.bat 这些批处理会去读 JAVA_HOME,而 javac 用的是绝对路径,
# 所以必须显式设好,否则 d8 会报 "JAVA_HOME is not set"。
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$javaSrc   = "$root\src-native"
$resDir    = "$root\app\src\main\res"
$assetsDir = "$root\app\src\main\assets"
$manifest  = "$root\app\src\main\AndroidManifest.xml"

$out       = "$root\build"
$genDir    = "$out\gen"
$classesDir= "$out\classes"
$dexDir    = "$out\dex"
$apkUnsign = "$out\app-unsigned.apk"
$apkAligned= "$out\app-aligned.apk"
$apkFinal  = "$out\MarisaKiki.apk"
$keystore  = "$out\debug.keystore"

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Check($path, $what) {
    if (-not (Test-Path $path)) { throw "缺少 $what : $path" }
}

# ---------------- 0. 环境检查 ----------------
Step "检查工具链"
Check "$jdk\bin\javac.exe"      'JDK javac'
Check "$buildTools\aapt2.exe"   'aapt2'
Check "$buildTools\d8.bat"      'd8'
Check "$buildTools\zipalign.exe" 'zipalign'
Check "$buildTools\apksigner.bat" 'apksigner'
Check $androidJar               'android.jar'
Check $manifest                 'AndroidManifest.xml'
Check $voskJar                  'Vosk classes.jar'
Check $jnaJar                   'JNA classes.jar'
Check "$voskSoDir\libvosk.so"   'libvosk.so'
Check "$jnaSoDir\libjnidispatch.so" 'libjnidispatch.so'
Check $ortJar                  'ONNX Runtime classes.jar'
Check "$ortSoDir\libonnxruntime.so" 'libonnxruntime.so'
Write-Host "    JDK        : $((cmd /c "`"$jdk\bin\java.exe`" -version 2>&1" | Select-Object -First 1))"
Write-Host "    build-tools: $buildTools"
Write-Host "    android.jar: $androidJar"

# ---------------- 1. 清理 ----------------
Step "清理输出目录"
foreach ($d in @($genDir, $classesDir, $dexDir)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# ---------------- 2. aapt2 compile 资源 ----------------
Step "aapt2 compile(编译资源)"
$compiled = "$out\compiled.zip"
& "$buildTools\aapt2.exe" compile --dir $resDir -o $compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }
Write-Host "    资源编译完成"

# ---------------- 3. aapt2 link(生成 R.java 与资源表) ----------------
Step "aapt2 link(链接资源,生成 R.java)"
& "$buildTools\aapt2.exe" link `
    -o "$out\resources.apk" `
    -I $androidJar `
    --manifest $manifest `
    -R $compiled `
    --java $genDir `
    -A $assetsDir `
    --min-sdk-version 24 `
    --target-sdk-version 35 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }
Check "$genDir\com\marisa\kiki\R.java" '生成的 R.java'
Write-Host "    R.java 已生成"

# ---------------- 4. javac 编译 Java ----------------
Step "javac 编译 Java 源码"
$sources = @()
$sources += Get-ChildItem $javaSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem $genDir  -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) { throw "没找到任何 .java 源码" }
Write-Host "    源文件数: $($sources.Count)"

$srcListFile = "$out\sources.txt"
# 必须**无 BOM** 写入:javac 会把 BOM 当成文件名的第一个字符,
# 报 "无效文件名: ?D:\..."。PowerShell 的 -Encoding UTF8 会加 BOM,所以用 .NET 写。
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($srcListFile, [string[]]$sources, $utf8NoBom)

& "$jdk\bin\javac.exe" `
    -encoding UTF-8 `
    -nowarn `
    -source 8 -target 8 `
    -bootclasspath $androidJar `
    -classpath "$androidJar;$voskJar;$jnaJar;$ortJar" `
    -d $classesDir `
    "@$srcListFile"
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败(看上面的错误)" }
Write-Host "    编译完成"

# ---------------- 5. d8 转 dex ----------------
Step "d8 生成 dex"
$classFiles = Get-ChildItem $classesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
if ($classFiles.Count -eq 0) { throw "没有生成 .class" }
# 注意:Vosk/JNA 的 classes.jar 必须作为**输入**交给 d8(不能只放 --classpath),
# 否则它们的类不会进 dex —— 运行时就是 NoClassDefFoundError: Lorg/vosk/Model;
# 一开始我就是这么错的。--lib 仍然只给平台类。
# 不含 libjnidispatch.so 的 .jar 里没有原生库,d8 只处理 .class,安全。
& "$buildTools\d8.bat" --lib $androidJar --min-api 24 --output $dexDir $classFiles $voskJar $jnaJar $ortJar
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }
Check "$dexDir\classes.dex" 'classes.dex'
Write-Host "    classes.dex 生成完成"

# ---------------- 6. 打包 APK ----------------
Step "打包 APK"
# resources.apk 里已有资源和 assets;把 classes.dex 塞进去即可
Copy-Item "$out\resources.apk" $apkUnsign -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($apkUnsign, 'Update')
try {
    $entryName = 'classes.dex'
    $existing = $zip.Entries | Where-Object { $_.FullName -eq $entryName }
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$dexDir\classes.dex", $entryName) | Out-Null

    # 离线识别引擎的原生库(Vosk + JNA),放进 lib/arm64-v8a/
    foreach ($so in @(
        @{ src = "$voskSoDir\libvosk.so";            name = "lib/arm64-v8a/libvosk.so" },
        @{ src = "$jnaSoDir\libjnidispatch.so";      name = "lib/arm64-v8a/libjnidispatch.so" },
        @{ src = "$ortSoDir\libonnxruntime.so";       name = "lib/arm64-v8a/libonnxruntime.so" },
        @{ src = "$ortSoDir\libonnxruntime4j_jni.so"; name = "lib/arm64-v8a/libonnxruntime4j_jni.so" }
    )) {
        if (Test-Path $so.src) {
            $old = $zip.Entries | Where-Object { $_.FullName -eq $so.name }
            if ($old) { $old.Delete() }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $so.src, $so.name) | Out-Null
            Write-Host "    已打入 $($so.name) ($([math]::Round((Get-Item $so.src).Length/1MB,2)) MB)"
        } else { Write-Host "    !! 缺少 $($so.src)" -ForegroundColor Yellow }
    }
} finally {
    $zip.Dispose()
}
Write-Host "    classes.dex 已打入 APK"

# ---------------- 7. zipalign ----------------
Step "zipalign"
& "$buildTools\zipalign.exe" -f -p 4 $apkUnsign $apkAligned
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

# ---------------- 8. 生成签名密钥(自用)并签名 ----------------
Step "签名"
if (-not (Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v `
        -keystore $keystore `
        -alias marisa `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass marisa123 -keypass marisa123 `
        -dname "CN=Marisa Kiki, OU=Local, O=Local, L=Local, S=Local, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool 生成密钥失败" }
    Write-Host "    密钥已生成: $keystore"
}
& "$buildTools\apksigner.bat" sign `
    --ks $keystore `
    --ks-key-alias marisa `
    --ks-pass pass:marisa123 `
    --key-pass pass:marisa123 `
    --out $apkFinal `
    $apkAligned
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败" }

# ---------------- 9. 校验 ----------------
Step "校验签名"
& "$buildTools\apksigner.bat" verify --print-certs $apkFinal

$size = [math]::Round((Get-Item $apkFinal).Length / 1MB, 2)
Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host " 构建成功!" -ForegroundColor Green
Write-Host " APK: $apkFinal" -ForegroundColor Green
Write-Host " 大小: $size MB" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
Write-Host ""
Write-Host "安装方式(任选):"
Write-Host "  1) 把这个 apk 传到手机,点击安装(需允许'未知来源')"
Write-Host "  2) 手机连 USB 后: $sdk\platform-tools\adb.exe install -r `"$apkFinal`""
