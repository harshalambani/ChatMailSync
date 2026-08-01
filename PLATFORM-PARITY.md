# Platform parity: Windows and Android move together

**Standing rule, not a one-off plan.** This project ships two clients over one
shared Python core. They are to be kept **head to head in features**. A change
that alters what a user can do is not finished when it works on one platform.

## Why this document exists

On 2026-07-31 the desktop app's default mail backend was flipped to IMAP
(app password) and the first release, `v0.1.0-beta`, went out. The Android
client was still **OAuth-only** and had never had an IMAP code path at all.
The gap was not noticed during the work; it surfaced only when the release
notes had to disclaim *"An Android client exists in the repo but is OAuth-only
and is not part of this release."*

The user's response was the reason for this file:

> "I was under assumption that this was parallely happening."

That assumption was reasonable and the codebase should make it true by
default. The failure mode is specific and repeatable: because `src/` is
genuinely shared, a change there *feels* cross-platform, so nobody checks the
UI and glue layers that are not shared. Most of the real work in a feature
lives in exactly those unshared layers.

## The rule

Every feature, setting, backend, or user-visible behaviour change must be
delivered on **both** clients, in the same change set, or the divergence must
be written down here with a reason.

"Later" is not a reason. "The core is shared so Android gets it for free" is
the specific belief that caused this document to exist.

## What is actually shared, and what is not

Shared - a change here lands on both platforms at once:

- `src/` - parser, sync engine, state DB, transports, config. The Android
  build mirrors this directory into
  `android/app/src/main/python/src/` via the `:app:syncPythonCore` Gradle Copy
  task on every build. That mirror is gitignored and is **never** edited
  directly; the top-level `src/` is the single source of truth.

Not shared - every one of these needs its own change:

| Concern | Windows | Android |
| --- | --- | --- |
| UI | `gui.py` (Tkinter) | Compose screens (`*Screen.kt`, `MainActivity.kt`) |
| Background/threading glue | `gui_worker.py` | `SyncWorker.kt`, `WatchFolderWorker.kt` |
| Settings persistence | `data/.settings.json` | `AppPrefs.kt` (SharedPreferences) |
| Secret storage | plaintext file + NTFS ACL | `SecretStore.kt` (AndroidKeyStore AES/GCM) |
| Help text | `docs/help.html`, `docs/user-guide.md` | `HelpScreen.kt` |
| Packaging | `build_portable.ps1` -> portable zip | Gradle -> APK/AAB |

Note the third and fourth rows especially: **settings and secrets are stored
twice, by different mechanisms.** Any new setting or credential therefore
needs deliberate work on each side, and the two implementations must agree on
semantics even though they disagree on storage. `AppPrefs.resolveMailBackend()`
exists purely to mirror `src/config.py:resolve_mail_backend()`; if one of
those changes, the other is now wrong.

## Checklist before calling a feature done

1. Does it work on Windows?
2. Does it work on Android?
3. If it adds a setting: is it persisted on both, with the same default and
   the same upgrade behaviour for existing installs?
4. If it adds a credential or secret: is it stored appropriately on both, and
   is it kept out of anywhere it would be persisted unencrypted? (On Android
   that specifically means **never** in WorkManager input `Data`, which is
   written to WorkManager's on-disk database.)
5. Is the help text updated on both?
6. Do the release notes describe one product, not two?

## Deliberate, accepted divergences

Only these. Anything else is a bug.

- **Packaging and distribution.** Portable Windows zip vs Android APK/AAB.
  Different artifacts, different release cadence is acceptable; different
  *features* is not.
- **Secret storage mechanism.** Windows uses a plaintext file restricted by
  NTFS ACL (an explicitly accepted trade-off, documented in `SECURITY.md`);
  Android uses AndroidKeyStore AES/GCM. Android has neither NTFS ACLs nor
  DPAPI, so the file layout/format is what is shared cross-platform and the
  hardening is layered per OS. See the comment on `IMAP_CREDENTIALS_FILE` in
  `src/config.py`, which anticipated exactly this.
- **Watched-folder mechanics.** Android uses SAF document URIs and a
  WorkManager periodic job with a platform-enforced 15-minute floor; Windows
  polls a plain filesystem path. Same feature, unavoidably different plumbing.

## Queued work - both platforms, both times

Neither of the following is done, and neither is Windows-first.

1. **P1 - Safer credential storage.** Revisit how the IMAP app password is
   kept at rest. Windows is currently plaintext + ACL by an accepted decision;
   Android already uses AndroidKeyStore AES/GCM. Scope a Windows equivalent
   (DPAPI or Credential Manager) and confirm both ends still agree on
   semantics. To be picked up immediately after the Android release.
2. **P2 - We are no longer a Gmail-only tool.** With IMAP as the default
   backend the app archives into Outlook, Yahoo, iCloud, Fastmail or any IMAP
   server, yet the product name, repository name, labels, help text, screen
   copy, notification strings and internal identifiers (`gmail_client.py`,
   `GmailTransport`, `getModule("src.gmail_client")`, "Test Gmail connection",
   "Connect Gmail first.") all still say Gmail. This needs a plan, not a
   find-and-replace: naming and identity, user-facing copy, internal API
   names, and how much of it is safe to rename after a public release has
   already shipped. Parked as P2, immediately after P1.
