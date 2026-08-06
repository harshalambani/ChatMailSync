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
2. **P2 - We are no longer a Gmail-only tool. DONE and merged (2026-08-06).**
   The final sweep is described at the end of this section. With IMAP
   as the default backend the app archives into Outlook, Yahoo, iCloud,
   Fastmail or any IMAP server. The product is renamed **WA Mail Sync**
   (Android package `com.wamailsync.app`), the shared core module is
   `src/mail_client.py` (`MailTransport`/`MailTransportError`), the Chaquopy
   bridge loads it via `getModule("src.mail_client")`, and user-facing copy
   ("Connect your mailbox first.", "writes nothing to your mailbox", etc.) no
   longer says Gmail unless the statement is genuinely Gmail-specific (the
   Gmail OAuth Testing-status limits, the Gmail deep-link/thread-open feature,
   real API identifiers like `gmail.insert`/`GMAIL_SCOPES`, and persisted
   on-disk/DB names such as `gmail_thread_id`, the `gmail_oauth` backend
   value, and the `wagmail_prefs`/`wagmail_imap_key` Android storage names,
   which are intentionally left alone to avoid orphaning existing installs).
   Repository folder name and remaining historical/dated docs were left as-is
   per the rename task's scope.

   **Closing sweep, 2026-08-06.** Two commits finished it. The only real
   user-facing defect found was in `WatchFolderWorker.triggerAutoSync`, which
   returned `"syncing to Gmail..."` from *after* the backend branch, so an IMAP
   user archiving into Fastmail was told their mail was going to Gmail. The
   rest were comments, docstrings and help copy.

   `help.html` turned out to matter more than its hit count suggested: it is
   the file bundled by `wa-chat-sync.spec` and opened by the Help button, and
   its setup section had already been updated for IMAP while the framing around
   it still said Gmail - so the shipped help page contradicted itself.
   `docs/privacy.html` had a genuine gap, not just a stale name: it described
   only the OAuth path and said nothing about the IMAP app password or the
   third-party IMAP server, which has been wrong since IMAP became the default.
   Google-scope text and the registered consent-screen name were left untouched.

   Three help texts (`help.html`, `HelpScreen.kt`, `docs/user-guide.md`) still
   described the pre-v0.2.4 reset behaviour and were corrected to describe the
   confirmation gate.

   ~460 case-insensitive "gmail" hits remain outside the historical docs and are
   all deliberate: OAuth scopes and the `gmail_oauth` backend value, the
   `imap.gmail.com` provider default and Gmail app-password instructions, the
   Gmail-gated deep-link, Gmail API limits, the `gmail_thread_id` /
   `gmail_label_id` columns, the frozen `wagmail_prefs` / `wagmail_imap_key` /
   `WAGmailSync Dev` cert-subject identifiers, and real repository URLs. Two
   items are deliberately out of scope and still open: `docs/CNAME`
   (`wagmail.ambani.tech`, needs DNS work) and the repository/folder name.
