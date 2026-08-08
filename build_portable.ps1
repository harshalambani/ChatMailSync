#Requires -Version 5.1
<#
.SYNOPSIS
    Build WAMailSync as a PortableApps-compatible folder.

.DESCRIPTION
    1. Runs PyInstaller with wa-chat-sync.spec  ->  dist\WAMailSync\
    2. Assembles the PortableApps directory layout under dist\WAMailSyncPortable\
    3. Generates the compiled PortableApps.com launcher (WAMailSyncPortable.exe)
    4. Optionally packages a distributable: the .paf.exe PortableApps.com
       installer (-Installer) and/or a plain portable zip (-Zip). Both are cut
       from the same clean staging tree, never from the live working copy.
    Data\ is created empty. Live credentials are copied in only with the
    explicit -SeedCredentials switch, and never for a package you intend to
    hand to anyone.

.EXAMPLE
    cd "C:\Users\inabm\Documents\Cowork Playground\WAMailSync"
    .\build_portable.ps1

    To skip PyInstaller (re-assemble layout only):
    .\build_portable.ps1 -SkipBuild

    To build AND sign the exe (self-signed dev cert):
    .\build_portable.ps1 -Sign

    To build, sign, and install the cert as trusted on this machine (run as admin):
    .\build_portable.ps1 -Sign -InstallCert

    To also produce the distributable .paf.exe installer:
    .\build_portable.ps1 -Installer

    To copy your real auth\ credentials into dist\ for local testing
    (the result is NOT distributable):
    .\build_portable.ps1 -SeedCredentials
#>

param(
    [switch]$SkipBuild,
    [switch]$Sign,
    [switch]$InstallCert,
    [switch]$Installer,
    [switch]$Zip,
    [switch]$SeedCredentials
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

$ProjectRoot   = $PSScriptRoot
$DistDir       = Join-Path $ProjectRoot "dist"
$BundleDir     = Join-Path $DistDir "WAMailSync"          # PyInstaller output
$PortableDir   = Join-Path $DistDir "WAMailSyncPortable"
$AppDir        = Join-Path $PortableDir "App\WAMailSync"
$DataDir       = Join-Path $PortableDir "Data"
$AppInfoDir    = Join-Path $PortableDir "App\AppInfo"

# ---------------------------------------------------------------------------
# PortableApps.com build tools
#
# These are third-party release artifacts, not our source, so they are
# deliberately NOT vendored into this repo - not in git, not in Git LFS.
# (Decision recorded 2026-08-05 in 2026-08-02-pending-status.md section A4.)
# We use the locally installed copies, and verify each one against a pinned
# SHA-256 *before* executing it. The pin is the whole point: these are
# binaries we hand a path to our package and then run.
#
# To upgrade a tool: update it via the PortableApps.com Platform, run
#   Get-FileHash <path> -Algorithm SHA256
# and paste the new hash plus version here in one reviewable change.
# ---------------------------------------------------------------------------

$LauncherGeneratorExe  = "C:\PortableApps\PortableApps.comLauncher\PortableApps.comLauncherGenerator.exe"
$LauncherGeneratorVer  = "2.2.9"
$LauncherGeneratorHash = "66794A62F8BDC8DF8A05FA36B9812FC34CAC02967AB478E2654C647FE530578D"
$LauncherGeneratorUrl  = "https://portableapps.com/apps/development/portableapps.com_launcher"

$InstallerExe  = "C:\PortableApps\PortableApps.comInstaller\PortableApps.comInstaller.exe"
$InstallerVer  = "3.9.17"
$InstallerHash = "6F025B106F65C95BCE8DA20D9638D90059663D402969C9F3DCFA9DBB28A4E30E"
$InstallerUrl  = "https://portableapps.com/apps/development/portableapps.com_installer"

$AppID = "WAMailSyncPortable"   # must match [Details]:AppID in appinfo.ini

function Resolve-PortableAppsTool {
    <#
        Returns the path to a PortableApps.com build tool, having first
        confirmed it is byte-for-byte the version we pinned.

        A mismatch is fatal, never a warning. The alternative - "hash differs,
        carrying on anyway" - means executing an unreviewed binary against the
        package we are about to hand to users, which is precisely the failure
        the pin exists to prevent.

        Deliberately does NOT download-and-run on a miss. Upstream ships these
        tools as .paf.exe installers that install software onto the machine,
        so silently fetching and executing one as a side effect of a build is
        a bigger action than a build script should take unasked. Instead we
        fail with the URL and the expected version so the human installs it.
    #>
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$ExpectedHash,
        [Parameter(Mandatory)] [string]$Version,
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$Url
    )

    if (-not (Test-Path $Path)) {
        Write-Error @"
$Name $Version was not found at:
    $Path

It is intentionally not vendored in this repo. Install it (once) from:
    $Url

The PortableApps.com Platform installs it to C:\PortableApps\ by default,
which is where this script looks.
"@
    }

    $actual = (Get-FileHash $Path -Algorithm SHA256).Hash
    if ($actual -ne $ExpectedHash) {
        Write-Error @"
$Name at '$Path' does not match the pinned SHA-256.

    expected : $ExpectedHash  ($Name $Version)
    actual   : $actual

Refusing to execute it. Either the tool was updated - in which case verify
the new binary, then update the pin and the version string at the top of
build_portable.ps1 in a single reviewable commit - or something replaced it,
in which case do not run this build.
"@
    }

    Write-Host "   Verified $Name $Version (SHA-256 pinned)" -ForegroundColor DarkGray
    return $Path
}

