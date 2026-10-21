#!/usr/bin/env pwsh
<#
.SYNOPSIS
    真机播放器性能测量：直播起播/切台 + 点播首帧/切集，解析 PLAYER_EVT 并对照
    docs/PLAYER_PERF.md §1 预算输出 PASS/FAIL。

.DESCRIPTION
    流程（默认）：
      1) adb logcat -c；
      2) 通过 QA 直达入口打开直播页，CH+ 连切 10 个频道；
      3) 打开指定点播详情，ENTER 播放，MENU 打开选集面板后再切 5 集；
      4) 拉取 logcat 中的 PLAYER_EVT；
      5) 解析 t_prepare_ms / t_first_frame_ms / rebuffer / src / degraded；
      6) 输出 n / P50 / P90 / max 表与预算比对，JSON 落盘。

    预算（PLAYER_PERF §1，target / hard upper）：
      VOD 首帧        2.0s / 3.0s
      直播首次起播    1.5s / 2.5s
      切台（同 CDN）  1.0s / 1.5s
      切台（跨 CDN）  2.0s / 3.0s
      切集（同源）    0.8s / 1.2s
      换源（跨站）    1.5s / 2.5s  ← 当前埋点没有 action=switchsrc，脚本标记 N/A
      二次缓冲        1 次 / 3 次（30 分钟窗口；本脚本不覆盖 30 分钟）

.NOTES
    不改 app 源码。若 PLAYER_EVT 不存在，脚本仍可跑通并在 JSON 中标记
    instrumentation_present=false；报告必须写「埋点缺失，无法测」。
