#!/usr/bin/env pwsh
<#
.SYNOPSIS
    一键构建 Android TV debug APK（JDK/Android SDK/Gradle 全部自动定位）。

.DESCRIPTION
    1) 校验工程根目录（settings.gradle.kts / build.gradle.kts / app/build.gradle.kts）；
    2) 设置 JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT（仅当前进程）；
    3) 执行 `gradle assembleDebug --no-daemon`；
    4) 打印退出码、APK 绝对路径、体积（字节 + MB）、SHA256；
    5) 构建成功但找不到 APK 时明确报错并以退出码 3 结束。

    环境默认值与 docs/TOOLCHAIN.md 一致，可用参数覆盖：
      JDK       D:\tvbuild\jdk-17.0.2
      Gradle    D:\tvbuild\gradle-8.7\bin\gradle.bat
      Android   D:\tvbuild\android-sdk

.PARAMETER DryRun
    只做路径/参数校验并打印将要执行的命令，不真正构建（工程骨架未完成时使用）。

.EXAMPLE
    pwsh -NoProfile -File tools/build_apk.ps1 -DryRun

.EXAMPLE
    pwsh -NoProfile -File tools/build_apk.ps1
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$JdkHome = '',
    [string]$AndroidHome = '',
    [string]$GradleExe = '',
    [string]$Task = 'assembleDebug',
    [string]$LogFile = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# 退出码约定：0 成功；1 参数/环境错误；2 gradle 构建失败；3 构建成功但 APK 缺失
$script:ExitEnv = 1
$script:ExitBuild = 2
$script:ExitNoApk = 3

function Say([string]$Message, [string]$Level = 'INFO') {
    $color = switch ($Level) {
        'OK'   { 'Green' }
        'WARN' { 'Yellow' }
        'FAIL' { 'Red' }
        default { 'Cyan' }
    }
    Write-Host ("[{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

function Fail([string]$Message, [int]$Code = 1) {
    Say $Message 'FAIL'
    exit $Code
}

function Resolve-FullPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return '' }
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Path))
}

# ---------- 1. 工程根目录 ----------
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$ProjectRoot = Resolve-FullPath $ProjectRoot
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    Fail "工程根目录不存在: $ProjectRoot"
}
$requiredFiles = @('settings.gradle.kts', 'build.gradle.kts', 'app\build.gradle.kts')
foreach ($rel in $requiredFiles) {
    $full = Join-Path $ProjectRoot $rel
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        Fail "缺少 Gradle 工程文件: $rel（工程根目录: $ProjectRoot）"
    }
}
Say "工程根目录: $ProjectRoot" 'OK'

# ---------- 2. JDK ----------
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $JdkHome = $env:JAVA_HOME
    } else {
        $JdkHome = 'D:\tvbuild\jdk-17.0.2'
    }
}
$JdkHome = Resolve-FullPath $JdkHome
$javaExe = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
    Fail "JAVA_HOME 无效：找不到 $javaExe（用 -JdkHome 指定 JDK 17 目录）"
}
Say "JAVA_HOME: $JdkHome" 'OK'

# ---------- 3. Android SDK ----------
if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and (Test-Path $env:ANDROID_HOME)) {
        $AndroidHome = $env:ANDROID_HOME
    } else {
        $AndroidHome = 'D:\tvbuild\android-sdk'
    }
}
$AndroidHome = Resolve-FullPath $AndroidHome
if (-not (Test-Path -LiteralPath $AndroidHome -PathType Container)) {
    Fail "ANDROID_HOME 无效：目录不存在 $AndroidHome（用 -AndroidHome 指定 SDK）"
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidHome 'platforms') -PathType Container)) {
    Fail "ANDROID_HOME 下缺少 platforms/ 目录: $AndroidHome"
}
Say "ANDROID_HOME: $AndroidHome" 'OK'

# ---------- 4. Gradle ----------
$gradleCandidates = New-Object System.Collections.Generic.List[string]
if (-not [string]::IsNullOrWhiteSpace($GradleExe)) {
    $gradleCandidates.Add((Resolve-FullPath $GradleExe))
}
if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_HOME)) {
    $gradleCandidates.Add((Join-Path $env:GRADLE_HOME 'bin\gradle.bat'))
}
$gradleCandidates.Add('D:\tvbuild\gradle-8.7\bin\gradle.bat')
$gradleCandidates.Add((Join-Path $ProjectRoot 'gradlew.bat'))
foreach ($name in @('gradle.bat', 'gradle.cmd', 'gradle')) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $cmd) { $gradleCandidates.Add($cmd.Source) }
}

