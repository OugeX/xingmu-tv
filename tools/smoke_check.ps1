#!/usr/bin/env pwsh
<#
.SYNOPSIS
    对 debug APK 做静态冒烟检查（TV 清单 / assets / dex / 体积）。

.DESCRIPTION
    检查项（缺一项即 FAIL，最终汇总 FAIL 列表）：
      1. APK 存在且是合法 zip；
      2. aapt2 dump badging 成功；
      3. LEANBACK_LAUNCHER 可启动 Activity（badging 缺失时回退查 xmltree）；
      4. uses-feature android.software.leanback 为 required=false；
      5. uses-feature android.hardware.touchscreen 为 required=false；
      6. assets/sources.builtin.json 存在且非空；
      7. assets/overseas_map.json 存在且非空；
      8. assets/pinyin_initials.json 存在且非空；
      9. assets/live_seed.json 存在且非空；
     10. classes*.dex 中存在 Lcom/tvbox/app/MainActivity;；
     11. APK 体积（debug >14MB WARN、>16MB FAIL；release >14MB FAIL）。

.PARAMETER ApkPath
    APK 路径；默认 app/build/outputs/apk/debug/app-debug.apk（相对工程根目录）。

.PARAMETER Aapt2
    aapt2(.exe) 路径；默认从 ANDROID_HOME / local.properties / D:\tvbuild\android-sdk 的
    build-tools 中挑最高版本。

.PARAMETER DryRun
    只打印解析到的工具链与将要执行的检查项，不做实际检查（APK 尚未产出时使用）。

.PARAMETER JsonOut
    可选：把检查结果写成 JSON（供 docs/QA_REPORT.md 引用）。

.EXAMPLE
    pwsh -NoProfile -File tools/smoke_check.ps1 -DryRun

.EXAMPLE
    pwsh -NoProfile -File tools/smoke_check.ps1
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$ApkPath = '',
    [string]$Aapt2 = '',
    [double]$MaxSizeMB = 14,
    [double]$HardMaxSizeMB = 16,
    [string]$JsonOut = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Say([string]$Message, [string]$Level = 'INFO') {
    $color = switch ($Level) {
        'PASS' { 'Green' }
        'WARN' { 'Yellow' }
        'FAIL' { 'Red' }
        default { 'Cyan' }
    }
    Write-Host ("[{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

$script:checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [string]$Id,
        [string]$Name,
        [string]$Status,
        [bool]$Required = $true,
        [string]$Detail = ''
    )
    $script:checks.Add([pscustomobject]@{
        id       = $Id
        name     = $Name
        status   = $Status
        required = $Required
        detail   = $Detail
    })
    $level = if ($Status -eq 'PASS') { 'PASS' } elseif ($Status -eq 'FAIL') { 'FAIL' } elseif ($Status -eq 'WARN') { 'WARN' } else { 'INFO' }
    Say ("{0} {1}{2}" -f $Id, $Name, $(if ($Detail) { " — $Detail" } else { '' })) $level
}

function Resolve-FullPath([string]$Path, [string]$Base) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return '' }
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path $Base $Path))
}

# ---------- 工程根目录 ----------
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    Say "工程根目录不存在: $ProjectRoot" 'FAIL'
    exit 1
}

# ---------- APK ----------
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
} else {
    $ApkPath = Resolve-FullPath $ApkPath $ProjectRoot
}
$apkExists = Test-Path -LiteralPath $ApkPath -PathType Leaf

# ---------- Android SDK / aapt2 ----------
function Get-SdkDirFromLocalProperties {
    $lp = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $lp -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $lp -Encoding utf8) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') {
                return ($Matches[1] -replace '\\\\', '\')
            }
        }
    }
    return $null
}

