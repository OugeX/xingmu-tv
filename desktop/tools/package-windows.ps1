#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Build the Windows MSI for Xingmu TV desktop.
    Locates JDK 17, WiX Toolset 3.x and libVLC, then runs `gradlew packageMsi`.
.NOTES
    jpackage MSI requires WiX Toolset 3.x (candle.exe/light.exe) on PATH.
    WiX downloads: https://github.com/wixtoolset/wix3/releases
.EXAMPLE
    pwsh -NoProfile -File tools/package-windows.ps1
#>
param(
    [string]$JdkHome = '',
    [string]$WixHome = '',
    [string]$VlcHome = ''
)

$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot

function Resolve-Jdk([string]$explicit) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += 'D:\tvbuild\jdk-17.0.2'
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path (Join-Path $dir 'bin\java.exe'))) { return (Resolve-Path $dir).Path }
    }
    throw 'JDK 17 not found. Pass -JdkHome or set JAVA_HOME.'
}

function Resolve-Wix([string]$explicit) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    if ($env:WIX) { $candidates += $env:WIX }
    $candidates += 'D:\tvbuild\wix314'
    $candidates += (Get-ChildItem 'C:\Program Files (x86)\WiX Toolset*' -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path (Join-Path $dir 'candle.exe'))) {
            $bin = if (Test-Path (Join-Path $dir 'bin\candle.exe')) { Join-Path $dir 'bin' } else { $dir }
            return (Resolve-Path $bin).Path
        }
    }
    throw 'WiX Toolset 3.x not found. Download https://github.com/wixtoolset/wix3/releases and pass -WixHome.'
}

function Resolve-Vlc([string]$explicit) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    if ($env:TVAPP_VLC_HOME) { $candidates += $env:TVAPP_VLC_HOME }
    $candidates += 'D:\tvbuild\vlc-3.0.21\vlc-3.0.21'
    $candidates += 'C:\Program Files\VideoLAN\VLC'
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path (Join-Path $dir 'libvlc.dll'))) { return (Resolve-Path $dir).Path }
    }
    return $null
}

$env:JAVA_HOME = Resolve-Jdk $JdkHome
$wix = Resolve-Wix $WixHome
$env:PATH = "$wix;$env:PATH"
Write-Host "[INFO] JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "[INFO] WiX bin  : $wix" -ForegroundColor Cyan
$vlc = Resolve-Vlc $VlcHome
if ($vlc) {
    $env:TVAPP_VLC_HOME = $vlc
    Write-Host "[INFO] libVLC  : $vlc (runtime dependency, not bundled)" -ForegroundColor Cyan
}

Push-Location $desktop
try {
    & .\gradlew.bat packageMsi --no-daemon --console=plain
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($code -ne 0) {
    Write-Host "[FAIL] gradlew packageMsi exited with $code" -ForegroundColor Red
    exit $code
}

$msi = Get-ChildItem (Join-Path $desktop 'build\compose\binaries\main\msi') -Filter *.msi -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $msi) {
    Write-Host '[FAIL] MSI not found under build\compose\binaries\main\msi' -ForegroundColor Red
    exit 3
}
$hash = (Get-FileHash -LiteralPath $msi.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host ''
Write-Host '========== PACKAGE RESULT ==========' -ForegroundColor Green
Write-Host "MSI    : $($msi.FullName)"
Write-Host "Bytes  : $($msi.Length)"
Write-Host "SHA256 : $hash"
Write-Host '====================================' -ForegroundColor Green