$gradlePath = $null
foreach ($candidate in $gradleCandidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        $gradlePath = [System.IO.Path]::GetFullPath($candidate)
        break
    }
}
if ($null -eq $gradlePath) {
    Fail "找不到 Gradle：请用 -GradleExe 指定，或确认 D:\tvbuild\gradle-8.7\bin\gradle.bat 存在"
}
Say "Gradle: $gradlePath" 'OK'

# ---------- 5. 骨架完成度提示（不阻断） ----------
$mainActivity = Join-Path $ProjectRoot 'app\src\main\java\com\tvbox\app\MainActivity.kt'
if (-not (Test-Path -LiteralPath $mainActivity -PathType Leaf)) {
    Say "提醒：$mainActivity 尚不存在，工程骨架可能未完成；构建可能成功但 APK 无可启动 Activity，smoke_check.ps1 会报 FAIL。" 'WARN'
}

# ---------- 6. 计算产物路径 ----------
$defaultApk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'

if ($DryRun) {
    Say 'DryRun 模式：仅校验参数，不执行 gradle。' 'WARN'
    Say "将执行: `"$gradlePath`" $Task --no-daemon（工作目录: $ProjectRoot）"
    Say "预期产物: $defaultApk"
    Say '参数校验通过。' 'OK'
    exit 0
}

# ---------- 7. 构建 ----------
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome

$gradleArgs = @($Task, '--no-daemon')
Say "执行: `"$gradlePath`" $($gradleArgs -join ' ')" 'INFO'
$started = Get-Date
Push-Location -LiteralPath $ProjectRoot
try {
    if (-not [string]::IsNullOrWhiteSpace($LogFile)) {
        $logFull = Resolve-FullPath $LogFile
        $logDir = Split-Path -Parent $logFull
        if (-not [string]::IsNullOrWhiteSpace($logDir) -and -not (Test-Path $logDir)) {
            New-Item -ItemType Directory -Path $logDir -Force | Out-Null
        }
        & $gradlePath @gradleArgs 2>&1 | Tee-Object -FilePath $logFull
        $buildExit = $LASTEXITCODE
        Say "构建日志: $logFull"
    } else {
        & $gradlePath @gradleArgs
        $buildExit = $LASTEXITCODE
    }
} finally {
    Pop-Location
}
$elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)

Say "gradle 退出码: $buildExit（耗时 ${elapsed}s）"
if ($buildExit -ne 0) {
    Say "构建失败：gradle 退出码 $buildExit。请查看上方错误输出。" 'FAIL'
    exit $script:ExitBuild
}

# ---------- 8. 定位 APK ----------
$apkPath = $null
if (Test-Path -LiteralPath $defaultApk -PathType Leaf) {
    $apkPath = $defaultApk
} else {
    $outputsRoot = Join-Path $ProjectRoot 'app\build\outputs\apk'
    if (Test-Path -LiteralPath $outputsRoot -PathType Container) {
        $found = Get-ChildItem -LiteralPath $outputsRoot -Recurse -Filter '*.apk' -File -ErrorAction SilentlyContinue |
            Sort-Object -Property LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -ne $found) {
            $apkPath = $found.FullName
            Say "预期路径不存在，使用最新 APK: $apkPath" 'WARN'
        }
    }
}
if ($null -eq $apkPath) {
    Say "构建报告成功，但找不到 APK！已查找: $defaultApk 及 $ProjectRoot\app\build\outputs\apk\**\*.apk" 'FAIL'
    exit $script:ExitNoApk
}

# ---------- 9. 产物信息 ----------
$item = Get-Item -LiteralPath $apkPath
$bytes = $item.Length
$mb = [math]::Round($bytes / 1MB, 2)
$sha = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host ''
Write-Host '========== BUILD RESULT ==========' -ForegroundColor Green
Write-Host "gradle 退出码 : 0"
Write-Host "APK           : $($item.FullName)"
Write-Host "体积          : $bytes bytes ($mb MB)"
Write-Host "SHA256        : $sha"
Write-Host "耗时          : ${elapsed}s"
Write-Host "预算          : ≤14MB（硬上限 16MB，见 docs/PLAN_ARCHITECTURE.md §12 A6）"
if ($mb -gt 16) {
    Write-Host '体积判定      : FAIL（超过 16MB 硬上限）' -ForegroundColor Red
} elseif ($mb -gt 14) {
    Write-Host '体积判定      : WARN（超过 14MB 目标，debug 包常见，release 必须复测）' -ForegroundColor Yellow
} else {
    Write-Host '体积判定      : PASS（≤14MB）' -ForegroundColor Green
}
Write-Host '=================================' -ForegroundColor Green

exit 0
