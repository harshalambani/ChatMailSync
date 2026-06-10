#Requires -Version 5.1
<#
.SYNOPSIS
    Sign WAGmailSync.exe with a self-signed code-signing certificate.

.DESCRIPTION
    1. Looks for an existing "CN=WAGmailSync Dev" cert in Cert:\CurrentUser\My.
    2. Creates one (valid 3 years) if none exists.
    3. Signs the exe in dist\WAGmailSyncPortable\App\WAGmailSync\ using signtool.
    4. With -InstallCert: installs the cert to LocalMachine\TrustedPublisher so
       Windows stops SmartScreen-flagging the exe on THIS machine (requires admin).

.PARAMETER InstallCert
    Add the signing cert to LocalMachine\TrustedPublisher.
    Requires an elevated (admin) PowerShell session.

.EXAMPLE
    # Sign only
    cd "C:\Users\user\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
    .\sign_exe.ps1

    # Sign and mark trusted on this machine (run as administrator)
    .\sign_exe.ps1 -InstallCert
#>

param(
    [switch]$InstallCert
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Signtool = "C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe"
$ExePath  = Join-Path $PSScriptRoot "dist\WAGmailSyncPortable\App\WAGmailSync\WAGmailSync.exe"

if (-not (Test-Path $ExePath)) {
    Write-Error "Exe not found at '$ExePath'. Run build_portable.ps1 first."
}

if (-not (Test-Path $Signtool)) {
    Write-Error "signtool.exe not found at '$Signtool'. Install the Windows 10 SDK."
}

# ---------------------------------------------------------------------------
# Step 1 - Get or create the dev signing certificate
# ---------------------------------------------------------------------------

$CertSubject = "CN=WAGmailSync Dev"

$cert = Get-ChildItem "Cert:\CurrentUser\My" -CodeSigningCert |
    Where-Object { $_.Subject -eq $CertSubject -and $_.NotAfter -gt (Get-Date) } |
    Sort-Object NotAfter -Descending |
    Select-Object -First 1

if (-not $cert) {
    Write-Host "`n==> Creating self-signed code-signing certificate..." -ForegroundColor Cyan
    $cert = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject $CertSubject `
        -CertStoreLocation "Cert:\CurrentUser\My" `
        -NotAfter (Get-Date).AddYears(3) `
        -KeyUsage DigitalSignature `
        -KeyAlgorithm RSA `
        -KeyLength 2048
    Write-Host "   Certificate created: $($cert.Thumbprint)" -ForegroundColor Green
    Write-Host "   Subject  : $($cert.Subject)"
    Write-Host "   Expires  : $($cert.NotAfter)"
} else {
    Write-Host "`n==> Using existing certificate: $($cert.Thumbprint)" -ForegroundColor Cyan
    Write-Host "   Subject  : $($cert.Subject)"
    Write-Host "   Expires  : $($cert.NotAfter)"
}

# ---------------------------------------------------------------------------
# Step 2 - Sign the exe (with DigiCert timestamp; fall back without if offline)
# ---------------------------------------------------------------------------

Write-Host "`n==> Signing exe..." -ForegroundColor Cyan
Write-Host "    $ExePath"

& $Signtool sign /fd SHA256 /sha1 $cert.Thumbprint /td SHA256 /tr "http://timestamp.digicert.com" $ExePath

if ($LASTEXITCODE -ne 0) {
    Write-Host "   (Timestamp server unreachable - retrying without timestamp)" -ForegroundColor Yellow
    & $Signtool sign /fd SHA256 /sha1 $cert.Thumbprint $ExePath
    if ($LASTEXITCODE -ne 0) {
        Write-Error "signtool failed (exit $LASTEXITCODE)."
    }
    Write-Host "==> Exe signed (no timestamp)." -ForegroundColor Green
} else {
    Write-Host "==> Exe signed with timestamp." -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Step 3 (optional) - Install cert to LocalMachine\TrustedPublisher
# ---------------------------------------------------------------------------

if ($InstallCert) {
    Write-Host "`n==> Installing cert to LocalMachine\TrustedPublisher..." -ForegroundColor Cyan
    try {
        $store = New-Object System.Security.Cryptography.X509Certificates.X509Store(
            "TrustedPublisher", "LocalMachine"
        )
        $store.Open("ReadWrite")
        $store.Add($cert)
        $store.Close()
        Write-Host "==> Certificate installed. Windows trusts this exe on this machine." -ForegroundColor Green
    } catch {
        Write-Error ("Could not install certificate. Run this script as Administrator. " +
                     "Error: " + $_)
    }
} else {
    Write-Host ""
    Write-Host "NOTE: Exe is signed, but SmartScreen may still warn on first launch." -ForegroundColor Yellow
    Write-Host "To mark it trusted on this machine, run once as Administrator:" -ForegroundColor Yellow
    Write-Host "   .\sign_exe.ps1 -InstallCert" -ForegroundColor Yellow
}

Write-Host ""