$sdkDir = $null
if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and (Test-Path $env:ANDROID_HOME)) {
    $sdkDir = $env:ANDROID_HOME
} else {
    $sdkDir = Get-SdkDirFromLocalProperties
    if ([string]::IsNullOrWhiteSpace($sdkDir) -or -not (Test-Path $sdkDir)) {
        if (Test-Path 'D:\tvbuild\android-sdk') { $sdkDir = 'D:\tvbuild\android-sdk' }
    }
}
if (-not [string]::IsNullOrWhiteSpace($sdkDir)) { $sdkDir = [System.IO.Path]::GetFullPath($sdkDir) }

$aapt2Path = $null
if (-not [string]::IsNullOrWhiteSpace($Aapt2)) {
    $aapt2Path = Resolve-FullPath $Aapt2 $ProjectRoot
    if (-not (Test-Path -LiteralPath $aapt2Path -PathType Leaf)) {
        Say "指定的 aapt2 不存在: $aapt2Path" 'FAIL'
        exit 1
    }
} elseif (-not [string]::IsNullOrWhiteSpace($sdkDir)) {
    $buildTools = Join-Path $sdkDir 'build-tools'
    if (Test-Path -LiteralPath $buildTools -PathType Container) {
        $versions = Get-ChildItem -LiteralPath $buildTools -Directory |
            Sort-Object -Property Name -Descending
        foreach ($v in $versions) {
            $candidate = Join-Path $v.FullName 'aapt2.exe'
            if (-not (Test-Path -LiteralPath $candidate)) { $candidate = Join-Path $v.FullName 'aapt2' }
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $aapt2Path = [System.IO.Path]::GetFullPath($candidate)
                break
            }
        }
    }
}

Write-Host ''
Write-Host '========== SMOKE CHECK CONFIG ==========' -ForegroundColor Cyan
Write-Host "工程根目录 : $ProjectRoot"
Write-Host "APK        : $ApkPath (exists=$apkExists)"
Write-Host "SDK        : $(if ($sdkDir) { $sdkDir } else { '<未找到>' })"
Write-Host "aapt2      : $(if ($aapt2Path) { $aapt2Path } else { '<未找到>' })"
Write-Host "体积预算   : target <= $MaxSizeMB MB / hard <= $HardMaxSizeMB MB"
Write-Host '========================================' -ForegroundColor Cyan

if ($DryRun) {
    Say 'DryRun 模式：仅校验参数与工具链，不读取 APK。' 'WARN'
    if ($null -eq $aapt2Path) { Say 'aapt2 未找到；实际运行会 FAIL（用 -Aapt2 指定）。' 'WARN' }
    Write-Host '将检查: C01 APK 存在 | C02 zip 可读 | C03 aapt2 badging | C04 package |'
    Write-Host '        C05 LEANBACK_LAUNCHER | C06 leanback required=false | C07 touchscreen required=false |'
    Write-Host '        C08-C11 assets (sources.builtin/overseas_map/pinyin_initials/live_seed) |'
    Write-Host '        C12 dex MainActivity | C13 APK size'
    Say '参数校验通过。' 'PASS'
    exit 0
}

if ($null -eq $aapt2Path) {
    Say '找不到 aapt2：请设置 ANDROID_HOME 或用 -Aapt2 指定（如 D:\tvbuild\android-sdk\build-tools\34.0.0\aapt2.exe）' 'FAIL'
    exit 1
}

# ---------- C01 APK 存在 ----------
if (-not $apkExists) {
    Add-Check 'C01' 'APK 存在' 'FAIL' $true "未找到 $ApkPath；先运行 tools/build_apk.ps1"
    Write-Host ''
    Say "RESULT: FAIL — APK 不存在，无法执行静态冒烟。命令: pwsh -NoProfile -File tools/build_apk.ps1" 'FAIL'
    exit 1
}
$apkItem = Get-Item -LiteralPath $ApkPath
$bytes = $apkItem.Length
$mb = [math]::Round($bytes / 1MB, 2)
Add-Check 'C01' 'APK 存在' 'PASS' $true "$ApkPath ($bytes bytes / $mb MB)"

