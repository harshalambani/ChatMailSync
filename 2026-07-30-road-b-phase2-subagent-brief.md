# Road B â€” Phase 2 build brief: backend selector + desktop UI

**Date:** 2026-07-30
**Repo:** `<repo root>`
**Depends on:** Phase 1 (uncommitted, already in the working tree â€” `ImapTransport` +
`build_imap_transport()` in `src/gmail_client.py`, `IMAP_PROVIDERS` /
`IMAP_CREDENTIALS_FILE` / `MAIL_BACKEND_*` in `src/config.py`, `tests/test_imap_transport.py`).

---

## 0. How you must work

**Escalate to the HUMAN, not to the agent that spawned you.** If you hit a
decision that changes user-visible behaviour, product semantics, or security
posture, stop and emit a block headed `QUESTION FOR HUMAN` stating the question,
the options, and your recommendation. Do not guess and do not invent a policy.
Two such questions are already known and listed in Â§6 â€” raise them *first*,
before writing UI code that depends on them.

**Do not commit and do not push.** The human commits. Leave everything in the
working tree.

**No new runtime dependencies.** `imaplib`, `email`, `json`, `tkinter`/
`customtkinter` (already used) only.

---

## 1. What Phase 2 is

Make the mail backend **user-selectable**, and make the desktop app able to run a
sync over either backend. This is **purely additive**: the Gmail API / OAuth path
stays fully supported and is the default. Nothing above the `GmailTransport`
Protocol boundary changes its contract.

Concretely, three things:

1. A persisted `mail_backend` setting (`gmail_oauth` | `imap`), default
   `gmail_oauth`.
2. The desktop call chain converted from passing `service=` to passing
   `transport=`, so one code path carries either backend.
3. A Settings/Connect UI that shows the OAuth "Connect" flow when the backend is
   `gmail_oauth`, and an email / app-password / provider-preset form when it is
   `imap` â€” plus credential persistence to `IMAP_CREDENTIALS_FILE`.

---

## 2. Read these first (do not skim)

- `src/gmail_client.py` â€” the `GmailTransport` Protocol (~line 195),
  `DiscoveryTransport`, `RestTransport`, `ImapTransport`, `build_service()`
  (line ~264 returns `DiscoveryTransport(build_service(creds))`),
  `build_imap_transport()`.
- `src/config.py` â€” `_apply_root()` (line 29), `set_root()` (line 59),
  `IMAP_CREDENTIALS_FILE` (line 56), `MAIL_BACKEND_*` (lines 93-95),
  `IMAP_PROVIDERS` (line 106). Note the `_restrict_auth_dir_acl` machinery that
  already hardens `auth/`.
- `src/sync_manager.py` â€” lines 124-145. `service=` and `transport=` are both
  accepted today; line 143 raises
  `ValueError("Pass at most one of service= or transport=, not both")`.
- `src/android_api.py:203` â€” **already passes `transport=`.** Android is done;
  only the desktop path needs converting. Use it as the reference shape.
- `gui.py` â€” settings persistence at lines 63-96 (`_SETTINGS_FILE`,
  `_DEFAULT_SETTINGS`, `_load_settings`, `_save_settings`), auth block at
  775-818, revoke/sign-out at 882-919, `_open_settings`/`_apply_settings` at
  937-952, `_SettingsWindow` at 996-1056.
- `gui_worker.py` â€” `SyncWorker.__init__` (55-69) and `_run` (78-100, passes
  `service=` at line 85), `check_auth_status()` (107-132), `connect_gmail()`
  (135-141).
- `cli.py:90-99` â€” `build_service()` â†’ `service=service`.
- `tests/test_config.py:28` â€” `SyncManager(service=None, dry_run=True)`.
  **A test pins the `service=` kwarg, so you may not delete it.**
- `tests/test_imap_transport.py`, `tests/test_gmail_transport.py` â€” the
  conformance style to match.

---

## 3. Work items

### 3.1 Settings: persist `mail_backend`

Add `mail_backend` to `_DEFAULT_SETTINGS` in `gui.py` with value
`config.DEFAULT_MAIL_BACKEND`. `_load_settings()` already merges saved values
over defaults key-by-key, so an existing `.settings.json` without the key picks
up the default â€” verify that, don't assume it.

Add the IMAP connection fields the user must supply: provider key, host, port,
email address. **The app password does not go in `.settings.json`** â€” it goes in
`IMAP_CREDENTIALS_FILE` (Â§3.4). Keep that separation explicit in the code.

### 3.2 Desktop chain: `service=` â†’ `transport=`

Convert, end to end:

- `gui.py:784-790` `_silent_build_service` â†’ build a *transport* for the active
  backend. Rename to something honest (`_silent_build_transport`) and update the
  attribute it sets.
- `gui.py:792-818` connect flow and `gui.py:703`-area worker construction.
- `gui_worker.py:55-69` `SyncWorker.__init__` â€” take `transport`.
- `gui_worker.py:85` â€” pass `transport=` to `_ProgressSyncManager`.
- `cli.py:90-99` â€” build a transport for the active backend.

`SyncManager`'s `service=` kwarg **stays** (test-pinned, and it is the
documented "authenticated googleapiclient service or None for dry-run" path).
You are changing *callers*, not the signature.

### 3.3 Auth status and connect, per backend

- `check_auth_status()` (`gui_worker.py:107`) currently reads `token.json`
  unconditionally. It must branch on the active backend: for `imap`, report
  status from the presence/validity of stored IMAP credentials, not from
  `token.json`. Keep the `(bool, str)` return shape â€” `gui.py:775-780` drives
  the status dot, label, button text and sign-out enablement off it.