#>
[CmdletBinding()]
param(
    [string]$Device = '192.168.1.9:5555',
    [string]$Package = 'com.tvbox.app',
    [string]$Activity = 'com.tvbox.app/.MainActivity',
    [int]$Zaps = 10,
    [int]$Episodes = 5,
    [string]$VodId = '89664',
    [string]$SourceId = 'bfzy1',
    [string]$VodTitle = '欢乐集结号',
    [int]$LiveWarmupSec = 10,
    [int]$DetailWaitSec = 18,
    [int]$PerZapWaitMs = 1800,
    [int]$PerEpisodeWaitMs = 3500,
    [string]$OutJson = 'tools/player_metrics.json',
    [string]$LiveLog = 'tools/player_live_events.log',
    [string]$VodLog = 'tools/player_vod_events.log',
    [string]$Adb = '',
    [switch]$SkipLive,
    [switch]$SkipVod,
    [switch]$ResetApp,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function Say([string]$Message, [string]$Level = 'INFO') {
    $color = switch ($Level) { 'PASS' { 'Green' } 'WARN' { 'Yellow' } 'FAIL' { 'Red' } default { 'Cyan' } }
    Write-Host ("[{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

function Resolve-Adb {
    if (-not [string]::IsNullOrWhiteSpace($Adb)) { return $Adb }
    foreach ($c in @('D:\tvbuild\android-sdk\platform-tools\adb.exe', (Join-Path $PWD 'platform-tools\adb.exe'))) {
        if (Test-Path -LiteralPath $c -PathType Leaf) { return $c }
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $cmd) { return $cmd.Source }
    throw '找不到 adb：请用 -Adb 指定 platform-tools/adb.exe'
}

$adbPath = Resolve-Adb
if (-not (Test-Path -LiteralPath $adbPath)) { Say "adb 不存在: $adbPath" 'FAIL'; exit 1 }

if ($Device -match ':') { & $adbPath connect $Device | Out-Null }
$devices = (& $adbPath devices) -join "`n"
if ($devices -notmatch [regex]::Escape($Device)) {
    Say "设备未连接: $Device；先执行 adb connect $Device" 'FAIL'
    exit 1
}
$adbBase = @('-s', $Device)
function Adb([string[]]$Arguments) { & $adbPath @adbBase @Arguments }
function AdbText([string[]]$Arguments) { (Adb $Arguments) -join "`n" }

if ($DryRun) {
    Say 'DryRun：仅打印测量计划，不执行 adb。' 'WARN'
    Say "device=$Device package=$Package zaps=$Zaps episodes=$Episodes vod=${VodId}/${SourceId}"
    Say "输出: $OutJson / $LiveLog / $VodLog"
    Say '流程: logcat -c -> live deep link -> CH+ x10 -> detail -> ENTER -> MENU + RIGHT/ENTER x5 -> 解析 PLAYER_EVT'
    exit 0
}

function Wait-AppForeground([int]$TimeoutSec = 5) {
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        $fg = AdbText @('shell', 'dumpsys', 'activity', 'activities')
        if ($fg -match 'mResumedActivity.*' + [regex]::Escape($Package)) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Format-Path([string]$Path) {
    $full = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Path))
    $dir = Split-Path -Parent $full
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    return $full
}

function Reset-AppForMeasure {
    if (-not $ResetApp) { return }
    Say 'ResetApp：pm clear 重置 app 数据/任务（仅 QA 测量用，会清空源表/偏好）' 'WARN'
    Adb @('shell', 'pm', 'clear', $Package) | Out-Null
    Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Seconds 2
}

# ---------- 1. 直播：起播 + 切台 ----------
if (-not $SkipLive) {
    Say "直播测量：logcat -c -> 启动 live -> CH+ x$Zaps" 'INFO'
    Reset-AppForMeasure
    Adb @('shell', 'logcat', '-c') | Out-Null
    Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Adb @('shell', 'am', 'start', '-S', '-W', '--activity-clear-task', '-n', $Activity, '--es', 'screen', 'live') | Out-Null
    Start-Sleep -Seconds $LiveWarmupSec
    if (-not (Wait-AppForeground 3)) { Say '直播页未在前台；继续按键，结果可能为空' 'WARN' }

    for ($i = 1; $i -le $Zaps; $i++) {
        Adb @('shell', 'input', 'keyevent', '166') | Out-Null   # KEYCODE_CHANNEL_UP（CH+）
        Start-Sleep -Milliseconds $PerZapWaitMs
        if (-not (Wait-AppForeground 1)) {
            Say "第 $i 次切台后 app 不在前台；尝试重新打开直播页" 'WARN'
            Adb @('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', $Activity, '--es', 'screen', 'live') | Out-Null
            Start-Sleep -Seconds 3
        }
    }
    $liveLogPath = Format-Path $LiveLog
    (AdbText @('shell', 'logcat', '-d', '-v', 'brief')) | Set-Content -LiteralPath $liveLogPath -Encoding utf8
    Say "直播日志: $liveLogPath" 'OK'
}

# ---------- 2. 点播：首帧 + 切集 ----------
if (-not $SkipVod) {
    Say "点播测量：启动 detail($VodId) -> ENTER 播放 -> MENU 选集 x$Episodes" 'INFO'
    Reset-AppForMeasure
    Adb @('shell', 'logcat', '-c') | Out-Null
    Adb @('shell', 'am', 'start', '-S', '-W', '--activity-clear-task', '-n', $Activity,
        '--es', 'screen', 'detail', '--es', 'vodId', $VodId, '--es', 'sourceId', $SourceId,
        '--es', 'title', $VodTitle) | Out-Null
    Start-Sleep -Seconds $DetailWaitSec
    if (-not (Wait-AppForeground 3)) { Say '详情页未在前台；继续发送 ENTER' 'WARN' }

    Adb @('shell', 'input', 'keyevent', '66') | Out-Null       # ENTER：播放第 1 集（默认焦点）
    Start-Sleep -Seconds 10

    for ($i = 1; $i -le $Episodes; $i++) {
        Adb @('shell', 'input', 'keyevent', '82') | Out-Null     # MENU：打开选集面板
        Start-Sleep -Milliseconds 800
        Adb @('shell', 'input', 'keyevent', '22') | Out-Null     # RIGHT：下一个分集
        Start-Sleep -Milliseconds 300
        Adb @('shell', 'input', 'keyevent', '66') | Out-Null     # ENTER：切换
        Start-Sleep -Milliseconds $PerEpisodeWaitMs
        Say "切集 $i/$Episodes 已发送"
    }
    $vodLogPath = Format-Path $VodLog
    (AdbText @('shell', 'logcat', '-d', '-v', 'brief')) | Set-Content -LiteralPath $vodLogPath -Encoding utf8
    Say "点播日志: $vodLogPath" 'OK'
}

# ---------- 3. 解析 PLAYER_EVT ----------
$events = New-Object System.Collections.Generic.List[object]
$logPaths = @()
if (-not $SkipLive -and (Test-Path $LiveLog)) { $logPaths += (Resolve-Path $LiveLog).Path }
if (-not $SkipVod -and (Test-Path $VodLog)) { $logPaths += (Resolve-Path $VodLog).Path }
foreach ($p in $logPaths) {
    $phase = if ($p -eq (Resolve-Path $LiveLog).Path) { 'live' } else { 'vod' }
    foreach ($line in Get-Content -LiteralPath $p -Encoding utf8) {
        if ($line -notmatch 'PLAYER_EVT') { continue }
        $action = if ($line -match 'action=(\w+)') { $Matches[1] } else { 'unknown' }
        $prep = if ($line -match 't_prepare_ms=(\d+)') { [int]$Matches[1] } else { $null }
        $first = if ($line -match 't_first_frame_ms=(\d+)') { [int]$Matches[1] } else { $null }
        $reb = if ($line -match 'rebuffer=(\d+)') { [int]$Matches[1] } else { $null }
        $src = if ($line -match 'src=(\S+)') { $Matches[1] } else { '' }
        $label = if ($line -match 'label=(\S+)') { $Matches[1] } else { '' }
        $from = if ($line -match 'from=(.*?) to=') { $Matches[1] } else { '' }
        $to = if ($line -match 'to=(.*?) t_prepare_ms=') { $Matches[1] } else { '' }
        $degraded = if ($line -match 'degraded=(\d+)') { [int]$Matches[1] } else { 0 }
        # t25：解码器会话复用证据（旧包没有这些字段 → $null）
        $decInit = if ($line -match 'decoder_init_ms=(\d+)') { [int]$Matches[1] } else { $null }
        $decInits = if ($line -match 'decoder_inits=(\d+)') { [int]$Matches[1] } else { $null }
        $codecReuse = if ($line -match 'codec_reuse=(\d+)') { [int]$Matches[1] } else { $null }
        $events.Add([pscustomobject]@{
            phase     = $phase
            action    = $action
            prepare   = $prep
            first     = $first
            rebuffer  = $reb
            src       = $src
            label     = $label
            from      = $from
            to        = $to
            degraded  = $degraded
            decoder_init_ms = $decInit
            decoder_inits   = $decInits
            codec_reuse     = $codecReuse
            raw       = $line.Trim()
        })
    }
}

function Get-StatRow([string]$Name, [object[]]$Values, [int]$TargetMs, [int]$UpperMs) {
    $vals = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [int]$_ } | Sort-Object)
    if ($vals.Count -eq 0) {
        return [pscustomobject]@{ metric = $Name; n = 0; p50 = $null; p90 = $null; max = $null; target_ms = $TargetMs; upper_ms = $UpperMs; result = 'N/A' }
    }
    $n = $vals.Count
    $p50 = $vals[[math]::Floor(($n - 1) * 0.50)]
    $p90 = $vals[[math]::Ceiling($n * 0.90) - 1]
    if ($p90 -lt 0) { $p90 = $vals[$n - 1] }
    $max = $vals[$n - 1]
    $result = if ($max -le $TargetMs) { 'PASS' } elseif ($max -le $UpperMs) { 'FAIL(target, within upper)' } else { 'FAIL(over upper)' }
    return [pscustomobject]@{ metric = $Name; n = $n; p50 = $p50; p90 = $p90; max = $max; target_ms = $TargetMs; upper_ms = $UpperMs; result = $result }
}