# ---------- C02 zip 可读 ----------
$zip = $null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    Add-Check 'C02' 'APK zip 可读' 'PASS' $true ("entries=" + $zip.Entries.Count)
} catch {
    Add-Check 'C02' 'APK zip 可读' 'FAIL' $true $_.Exception.Message
}

# ---------- C03 aapt2 dump badging ----------
$badgingLines = @()
$badgingText = ''
$badgingOk = $false
if ($null -ne $zip) {
    try {
        $badgingText = (& $aapt2Path dump badging $ApkPath 2>&1 | Out-String)
        $badgingExit = $LASTEXITCODE
        if ($badgingExit -eq 0 -and -not [string]::IsNullOrWhiteSpace($badgingText)) {
            $badgingOk = $true
            $badgingLines = @($badgingText -split "`r?`n")
            Add-Check 'C03' 'aapt2 dump badging' 'PASS' $true ("exit=0, lines=" + $badgingLines.Count)
        } else {
            Add-Check 'C03' 'aapt2 dump badging' 'FAIL' $true "exit=$badgingExit"
        }
    } catch {
        Add-Check 'C03' 'aapt2 dump badging' 'FAIL' $true $_.Exception.Message
    }
} else {
    Add-Check 'C03' 'aapt2 dump badging' 'FAIL' $true 'APK zip 不可读，跳过'
}

# ---------- C04 package ----------
if ($badgingOk) {
    $pkg = ''
    foreach ($line in $badgingLines) {
        if ($line -match "^package:\s*name='([^']+)'") { $pkg = $Matches[1]; break }
    }
    if ($pkg -eq 'com.xingmu.tv') {
        Add-Check 'C04' 'applicationId = com.xingmu.tv' 'PASS' $false $pkg
    } elseif ([string]::IsNullOrWhiteSpace($pkg)) {
        Add-Check 'C04' 'applicationId = com.xingmu.tv' 'WARN' $false 'badging 未解析到 package 行'
    } else {
        Add-Check 'C04' 'applicationId = com.xingmu.tv' 'WARN' $false "实际为 $pkg"
    }
}

# ---------- C05 LEANBACK_LAUNCHER ----------
if ($badgingOk) {
    $leanbackLine = $null
    foreach ($line in $badgingLines) {
        if ($line -match '^leanback-launchable-activity:') { $leanbackLine = $line; break }
    }
    $activityName = ''
    if ($null -ne $leanbackLine -and $leanbackLine -match "name='([^']+)'") {
        $activityName = $Matches[1]
    }
    if ($null -ne $leanbackLine) {
        Add-Check 'C05' 'LEANBACK_LAUNCHER Activity' 'PASS' $true $leanbackLine.Trim()
    } else {
        # 回退：直接看 binary manifest 是否含 category LEANBACK_LAUNCHER
        $xmltree = ''
        try { $xmltree = (& $aapt2Path dump xmltree --file AndroidManifest.xml $ApkPath 2>&1 | Out-String) } catch { }
        if ($xmltree -match 'LEANBACK_LAUNCHER') {
            Add-Check 'C05' 'LEANBACK_LAUNCHER Activity' 'PASS' $true 'badging 无 leanback 行，但 AndroidManifest.xml 含 CATEGORY_LEANBACK_LAUNCHER'
        } else {
            Add-Check 'C05' 'LEANBACK_LAUNCHER Activity' 'FAIL' $true 'badging 无 leanback-launchable-activity，manifest 也无 CATEGORY_LEANBACK_LAUNCHER'
        }
    }
}

