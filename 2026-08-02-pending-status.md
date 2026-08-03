# WA Mail Sync — What's Pending

**As of:** 2026-08-02
**Released:** v0.2.1-beta (prerelease, targeting `main` at `c34b4d1`) — Windows portable zip published.
**Test suite:** 157 passing.
**Working tree:** dirty — see §C.

---

## A. Waiting on your decision

Nothing below moves until you say so.

### A1. Test the Android APK — *you asked for this*
Built from a clean worktree of the `v0.2.1-beta` tag, signed, verified, and now **published**.

- **Download:** attached to the [v0.2.1-beta release](https://github.com/harshalambani/WAGMailSync/releases/tag/v0.2.1-beta) as `WAMailSync-v0.2.1-beta.apk`
- **Size:** ~21 MB (22,067,808 bytes), SHA-256 `da4cd7dd…c8cd055f`
- **Verified:** `package com.wamailsync.app`, versionCode 3, versionName `0.2.1-beta`, label "WA Mail Sync", `arm64-v8a`, minSdk 24, targetSdk 36
- **Signer:** `CN=Harshal Ambani, OU=Personal, O=WA Chat Sync to Gmail, C=IN`, SHA-256 `096ca121…905095`

> **⚠ Install warning.** This is *release*-signed. It will not install over your existing *debug*
> build — Android rejects it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstalling the debug build
> first wipes app data **including `sync_state.db`**, after which the app treats every chat as new
> and re-syncs everything. Decide how you want to handle that before installing.

### A2. Attach the APK to the v0.2.1-beta GitHub release
Same code as the tag, so it belongs on that release rather than a new one. Not done.

### A3. Commit the DPAPI credential encryption?
Built and green, but uncommitted. Your call on whether to commit, and whether it warrants its own
release. See §C for the file list.

### A4. Build the `.paf.exe` PortableApps installer
Researched, deliberately not started. Blockers:
- The repo has **zero icon assets**; `Icons=1` in `appinfo.ini` requires real ones.
- `portable/App/AppInfo/appinfo.ini` is stale — Name still "WAGmail Sync", PackageVersion/
  DisplayVersion still `1.0.0`.
- The `platform-agnostic-skills-portable` repo vendors `PortableApps.comInstaller.exe` under
  **Git LFS**. Reusing that approach means either adding LFS here or committing MBs as plain git
  objects — a real decision, since LFS checkout bandwidth is the actual quota lever.
- A local installer does exist at `C:\PortableApps\PortableApps.comInstaller\PortableApps.comInstaller.exe`.

### A5. Delete the tag worktree — needs an explicit approval against a listed set
`scratchpad\wt-v021` contains copies of `release.jks` and `keystore.properties`. Per your standing
rule I will present exact paths, sizes and dates before removing anything. Local fixed drive, so it
goes to the Recycle Bin.

---

## B. Queued work — no decision needed, just sequencing

### B1. Verify end-to-end IMAP delivery on device  ← **highest value outstanding**
Never done. The headless `WatchFolderWorker` has not been exercised against IMAP on the phone. This
is also the **gate on the entire app-store phase** — nothing gets submitted anywhere until it passes.

### B2. Create icon assets
Needed: `appicon.ico` plus `appicon_16/32/75/128/1024.png`. Unblocks **both** the `.paf.exe` (A4)
**and** every store listing. Best value-per-effort after B1.

### B3. Update `WA-Mail-Sync-Password-Storage.docx` §4.1
It currently describes Windows DPAPI as not yet built. That is accurate for the released
v0.2.1-beta, but becomes wrong the moment the §C changes ship.

### B4. Clear Unaise Urfi's rows from `sync_state.db`
So that chat can be re-archived. Offered previously, never carried out.

### B5. Rename the Windows build artifacts
`build_portable.ps1`, `wa-chat-sync.spec` (`name="WAGmailSync"`), the `.bat` launcher, and
`WAGMAIL_ROOT`. Deferred — ties into the broader package-name / rename decision.

### B6. Device cleanup
`/sdcard/Download/wamail-test/` still holds test screenshots.

---

## C. Uncommitted working tree

| State | File |
|---|---|
| new | `src/secret_store.py` — Windows DPAPI wrapper (224 lines) |
| new | `tests/test_secret_store.py` — real unmocked DPAPI round-trips |
| new | `WA-Mail-Sync-Password-Storage.docx` — the password-design document |
| new | `2026-08-02-android-store-distribution-phase.md` — the store phase plan |
| modified | `gui_worker.py` — DPAPI on save, `resolve_imap_password()` shared reader |
| modified | `gui.py` — silent transport path now uses `resolve_imap_password` |
| modified | `tests/test_gui_backend.py` — DPAPI branches + new tests |
| modified | `2026-07-04-playstore-publishing-sop.md` — superseded banner (approved 2026-08-02) |

All 157 tests pass with these in place.

---

## D. Phases on the books

- **Android store distribution** — MID priority, **gated on you battle-testing the app**. Full plan
  in `2026-08-02-android-store-distribution-phase.md`. Samsung Galaxy Store first, then Indus
  Appstore, then Xiaomi GetApps; Google Play deferred; F-Droid / Amazon / Huawei ruled out.
  Open sub-decisions: the cross-store signing choice (own key everywhere vs Play App Signing — they
  are mutually exclusive for updates), backing up `release.jks` off-machine, a privacy-policy URL,
  and checking whether Google's developer-verification programme alone meets the goal without any
  listing at all.
- **Safer credential storage (P1)** — the DPAPI work in §C is this phase, part-delivered.
- **"No longer Gmail-only" renaming (P2)** — B5 is a piece of it.

---

## Suggested order

**B1 → B2 → A1.** B1 is the gate on everything you said you want next; B2 pays into two separate
deliverables; A1 is yours to schedule around the data-wipe warning.