$liveEvents = @($events | Where-Object { $_.phase -eq 'live' })
$vodEvents = @($events | Where-Object { $_.phase -eq 'vod' })

# 直播阶段第一条事件（action=play 或 zap）视为首次起播；后续 zap 才是切台
$liveStart = if ($liveEvents.Count -gt 0) { @($liveEvents[0]) } else { @() }
$zapEvents = @($liveEvents | Where-Object { $_.action -eq 'zap' })
if ($liveStart.Count -gt 0 -and $liveStart[0].action -eq 'zap' -and $zapEvents.Count -gt 0) {
    $zapEvents = @($zapEvents | Select-Object -Skip 1)
}

# 点播阶段第一条 action=play 视为首帧；其余 play 视为切集（埋点没有 action=episode）
$vodFirst = if ($vodEvents.Count -gt 0) { @($vodEvents[0]) } else { @() }
$episodeEvents = if ($vodEvents.Count -gt 1) { @($vodEvents | Select-Object -Skip 1) } else { @() }
$playEvents = @($vodEvents | Where-Object { $_.action -eq 'play' })

$rows = @(
    (Get-StatRow 'VOD first frame' $vodFirst.first 2000 3000),
    (Get-StatRow 'VOD prepare'     $vodFirst.prepare 2000 3000),
    (Get-StatRow 'Live first start' $liveStart.first 1500 2500),
    (Get-StatRow 'Zap (same CDN)'   $zapEvents.first 1000 1500),
    (Get-StatRow 'Episode switch'   $episodeEvents.first 800 1200)
)

