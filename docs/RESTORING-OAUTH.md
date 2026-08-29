# Restoring Google sign-in (OAuth)

Google sign-in was removed in **v2.0.0**. This document is the map back: what
was taken out, where it still lives, and what you would have to do outside the
repo to make it work again.

Nothing here is needed to run the app. IMAP with an app password is the only
backend, and it reaches Gmail perfectly well.

---

## 1. Why it was removed

The OAuth client never left Google's **Testing** publishing status. Moving to
*In production* requires passing a CASA (Cloud Application Security
Assessment) — an **annual, paid** third-party audit — because the app requests
a restricted Gmail scope. Testing status carries two hard caps that Google
enforces and the app cannot extend:

- at most **100 test users**, each added by hand in the Cloud console;
- consent **expires roughly 7 days** after it is granted, so every user has to
  sign in again about weekly.

The trigger for removal was the Samsung Galaxy Store submission. A live
`GoogleAuthUtil` call on Android forces a Google-account entry in the store's
data-safety declaration, and Samsung has no "declared but dormant" category —
either the app collects a Google account or it does not. Since the OAuth path
could not be offered to the public anyway, declaring it would have been
declaring a capability nobody could use.

## 2. The exact point to restore from

| | |
|---|---|
| Last commit with OAuth present | **`110b4cd`** — "Back up because you will need it, not because you are moving (v1.17.0) (#42)" |
| Tag | `v1.17.0` |
| The strip commit | **`b8216ae`** - "Remove Google sign-in; keep one export per chat in processed/", released as v2.0.0 |

Two ways back:

```bash
cd "C:\Users\inabm\Documents\Cowork Playground\ChatMailSync"

# A. Undo the whole strip as one change, keeping everything since.
git revert b8216ae

# B. Take one file back as it was, and re-apply later changes by hand.
git checkout 110b4cd -- src/mail_client.py
```

`git revert` will conflict on anything changed since — expect conflicts in
`src/config.py`, `gui.py` and `AppPrefs.kt`, which have all moved on. B is
usually the calmer route for a single file.

## 3. What was removed, file by file

### Python core

| File | What went |
|---|---|
| `src/mail_client.py` | `get_credentials()`, `build_service()`, `DiscoveryTransport`, `RestTransport`, `build_transport()`, `set_token()`, `_GmailService`, `OAUTH_BROWSER_TIMEOUT_SECONDS`, the `google.oauth2` / `googleapiclient` imports and the `TYPE_CHECKING` block. ~10.5 KB. |
| `src/config.py` | `CREDENTIALS_FILE`, `TOKEN_FILE` (survives as `LEGACY_TOKEN_FILE`, used only to recognise a former OAuth user), `GMAIL_SCOPES`, `MAIL_BACKEND_GMAIL_OAUTH` (survives as `LEGACY_MAIL_BACKEND_GMAIL_OAUTH`), and the whole v1.6.0 visibility gate (`should_latch_oauth`, `oauth_is_visible`, the `CHATMAILSYNC_ENABLE_OAUTH` env var). |
| `gui_worker.py` | `_check_gmail_auth_status()`, `connect_gmail()`, the OAuth branch of `build_transport_for_active_backend()`, the `TOKEN_FILE` / `CREDENTIALS_FILE` imports. |
| `gui.py` | Connect/Sign-out buttons, the backend dropdown (one backend now needs no menu), `_warn_oauth_is_limited()`, the seven-tap unlock, `_BACKEND_LABELS_REV`. |
| `cli.py` | the `google.*` logger silencers. |
| `setup_auth.py` | deleted outright — the whole file was the one-time OAuth2 init script. |
| `requirements.txt` | `google-auth`, `google-auth-oauthlib`, `google-api-python-client` (and, transitively out of the lock, `google-api-core`, `google-auth-httplib2`, `googleapis-common-protos`, `httplib2`, `oauthlib`, `proto-plus`, `protobuf`, `pyasn1*`, `requests-oauthlib`, `rsa`, `uritemplate`). |