# ---------- C06/C07 uses-feature ----------
function Test-RequiredFeature {
    param(
        [string]$Id,
        [string]$FeatureName,
        [string]$Label
    )
    if (-not $badgingOk) {
        Add-Check $Id $Label 'FAIL' $true 'badging 不可用，无法检查'
        return
    }
    $line = $null
    foreach ($l in $badgingLines) {
        if ($l -match '^\s*uses-feature(-not-required)?:\s*name=' -and $l -match [regex]::Escape($FeatureName)) {
            $line = $l
            break
        }
    }
    if ($null -eq $line) {
        Add-Check $Id $Label 'FAIL' $true "缺少 uses-feature $FeatureName（老设备可能因隐式 required=true 装不上）"
        return
    }
    $isNotRequired = $line -match '^\s*uses-feature-not-required:'
    if ($isNotRequired) {
        Add-Check $Id $Label 'PASS' $true $line.Trim()
    } else {
        Add-Check $Id $Label 'FAIL' $true "存在但 required=true：$($line.Trim())"
    }
}

Test-RequiredFeature -Id 'C06' -FeatureName 'android.software.leanback' -Label 'uses-feature leanback required=false'
Test-RequiredFeature -Id 'C07' -FeatureName 'android.hardware.touchscreen' -Label 'uses-feature touchscreen required=false'

# ---------- C08-C11 assets ----------
$assetNames = [ordered]@{
    'C08' = 'sources.builtin.json'
    'C09' = 'overseas_map.json'
    'C10' = 'pinyin_initials.json'
    'C11' = 'live_seed.json'
}
if ($null -ne $zip) {
    foreach ($id in $assetNames.Keys) {
        $name = $assetNames[$id]
        $entry = $null
        foreach ($e in $zip.Entries) {
            if ($e.FullName -eq "assets/$name") { $entry = $e; break }
        }
        if ($null -eq $entry) {
            Add-Check $id "assets/$name" 'FAIL' $true 'APK 内缺失'
        } elseif ($entry.Length -le 0) {
            Add-Check $id "assets/$name" 'FAIL' $true '存在但为空文件'
        } else {
            Add-Check $id "assets/$name" 'PASS' $true "$($entry.Length) bytes"
        }
    }
} else {
    foreach ($id in $assetNames.Keys) {
        Add-Check $id "assets/$($assetNames[$id])" 'FAIL' $true 'APK zip 不可读，跳过'
    }
}

# ---------- C12 dex MainActivity ----------
$dexChecked = @()
$dexFound = $false
$mainActivityNeedle = 'Lcom/tvbox/app/MainActivity;'
if ($null -ne $zip) {
    $dexEntries = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    foreach ($entry in $dexEntries) {
        $dexChecked += $entry.FullName
        try {
            $stream = $entry.Open()
            $ms = New-Object System.IO.MemoryStream
            try {
                $stream.CopyTo($ms)
            } finally {
                $stream.Dispose()
            }
            # dex 的字符串表是 MUTF-8；Latin1 映射逐字节保留，ASCII 类描述符可直接子串检索
            $text = [System.Text.Encoding]::Latin1.GetString($ms.ToArray())
            $ms.Dispose()
            if ($text.Contains($mainActivityNeedle)) {
                $dexFound = $true
                Add-Check 'C12' 'dex 包含 MainActivity' 'PASS' $true "$($entry.FullName) 命中 $mainActivityNeedle"
                break
            }
        } catch {
            Add-Check 'C12' 'dex 包含 MainActivity' 'FAIL' $true "读取 $($entry.FullName) 失败: $($_.Exception.Message)"
        }
    }
    if (-not $dexFound) {
        if ($dexEntries.Count -eq 0) {
            Add-Check 'C12' 'dex 包含 MainActivity' 'FAIL' $true 'APK 内没有 classes*.dex'
        } elseif (($script:checks | Where-Object { $_.id -eq 'C12' }).Count -eq 0) {
            Add-Check 'C12' 'dex 包含 MainActivity' 'FAIL' $true ("未命中 $mainActivityNeedle（已查: " + ($dexChecked -join ', ') + ')')
        }
    }
} else {
    Add-Check 'C12' 'dex 包含 MainActivity' 'FAIL' $true 'APK zip 不可读，跳过'
}

