#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Dev launcher for Xingmu TV desktop (Windows).
    Locates JDK 17 + libVLC, then runs `gradlew run`.
.EXAMPLE
    pwsh -NoProfile -File tools/run-dev.ps1 -AutoplayLive
    pwsh -NoProfile -File tools/run-dev.ps1 -Smoke
    pwsh -NoProfile -File tools/run-dev.ps1 -VlcHome D:\VLC
#>
param(
    [switch]$Smoke,
    [switch]$AutoplayLive,
    [string]$VlcHome = ''
)

$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot

function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'D:\tvbuild\jdk-17.0.2'
    $candidates += (Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path (Join-Path $dir 'bin\java.exe'))) { return $dir }
    }
    throw 'JDK 17 not found. Set JAVA_HOME or install JDK 17.'
}

function Find-Vlc([string]$explicit) {
    if ($explicit -and (Test-Path (Join-Path $explicit 'libvlc.dll'))) { return (Resolve-Path $explicit).Path }
    if ($env:TVAPP_VLC_HOME -and (Test-Path (Join-Path $env:TVAPP_VLC_HOME 'libvlc.dll'))) { return $env:TVAPP_VLC_HOME }
    $candidates = @(
        'D:\tvbuild\vlc-3.0.21\vlc-3.0.21',
        'C:\Program Files\VideoLAN\VLC',
        'C:\Program Files (x86)\VideoLAN\VLC',
        (Join-Path $env:LOCALAPPDATA 'Programs\VideoLAN\VLC')
    )
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path (Join-Path $dir 'libvlc.dll'))) { return $dir }
    }
    return $null
}

$env:JAVA_HOME = Find-Jdk
$vlc = Find-Vlc $VlcHome
if ($vlc) {
    $env:TVAPP_VLC_HOME = $vlc
    Write-Host "[INFO] libVLC: $vlc" -ForegroundColor Cyan
} else {
    Write-Host '[WARN] libVLC not found; the app will start but playback is disabled until VLC is installed/set.' -ForegroundColor Yellow
}

$argsList = @()
if ($Smoke) { $argsList += '--smoke' }
if ($AutoplayLive) { $argsList += '--autoplay-live' }
if ($argsList.Count -gt 0) {
    $argString = ($argsList -join ' ')
    Write-Host "[INFO] gradle run --args=`"$argString`"" -ForegroundColor Cyan
    & (Join-Path $desktop 'gradlew.bat') run --no-daemon --console=plain "--args=$argString"
} else {
    & (Join-Path $desktop 'gradlew.bat') run --no-daemon --console=plain
}
exit $LASTEXITCODE
