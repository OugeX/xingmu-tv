# 冻结构建产物（唯一被测版本）
# 用法: pwsh -File tools/freeze_apk.ps1 -Version v0.2.0
param(
    [string]$Version = "v0.2.0"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = 'D:\tvbuild\jdk-17.0.2'
$env:ANDROID_HOME = 'D:\tvbuild\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\tvbuild\android-sdk'

Write-Output "== 1/3 构建 =="
& D:\tvbuild\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon --no-build-cache -q
if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }

$apk = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "找不到 APK: $apk" }

Write-Output "== 2/3 冻结到 release/ =="
New-Item -ItemType Directory -Force release | Out-Null
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$short = $sha.Substring(0, 8)
$dest = "release\xingmu-tv-$Version-$short.apk"
Copy-Item $apk $dest -Force

$meta = [ordered]@{
    version  = $Version
    sha256   = $sha
    size     = (Get-Item $dest).Length
    file     = $dest
    frozenAt = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    commit   = (git rev-parse HEAD 2>$null)
}
$meta | ConvertTo-Json | Set-Content release\FROZEN.json -Encoding UTF8

Write-Output "== 3/3 完成 =="
Write-Output "文件   : $dest"
Write-Output "SHA256 : $sha"
Write-Output "大小   : $($meta.size) B"
Write-Output "提交   : $($meta.commit)"
Write-Output ""
Write-Output "qa 请只安装这个文件，不要用 app/build 下的路径："
Write-Output "  adb install -r `"$dest`""
