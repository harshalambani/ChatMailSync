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
| Secret storage | `src/secret_store.py` (DPAPI) + NTFS ACL | `SecretStore.kt` (AndroidKeyStore AES/GCM) |
| Help text | `portable/help.html`, `docs/user-guide.md` | `HelpScreen.kt` |
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
- **Secret storage mechanism.** Windows uses DPAPI `CryptProtectData` over a
  file that is also NTFS-ACL restricted; Android uses AndroidKeyStore AES/GCM.
  Android has neither NTFS ACLs nor DPAPI, so the file layout/format is what is
  shared cross-platform and the hardening is layered per OS. See the comment on
  `IMAP_CREDENTIALS_FILE` in `src/config.py`, which anticipated exactly this,
  and the *Security and credential storage* section of `README.md` for the
  threat model both layers are aiming at. What must stay in parity is the
  *guarantee* - the password is encrypted at rest on both platforms - not the
  API used to get there.
- **Watched-folder mechanics.** Android uses SAF document URIs and a
  WorkManager periodic job with a platform-enforced 15-minute floor; Windows
  (`src/watch_folder.py`, wired up in `gui.py`) picks a plain filesystem path
  and polls it from the GUI's Tk `after()` timer. Same feature, unavoidably
  different plumbing - and two consequences the user does see, which is why
  they are written down rather than left to be discovered:
  - **Windows only checks while the app is open.** A Tk timer cannot run
    otherwise, and the alternative - a Windows service or a Task Scheduler
    entry - is a much larger thing to install, own and uninstall than this
    product currently is. Said in the Settings note and in the help on both
    platforms. Android, being a scheduled job, does check with the app closed.
  - **Windows offers a 5-minute interval, Android's shortest is 15.** The floor
    is WorkManager's and cannot be lowered; a timer has no such rule. Every
    other interval, and every label, is the same list on both.

  Everything the user is promised *is* the same on both: the scan is
  non-recursive, a source is imported only once, the synced-file rule is
  applied only after delivery is confirmed, and the settings keys are the same
  names in the same shapes. The one wording difference is deliberate - Android
  says "Delete after import" where Windows says "Delete after import (Recycle
  Bin)", because the Windows build recycles rather than erasing and it would be
  wrong to hide that behind matched wording.

  > Recorded honestly: this entry described the Windows half in the present
  > tense before the Windows half existed. It is true as of the build that
  > added `src/watch_folder.py`; earlier releases had the Android side only.

## Parity does not mean the two clients cooperate

Worth stating here because the rest of this document reads as "the two clients
are the same product", and a user can reasonably extend that to "so they share
what they have done". They do not.

`sync_state.db` is **per-instance, not per-mailbox**. It sits next to the
instance that wrote it and nothing about it reaches the mailbox, so two instances
pointed at the same account have no knowledge of each other. The second one
starts from zero and re-files every chat it is given. The de-duplication the
whole product rests on is local by construction, and the app can add mail but
never remove it, so the cleanup falls entirely on the user.

The word is **instance**, deliberately - not "device" and not "platform". The
cross-platform pairing is only the most visible case; two Android phones, two
Windows PCs, and two copies of the portable app in different folders on a single
PC are all the same failure, because each portable copy carries its own `Data\`.
Wording that names Windows-and-Android reads as an exhaustive list and quietly
blesses the rest, so no user-facing copy anywhere states it that way.

The rule for users is therefore **one instance per mailbox**, and it is stated in
the same words, in the same position, on both clients (2026-08-11):

| | Windows | Android |
| --- | --- | --- |
| In-app | `gui.py`, mail-account panel, above the backend picker | `MailAccountScreen.kt`, same position |
| Help | `portable/help.html`, `docs/user-guide.md` | `HelpScreen.kt` FAQ |

Weighting was decided deliberately and should not be escalated without a
reason: one quiet caption line in the standard muted style on the one screen
where the mistake is actually made - no dialog, no banner, no warning colour,
not repeated on the sync screen. The full explanation lives in the help. A
caveat that interrupts work it does not apply to gets dismissed unread.

This is the same root cause as the queued **P3 - migration to a new device**
work: replacing an instance is the *supported* case and is exactly the one that
needs `sync_state.db` carried across.

## Queued work - both platforms, both times

Both entries below are now complete; they are kept for the record because the
reasoning behind each is still the reasoning the code follows.

1. **P1 - Safer credential storage. DONE (Windows DPAPI shipped in
   v0.2.2-beta; docs corrected 2026-08-06).** Both ends now encrypt the IMAP
   app password at rest: Windows with DPAPI `CryptProtectData`
   (`src/secret_store.py`, written as `password_dpapi`, ACL retained
   underneath as the fail-loud layer), Android with AndroidKeyStore AES/GCM
   (`SecretStore.kt`). Semantics agree - both are per-device and per-user by
   design, so neither secret survives being copied to another machine or
   account, and both surface that as a re-enter-the-password message rather
   than a silent failure. Legacy plaintext Windows files upgrade themselves
   on first read (`gui_worker.resolve_imap_password`).
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

   **Superseded 2026-08-08 — the three Android names above were renamed after
   all.** They are now `wamail_prefs` (`AppPrefs.kt`), `wamail_imap_key`
   (`SecretStore.kt`) and `filesDir/wamail` (`WaMailApplication.kt`). The
   "orphaning existing installs" reason was real but had expired: the only
   install was the test device, and it was being cleared for a fresh sync with a
   new app password, so there was nothing left to orphan. The stronger claim
   made elsewhere — that renaming the Keystore alias makes a saved password
   *permanently undecryptable* — was wrong on inspection. `SecretStore.getSecret()`
   catches a failed decrypt, clears the dead blob and returns null, which
   callers already read as "no password saved"; the behaviour was written for
   the Keystore-wiped and restored-to-another-device cases and an alias rename
   lands on the same path. Timing drove the decision: once this is on the Galaxy
   Store the same edit silently wipes every user's settings on update, so the
   pre-store window was the last cheap one. Note the asymmetry that remains -
   the prefs and Keystore renames lose nothing recoverable, but the python-root
   rename leaves the old `filesDir/wagmail` tree with its chat exports and
   `sync_state.db` on the device. It is only complete once app storage is
   cleared or the app reinstalled.

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
   `gmail_label_id` columns, the then-frozen `wagmail_prefs` /
   `wagmail_imap_key` identifiers (renamed 2026-08-08, see above), and real
   repository URLs.

   Two items were deliberately out of scope at the time. The cert subject is now
   `CN=WAMailSync Dev` (2026-08-06), and `docs/CNAME` is now
   `wamailsync.ambani.tech`, cut over once the DNS record existed. The
   repository and folder name remain open.