$rebufferMax = if ($events.Count) { ($events | Where-Object { $null -ne $_.rebuffer } | Measure-Object -Property rebuffer -Maximum).Maximum } else { $null }
$instrumentation = $events.Count -gt 0

$report = [ordered]@{
    generated_at      = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
    device            = $Device
    package           = $Package
    instrumentation_present = $instrumentation
    event_count       = $events.Count
    live_event_count  = $liveEvents.Count
    play_event_count  = $playEvents.Count
    rebuffer_max      = $rebufferMax
    source_switch     = 'N/A: PLAYER_EVT 无 action=switchsrc（t9 埋点缺失），无法分离换源耗时'
    budget_source     = 'docs/PLAYER_PERF.md §1'
    metrics           = @($rows)
    # PowerShell 7 的 ConvertTo-Json 不能直接序列化泛型 List，先转数组
    events            = @($events.ToArray())
}
$jsonPath = Format-Path $OutJson
($report | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $jsonPath -Encoding utf8

# ---------- 4. 输出 ----------
Write-Host ''
Write-Host '========== PLAYER PERFORMANCE (PLAYER_PERF §1) ==========' -ForegroundColor Cyan
if (-not $instrumentation) {
    Say 'PLAYER_EVT 埋点缺失：日志中没有 PLAYER_EVT，无法测。' 'FAIL'
} else {
    $rows | Format-Table metric, n, p50, p90, max, target_ms, upper_ms, result -AutoSize
    Write-Host "PLAYER_EVT: $($events.Count) 条（zap=$($liveEvents.Count), play=$($playEvents.Count)）"
    Write-Host "rebuffer_max=$rebufferMax（30 分钟窗口预算 ≤1；本脚本窗口不足，仅记录）"
    Write-Host 'Source switch: N/A（无 action=switchsrc 埋点）'
}
Write-Host "JSON: $jsonPath"
$failed = @($rows | Where-Object { $_.result -like 'FAIL*' })
if (-not $instrumentation -or $failed.Count -gt 0) { exit 1 }
exit 0
