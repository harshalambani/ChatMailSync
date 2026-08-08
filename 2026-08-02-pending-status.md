# WA Mail Sync — What's Pending

**As of:** 2026-08-07 (header refreshed; individual entries below carry their own dates)
**Released:** v1.0.1 (`main` at `843f00c`) — APK plus the Windows `.paf.exe` installer and portable
zip. The credential-seeding hazard that once blocked shipping a Windows package was fixed in
`88ae11c`: packaging never touches `dist\WAMailSyncPortable`, it builds from a separate staging tree
with an empty `Data\` skeleton and refuses to package if any credential or user-data file is found.
**Test suite:** 195 passing (171, +13 for the version display, +11 for the progress-output guard).
**CI:** `.github/workflows/tests.yml` runs the suite on Windows 3.14 and Linux 3.13 (merged as #4);
`codeql.yml` is no longer the only workflow.

---

## A. Waiting on your decision

Nothing below moves until you say so.

### A1. ~~Test the Android APK~~ — **DONE 2026-08-07, signed off**
The v1.0.1 APK was verified by SHA-256 against the release before installing, went on over the top
with `adb install -r`, and the device now reports **versionCode 8 / `1.0.1`** (`lastUpdateTime`
2026-08-07 16:15:25). Same signing key, so `sync_state.db`, the saved IMAP password and all settings
survived. A real sync was then run on it and completed cleanly — Bijal Ambani, 451 messages, 67
chunks, one continuous run (a single stable WorkManager id, not a retry loop), no exception and no
worker failure. **This clears the gate on the Android store phase in §D.**

Two inefficiencies were found by watching that run in `logcat`, both fixed the same day and neither
affecting correctness:

1. **`SyncWorker` re-posted unchanged progress ~4×/second.** The poll loop ticks every 250ms whether
   or not an event arrived and posted the identical payload each time; `setProgress()` is a write
   into WorkManager's Room database and `notify()` rebuilds a system notification, so one chat
   sitting on a single chunk for 3.5 minutes cost ~840 disk writes and as many notification updates
   to say nothing new. Now posted only when the text, fraction or log actually changed.
2. **`_print_progress` drew a terminal progress bar into logcat.** Chaquopy forwards `sys.stderr` to
   logcat at *warning* level, where a carriage return means nothing, so every redraw became its own
   entry — ~67 for that one chat, each carrying the contact's display name into the system log and
   burying any genuine stderr warning. The existing guard only covered `sys.stderr is None` (the
   PyInstaller GUI bundle); it now requires `isatty()`, so the bar is drawn on a console and nowhere
   else. Off-console callers already have `on_chunk`, which the GUI and the Android worker use.

The historical record of what was open, kept for the dates:

**~~Still to be exercised.~~**
The release-signed build installed cleanly *as an update* (`firstInstallTime` 2026-08-03 14:07:31
vs `lastUpdateTime` 14:51:02) — `sync_state.db`, the saved IMAP password, the watched folder and all
settings survived. The earlier data-wipe warning about the debug build no longer applies.

**Still open.** ~~The phone is running the pre-bump versionCode 3 binary~~ — **measured on device
2026-08-07** (`adb shell dumpsys package com.wamailsync.app`, serial `RZCY81Q6WZV`): it is on
**versionCode 6 / `0.2.4-beta`**, `lastUpdateTime` 2026-08-06. So the "versionCode 3" claim above
was stale in one direction and the v0.2.2-beta paragraph below it in another; neither was checked
against the phone before being written down.

The current target is the [v1.0.1 APK](https://github.com/harshalambani/WAMailSync/releases/tag/v1.0.1)
(`WAMailSync-v1.0.1.apk`, 22,067,840 bytes, SHA-256
`7d1742bfb5e243d80d984dd00fe8d232fb6ff579eeb18bf9a474dca81f34a708`, **versionCode 8**), which is
two releases ahead of what is installed. Same signing key, so `adb install -r` goes straight over
the top and keeps `sync_state.db`, the saved password and settings.

### A2. ~~Attach the APK to the GitHub release~~ — **done 2026-08-05** (v0.2.2-beta)

### A3. ~~Commit the DPAPI credential encryption~~ — **done**, shipped in v0.2.2-beta.

### A4. ~~Build the `.paf.exe` PortableApps installer~~ — **DONE 2026-08-06**

Shipped in `3cb0157` (compiled launcher + `.paf.exe` installer, both generator tools pinned by
SHA-256 and verified before execution), `eb4b3b4` (portable zip cut from the same clean staging
tree), `88ae11c` (stop seeding live credentials into the distributable tree), and released as part
of **v1.0.0**, which carries `WAMailSyncPortable_1.0.0_English.paf.exe` and
`WAMailSyncPortable_1.0.0.zip` alongside the APK.

**Correction, 2026-08-07:** this entry sat here marked open for a day after the work landed, and was
read as a live blocker when deciding what to ship in v1.0.1 — leading to that release going out
Android-only on a reason that no longer existed. The Windows 1.0.1 package was built and attached
afterwards. The decisions below are kept as the record of how it was designed.

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
- ~~Writing `launcher.ini` and generating `WAMailSync.exe`~~ — **done** (decision 1 above).
- ~~Obtaining and recording the pinned SHA-256 for the official installer download~~ — **done**;
  the pins live at the top of `build_portable.ps1` with the upgrade procedure beside them.
- **Still open, carried to B8:** the splash settings go in `App\AppInfo\Launcher\WAMailSyncPortable.ini`,
  which A4 created but did not populate with them. Requires timing the frozen exe first. This is now
  the only surviving part of A4 and belongs to B8.

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
table, and a version line now reading v1.0.0. ~~Awaiting sign-off.~~ **Signed off 2026-08-07.**
B3 is closed; the AES-128-GCM correction and §2.9/§3.8 stand as written. The pre-correction copy
kept at `scratchpad/WA-Mail-Sync-Password-Storage.PRE-AESFIX.docx` is now redundant.

### B4. Clear Unaise Urfi's rows from `sync_state.db`
So that chat can be re-archived. Offered previously, never carried out.

### B7. "Gmail" / "WAGmail" naming sweep — *scoped 2026-08-03*
Full plan in `2026-08-03-gmail-naming-sweep.md`. 704 "gmail" hits across 52 files, "wagmail" in 25.
Not a find-and-replace: two traps documented there — the frozen runtime identifiers
(`wagmail_prefs`, `wagmail_imap_key`, python root `wagmail`) whose rename makes a user's saved
password permanently undecryptable, and `Completed/**` + dated docs which are historical records
and say "Gmail" accurately.

**Status 2026-08-08: DONE.** The sweep itself closed 2026-08-06; the frozen identifiers were
renamed on 2026-08-08 (`wamail_prefs`, `wamail_imap_key`, `filesDir/wamail`) once a fresh sync with
a new app password made the "orphaned install" premise moot. The "permanently undecryptable" trap
above turned out not to exist — `SecretStore.getSecret()` clears an undecryptable blob and returns
null, so the app just re-prompts; see the superseded banner in §2a of the sweep doc. Only the
python-root rename leaves anything behind (the old `filesDir/wagmail` tree), which the device
storage clear handles. No live identifier in the tree still says "wagmail".

### B5. Rename the Windows build artifacts
`build_portable.ps1`, `wa-chat-sync.spec` (`name="WAGmailSync"`), the `.bat` launcher, and
`WAGMAIL_ROOT`. Deferred — ties into the broader package-name / rename decision.

**Status 2026-08-08: DONE.** `WAGMAIL_ROOT` is closed — the fallback was deleted from `src/config.py`
and `WAMAILSYNC_ROOT` is now the only name honoured, with the test in `tests/test_config.py` inverted
to assert the old name is *ignored* rather than honoured. `wa-chat-sync.spec` is `name="WAMailSync"`
in both blocks, and the `.bat` launcher no longer exists — it was replaced by the PortableApps `.ini`
launcher, which is what made an icon possible. Nothing named here still says "WAGmail".

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

*Rewritten 2026-08-07. The previous order was written around v0.2.2-beta and listed A4 and B3 as
upcoming when both had shipped — the same staleness that sent v1.0.1 out Android-only. Anything
below that is not measured is marked as such.*

*Updated again 2026-08-07 (evening): A1 and the CI test workflow are both done.*

B1, B2, A4, B3, **A1** and the **CI test workflow** are done. Next: **B8 → P2 (B5 + B7) → P3**.

Nothing is gating the Android store phase any more — A1 was the last item in front of it, and it
passed on versionCode 8 with a real 451-message sync. Whether the store phase or B8 goes first is a
priority call, not a dependency.

The **CI test workflow** landed 2026-08-07 as `.github/workflows/tests.yml`, merged as #4. It exists
because Dependabot #3 (`cryptography` 49 → 50, lock-only) arrived with three green checks that
knew nothing about whether the app worked — `codeql.yml` was the repository's only workflow, so
validating a dependency bump meant building a venv and running the suite by hand. It installs
`requirements-lock.txt` with `--require-hashes`, the way `build_portable.ps1` does, because
lock-only changes are most of what Dependabot opens here and they do change what ships.

B8 still wants the frozen exe timed before its startup/shutdown half can be judged; its splash
settings in `App\AppInfo\Launcher\WAMailSyncPortable.ini` are the surviving piece of A4.

Also outstanding but not sequenced here: bumping `github/codeql-action` v3 → v4 before its
December 2026 deprecation, and exercising Google sign-in once on a real build — `cryptography` 50
is only reachable on that path and the suite mocks it.
