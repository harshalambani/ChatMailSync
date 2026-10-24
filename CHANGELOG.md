# Changelog

This file was started with the 2.2.0 release. Earlier releases are described
by their own commits and tags (`git log`, `git tag -l`) and by the per-release
notes in `fastlane/metadata/android/en-US/changelogs/`.

## Unreleased

Nothing yet.

## 3.0.0

Not yet released (versionCode 46). The redesigned Settings.

- UX-01: Settings and Advanced are now lists of rows with icons and one-line
  summaries. Dropdowns became dialogs with Cancel, help and the privacy policy
  moved to a Help screen, and Run setup again has a back button.

## 2.9.2

Released 2026-10-22 (versionCode 45). Renamed contacts are recognised.

- A chat that was renamed in WhatsApp is now recognised as the same chat and
  linked to its earlier history, instead of starting over as a new chat.
- Chats whose names are written in non-Latin scripts (Devanagari, Arabic,
  Chinese and so on) now each get their own identity, so two different
  chats no longer risk being treated as one.
- Answering "Is this the same chat?" now says so when saving the answer
  failed ("Couldn't save your answer. Please try again."), and the question
  stays open so you can answer again.
- A background sync that is waiting for that answer now shows one
  notification, with no chat name in it. It clears when you open the app.
- Space left over from older versions is freed on the first start.
- The "Is this the same chat?" question no longer disappears when you tap outside it; a held chat shows as waiting on Home with a button to answer it, and a sync you start yourself says when it held a chat.

## 2.9.1

Released 2026-10-22 (versionCode 44). A much smaller download.

- The app no longer carries the Python runtime the old sync engine needed,
  so it is about 3 MB instead of 23 MB. Settings, history and syncs carry
  on exactly as before.
- The Open-source licences screen and the root `NOTICE` file now list only
  what the app still uses: AndroidX and Jetpack Compose, the Kotlin
  standard library and kotlinx.coroutines (all Apache-2.0). The Python
  runtime, python-dateutil and six entries are gone.
- JUnit 4 (EPL-1.0) is kept as a test-only dependency and does not ship in
  the app.

## 2.9.0

Released 2026-10-21 (versionCode 43). A new sync engine, written entirely
in Kotlin. Needs Android 11 or newer; phones on Android 7 to 10 stay on
2.2.0. Upgrading keeps your history, so nothing is mailed twice.

- Photos and files sent with a caption are now attached.
- Cancel stops promptly, between two emails of the same chat export, and a
  stopped run is shown as stopped rather than failed. A Stop that arrives
  during recovery of an interrupted run reads as stopped, not "Still
  running", and a force close after Stop no longer re-runs the sync.
- Each chat export is removed from your phone once its sync completes.
- An export that reads as zero messages is kept and reported instead of
  being silently dropped.
- The end-of-sync notification is no longer lost when the sync ends.
- Each part of a long chat now carries its "Part k/N" label on its subject.
- The picture of what was last synced no longer steps backwards after a
  recovered run, and recent runs, senders and chats list in a stable order.
- Restoring a backup is stricter: the restored database is size-capped,
  `messages_cutoff` is carried across, and history of the wrong type is
  refused rather than half-loaded.
- Added third-party licence notices: a root `NOTICE` file covering every
  component that shipped inside the APK, plus an "Open-source licences"
  screen (Settings -> Help & About) that bundles the same text into the app.
  A guard test fails the build if a runtime dependency is added without a
  matching NOTICE entry.
- The privacy policy is rewritten for this version.
- The Kotlin tests now also run on real Android (emulator jobs on API 30
  and 34).

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