### Android

| File | What went |
|---|---|
| `android/app/build.gradle.kts` | `implementation("com.google.android.gms:play-services-auth:21.6.0")` |
| `MainActivity.kt` | every `com.google.android.gms.*` import, `GMAIL_SCOPES`, `refreshStaleToken()`, the Phase-A3 connection block, `onMailBackendChange`. |
| `AppPrefs.kt` | `oauthIsVisible`, `isOauthUnlocked`, `setOauthUnlocked`, `isOauthVisible`, `latchOauthIfInUse`. `MAIL_BACKEND_GMAIL_OAUTH` survives as `LEGACY_MAIL_BACKEND_GMAIL_OAUTH`. |
| `SyncWorker.kt` | `KEY_ACCESS_TOKEN`, the missing-token guard, the `token` parameter, the `set_token` call. |
| `WatchFolderWorker.kt` | the `GoogleAuthUtil.getToken(...)` branch of `triggerAutoSync`. |
| `SettingsScreen.kt` | the seven-tap version unlock. |
| `MailAccountScreen.kt` | the backend dropdown and the whole OAuth branch of the form. |
| `ChatDetailScreen.kt` | the "Open in Gmail" deep-link button (`isGmailBackend`). Note `isGmailMailbox` stayed — it now tests the IMAP **host**, because Gmail-over-IMAP still needs the folders-are-labels reset wording. |

### Tests

Deleted: `tests/test_oauth_recovery.py`,
`android/app/src/test/java/com/chatmailsync/app/OauthVisibilityTest.kt`.
Rewritten: `tests/test_config.py`, `tests/test_gui_backend.py`,
`tests/test_mail_transport.py`, `tests/test_imap_transport.py`.

## 4. What deliberately stayed

These are **not** leftovers — putting them back is not part of a restore, and
removing them would break real users:

- `LEGACY_TOKEN_FILE` and `LEGACY_MAIL_BACKEND_GMAIL_OAUTH` — the two pieces of
  evidence that someone *used* to be on Google sign-in.
- `config.is_legacy_oauth_user()` / `AppPrefs.isLegacyOauthUser()`, and the
  one-time "Google sign-in has been removed" notice on each front-end.
- `resolve_mail_backend()` returning `imap` for a saved `gmail_oauth`. It
  deliberately does *not* echo the saved value back: that would hand the worker
  a backend name nothing can build a transport for, and the user would meet a
  crash instead of an explanation.
- `gmail_thread_id` in the database and in `sync_state.db` — historical rows
  still carry it.
- Everything Gmail-**mailbox** related (`is_gmail_mailbox`, the folders-are-
  labels reset wording). Pointing IMAP at `imap.gmail.com` is still the common
  case.

## 5. What is *not* in the repo, and never was

Restoring the code is the easy half. The other half lives in Google Cloud:

1. A Google Cloud project with the **Gmail API** enabled.
2. An **OAuth 2.0 client ID** of type *Desktop app* (Windows) and the Android
   client (SHA-1 of the signing key + package name `com.chatmailsync.app`).
3. The downloaded client JSON saved as `auth/credentials.json`. **This file is
   gitignored and has never been committed** — it cannot be recovered from git
   history. It must be downloaded again from the Cloud console.
4. The consent screen configured with scope `gmail.insert` (+ `gmail.labels`),
   and either the 100-test-user list maintained by hand, or a passed CASA
   assessment to reach *In production*.

Without step 3 no amount of `git revert` produces a working sign-in.

## 6. If you restore it

Platform parity is mandatory (`PLATFORM-PARITY.md`): the feature comes back on
**both** front-ends in the same batch, or not at all. And before it ships on
Android, the Galaxy Store data-safety declaration must be updated to say the
app collects a Google account — that is what removing it avoided.
