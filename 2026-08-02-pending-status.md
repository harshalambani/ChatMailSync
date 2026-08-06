# WA Mail Sync — What's Pending

**As of:** 2026-08-05
**Released:** v0.2.2-beta (prerelease, `main` at `381eee2`) — **Android APK only**. The Windows
portable zip was deliberately not built for this release: `build_portable.ps1` preserves an existing
`dist\...\Data\` containing live credentials and real exports, so shipping it requires staging
`App\` + launcher only and recreating an empty `Data\`. Previous release v0.2.1-beta did carry the
Windows zip.
**Test suite:** 158 passing.
**Working tree:** clean as of the v0.2.2-beta merge.

---

## A. Waiting on your decision

Nothing below moves until you say so.

### A1. ~~Test the Android APK~~ — **installed 2026-08-03, still to be exercised**
The release-signed build installed cleanly *as an update* (`firstInstallTime` 2026-08-03 14:07:31
vs `lastUpdateTime` 14:51:02) — `sync_state.db`, the saved IMAP password, the watched folder and all
settings survived. The earlier data-wipe warning about the debug build no longer applies.

**Still open:** the phone is running the pre-bump **versionCode 3** binary. The
[v0.2.2-beta APK](https://github.com/harshalambani/WAGMailSync/releases/tag/v0.2.2-beta)
(`WAMailSync-v0.2.2-beta.apk`, 22,067,820 bytes, SHA-256
`C24AE333…CCC37895`, versionCode 4) has **not** been installed, so the four fixes in it are
untested on device. Same signing key, so it installs straight over the top.

### A2. ~~Attach the APK to the GitHub release~~ — **done 2026-08-05** (v0.2.2-beta)

### A3. ~~Commit the DPAPI credential encryption~~ — **done**, shipped in v0.2.2-beta.

### A4. Build the `.paf.exe` PortableApps installer

**Two decisions taken 2026-08-05 — do not re-litigate:**

1. **Compiled `.exe` launcher, not the `.bat`.** The `.bat` cannot carry an icon, so the icon set
   generated in B2 does nothing for the portable bundle until a real launcher exists. A4 therefore
   includes: write `launcher.ini`, run the PortableApps.com Launcher generator to produce
   `WAMailSync.exe`, and retire the `.bat`.
2. **Do not vendor `PortableApps.comInstaller.exe` — fetch it with a pinned hash.** It is a
   published third-party release artifact, not our source, so storing it in git (LFS or plain) is
   solving the wrong problem. `build_portable.ps1` should use
   `C:\PortableApps\PortableApps.comInstaller\PortableApps.comInstaller.exe` when present and
   otherwise download from the official URL, **verifying a pinned SHA-256 before executing it** —
   the pin is the point, since this is a fetched binary we then run. Zero LFS quota, no submodule,
   nothing binary in git, and the vendor's own release is the shared source across every repo.

   Rejected alternatives, with reasons: `.lfsconfig` pointing at another repo's LFS store works but
   is fragile (every clone needs read access to that repo, quota bills against its owner, and
   renaming/archiving it silently breaks checkout everywhere). A tools repo consumed as a git
   submodule is the correct answer *if* offline/air-gapped builds are ever needed, at the cost of
   real clone and CI friction. Note LFS quota is per-**account**, not per-repo, so repos under the
   same owner already share one pool — there was never a separate "shared LFS" to configure.

   The sibling `platform-agnostic-skills-portable` repo currently vendors this binary under LFS and
   should be migrated to the same design; a hand-off prompt for that was written 2026-08-05.

Remaining blockers:
- ~~The repo has zero icon assets~~ — **cleared 2026-08-03** (B2 below). `appicon.ico` +
  `appicon_16/32/75/128/1024.png` now sit in `portable/App/AppInfo/`, so `Icons=1` is satisfied.
- ~~`portable/App/AppInfo/appinfo.ini` is stale~~ — **cleared** on
  `rename/wamailsync-build-artifacts`: Name "WA Mail Sync", version `0.2.1-beta`.
- ~~Vendoring `PortableApps.comInstaller.exe` (LFS vs plain git)~~ — **decided 2026-08-05**: neither,
  see decision 2 above. A local copy already exists at
  `C:\PortableApps\PortableApps.comInstaller\PortableApps.comInstaller.exe`.
- **Open:** actually writing `launcher.ini` and generating `WAMailSync.exe` (decision 1 above).
- **Open:** obtaining and recording the pinned SHA-256 for the official installer download.
- **Open:** the splash settings from **B8** go in the same `App\AppInfo\Launcher\` ini this step
  creates, so fold them in here rather than reopening the file later. Requires timing the frozen exe
  first.

### A5. ~~Delete the tag worktree~~ — **resolved 2026-08-05, nothing to delete**
`scratchpad\wt-v021` no longer exists and `git worktree list` shows only the main checkout, so the
copies of `release.jks` and `keystore.properties` it held are gone with it. No deletion was
performed by this session.

---

## B. Queued work — no decision needed, just sequencing

### B1. ~~Verify end-to-end IMAP delivery on device~~ — **PASSED**, store-phase gate is clear
Verified against a live Gmail mailbox: 2 chats, 68 messages, clean foreground-service start and
shutdown. The four v0.2.2-beta fixes have had only limited on-device exercise beyond that — see A1.

### B2. Create icon assets — **DONE 2026-08-03**
Generated from `WhatsApp Gmail sync icon.zip` (which was already in the repo — my earlier "zero icon
assets" claim was wrong) into `portable/App/AppInfo/`: `appicon.ico` (10 frames, 16-256) plus
`appicon_16/32/75/128/1024.png`. `wa-chat-sync.spec` now sets `icon=` instead of `None`, so the
frozen `WAMailSync.exe` carries it. Two caveats in §4a of the store-phase doc: the 1024 is an
upscale (no source exceeds 512), and the arrows are illegible at 16px.

Still open: the `.bat` launcher cannot carry an icon — a PortableApps.com-style `.exe` launcher
would be needed for that, which is part of A4 rather than B2.

### B3. ~~Update `WA-Mail-Sync-Password-Storage.docx` §4.1~~ — **done 2026-08-06**
This entry was itself out of date. The `.docx` was regenerated on 5 Aug, *after* DPAPI shipped, so
§2.1/§2.3/§4.1 already described DPAPI correctly and the "actively wrong" claim no longer held.
`scratchpad/make_password_doc.py` is indeed gone with its session, so the doc was edited in place
with `python-docx` rather than regenerated. What it was genuinely missing was the user-visible
consequence of both secrets being device-bound: new §2.9 (moving the bundle to another PC) and
§3.8 (moving to a new phone), a matching "If the device changes" row in the platform comparison
table, and a version line now reading v1.0.0. Awaiting sign-off.

### B4. Clear Unaise Urfi's rows from `sync_state.db`
So that chat can be re-archived. Offered previously, never carried out.

### B7. "Gmail" / "WAGmail" naming sweep — *scoped 2026-08-03*
Full plan in `2026-08-03-gmail-naming-sweep.md`. 704 "gmail" hits across 52 files, "wagmail" in 25.
Not a find-and-replace: two traps documented there — the frozen runtime identifiers
(`wagmail_prefs`, `wagmail_imap_key`, python root `wagmail`) whose rename makes a user's saved
password permanently undecryptable, and `Completed/**` + dated docs which are historical records
and say "Gmail" accurately.

### B5. Rename the Windows build artifacts
`build_portable.ps1`, `wa-chat-sync.spec` (`name="WAGmailSync"`), the `.bat` launcher, and
`WAGMAIL_ROOT`. Deferred — ties into the broader package-name / rename decision.

### B8. Splash, startup time and shutdown on the portable build — *opened 2026-08-06*
Full write-up in [`2026-08-06-splash-and-startup-learnings.md`](2026-08-06-splash-and-startup-learnings.md),
carried over from PASk 3.3.0-3.4.1 where every number in it was measured on a real frozen build.
Nothing here is started.

**Sequencing:** the splash items belong **inside A4**, not after it — `splash.jpg` and `SplashTime`
live in `App\AppInfo\Launcher\WAMailSyncPortable.ini`, which A4 creates. Doing A4 without them means
opening the same file twice.

The checklist, and why each item is on it:

- **Time the frozen exe** (launcher-start to visible window, warm and cold) before choosing any
  number. PASk sized its splash against a *source* run and shipped one that cleared with the screen
  still empty for ~8.6s — source was ~9s, frozen was 14.6s. `python gui.py` is not the program we
  ship.
- **Add `splash.jpg` + `SplashTime`** sized just *under* the warm figure, with the warm/cold numbers,
  the date and the reasoning in a comment above it. There is no splash today, and it is the cheapest
  perceived-speed win available since it costs zero actual milliseconds. It is a **timed overlay,
  not a ready signal** — nothing clears it when the window appears, so overshoot parks a topmost
  image over a usable window. Leave cold starts uncovered.
- **Do not set `LaunchAppAfterSplash`.** It runs the splash to completion *before* starting the app,
  adding its duration to start time rather than hiding start behind it. It reads like the obviously
  correct setting and is the opposite.
- **Prefer a static image — no version number.** A build-time Pillow render is how PASk shipped a
  3.4.0 splash reading "Version 3.3.0": the build script ran under the invoking interpreter, which
  had Pillow locally and not on CI, and a `try/except` shipped the stale committed image. Skipping
  the render skips the whole trap. Also: a missing `splash.jpg` disables the splash **silently**.
- **Verify in a CI-built package**, not a local one — a local build proves nothing about the path
  that breaks.
- **Audit for network timeouts on the path to the first window.** `gui.py:860` already threads
  transport construction (`_silent_build_transport`); the risk is anything added later that isn't —
  a token refresh, a capability probe, a DNS lookup, an update check.
- **Size worker-filled widgets for their final content** so they don't grow when data lands. Start
  in the shape you finish in, and don't let a placeholder show a guess styled as a fact.
- **Comment two shutdown invariants** next to the thread creation in `gui_worker.py`. This bug does
  *not* bite us today and the reason is worth knowing: `WAMailSyncPortable.ini` sets
  `SingleAppInstance=false` and never sets `SinglePortableAppInstance`, so no mutex is held during a
  slow exit; and every worker thread is `daemon=True`, so shutdown never joins them. **Adding
  `SinglePortableAppInstance=true` — a reasonable thing to want — brings the bug with it**, because
  `WaitForProgram=true` is already set. In PASk that combination made closing the app block
  relaunching for 10-17s with no window, no error and no message.

### B6. Device cleanup
`/sdcard/Download/wamail-test/` still holds test screenshots.

---

## C. ~~Uncommitted working tree~~ — **all committed**

Everything that sat here (Windows DPAPI `src/secret_store.py` + tests, the password-storage `.docx`,
the store-distribution phase doc, the `gui_worker.py`/`gui.py`/`test_gui_backend.py` changes, the
superseded banner) went in with the v0.2.2-beta merge `381eee2`. Tree is clean; 158 tests pass.

---

## D. Phases on the books

- **Android store distribution** — MID priority, **gated on you battle-testing the app**. Full plan
  in `2026-08-02-android-store-distribution-phase.md`. Samsung Galaxy Store first, then Indus
  Appstore, then Xiaomi GetApps; Google Play deferred; F-Droid / Amazon / Huawei ruled out.
  Open sub-decisions: the cross-store signing choice (own key everywhere vs Play App Signing — they
  are mutually exclusive for updates), backing up `release.jks` off-machine, a privacy-policy URL,
  and checking whether Google's developer-verification programme alone meets the goal without any
  listing at all.
- **Safer credential storage (P1)** — ~~the DPAPI work in §C is this phase, part-delivered~~
  **complete 2026-08-06.** The code half shipped in v0.2.2-beta (§C); the remaining half was
  documentation that still described the Windows password as plaintext and argued against
  encrypting it — corrected in `README.md`, `PLATFORM-PARITY.md`, `SecretStore.kt`'s header, and
  `docs/user-guide.md`, which also gained the missing user-facing note that a DPAPI blob does not
  travel to another PC or Windows account. B3 (the `.docx`) is the one artefact still uncorrected.
- **"No longer Gmail-only" renaming (P2)** — B5 is a piece of it.
- **Migration to a new device (P3)** — *opened 2026-08-06.* Both platforms now bind the saved
  password to one device by design (DPAPI to a Windows account+machine, the Keystore key to the
  phone's secure hardware), and §2.9 / §3.8 of the password-storage doc record the consequence but
  only as "re-enter it once". The password is the easy part; nothing yet says what happens to
  **`sync_state.db`** — the message fingerprints that stop a re-sync duplicating everything already
  in the mailbox. Moving to a new PC or phone without carrying that database forward means the next
  sync re-files every chat into fresh threads.

  **Scope decided 2026-08-06:** the password is **deliberately not migrated** — re-entering it once
  is the correct behaviour and follows from the device-bound design, not a gap to work around. Any
  migration feature must not weaken that. **`sync_state.db` is the critical payload** and the real
  subject of this phase. Still to settle: whether the app offers an explicit export/import rather
  than expecting the user to locate the file, whether settings and the watched-folder path travel
  with it, and whether cross-platform (Windows to Android or back) is in scope or only like-for-like.
  Not started; no code written.

---

## Suggested order

B1 and B2 are both done. Next: **A1 (install v0.2.2 and exercise the four fixes) → A4 (launcher +
pinned-hash installer fetch, **with B8's splash settings folded in**) → B3**. B8 is not a separate
slot: its splash half belongs inside A4's ini, and its startup/shutdown half is an audit that wants
the frozen exe A4 produces. A1 first because everything else is downstream of trusting the
current build; A4 because it is the only thing standing between the icon set and a real Windows
deliverable; B3 because it is the one document that is now actively wrong rather than merely stale.

Also outstanding but not sequenced here: a Windows portable zip for v0.2.2-beta (needs the `Data\`
sanitization described at the top), and dismissing the GitHub secret alert as a false positive.
