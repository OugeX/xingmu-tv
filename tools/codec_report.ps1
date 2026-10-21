#!/usr/bin/env pwsh
<#
.SYNOPSIS
    解码器会话复用 / init 成本汇总（t25 / PERF_ROADMAP P2-9）。

.DESCRIPTION
    从 logcat 日志或文件里解析 TVBox/CODEC_EVT 与 TVBox/PLAYER_EVT：
      - CODEC_EVT action=init   → 每次 MediaCodec 初始化的 decoder / init_ms
      - CODEC_EVT action=format → DecoderReuseEvaluation 的 result / discard 原因
      - PLAYER_EVT decoder_inits= / decoder_init_ms= / codec_reuse=
    输出：init 次数、init 总耗时、均值、最大、复用评估分布，以及「切台次数 vs init 次数」。
    用于判断 Media3 在 MStar 上到底有没有复用会话（init 次数 < 切台次数 = 有复用）。

.EXAMPLE
    pwsh tools/codec_report.ps1
    pwsh tools/codec_report.ps1 -LogPath tools/player_live_events.log
    pwsh tools/codec_report.ps1 -Adb D:\tvbuild\android-sdk\platform-tools\adb.exe
#>
[CmdletBinding()]
param(
    [string]$LogPath = '',
    [string]$Device = '192.168.1.9:5555',
    [string]$Adb = '',
    [string]$OutJson = 'tools/codec_report.json'
)

$ErrorActionPreference = 'Stop'

function Say([string]$Message, [string]$Level = 'INFO') {
    $color = switch ($Level) { 'PASS' { 'Green' } 'WARN' { 'Yellow' } 'FAIL' { 'Red' } default { 'Cyan' } }
    Write-Host ("[{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $adbPath = $Adb
    if ([string]::IsNullOrWhiteSpace($adbPath)) {
        foreach ($c in @('D:\tvbuild\android-sdk\platform-tools\adb.exe', (Join-Path $PWD 'platform-tools\adb.exe'))) {
            if (Test-Path -LiteralPath $c -PathType Leaf) { $adbPath = $c; break }
        }
    }
    if ([string]::IsNullOrWhiteSpace($adbPath)) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $cmd) { $adbPath = $cmd.Source }
    }
    if ([string]::IsNullOrWhiteSpace($adbPath)) { Say '找不到 adb，请用 -LogPath 指定已有日志' 'FAIL'; exit 1 }
    $lines = & $adbPath -s $Device logcat -d 2>$null
} else {
    if (-not (Test-Path -LiteralPath $LogPath)) { Say "日志不存在: $LogPath" 'FAIL'; exit 1 }
    $lines = Get-Content -LiteralPath $LogPath
}

$inits = New-Object System.Collections.Generic.List[object]
$formats = New-Object System.Collections.Generic.List[object]
$plays = New-Object System.Collections.Generic.List[object]

foreach ($line in $lines) {
    if ($line -match 'CODEC_EVT: action=init decoder=(?<dec>\S+) init_ms=(?<ms>\d+) count=(?<cnt>\d+) total_ms=(?<total>\d+) reuse=(?<reuse>\d)') {
        $inits.Add([pscustomobject]@{ decoder = $Matches['dec']; init_ms = [int]$Matches['ms']; reuse = [int]$Matches['reuse'] })
        continue
    }
    if ($line -match 'CODEC_EVT: action=format decoder=(?<dec>\S+) result=(?<res>\S+) discard=(?<dis>[\d, ]*) fmt=(?<fmt>\d+x\d+)') {
        $formats.Add([pscustomobject]@{ decoder = $Matches['dec']; result = $Matches['res']; discard = $Matches['dis'].Trim() })
        continue
    }
    if ($line -match 'PLAYER_EVT: action=(?<act>\S+).*decoder_init_ms=(?<ms>\d+) decoder_inits=(?<inits>\d+) codec_reuse=(?<reuse>\d)') {
        $plays.Add([pscustomobject]@{ action = $Matches['act']; decoder_init_ms = [int]$Matches['ms']; decoder_inits = [int]$Matches['inits']; codec_reuse = [int]$Matches['reuse'] })
    }
}

if ($inits.Count -eq 0 -and $plays.Count -eq 0 -and $formats.Count -eq 0) {
    Say '没有 CODEC_EVT / PLAYER_EVT：埋点缺失或本次运行未起播' 'FAIL'
    exit 2
}

$initTimes = @($inits | ForEach-Object { $_.init_ms })
$sorted = @($initTimes | Sort-Object)
function Pct([int[]]$Values, [double]$P) {
    if ($Values.Count -eq 0) { return 0 }
    $idx = [math]::Min($Values.Count - 1, [math]::Max(0, [int][math]::Ceiling($P / 100.0 * $Values.Count) - 1))
    return $Values[$idx]
}

$zapCount = @($plays | Where-Object { $_.action -eq 'zap' }).Count
$reuseFormatCount = @($formats | Where-Object { $_.result -like 'YES*' }).Count

Say ("init 次数={0}  zap 次数={1}  复用评估={2}（YES 占 {3}）" -f $inits.Count, $zapCount, $formats.Count, $reuseFormatCount) 'INFO'
if ($initTimes.Count -gt 0) {
    Say ("init 耗时: P50={0}ms P90={1}ms max={2}ms 合计={3}ms" -f (Pct $sorted 50), (Pct $sorted 90), ($sorted[-1]), (($initTimes | Measure-Object -Sum).Sum)) 'INFO'
}
if ($zapCount -gt 0) {
    $verdict = if ($inits.Count -lt $zapCount) { '有复用（init < zap）' } else { '无复用（每次切台都重新 init）' }
    Say ("判定: {0}" -f $verdict) 'WARN'
}

$formats | Group-Object result, discard | ForEach-Object {
    Say ("复用评估 {0} × {1}" -f $_.Name, $_.Count) 'INFO'
}

$result = [pscustomobject]@{
    init_count       = $inits.Count
    zap_count        = $zapCount
    init_p50_ms      = (Pct $sorted 50)
    init_p90_ms      = (Pct $sorted 90)
    init_max_ms      = if ($sorted.Count -gt 0) { $sorted[-1] } else { 0 }
    init_total_ms    = (($initTimes | Measure-Object -Sum).Sum)
    reuse_yes_count  = $reuseFormatCount
    codec_reuse_flag = if ($plays.Count -gt 0) { $plays[-1].codec_reuse } else { $null }
}
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $OutJson -Encoding UTF8
Say "JSON: $OutJson" 'PASS'
