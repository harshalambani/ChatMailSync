# Changelog

This file was started with the 2.2.0 release. Earlier releases are described
by their own commits and tags (`git log`, `git tag -l`) and by the per-release
notes in `fastlane/metadata/android/en-US/changelogs/`.

## Unreleased

- Added third-party licence notices: a root `NOTICE` file covering every
  component that ships inside the APK (Chaquopy/MIT, the embedded CPython
  3.13 interpreter and standard library/PSF-2.0, python-dateutil/Apache-2.0
  AND BSD-3-Clause, six/MIT, AndroidX and Jetpack Compose/Apache-2.0, the
  Kotlin stdlib/Apache-2.0, and kotlinx.coroutines/Apache-2.0), plus a new
  "Open-source licences" screen (Settings → Help & About) that bundles the
  same text into the app. A guard test fails the build if a runtime
  dependency or the Chaquopy pip package is added without a matching NOTICE
  entry.

## 2.2.0

Six batches of work since the 2.1.5 / `windows-final` tag:

- **Batch 1** -- chat-sender tracking and a self-sender state field, the
  groundwork for working out which name in an export is the app user's own.
- **Batch 2** -- a Me row on the masthead, Me name entry moved into its own
  screen, a Me strip on the chat detail screen, and a one-time Home banner
  the first time the app learns the user's name from an export.
- **Batch 3** -- easier app-password entry during setup, and promoting the
  common mail providers (with AOL added) ahead of the rest of the list.
- **Batch 4** -- a four-step first-run setup that replaces the earlier,
  longer walkthrough.
- **Batch 5** -- Basic and Advanced settings sections, with follow-up polish
  (back-button labels, chip wrapping) and a bounded "Test connection" that
  always returns instead of being able to hang.
- **Batch 6** -- "Save & connect" made to share that same bounded,
  exactly-once connection check as "Test connection"; the dead
  `fadingEdgesHorizontal` helper removed; the FAQ kept in sync between
  `HelpScreen.kt` and `docs/user-guide.md`, and the user guide's leftover
  Windows-desktop wording corrected to describe the Android app; version
  bump to 2.2.0 (versionCode 42).
- **Batch 7** -- Backup & restore moved out of the Settings row list into its
  own screen, reached the same way as Mail account, Me and Advanced; its
  Settings row now carries a status pill ("Backed up `<date>`", "Backup due"
  or "No backup") so the state is visible without opening the screen.
- **Batch 7 follow-up** -- fixed a bug (confirmed on the Nord) where the Mail
  account screen and Settings kept showing the pre-restore mailbox (e.g.
  Gmail with a blank email) right after a successful restore, even though
  the restored settings were already correctly saved -- reopening the app
  was the only workaround, and pressing "Save & connect" without it would
  have overwritten the restored account with defaults. Also added a "Moving
  from another phone? Restore from a backup" option on the very first screen
  of a fresh install, so setting up a new phone from a backup no longer
  means clicking through to Settings first; a successful restore there can
  continue straight into mail setup with the restored provider and email
  already filled in.
- **Batch 7b** -- a restore during first-run setup no longer walks the user
  through "Step 3 of 4" and "Step 4 of 4"; it finishes at the app-password
  step and lands on Home, the same as skipping mail setup for later. Every
  successful restore, on first run and on the Backup & restore screen, now
  shows an inline summary of what came back (chats, already-sent count,
  cutoffs, mail account and the settings the bundle carried) and what still
  needs redoing (app password, watched folder) -- never a dialog or toast.

The Windows desktop app is not part of this or any future release; it ended
at v2.1.5 (tag `windows-final`), per `docs/RELEASING.md`.