# ---------- C13 体积 ----------
$isDebug = $ApkPath -match '[\\/]debug[\\/]'
if ($mb -gt $HardMaxSizeMB) {
    Add-Check 'C13' 'APK 体积' 'FAIL' $true "$mb MB > hard max $HardMaxSizeMB MB"
} elseif ($mb -gt $MaxSizeMB) {
    if ($isDebug) {
        Add-Check 'C13' 'APK 体积' 'WARN' $true "$mb MB > target $MaxSizeMB MB（debug 包，release 必须复测 ≤$MaxSizeMB MB）"
    } else {
        Add-Check 'C13' 'APK 体积' 'FAIL' $true "$mb MB > target $MaxSizeMB MB"
    }
} else {
    Add-Check 'C13' 'APK 体积' 'PASS' $true "$mb MB <= $MaxSizeMB MB"
}

# ---------- C14 sdk info ----------
if ($badgingOk) {
    $sdkLine = @($badgingLines | Where-Object { $_ -match '^(sdkVersion|targetSdkVersion):' }) -join '; '
    if ($sdkLine) { Add-Check 'C14' 'SDK 版本信息' 'INFO' $false $sdkLine }
}

if ($null -ne $zip) { $zip.Dispose() }

# ---------- 汇总 ----------
$requiredFails = @($script:checks | Where-Object { $_.required -and $_.status -eq 'FAIL' })
$warnings = @($script:checks | Where-Object { $_.status -eq 'WARN' })

Write-Host ''
Write-Host '========== SUMMARY ==========' -ForegroundColor Cyan
foreach ($c in $script:checks) {
    $color = switch ($c.status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'WARN' { 'Yellow' }
        default { 'Gray' }
    }
    Write-Host ("{0,-4} {1,-6} {2,-38} {3}" -f $c.id, $c.status, $c.name, $c.detail) -ForegroundColor $color
}

if ($requiredFails.Count -gt 0) {
    Write-Host ''
    Write-Host "FAIL 列表（$($requiredFails.Count) 项）:" -ForegroundColor Red
    foreach ($c in $requiredFails) {
        Write-Host "  - [$($c.id)] $($c.name): $($c.detail)" -ForegroundColor Red
    }
    Write-Host "RESULT: FAIL（$($requiredFails.Count) required check(s) failed, $($warnings.Count) warning(s)）" -ForegroundColor Red
} else {
    Write-Host ''
    Write-Host "RESULT: PASS（all required checks passed, $($warnings.Count) warning(s)）" -ForegroundColor Green
}

if (-not [string]::IsNullOrWhiteSpace($JsonOut)) {
    $jsonOutPath = Resolve-FullPath $JsonOut $ProjectRoot
    $jsonDir = Split-Path -Parent $jsonOutPath
    if (-not [string]::IsNullOrWhiteSpace($jsonDir) -and -not (Test-Path $jsonDir)) {
        New-Item -ItemType Directory -Path $jsonDir -Force | Out-Null
    }
    $report = [ordered]@{
        generated_at = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
        apk          = $ApkPath
        size_bytes   = $bytes
        size_mb      = $mb
        aapt2        = $aapt2Path
        result       = $(if ($requiredFails.Count -gt 0) { 'FAIL' } else { 'PASS' })
        failures     = @($requiredFails | ForEach-Object { [pscustomobject]@{ id = $_.id; name = $_.name; detail = $_.detail } })
        warnings     = @($warnings | ForEach-Object { [pscustomobject]@{ id = $_.id; name = $_.name; detail = $_.detail } })
        # 注意：PowerShell 7 的 ConvertTo-Json 无法序列化泛型 List[object]，必须先转数组
        checks       = @($script:checks.ToArray())
    }
    ($report | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $jsonOutPath -Encoding utf8
    Write-Host "JSON 报告: $jsonOutPath"
}

if ($requiredFails.Count -gt 0) { exit 1 }
exit 0
