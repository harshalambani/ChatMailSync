# Backup reframe + scrollbar track — plan for v1.17.0

Approved 2026-08-28. AB4 (all sub-items), AB4.5, AB5.1, AB5.2 = option 9.2.
Nothing below is coded yet. Both front-ends in the same batch (PLATFORM-PARITY.md).

## Why

`android:allowBackup="false"` means `sync_state.db` — the record of what has
already been mailed — is destroyed by Clear data, uninstall/reinstall, or a
factory reset. Losing it does not lose the archive (the mailbox holds that),
but it makes the app mail **every chat a second time** into a mailbox that
cannot tell the copies apart, because the app can add mail and never remove it.
Today the only protection is the manual bundle, and it is presented solely as a
migration tool, under a heading a user with no new phone will never open.

## 1. Rename + reframe (AB4.1–4.3)

1.1 Section heading `SettingsScreen.kt` ~:278: "Move to a new phone" ->
    **"Backup & restore"**, blurb naming both uses: carrying history to another
    device, *and* getting it back after a reset, reinstall or Clear data.
1.2 Every other "Move to a new phone" string -> **"Move to another device"**:
    `MailAccountScreen.kt:399`, `MainActivity.kt:675` (comment),
    `HelpScreen.kt:137`, `docs/user-guide.md:454,462`,
    `gui.py:3093,3101,3336,3595`, `help.html:351,354`,
    `portable/help.html:89`.
    Arrow convention: Android/py copy uses ASCII `->`, `help.html` uses `&rarr;`.
1.3 New FAQ entry: **"What happens if I reinstall the app or reset my device?"**
    `tests/test_faq_parity.py` requires the identical question string, in the
    same order, in `HelpScreen.kt`, `help.html`, `docs/user-guide.md`.

## 2. Make backups routine, not a one-off (AB4.2, AB4.4)

2.1 New `AppPrefs` key `last_backup_at` (epoch millis), written on a successful
    save; Windows equivalent in the settings JSON. `AppPrefs.kt` has no
    "backup" key today — this is new.
2.2 Under the two buttons: **"Last backup: <date>"** / **"No backup saved yet"**.
2.3 Home screen: a quiet in-window staleness line when no backup exists or the
    last one is older than ~30 days. **No pop-up, no dialog** — in the main
    window, in place. Same on Windows.

## 3. Let Android back the app up (AB4.5 — user voted `true`)

3.1 `AndroidManifest.xml:12` `android:allowBackup="false"` -> `"true"`.
3.2 Add `res/xml/data_extraction_rules.xml` (+ the legacy
    `full_backup_content` attr for API < 31) naming exactly what is included:
    `sync_state.db` and prefs; nothing else.
3.3 What this does and does not cover — say it in the FAQ:
    - Covered: Android Auto Backup / Smart Switch now carry the sync record and
      settings, so a restore-to-new-phone or a reinstall keeps history.
    - Exposed: chat display names, one-way message hashes, run history,
      mail address, IMAP host, watched-folder path. **Not** message content
      (hashes are one-way) and **not** the app password (Android Keystore,
      non-exportable — Keystore blobs never survive a restore, so the user
      re-enters the password once after any restore).
    - Auto Backup is E2E-encrypted with a lock-screen-derived key on modern
      devices.
3.4 The manual bundle stays — it is the only Android<->Windows path.

## 4. Scrollbar track (AB5.2 = option 9.2)

`ScrollFade.kt`: keep the 4dp thumb at 0.35 alpha; draw a **full-height track**
behind it at ~0.10 alpha, same colour, same width, same corner radius, same 2dp
inset — in **both** `verticalScrollbar` overloads (LazyListState and
ScrollState). Windows parity equivalent in `gui.py` scrollbar styling.
Affected screens (no per-screen change needed, they all call the modifier):
ChatDetail:102, ChatsList:385, Help:242, Home:218, ImportPicker:396,
MailAccount:369, MailSetupWizard:171, Queue:138+163.

## 5. Duplicate `sync_runs` rows (AB5.1) — NOT YET REPORTED

Status: **blocked on evidence, deletion not yet approved against a real list.**
The install is release-signed, so there is no `run-as` and no root: the live
`sync_state.db` cannot be read or edited over adb. Route:
5.1 Settings -> "Save a backup" on the phone, SAF-save to Downloads, `adb pull`
    the `.cmsbackup`, read `sync_runs` **read-only**.
5.2 Report the exact rows (row id, chat, trigger, timestamps, counts) and get
    approval against that list — a count is not a list.
5.3 Then ship a one-time in-app cleanup keyed on the same natural key as the
    import fix, `src/migration.py::_RUN_COLUMNS`, reporting what it removed.

## 6. Release

v1.17.0. `versionCode`/`versionName` in `build.gradle.kts` (8-space indent) and
`appinfo.ini` bump in the **same commit**. Windows `.\build_portable.ps1
-Installer -Zip`; Android `./gradlew :app:assembleRelease --no-daemon`. APK
published alongside the Windows assets, per-asset SHA-256 in the notes,
`gh release create --target main` (a full SHA is rejected as target). On-device
pass before release.

## 7. Tooltip white box (found mid-build)

A hover on Windows stranded a blank white 200x200 `Toplevel` pinned topmost
over the app. Cause: `_Tooltip._show` built the window, then handed the Label a
`gui_theme` colour, which is a `(light, dark)` **pair** -- plain tkinter cannot
take a tuple and raised `TclError`, after the window existed and before
anything held a reference to it, so nothing was left that could close it.
Fixed in `gui.py`: a `_plain_color()` resolver picks from the pair by
appearance mode, the window is recorded *before* the Label is built, and the
Label is wrapped so a failure hides rather than strands.

## 8. Riding along in this release

8.1 **Share target did nothing.** A WhatsApp export shared to the app arrived
    while the activity was still starting, and `setContent` does not compose
    during `onCreate` -- composition happens at `onAttachedToWindow`, after
    `onCreate` has returned, so the intent was handled into a UI that did not
    exist yet. Fixed with a `pendingImport` / `takePendingImport()` handoff
    drained by a `LaunchedEffect(Unit)`.
8.2 **`(n)` suffix normalisation.** `parser.extract_chat_info` treated
    `WhatsApp Chat with Bijal (1).txt` as a different chat from
    `... Bijal.txt`, so a re-download mailed the whole chat again. The suffix
    is now stripped; 2 tests cover it.
8.3 **Duplicate `sync_runs` sweep** -- see 5 above; shipped as a one-time
    `PRAGMA user_version` repair in `state.init_db()`.

## Status at time of writing (all coded, tests green)

1 done -- headings, path strings and the new FAQ, byte-identical across the
three parity files. 2 done -- `last_backup_at` on both platforms, stamped only
on a save that reported ok, with a Settings line and an in-window Home line
that hides itself once a backup is recent. 3 done -- `allowBackup="true"` plus
`data_extraction_rules.xml` and the legacy `full_backup_content.xml`, an
allow-list naming only `sync_state.db` and `chatmailsync_prefs.xml`; the WAL
sidecars are excluded on purpose, because a valid slightly-stale database
beats a torn one. 4 done -- a full-height track behind the thumb in both
`verticalScrollbar` overloads and on `CTkScrollbar`. 452 tests pass; both
Kotlin compile and the debug manifest merge are clean.

## Backlog after this (agreed M4 order)

Samsung Galaxy Store phase -> v2.0.0 confidence bump -> simplified 16/32px
`.ico` cut -> B4 (clear Unaise Urfi's rows from `sync_state.db`).