- `connect_gmail()` (`gui_worker.py:135`) is OAuth-specific. Add a sibling for
  IMAP that validates by *actually logging in* (`build_imap_transport()` +
  `labels_list()`) and posts the same `auth_ok` / `auth_error` queue events, so
  `_poll_auth_queue` (`gui.py:799-818`) needs no structural change. A wrong app
  password must surface as a clear permanent error â€” Phase 1 already maps
  login failure to a non-retryable `GmailTransportError`.
- The UI strings say "Gmail" in several places (e.g. "Gmail authentication
  successful." at `gui.py:812`). Make them backend-appropriate.

### 3.4 Credential storage

Write `IMAP_CREDENTIALS_FILE` (already reserved in config, already routed
through `_apply_root`) as JSON containing the email address, host, port and app
password. Reuse the existing `auth/` ACL hardening â€” do not write a second
mechanism.

**Recorded decision, carry it forward:** the ACL lock is a *Windows-side
hardening of a common file-based scheme*, not a cross-platform mechanism.
Android has neither NTFS ACLs nor DPAPI. The scheme is shared; the protection
under it differs per platform. Do not add a Windows-only store that Android
cannot mirror.

**Security, non-negotiable:** the app password must never reach a log line, an
exception message, or a UI label â€” not even partially masked. Phase 1's
`_strip_secret` exists for this; reuse it. The password field in the UI is
masked input and is never echoed back after saving.

### 3.5 Sign out / revoke

`gui.py:882-919` POSTs to `https://oauth2.googleapis.com/revoke` and deletes
`token.json`. There is **no IMAP equivalent** â€” there is no server-side grant to
revoke. See Â§6(b): this is one of the two questions to raise before building it.
The proposal on the table is that under the `imap` backend the button becomes
"Forget saved password", deleting `IMAP_CREDENTIALS_FILE` only.

### 3.6 Tests

Mirror the Phase 1 style. At minimum:

- `mail_backend` defaults to `gmail_oauth`; an existing settings file without
  the key loads the default; a saved value round-trips.
- `check_auth_status()` under each backend, including "no credentials stored".
- Backend selection picks the right transport builder, and the OAuth path is
  byte-for-byte unaffected when `mail_backend == "gmail_oauth"`.
- Credentials file: written with the expected shape, and the app password never
  appears in any log record or exception string produced by a failed connect.
- The existing suite must stay green â€” `tests/test_config.py:28` in particular.

---

## 4. Hard constraints

- **Additive only.** OAuth stays fully supported and is the default. No behaviour
  change for a user who never touches the new setting.
- **Nothing above the Protocol boundary changes.** `sync_manager`, threading,
  chunking, state DB: untouched.
- **Date/time parity.** The IMAP path already derives APPEND internaldate from
  the message's own `Date:` header (live-verified against a real Gmail mailbox:
  a 2019 `Date:` came back as `INTERNALDATE 2019-03-14`). Nothing in Phase 2 may
  disturb that.
- No new runtime dependencies.
- Do not touch `DiscoveryTransport` or `RestTransport`. Both Gmail-API
  transports stay.
- `auth/`, `data/`, `Issues/` are gitignored. Never write a secret anywhere else.

---

## 5. Already-verified facts (do not re-litigate)

From the live smoke test against a real Gmail mailbox, 2026-07-30:

1. Hierarchy delimiter is `/` â€” matches the app's canonical form; no translation
   needed for Gmail. All Mail is `[Gmail]/All Mail`.
2. `From: ZZ-SmokeTest <whatsapp-sync@local>` accepted unchanged despite being
   non-FQDN.
3. Explicit APPEND internaldate is **honoured**.
4. A message appearing in both its label folder and `[Gmail]/All Mail` is normal
   Gmail label behaviour, not duplication.
5. UIDPLUS is available on Gmail (`APPENDUID` returned); the synthetic
   `threadId` contract held.

Also settled in Phase 1: `imaplib`'s `create()`/`list()`/`subscribe()` do **not**
raise on a `NO` response â€” only `login()` self-raises. Every non-login call needs
an explicit `typ != "OK"` check.

---

## 6. Raise these to the human BEFORE writing dependent UI code

**(a) Migration for users who already hold a valid `token.json`.** Do they stay
silently on `gmail_oauth` (default, zero prompts, nothing changes for them), or
are they shown a one-time "you can now choose a backend" prompt? This decides
whether Phase 2 ships a first-run migration path at all. Recommendation: stay
silent â€” the default already routes them correctly â€” but confirm.

**(b) What "Sign out" does when the active backend is IMAP.** There is no
server-side grant to revoke. Recommendation: the button becomes "Forget saved
password" and deletes `IMAP_CREDENTIALS_FILE`; the existing OAuth revoke path is
kept unchanged and used only when the backend is `gmail_oauth`. Confirm the
wording and whether it should also warn that the app password itself remains
valid at the provider until the user revokes it there.

---

## 7. Definition of done

- Both backends selectable; `gmail_oauth` is default and its behaviour is
  unchanged.
- A full desktop sync runs over `transport=` on either backend.
- IMAP credentials persist to the ACL-hardened `auth/` file, and the app password
  appears in no log, exception, or UI string.
- Auth status, connect, and sign-out all behave correctly per backend.
- New tests added; full suite green.
- Nothing committed. Report what changed, what you decided, and anything you
  could not verify.