# ---------------------------------------------------------------------------
# Step 1 - PyInstaller
# ---------------------------------------------------------------------------

if (-not $SkipBuild) {
    Write-Host "`n==> Installing pinned dependencies from requirements-lock.txt..." -ForegroundColor Cyan
    $LockFile = Join-Path $ProjectRoot "requirements-lock.txt"
    if (-not (Test-Path $LockFile)) {
        Write-Error "requirements-lock.txt not found at '$LockFile'. Regenerate it with pip-compile --generate-hashes."
    }
    # $ErrorActionPreference is Stop for the script, but under PowerShell 5.1
    # that also applies to anything a native exe writes to stderr: PS wraps each
    # stderr line in a NativeCommandError and kills the build on it. pip writes
    # ordinary warnings there - an unrelated half-uninstalled package elsewhere
    # in site-packages is enough - so the build died on someone else's mess
    # while pip itself had exited 0. The exit code is the only thing that
    # actually says whether pip succeeded, so let stderr through and check that.
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & python -m pip install --require-hashes -r $LockFile
    $pipExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP
    if ($pipExit -ne 0) {
        Write-Error "Installing from requirements-lock.txt failed (exit $pipExit). Aborting."
    }

    Write-Host "`n==> Running PyInstaller..." -ForegroundColor Cyan
    # Resolve pyinstaller exe (handles cases where Scripts\ is not on PATH)
    $PyInstallerCmd = Get-Command pyinstaller -ErrorAction SilentlyContinue
    if ($PyInstallerCmd) {
        $PyInstaller = $PyInstallerCmd.Source
    } else {
        $PyInstaller = "$env:APPDATA\Python\Python314\Scripts\pyinstaller.exe"
    }
    # Same stderr trap as pip above - PyInstaller logs INFO/WARNING to stderr
    # as a matter of course, so its exit code is the only reliable signal.
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $PyInstaller "$ProjectRoot\wa-chat-sync.spec" --clean --noconfirm
    $paExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEAP
    if ($paExit -ne 0) {
        Write-Error "PyInstaller failed (exit $paExit). Aborting."
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

# App\WAMailSync\ - the frozen exe + all its DLLs / data
New-Item -ItemType Directory -Force $AppDir | Out-Null
Copy-Item "$BundleDir\*" $AppDir -Recurse

# App\AppInfo\ - metadata (appinfo.ini, icon)
New-Item -ItemType Directory -Force $AppInfoDir | Out-Null
$AppInfoSrc = Join-Path $ProjectRoot "portable\App\AppInfo"
if (Test-Path $AppInfoSrc) {
    # -Force: Launcher\ survives a previous run, and Copy-Item -Recurse errors on
    # an existing subdirectory rather than merging into it.
    Copy-Item "$AppInfoSrc\*" $AppInfoDir -Recurse -Force
} else {
    Write-Host "   (No portable\App\AppInfo\ found - skipping icon/ini copy)" -ForegroundColor Yellow
}

# The launcher .ini sets SplashTime, and a missing splash.jpg does not make
# that an error - SplashScreen.nsh just sets DisableSplashScreen=true and the
# package builds cleanly with no splash at all. That failure is invisible
# until someone launches the shipped build and notices nothing appeared, so
# check it here rather than trusting the copy above.
#
# The path below is not a choice. The launcher generator compiles in a
# HARDCODED splash location and the built package cannot be told to look
# anywhere else:
#
#   Plugin Command: show $0 0 0 -1 /L $EXEDIR\App\AppInfo\Launcher\splash.jpg
#
# v1.1.0 shipped the file one directory up, at App\AppInfo\splash.jpg, and
# this guard - written specifically to catch a silent splash failure - checked
# that same wrong directory, so it passed while the splash never once
# appeared. newadvsplash given a missing file draws nothing and reports no
# error, so nothing else in the build catches it. If this path is ever edited,
# it must be edited to match the generator, not to match where the file
# happens to be.
$LauncherDir = Join-Path $AppInfoDir "Launcher"
$SplashDest = Join-Path $LauncherDir "splash.jpg"
$StagedInis = @(Get-ChildItem $LauncherDir -Filter *.ini -ErrorAction SilentlyContinue)
if ($StagedInis.Count -gt 0 -and (Select-String -Path $StagedInis.FullName -Pattern '^\s*SplashTime\s*=' -Quiet)) {
    if (-not (Test-Path $SplashDest)) {
        Write-Error "The launcher .ini sets SplashTime but App\AppInfo\Launcher\splash.jpg is missing. The generator would silently disable the splash instead of failing. Restore portable\App\AppInfo\Launcher\splash.jpg (regenerate it from appicon_1024.png if it is gone)."
    }
}

# help.html at the package root - required by the PortableApps.com Format, and
# the installer refuses to build without it.
$HelpSrc = Join-Path $ProjectRoot "portable\help.html"
if (-not (Test-Path $HelpSrc)) {
    Write-Error "portable\help.html not found. The PortableApps.com Format requires a help.html at the package root and the installer will not build without one."
}
Copy-Item $HelpSrc (Join-Path $PortableDir "help.html")

# Data\ - create subdirs only if they don't already exist (preserve user data).
foreach ($sub in @("auth", "data\inbox", "data\processed")) {
    New-Item -ItemType Directory -Force (Join-Path $DataDir $sub) | Out-Null
}

# Data\auth\ is left EMPTY by default, and that is the point.
#
# This script used to copy the project's real auth\credentials.json and
# token.json in here on every build. That put live credentials inside the one
# directory tree whose whole purpose is to be handed to someone else, and the
# only thing standing between that and a bad upload was remembering not to zip
# it. -SeedCredentials makes it a deliberate act instead of the default.
#
# For local smoke-testing prefer running the app from the repo with
# WAMAILSYNC_ROOT pointed at the project root - it reads the same auth\ folder
# without ever copying it. Note that the PortableApps launcher always sets
# WAMAILSYNC_ROOT to its own Data\, so this applies to running the app
# directly, not to launching WAMailSyncPortable.exe.
if ($SeedCredentials) {
    Write-Host "   -SeedCredentials: copying LIVE credentials into dist\ - do not distribute this folder." -ForegroundColor Yellow
    foreach ($authFile in @("credentials.json", "token.json")) {
        $src  = Join-Path $ProjectRoot "auth\$authFile"
        $dest = Join-Path $DataDir "auth\$authFile"
        if (Test-Path $src) {
            if (-not (Test-Path $dest)) {
                Copy-Item $src $dest
                Write-Host "   Seeded Data\auth\$authFile" -ForegroundColor Yellow
            } else {
                Write-Host "   Kept existing Data\auth\$authFile (not overwritten)" -ForegroundColor DarkGray
            }
        } elseif ($authFile -eq "credentials.json") {
            Write-Host "   WARNING: auth\credentials.json not found - nothing to seed." -ForegroundColor Yellow
        }
    }
}

# Credentials from an earlier build survive in Data\ because Step 2 preserves
# it. Flag them rather than removing them - they may be a live token the user
# is mid-test with, and deleting user data is never this script's call.
$stale = @("credentials.json", "token.json") |
    ForEach-Object { Join-Path $DataDir "auth\$_" } |
    Where-Object { Test-Path $_ }
if ($stale -and -not $SeedCredentials) {
    Write-Host "   NOTE: this package already carries credentials from an earlier build:" -ForegroundColor Yellow
    $stale | ForEach-Object { Write-Host "         $_" -ForegroundColor Yellow }
    Write-Host "         Not distributable as-is. Use -Installer, which packages a clean staging tree." -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Step 3 - Compiled launcher  (WAMailSyncPortable.exe)
#
# Replaces the hand-written WAMailSyncPortable.bat. A .bat cannot carry an
# icon, which is why the icon set generated in B2 did nothing for the portable
# bundle. The generator reads App\AppInfo\Launcher\<AppID>.ini (copied into
# place by Step 2) and emits <AppID>.exe at the package root.
# ---------------------------------------------------------------------------

Write-Host "`n==> Generating PortableApps.com launcher..." -ForegroundColor Cyan

$LauncherExe = Join-Path $PortableDir "$AppID.exe"
$LauncherIni = Join-Path $AppInfoDir "Launcher\$AppID.ini"

# The generator's failure mode when this is missing is to build a launcher with
# no configuration rather than to error, so check it ourselves.
if (-not (Test-Path $LauncherIni)) {
    Write-Error "Launcher config not found at '$LauncherIni'. It must be named after [Details]:AppID ($AppID) or the generator silently produces an unconfigured launcher."
}

$GenExe = Resolve-PortableAppsTool `
    -Path $LauncherGeneratorExe `
    -ExpectedHash $LauncherGeneratorHash `
    -Version $LauncherGeneratorVer `
    -Name "PortableApps.com Launcher Generator" `
    -Url $LauncherGeneratorUrl

# Passing a package path puts the generator in unattended mode: it skips the
# welcome page, compiles, and closes itself on success.
Start-Process -FilePath $GenExe -ArgumentList "`"$PortableDir`"" -Wait

$GenLog = Join-Path (Split-Path $GenExe) "Data\PortableApps.comLauncherGeneratorLog.txt"
if (-not (Test-Path $LauncherExe)) {
    if (Test-Path $GenLog) {
        Write-Host "`n--- PortableApps.comLauncherGeneratorLog.txt ---" -ForegroundColor Yellow
        Get-Content $GenLog | Write-Host
        Write-Host "--- end of log ---`n" -ForegroundColor Yellow
    }
    Write-Error "Launcher generation failed - '$LauncherExe' was not produced."
}
Write-Host "   Built $AppID.exe" -ForegroundColor Green

# A .bat left over from a build before this change would ship alongside the
# real launcher and give users two entry points, one of them stale. Flagged
# rather than deleted - removing files is your call, not this script's.
$StaleBat = Join-Path $PortableDir "$AppID.bat"
if (Test-Path $StaleBat) {
    $batInfo = Get-Item $StaleBat
    Write-Host "   WARNING: a superseded launcher is still in the package and would be shipped:" -ForegroundColor Yellow
    Write-Host "            $($batInfo.FullName)" -ForegroundColor Yellow
    Write-Host "            $($batInfo.Length) bytes, last written $($batInfo.LastWriteTime)" -ForegroundColor Yellow
    Write-Host "            Remove it before publishing (send to Recycle Bin, do not permanently delete)." -ForegroundColor Yellow
}

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
# Step 5 (optional) - clean staging tree, shared by every distributable
#
# NOT built from $PortableDir directly. That directory is the live working
# copy: Step 2 deliberately preserves its Data\ across rebuilds so auth tokens
# survive, which means it holds real credentials and real chat exports. A
# packager takes everything it is pointed at, so pointing it there would
# publish those. Instead we stage App\ + AppInfo\ + the launcher into a clean
# tree with an empty Data\ skeleton, and package that.
#
# Both -Installer and -Zip consume this same tree. That is the point: two
# staging routines would be two definitions of "what ships", and the day they
# drift is the day the zip carries something the installer was scanned for.
# ---------------------------------------------------------------------------

if ($Installer -or $Zip) {
    Write-Host "`n==> Staging a clean package..." -ForegroundColor Cyan

    $StageDir = Join-Path $DistDir "$AppID-stage"
    if (Test-Path $StageDir) {
        # Rebuilt from scratch every run: a leftover stage tree from an earlier
        # build could carry files that are no longer part of the package.
        Remove-Item $StageDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force $StageDir | Out-Null

    Copy-Item (Join-Path $PortableDir "App") $StageDir -Recurse
    Copy-Item $LauncherExe $StageDir
    Copy-Item (Join-Path $PortableDir "help.html") $StageDir

    # Pre-flight the metadata the installer insists on.
    #
    # The installer takes no arguments beyond the package path - every value it
    # needs comes from App\AppInfo\appinfo.ini - and when one is missing it asks
    # for it in a modal dialog. In an unattended or backgrounded build that
    # dialog is invisible: the build simply appears to hang, then reports a
    # failure with no clue why. Checking here converts that into an immediate,
    # readable error naming the exact field.
    $stageInfo = Join-Path $StageDir "App\AppInfo\appinfo.ini"
    $infoText = Get-Content $stageInfo -Raw
    foreach ($field in @("Name", "AppID", "Publisher", "Homepage", "Category", "Description", "Language")) {
        if ($infoText -notmatch "(?m)^\s*$field\s*=\s*\S") {
            Write-Error "appinfo.ini has no value for '$field'. The installer would stop and prompt for it in a dialog you cannot see during an unattended build. Set it in portable\App\AppInfo\appinfo.ini."
        }
    }

    # Empty Data\ skeleton - structure only, none of the contents.
    foreach ($sub in @("auth", "data\inbox", "data\processed")) {
        New-Item -ItemType Directory -Force (Join-Path $StageDir "Data\$sub") | Out-Null
    }

    # Editor and tool leftovers must not ride along either. v1.1.0 shipped a
    # splash.jpg.bak in App\AppInfo\ - untracked, so git never showed it, and
    # byte-identical to splash.jpg, so nothing looked wrong. Harmless in that
    # instance, but "an untracked file next to the real one" is also what a
    # half-edited config or a credential copy looks like, and the package is
    # the last place to notice.
    $stray = Get-ChildItem $StageDir -Recurse -File -Include "*.bak", "*.orig", "*.rej", "*~"
    if ($stray) {
        $stray | ForEach-Object { Write-Host "   STRAY: $($_.FullName)" -ForegroundColor Red }
        Write-Error "Refusing to package: editor/tool leftover files are present in the staging tree (listed above). Remove them from portable\ and rebuild."
    }

    # Belt and braces: prove nothing secret rode along in App\ before we
    # hand the tree to the packager.
    $leaked = Get-ChildItem $StageDir -Recurse -File -Include `
        "credentials.json", "token.json", "*.jks", "keystore.properties", "sync_state.db"
    if ($leaked) {
        $leaked | ForEach-Object { Write-Host "   LEAK: $($_.FullName)" -ForegroundColor Red }
        Write-Error "Refusing to package: credential or user-data files are present in the staging tree (listed above)."
    }

    # DisplayVersion is the single source of truth for what this build is
    # called, so the zip name cannot drift from the installer's.
    if ($infoText -match "(?m)^\s*DisplayVersion\s*=\s*(\S+)") {
        $DisplayVersion = $Matches[1]
    } else {
        Write-Error "appinfo.ini has no DisplayVersion. Cannot name the package."
    }
}

# ---------------------------------------------------------------------------
# Step 5a (optional) - .paf.exe PortableApps.com installer
# ---------------------------------------------------------------------------

if ($Installer) {
    Write-Host "`n==> Building .paf.exe installer..." -ForegroundColor Cyan

    $InstExe = Resolve-PortableAppsTool `
        -Path $InstallerExe `
        -ExpectedHash $InstallerHash `
        -Version $InstallerVer `
        -Name "PortableApps.com Installer" `
        -Url $InstallerUrl

    # The installer writes <AppID>_<version>.paf.exe next to the package
    # directory, i.e. into $DistDir.
    $before = @(Get-ChildItem $DistDir -Filter "*.paf.exe" -File | Select-Object -ExpandProperty Name)
    Start-Process -FilePath $InstExe -ArgumentList "`"$StageDir`"" -Wait
    $paf = Get-ChildItem $DistDir -Filter "*.paf.exe" -File |
           Where-Object { $_.Name -notin $before -or $_.LastWriteTime -gt (Get-Date).AddMinutes(-5) } |
           Sort-Object LastWriteTime -Descending |
           Select-Object -First 1

    if (-not $paf) {
        Write-Error "Installer build failed - no .paf.exe appeared in '$DistDir'."
    }
    Write-Host "   Built $($paf.Name) ($($paf.Length) bytes)" -ForegroundColor Green
    Write-Host "   SHA-256 $((Get-FileHash $paf.FullName -Algorithm SHA256).Hash)" -ForegroundColor DarkGray
    $PafPath = $paf.FullName
}

# ---------------------------------------------------------------------------
# Step 5b (optional) - plain portable zip
#
# For people who will not run an installer, or who want the folder on a stick.
# Same staged tree as the .paf.exe above, so it has already been through the
# credential scan - do not be tempted to zip $PortableDir instead, which is the
# live working copy with real credentials in Data\.
#
# Zipped with the AppID folder as the top level rather than loose files, so
# extracting to a drive root does not scatter App\, Data\ and the exe across
# it - the same shape the installer produces on disk.
# ---------------------------------------------------------------------------

if ($Zip) {
    Write-Host "`n==> Building portable zip..." -ForegroundColor Cyan

    $ZipPath = Join-Path $DistDir "$($AppID)_$($DisplayVersion).zip"
    if (Test-Path $ZipPath) {
        Remove-Item $ZipPath -Force
    }

    # Compress-Archive on a directory (no \*) keeps it as the root entry, but
    # names that entry after the source directory - which here is
    # "<AppID>-stage", a build-internal name the user should never see after
    # extracting. So copy the staged tree to a correctly named sibling and zip
    # that, rather than shipping the wrong folder name.
    $ZipRoot = Join-Path $DistDir $AppID
    if (Test-Path $ZipRoot) {
        Remove-Item $ZipRoot -Recurse -Force
    }
    Copy-Item $StageDir $ZipRoot -Recurse
    Compress-Archive -Path $ZipRoot -DestinationPath $ZipPath -CompressionLevel Optimal
    Remove-Item $ZipRoot -Recurse -Force

    $zipItem = Get-Item $ZipPath
    Write-Host "   Built $($zipItem.Name) ($($zipItem.Length) bytes)" -ForegroundColor Green
    Write-Host "   SHA-256 $((Get-FileHash $ZipPath -Algorithm SHA256).Hash)" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

Write-Host "`n==> Build complete." -ForegroundColor Green
Write-Host "    Portable folder : $PortableDir"
Write-Host "    Launch with     : $LauncherExe"
if ($Installer) {
    Write-Host "    Installer       : $PafPath"
}
if ($Zip) {
    Write-Host "    Portable zip    : $ZipPath"
}
Write-Host ""
Write-Host "    Data\ ships empty. The app prompts for mail settings on first run;"
Write-Host "    for the Gmail API backend, drop credentials.json into Data\auth\ first."
if (-not $SeedCredentials) {
    Write-Host "    (-SeedCredentials copies this machine's live credentials in, for testing only.)" -ForegroundColor DarkGray
}
Write-Host ""
