# Changelog

This file was started with the 2.2.0 release. Earlier releases are described
by their own commits and tags (`git log`, `git tag -l`) and by the per-release
notes in `fastlane/metadata/android/en-US/changelogs/`.

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

The Windows desktop app is not part of this or any future release; it ended
at v2.1.5 (tag `windows-final`), per `docs/RELEASING.md`.
