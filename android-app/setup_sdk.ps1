# 手工布局 Android SDK:把下好的组件 zip 解到正确位置并写入许可证。
#
# 为什么不用 sdkmanager:它的下载通道在本机连不上 Google
# ("Failed to download any source lists"),而**直接下 zip 是通的**。
#
# 各 zip 的真实内部结构(实测):
#     platform-35_r02.zip              -> android-35/…          要放到 platforms/android-35/
#     platform-tools-latest-windows.zip-> platform-tools/…      直接解到 SDK 根即可
#     build-tools_r35_windows.zip      -> android-15/…          要放到 build-tools/35.0.0/
#         (注意:build-tools 的 zip 用老式 android-15 目录名,不能只看目录名判断版本)
#
# 用法:
#     powershell -ExecutionPolicy Bypass -File setup_sdk.ps1

$ErrorActionPreference = 'Stop'
$tools = 'D:\nbjj\dsh\android-tools'
$sdk   = 'D:\nbjj\dsh\android-sdk'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

New-Item -ItemType Directory -Force -Path $sdk | Out-Null
Write-Host "SDK 目录: $sdk"

# ---------------- 1. Android 35 平台 ----------------
Step "platform-35"
$pzip = Join-Path $tools 'platform-35_r02.zip'
$pdst = Join-Path $sdk 'platforms\android-35'
if (-not (Test-Path "$pdst\android.jar")) {
    if (-not (Test-Path $pzip)) { throw "缺少 platform-35_r02.zip" }
    # zip 内部是 android-35/,所以解到 platforms/ 下正好
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'platforms') | Out-Null
    Expand-Archive -Path $pzip -DestinationPath (Join-Path $sdk 'platforms') -Force
}
if (Test-Path "$pdst\android.jar") { Write-Host "  [OK] android.jar" }
else { throw "platform-35 解压后仍找不到 android.jar" }

# ---------------- 2. platform-tools ----------------
Step "platform-tools"
$tzip = Join-Path $tools 'platform-tools-latest-windows.zip'
$tdst = Join-Path $sdk 'platform-tools'
if (-not (Test-Path "$tdst\adb.exe")) {
    if (-not (Test-Path $tzip)) { throw "缺少 platform-tools-latest-windows.zip" }
    # zip 内部是 platform-tools/,解到 SDK 根即可
    Expand-Archive -Path $tzip -DestinationPath $sdk -Force
}
if (Test-Path "$tdst\adb.exe") { Write-Host "  [OK] adb.exe" }
else { throw "platform-tools 解压后仍找不到 adb.exe" }

# ---------------- 3. build-tools 35.0.0 ----------------
Step "build-tools 35.0.0"
$bzip = Join-Path $tools 'build-tools_r35_windows.zip'
$bdst = Join-Path $sdk 'build-tools\35.0.0'
if (-not (Test-Path "$bdst\aapt2.exe")) {
    if (-not (Test-Path $bzip)) { throw "缺少 build-tools_r35_windows.zip" }
    $tmp = Join-Path $sdk '_bt_tmp'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Expand-Archive -Path $bzip -DestinationPath $tmp -Force
    # 找到含 aapt2.exe 的那一层(实际是 android-15/),整层搬到 build-tools/35.0.0
    $aapt2 = Get-ChildItem $tmp -Recurse -Filter 'aapt2.exe' | Select-Object -First 1
    if (-not $aapt2) { throw "build-tools zip 里没有 aapt2.exe" }
    $srcDir = $aapt2.Directory.FullName
    if (Test-Path $bdst) { Remove-Item $bdst -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path $bdst -Parent) | Out-Null
    Move-Item $srcDir $bdst
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
if (Test-Path "$bdst\aapt2.exe") { Write-Host "  [OK] aapt2.exe" }
else { throw "build-tools 布局失败" }

# ---------------- 4. 许可证 ----------------
Step "写许可证(否则构建会报未接受协议)"
$lic = Join-Path $sdk 'licenses'
New-Item -ItemType Directory -Force -Path $lic | Out-Null
Set-Content -Path (Join-Path $lic 'android-sdk-license') -Encoding ascii -Value @(
    '24333f8a63b6825ea9c5514f83c2829b004d1fee',
    'd56f5187479451eabf01fb78af6dfcb131a6481e',
    '8933bad161af4178b1185d1a37fbf41ea5269c55'
)
Set-Content -Path (Join-Path $lic 'android-sdk-preview-license') -Encoding ascii -Value @(
    '84831b9409646a918e30573bab4c9c91346d8abd'
)
Set-Content -Path (Join-Path $lic 'android-googletv-license') -Encoding ascii -Value @(
    '601085b94cd77f0b54ff86406957099ebe79c4d6'
)
Write-Host "  [OK] licenses/"

# ---------------- 5. 汇总 ----------------
Step "SDK 布局结果"
$checks = @(
    @{p='platforms\android-35\android.jar';        d='Android 35 平台'},
    @{p='platform-tools\adb.exe';                  d='平台工具 adb'},
    @{p='build-tools\35.0.0\aapt2.exe';            d='资源编译器 aapt2'},
    @{p='build-tools\35.0.0\d8.bat';               d='dex 编译器 d8'},
    @{p='build-tools\35.0.0\zipalign.exe';         d='对齐工具 zipalign'},
    @{p='build-tools\35.0.0\apksigner.bat';        d='签名工具 apksigner'},
    @{p='licenses\android-sdk-license';            d='许可证'}
)
$allOk = $true
foreach ($c in $checks) {
    $full = Join-Path $sdk $c.p
    if (Test-Path $full) { Write-Host ("  [OK]   {0,-22} {1}" -f $c.d, $c.p) }
    else { Write-Host ("  [缺失] {0,-22} {1}" -f $c.d, $c.p) -ForegroundColor Red; $allOk = $false }
}
Write-Host ""
if ($allOk) { Write-Host "全部就位,可以构建 APK 了。" -ForegroundColor Green }
else { Write-Host "还有缺失项,请检查上面的下载。" -ForegroundColor Yellow }
