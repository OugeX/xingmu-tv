#!/usr/bin/env pwsh
<#
.SYNOPSIS
    播放态 PSS / gfxinfo 掉帧率趋势采样（默认 10 分钟，每 60 秒一次）。

.DESCRIPTION
    1) 连接设备（默认 192.168.1.9:5555），唤醒并保持屏幕常亮；
    2) 通过 app 的 QA 直达入口打开直播播放页；
    3) 每 IntervalSec 采集 `dumpsys meminfo` 的 TOTAL PSS 与 `dumpsys gfxinfo` 的
       Total/Janky frames，输出趋势表、CSV、JSON；
    4) 与 docs/PLAYER_PERF.md §1 的播放态预算比对：
         PSS ≤340MB（硬上限 420MB）、掉帧 ≤10%（硬上限 20%）。

.NOTES
    采集的是「播放中」的进程级数据；设备/网络变化会造成波动，报告需同时记录时间。
    设备若中途退出播放页，仍继续采样并在结果中标记 app_exited。
#>
[CmdletBinding()]
param(
    [string]$Device = '192.168.1.9:5555',
    [string]$Package = 'com.xingmu.tv',
    [string]$Activity = 'com.xingmu.tv/.MainActivity',
    [int]$DurationSec = 600,
    [int]$IntervalSec = 60,
    [int]$WarmupSec = 10,
    [string]$OutCsv = 'tools/player_pss_trend.csv',
    [string]$OutJson = 'tools/player_pss_trend.json',
    [string]$Adb = '',
    [switch]$NoLaunch,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Say([string]$Message, [string]$Level = 'INFO') {
    $color = switch ($Level) { 'PASS' { 'Green' } 'WARN' { 'Yellow' } 'FAIL' { 'Red' } default { 'Cyan' } }
    Write-Host ("[{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

function Resolve-Adb {
    if (-not [string]::IsNullOrWhiteSpace($Adb)) { return $Adb }
    $candidates = @(
        'D:\tvbuild\android-sdk\platform-tools\adb.exe',
        (Join-Path $PWD 'platform-tools\adb.exe')
    )
    foreach ($c in $candidates) { if (Test-Path -LiteralPath $c -PathType Leaf) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $cmd) { return $cmd.Source }
    throw '找不到 adb：请用 -Adb 指定 platform-tools/adb.exe'
}

$adbPath = Resolve-Adb
if (-not (Test-Path -LiteralPath $adbPath)) { Say "adb 不存在: $adbPath" 'FAIL'; exit 1 }

if ($Device -match ':') {
    & $adbPath connect $Device | Out-Null
}
$devices = (& $adbPath devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Device)) {
    Say "设备未连接: $Device；先执行 adb connect $Device" 'FAIL'
    exit 1
}
if ($Device -notmatch ':') { $adbArgs = @('-s', $Device) } else { $adbArgs = @('-s', $Device) }

function Invoke-Adb([string[]]$Arguments) {
    & $adbPath @adbArgs @Arguments
}

if ($DryRun) {
    Say 'DryRun：仅打印采样计划，不执行 adb。' 'WARN'
    Say "device=$Device package=$Package duration=${DurationSec}s interval=${IntervalSec}s"
    Say "输出: $OutCsv / $OutJson"
    exit 0
}

# 屏幕常亮 + 唤醒（失败不阻断）
try { Invoke-Adb @('shell', 'svc', 'power', 'stayon', 'true') | Out-Null } catch { }
try { Invoke-Adb @('shell', 'input', 'keyevent', '224') | Out-Null } catch { }

if (-not $NoLaunch) {
    Say "启动直播播放页: $Activity (--es screen live)"
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Invoke-Adb @('shell', 'am', 'start', '-S', '-W', '--activity-clear-task', '-n', $Activity, '--es', 'screen', 'live') | Out-Null
    Start-Sleep -Seconds $WarmupSec
}

# 复位 gfxinfo 计数，之后每次采样得到的是累计窗口
Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package, 'reset') | Out-Null

$samples = New-Object System.Collections.Generic.List[object]
$started = Get-Date
$steps = [math]::Floor($DurationSec / $IntervalSec)
if ($steps -lt 1) { $steps = 1 }

Say "开始采样: ${DurationSec}s / ${IntervalSec}s，共 $($steps + 1) 个时间点"
for ($i = 0; $i -le $steps; $i++) {
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    $mem = (Invoke-Adb @('shell', 'dumpsys', 'meminfo', $Package)) -join "`n"
    $gfx = (Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package)) -join "`n"

    $pssKb = $null
    $m = [regex]::Match($mem, '(?m)^\s*TOTAL\s+(\d+)\s+')
    if ($m.Success) { $pssKb = [int]$m.Groups[1].Value }
    $pssMb = if ($null -ne $pssKb) { [math]::Round($pssKb / 1024, 1) } else { $null }

    $frames = 0
    $janky = 0
    $jankPct = $null
    $fm = [regex]::Match($gfx, 'Total frames rendered:\s*(\d+)')
    if ($fm.Success) { $frames = [int]$fm.Groups[1].Value }
    $jm = [regex]::Match($gfx, 'Janky frames:\s*(\d+)\s*\(([\d.]+)%\)')
    if ($jm.Success) {
        $janky = [int]$jm.Groups[1].Value
        $jankPct = [double]$jm.Groups[2].Value
    } elseif ($fm.Success) {
        $jankPct = 0.0
    }

    $fg = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'activities')) -join "`n"
    $appAlive = $fg -match 'mResumedActivity.*' + [regex]::Escape($Package)

    $pssVerdict = if ($null -eq $pssMb) { 'NO_DATA' } elseif ($pssMb -le 340) { 'PASS' } elseif ($pssMb -le 420) { 'WARN' } else { 'FAIL' }
    $jankVerdict = if ($null -eq $jankPct) { 'NO_DATA' } elseif ($jankPct -le 10) { 'PASS' } elseif ($jankPct -le 20) { 'WARN' } else { 'FAIL' }

    $row = [pscustomobject]@{
        t_sec      = $elapsed
        pss_kb     = $pssKb
        pss_mb     = $pssMb
        frames     = $frames
        janky      = $janky
        jank_pct   = $jankPct
        app_alive  = $appAlive
        pss_result = $pssVerdict
        jank_result= $jankVerdict
    }
    $samples.Add($row)
    Say ("t={0,3}s PSS={1,7} KB ({2,5} MB) {3,-4} | frames={4,4} janky={5,3} ({6}%) {7}" -f `
        $elapsed, $(if ($null -ne $pssKb) { $pssKb } else { '?' }), $(if ($null -ne $pssMb) { $pssMb } else { '?' }), `
        $pssVerdict, $frames, $janky, $(if ($null -ne $jankPct) { $jankPct } else { '?' }), $jankVerdict) `
        $(if ($pssVerdict -eq 'FAIL' -or $jankVerdict -eq 'FAIL') { 'FAIL' } elseif ($pssVerdict -eq 'WARN' -or $jankVerdict -eq 'WARN') { 'WARN' } else { 'INFO' })

    if ($i -lt $steps) { Start-Sleep -Seconds $IntervalSec }
}

# CSV
$csvPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutCsv))
$csvDir = Split-Path -Parent $csvPath
if ($csvDir -and -not (Test-Path $csvDir)) { New-Item -ItemType Directory -Path $csvDir -Force | Out-Null }
$samples | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

# JSON
$validPss = @($samples | Where-Object { $null -ne $_.pss_mb })
$validJank = @($samples | Where-Object { $null -ne $_.jank_pct })
$summary = [ordered]@{
    device        = $Device
    package       = $Package
    started_at    = $started.ToString('yyyy-MM-ddTHH:mm:ssK')
    duration_sec  = $DurationSec
    interval_sec  = $IntervalSec
    sample_count  = $samples.Count
    pss_avg_mb    = if ($validPss.Count) { [math]::Round(($validPss | Measure-Object -Property pss_mb -Average).Average, 1) } else { $null }
    pss_max_mb    = if ($validPss.Count) { ($validPss | Measure-Object -Property pss_mb -Maximum).Maximum } else { $null }
    jank_avg_pct  = if ($validJank.Count) { [math]::Round(($validJank | Measure-Object -Property jank_pct -Average).Average, 2) } else { $null }
    jank_max_pct  = if ($validJank.Count) { ($validJank | Measure-Object -Property jank_pct -Maximum).Maximum } else { $null }
    pss_budget_mb = 340
    jank_budget_pct = 10
    app_exited    = @($samples | Where-Object { -not $_.app_alive }).Count -gt 0
    # PowerShell 7 的 ConvertTo-Json 不能直接序列化泛型 List，先转数组
    samples       = @($samples.ToArray())
}
$jsonPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutJson))
($summary | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $jsonPath -Encoding utf8

Write-Host ''
Write-Host '========== PSS / JANK TREND ==========' -ForegroundColor Cyan
$samples | Format-Table t_sec, pss_mb, frames, janky, jank_pct, pss_result, jank_result, app_alive -AutoSize
Write-Host "PSS avg/max: $($summary.pss_avg_mb) / $($summary.pss_max_mb) MB（预算 ≤340MB）"
Write-Host "Jank avg/max: $($summary.jank_avg_pct) / $($summary.jank_max_pct) %（预算 ≤10%）"
Write-Host "CSV: $csvPath"
Write-Host "JSON: $jsonPath"

$failed = @($samples | Where-Object { $_.pss_result -eq 'FAIL' -or $_.jank_result -eq 'FAIL' })
if ($failed.Count -gt 0) { exit 1 }
exit 0
