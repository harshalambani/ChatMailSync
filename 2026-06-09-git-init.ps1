# WA Chat Sync to Gmail — First commit script
# Run from the project root:
#   cd "C:\Users\user\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
#   .\2026-06-09-git-init.ps1

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$proj = "C:\Users\user\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
Set-Location $proj

# --- Step 0: AST syntax check all Python files ---
Write-Host "=== Syntax check ===" -ForegroundColor Cyan
$pyFiles = @("gui.py", "gui_worker.py", "cli.py", "setup_auth.py",
             "src/config.py", "src/parser.py", "src/gmail_client.py",
             "src/state.py", "src/sync_manager.py", "src/html_renderer.py",
             "src/media_extractor.py")
foreach ($f in $pyFiles) {
    python -c "import ast; ast.parse(open('$f', encoding='utf-8').read()); print('$f - OK')"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ABORT: Syntax error in $f" -ForegroundColor Red
        exit 1
    }
}
Write-Host ""

# --- Step 1: Clean any partial .git from a failed prior attempt ---
if (Test-Path ".git") {
    Write-Host "Removing existing .git directory..." -ForegroundColor Yellow
    Remove-Item -Recurse -Force .git
}

# --- Step 2: git init ---
Write-Host "`n=== git init ===" -ForegroundColor Cyan
git init -b main
git config user.email "harshal.subscribe@hotmail.com"
git config user.name "Harshal"

# --- Step 3: Verify .gitignore coverage ---
Write-Host "`n=== .gitignore contents ===" -ForegroundColor Cyan
Get-Content .gitignore

# --- Step 4: Stage everything ---
Write-Host "`n=== Staging files ===" -ForegroundColor Cyan
git add .

# --- Step 5: Show what will be committed (safety check) ---
Write-Host "`n=== Files staged for commit ===" -ForegroundColor Cyan
git status --short

# Quick check: nothing from auth/ or data/sync_state.db should appear
$bad = git status --short | Where-Object { $_ -match "auth/" -or $_ -match "token" -or $_ -match "credentials" -or $_ -match "sync_state" -or $_ -match "\.settings\.json" }
if ($bad) {
    Write-Host "`n!!! ABORT: Sensitive files are staged:" -ForegroundColor Red
    $bad | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host "Fix .gitignore and re-run." -ForegroundColor Red
    exit 1
}

Write-Host "`nNo sensitive files detected in staging area." -ForegroundColor Green

# --- Step 6: Commit ---
Write-Host "`n=== Committing ===" -ForegroundColor Cyan
git commit -m "Initial commit: WhatsApp Gmail sync v1 with security hardening"

# --- Step 7: Add remote and push ---
Write-Host "`n=== Adding remote and pushing ===" -ForegroundColor Cyan
git remote add origin https://github.com/harshalambani/WAGMailSync.git
git push -u origin main

# --- Step 8: Report ---
Write-Host "`n=== Done ===" -ForegroundColor Green
$sha = git rev-parse --short HEAD
Write-Host "Commit SHA: $sha"
Write-Host "Remote: https://github.com/harshalambani/WAGMailSync"
Write-Host "Branch: main"
