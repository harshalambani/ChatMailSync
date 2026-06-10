#Requires -Version 5.1
<#
.SYNOPSIS
    Build WAGmailSync as a PortableApps-compatible folder.

.DESCRIPTION
    1. Runs PyInstaller with wa-chat-sync.spec  ->  dist\WAGmailSync\
    2. Assembles the PortableApps directory layout under dist\WAGmailSyncPortable\
    3. Copies user-supplied credentials.json into the bundle (if present).

.EXAMPLE
    cd "C:\Users\user\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
    .\build_portable.ps1

    To skip PyInstaller (re-assemble layout only):
    .\build_portable.ps1 -SkipBuild

    To build AND sign the exe (self-signed dev cert):
    .\build_portable.ps1 -Sign

    To build, sign, and install the cert as trusted on this machine (run as admin):
    .\build_portable.ps1 -Sign -InstallCert
#>

param(
    [switch]$SkipBuild,
    [switch]$Sign,
    [switch]$InstallCert
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

$ProjectRoot   = $PSScriptRoot
$DistDir       = Join-Path $ProjectRoot "dist"
$BundleDir     = Join-Path $DistDir "WAGmailSync"        # PyInstaller output
$PortableDir   = Join-Path $DistDir "WAGmailSyncPortable"
$AppDir        = Join-Path $PortableDir "App\WAGmailSync"
$DataDir       = Join-Path $PortableDir "Data"
$AppInfoDir    = Join-Path $PortableDir "App\AppInfo"
$LauncherDir   = Join-Path $PortableDir "Other\Source"

# ---------------------------------------------------------------------------
# Step 1 - PyInstaller
# ---------------------------------------------------------------------------

if (-not $SkipBuild) {
    Write-Host "`n==> Running PyInstaller..." -ForegroundColor Cyan
    # Resolve pyinstaller exe (handles cases where Scripts\ is not on PATH)
    $PyInstallerCmd = Get-Command pyinstaller -ErrorAction SilentlyContinue
    if ($PyInstallerCmd) {
        $PyInstaller = $PyInstallerCmd.Source
    } else {
        $PyInstaller = "$env:APPDATA\Python\Python314\Scripts\pyinstaller.exe"
    }
    & $PyInstaller "$ProjectRoot\wa-chat-sync.spec" --clean --noconfirm
    if ($LASTEXITCODE -ne 0) {
        Write-Error "PyInstaller failed (exit $LASTEXITCODE). Aborting."
    }
    Write-Host "==> PyInstaller done." -ForegroundColor Green
} else {
    Write-Host "`n==> Skipping PyInstaller (--SkipBuild)." -ForegroundColor Yellow
}

if (-not (Test-Path $BundleDir)) {
    Write-Error "Bundle not found at '$BundleDir'. Run without -SkipBuild first."
}

# ---------------------------------------------------------------------------
# Step 2 - Assemble PortableApps layout
# ---------------------------------------------------------------------------

Write-Host "`n==> Assembling PortableApps layout..." -ForegroundColor Cyan

# Only wipe App\ (the frozen exe) - never touch Data\ so auth tokens and
# user data survive rebuilds.  This means re-auth is NOT required after
# every build update.
if (Test-Path $AppDir) {
    Remove-Item $AppDir -Recurse -Force
}

# App\WAGmailSync\ - the frozen exe + all its DLLs / data
New-Item -ItemType Directory -Force $AppDir | Out-Null
Copy-Item "$BundleDir\*" $AppDir -Recurse

# App\AppInfo\ - metadata (appinfo.ini, icon)
New-Item -ItemType Directory -Force $AppInfoDir | Out-Null
$AppInfoSrc = Join-Path $ProjectRoot "portable\App\AppInfo"
if (Test-Path $AppInfoSrc) {
    Copy-Item "$AppInfoSrc\*" $AppInfoDir -Recurse
} else {
    Write-Host "   (No portable\App\AppInfo\ found - skipping icon/ini copy)" -ForegroundColor Yellow
}

# Data\ - create subdirs only if they don't already exist (preserve user data).
foreach ($sub in @("auth", "data\inbox", "data\processed")) {
    New-Item -ItemType Directory -Force (Join-Path $DataDir $sub) | Out-Null
}

# Seed Data\auth\ from the project's auth\ folder - only if the destination
# file does not already exist (never overwrite tokens the user has live).
foreach ($authFile in @("credentials.json", "token.json")) {
    $src  = Join-Path $ProjectRoot "auth\$authFile"
    $dest = Join-Path $DataDir "auth\$authFile"
    if (Test-Path $src) {
        if (-not (Test-Path $dest)) {
            Copy-Item $src $dest
            Write-Host "   Seeded Data\auth\$authFile" -ForegroundColor Green
        } else {
            Write-Host "   Kept existing Data\auth\$authFile (not overwritten)" -ForegroundColor DarkGray
        }
    } else {
        if ($authFile -eq "credentials.json") {
            Write-Host "   WARNING: credentials.json not found - user must copy it to Data\auth\ before first run." -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------------------
# Step 3 - Launcher script  (WAGmailSyncPortable.bat)
# ---------------------------------------------------------------------------

Write-Host "`n==> Writing launcher..." -ForegroundColor Cyan

$LauncherBat = Join-Path $PortableDir "WAGmailSyncPortable.bat"
$LauncherContent = @'
@echo off
:: WAGmailSync PortableApps launcher
:: Sets WAGMAIL_ROOT so the frozen app resolves paths relative to Data\
setlocal

set "PA_ROOT=%~dp0"
set "WAGMAIL_ROOT=%PA_ROOT%Data"

start "" "%PA_ROOT%App\WAGmailSync\WAGmailSync.exe"
endlocal
'@
Set-Content -Path $LauncherBat -Value $LauncherContent -Encoding ASCII

# ---------------------------------------------------------------------------
# Step 4 (optional) - Code-sign the exe
# ---------------------------------------------------------------------------

if ($Sign) {
    Write-Host "`n==> Code-signing..." -ForegroundColor Cyan
    $SignArgs = @("$ProjectRoot\sign_exe.ps1")
    if ($InstallCert) { $SignArgs += "-InstallCert" }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $SignArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Signing failed (exit $LASTEXITCODE). Exe was built but is unsigned."
    }
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

Write-Host "`n==> Build complete." -ForegroundColor Green
Write-Host "    Portable folder : $PortableDir"
Write-Host "    Launch with     : $LauncherBat"
Write-Host ""
Write-Host "    Before first run, ensure Data\auth\credentials.json is present,"
Write-Host "    then run setup_auth.py (or launch the app - it will prompt for auth)."
Write-Host ""
