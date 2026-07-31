# Road B â€” IMAP APPEND refactor plan

**Status:** Decided, not started (2026-07-30).
**Supersedes:** Road C (Google OAuth verification) â€” abandoned.
**Repo:** `<repo root>`

---

## 1. Why we pivoted

Google's OAuth verification passed every **functional** check. The sole remaining
requirement is an annual paid **CASA ADA-AL1** (formerly Tier 2) security
assessment, triggered purely because `gmail.insert` is a RESTRICTED scope. It's
due **Oct 27 2026** and recurs every year.

- CASA Tier 2 is a DAST / OWASP-ZAP scan of a running app at a URL â€” meaningless
  for a serverless, on-device tool with no backend.
- Cheapest ADA-authorized lab (TAC Security) is ~**$540â€“855 per annual cycle**.
  KPMG and every other provider are enterprise-priced (more expensive). No free
  self-scan route remains. CASA Ready (open-source) only reduces prep, still
  needs a paid lab.
- Not worth a recurring fee for a personal/family tool.

**Road B drops OAuth and the restricted scope entirely**, delivering mail via
standard **IMAP APPEND + an app-specific password**. Result: no Google
verification, no CASA, ever. Bonus â€” the app becomes **provider-agnostic**
(Gmail / Outlook / iCloud / Yahoo / Fastmail), not Gmail-only.

---

## 2. The key architectural finding

All mail I/O is already isolated behind a tiny structural interface, so this is a
new-transport job, not a rewrite.

`GmailTransport` Protocol â€” `src/gmail_client.py` (~line 194) â€” has exactly three
methods:

| Method | Returns |
|---|---|
| `labels_list() -> dict` | `{"labels": [{"name":â€¦, "id":â€¦}, â€¦]}` |
| `labels_create(body: dict) -> dict` | `{"id": â€¦}` (body has `{"name": "WhatsApp/Mom", â€¦}`) |
| `messages_insert(body: dict, thread_id: Optional[str]) -> dict` | `{"id":â€¦, "threadId":â€¦}` (body is `{"raw": <base64url RFC822>, "labelIds": [label_id]}`) |

Two implementations exist today: `DiscoveryTransport` (googleapiclient, Windows)
and `RestTransport` (direct REST over a bearer token, Android). Both raise a
normalized `GmailTransportError(message, status)`.

**Everything above the Protocol is transport-agnostic and must NOT change:**
`get_or_create_label`, `chunk_messages`, `_build_html_mime_message`,
`_insert_with_backoff`, `push_chunks`, `push_chat` (all in
`src/gmail_client.py`), and `SyncManager` in `src/sync_manager.py` (accepts
`transport: GmailTransport` at ~line 130; calls `push_chat(transport=â€¦)` at
~lines 285 and 488).

Threading is already done client-side via RFC822 `In-Reply-To` / `References`
headers the app writes itself (`_build_html_mime_message`, ~line 526). The
returned `threadId` is only ever used as an opaque token that's persisted and
echoed back on the next chunk â€” so a synthetic value works fine.

**Road B = add a 4th `ImapTransport` (~100 lines) + swap the auth/config layer.**

---

## 3. IMAP mapping

| Protocol method | IMAP realization |
|---|---|
| `labels_list()` | IMAP `LIST` â†’ `{"labels":[{"name":f,"id":f}]}` (id == name; IMAP has no label IDs) |
| `labels_create(body)` | IMAP `CREATE` folder `body["name"]`; treat "already exists" as success; parent is ensured first by `get_or_create_label` |
| `messages_insert(body, thread_id)` | decode `body["raw"]` (urlsafe_b64 â†’ RFC822 bytes), `APPEND` to folder `body["labelIds"][0]`; return `{"id": <APPENDUID or synthetic>, "threadId": thread_id or <stable synthetic, e.g. the Message-ID>}` |

Tricky points to design carefully:

- **Threading** â€” no server-side thread IDs. `messages_insert` must return a
  stable, non-None `threadId` on first insert and echo it thereafter, so
  `push_chunks`' persistence loop keeps working. Confirm the `src/state.py`
  contract (`gmail_thread_id`, `anchor_message_id`, `gmail_message_id`) holds
  with synthetic values.
- **Hierarchy delimiter** â€” Gmail uses `/`, other servers may use `.`. Read the
  delimiter from the `LIST` response and translate `WhatsApp/<Chat>` names.
- **Gmail-over-IMAP** â€” a "label" is a folder; APPEND applies the label and the
  message also lands in All Mail. Consider setting `\Seen` on APPEND so the
  archive isn't unread-noise, and preserving date via APPEND internaldate.
- **Error normalization** â€” wrap `imaplib` errors into the existing
  `GmailTransportError(message, status=None)` so `_insert_with_backoff`'s retry
  policy still applies.

---

## 4. Auth model change (the real substance)

- Replace OAuth on the IMAP path with `imaplib.IMAP4_SSL(host, 993)` +
  `login(email, app_password)`.
- OAuth entry points to retire/replace: `get_credentials()` / `build_service()`
  / `build_transport()` in `src/gmail_client.py`; `setup_auth.py`; the GUI
  Connect flow (`connect_gmail` in `gui_worker.py:135`; `gui.py` ~782);
  `cli.py` (~line 90).
- New settings surface: email + app-specific password + provider preset. Reuse
  the ACL-locked `auth/` dir (`_restrict_auth_dir_acl` in `src/gmail_client.py`)
  for credential storage; consider Windows Credential Manager.

**Caveats:** app passwords require 2FA on the account. Some Microsoft 365
tenants block basic-auth IMAP (personal Outlook.com is fine).

---

## 5. Config change

Add a provider-preset table in `src/config.py` (currently holds `GMAIL_SCOPES`
at ~line 69, which is dropped):

| Provider | Host | Port |
|---|---|---|
| Gmail | `imap.gmail.com` | 993 |
| Outlook / 365 | `outlook.office365.com` | 993 |
| Yahoo | `imap.mail.yahoo.com` | 993 |
| iCloud | `imap.mail.me.com` | 993 |
| Fastmail | `imap.fastmail.com` | 993 |

---

## 6. Dependency + build impact

Removes: `google-api-python-client`, `google-auth-oauthlib`,
`google-auth-httplib2`, `httplib2`, `google-auth`. `imaplib` is stdlib. Update
`requirements*.txt`, the PyInstaller spec, and note the bundle-size reduction.
Verify nothing else imports those packages.

---

## 7. Phased plan

1. **Core (headless, testable):** `ImapTransport` implementing the 3 Protocol
   methods over `imaplib`, a transport factory, provider-preset config, unit
   tests against a mocked `imaplib`. Add a conformance test proving
   `ImapTransport` satisfies the Protocol identically to the others.
2. **Desktop UI:** replace the OAuth Connect flow (`gui_worker.py:135`,
   `gui.py`, `cli.py`, `setup_auth.py`) with an email + app-password + provider
   preset form; store credentials in the ACL-locked `auth/` dir.
3. **Strip OAuth + docs:** remove the five Google deps, rewrite
   `docs/privacy.html` + README to drop all OAuth/Gmail-API language, and reply
   to Google to **cancel** the verification request.
4. **Android (optional/later):** `imaplib` under Chaquopy + an app-password
   settings screen.

**Decisions already made:** Windows desktop first; Android later. Per team
preference, hand the actual build to a Sonnet subagent while the main session
coordinates and reviews.

### 7a. REVISED 2026-07-30 â€” dual backend, user's choice (supersedes "IMAP-only")

The earlier "ship IMAP-only" decision is **reversed**. The Gmail API / OAuth path
**stays active and supported**; IMAP becomes a **second backend the user
selects**. This changes the phases above as follows:

- **Phase 1 is unaffected** â€” it was always purely additive (a 4th transport
  next to `DiscoveryTransport` and `RestTransport`). Build it as written.
- **Phase 2 becomes "add a backend selector"**, not "replace the Connect flow".
  Settings grows a `mail_backend` choice (`gmail_oauth` | `imap`), persisted, and
  the desktop UI shows the OAuth Connect button or the
  email/app-password/provider form depending on it. The `service=` â†’
  `transport=` plumbing fix (Â§9.3) is still required, because it's what lets one
  code path carry either backend.
- **Phase 3 is largely cancelled.** The five Google deps **stay**. `GMAIL_SCOPES`
  stays. `setup_auth.py` stays. `tests/test_gmail_transport.py` keeps its
  `googleapiclient` import (Â§9's test-suite note no longer applies). Docs are
  *added to* rather than rewritten: `docs/privacy.html` and the README must now
  describe **both** backends and be explicit about which data path applies to
  which choice. **Do not cancel the Google verification request** â€” see below.
- **Phase 4 (Android)** likewise keeps its OAuth path and gains IMAP.

**The CASA question does not disappear, it changes shape.** Keeping
`gmail.insert` means the OAuth backend still can't reach *verified/production*
status without the annual CASA ADA-AL1 assessment. The workable position is:
leave the OAuth app in **Testing** publishing status with the family accounts as
test users, which needs no CASA. The documented cost of that is that Google
expires refresh tokens for sensitive/restricted scopes on unverified apps after
**7 days**, so OAuth users would face periodic re-consent â€” which is precisely
the friction the IMAP backend exists to avoid. **Confirm the current publishing
status and the actual observed token lifetime before treating this as settled**;
it is the one thing that decides whether the OAuth backend is genuinely usable
long-term or just a legacy path. Verification status itself is a decision for the
human, not the build agent.

---

## 8. Risks

- Threading-token stability with synthetic IDs (Â§3).
- Hierarchy-delimiter differences across providers (Â§3).
- Gmail All-Mail duplication / unread noise on APPEND (Â§3).
- M365 tenants blocking basic-auth IMAP (Â§4).
- App-password availability requires 2FA (Â§4).

---

## 9. Findings from code verification (planning session, 2026-07-30)

Five things the code says that change or sharpen the plan above.

**9.1 â€” CRLF line endings are mandatory for APPEND.**
`_build_html_mime_message` (~line 607) does
`base64.urlsafe_b64encode(root.as_bytes())`. Python's default compat32 policy
emits **LF** line separators. The Gmail API tolerates that; **IMAP APPEND does
not** â€” RFC 3501 literals must be CRLF-terminated, and servers may reject or
silently corrupt an LF-only message. `ImapTransport.messages_insert` must
normalize to CRLF after decoding (`re.sub(rb"\r?\n", b"\r\n", raw_bytes)`), not
`_build_html_mime_message`, so the Gmail-API transports stay byte-identical.

**9.2 â€” Retry policy silently disappears on IMAP unless statuses are mapped.**
`_insert_with_backoff` (~line 700) retries on `exc.status in (429, 500, 502,
503, 504)` plus `(socket.timeout, TimeoutError, ConnectionError, OSError)`.
`imaplib.IMAP4.error` / `IMAP4.abort` derive from `Exception`, **not** `OSError`
â€” so a naive `GmailTransportError(msg, status=None)` wrapper gets **zero
retries**. `ImapTransport` must map transient IMAP conditions (`IMAP4.abort`,
dropped connection, `[SERVERBUG]`, `[UNAVAILABLE]`, `[INUSE]`) to a retryable
pseudo-status (`503`), and permanent ones (auth failure, `[OVERQUOTA]`,
`[TRYCREATE]`, `NO` on a bad folder) to a non-retryable one (`401` / `403` /
`400`). Bare `imaplib.IMAP4.error` should NOT be blanket-retried.

**9.3 â€” The desktop path plumbs `service=`, not `transport=`.**
The plan's Â§2 claim that everything above the Protocol is untouched holds for
`gmail_client.py` and `sync_manager.py` internals, but the **desktop call chain
passes a raw googleapiclient Resource** and only gets wrapped at the bottom:
`gui.py:788` `self._service = build_service()` â†’ `gui.py:703` `service=` â†’
`gui_worker.py:63,85` `service=` â†’ `sync_manager.py:130-145`, which wraps
`service` in `DiscoveryTransport` internally. Phase 2 must switch this chain to
`transport=` end to end (`SyncManager` already accepts it, and rejects both at
line 142). Also in scope for phase 2: `check_auth_status` (`gui_worker.py:113-117`,
reads `token.json` via google `Credentials`) and the **token-revoke flow at
`gui.py:882`**, which has no IMAP equivalent and becomes "forget saved password".
Neither is named in Â§4 above.

**9.4 â€” `From:` is a non-FQDN address.**
`_format_sender` (~line 486) emits `Name <whatsapp-sync@local>`. Gmail's APPEND
is lenient (APPEND is not SMTP submission), but a strict server may reject the
domain. Must be confirmed by a live smoke test, per provider â€” do not assume.

**9.5 â€” `get_or_create_label` already forces the ID contract.**
It reads `{lbl["name"]: lbl["id"]}` from `labels_list()` and returns
`resp["id"]` from `labels_create()` (~lines 356-381), then that value is used
verbatim as `body["labelIds"][0]`. So `ImapTransport` returning `id == name`
(the folder path) is not just convenient, it is **required** for the existing
code to work unmodified. `_sanitise_label_name` also already replaces `/` inside
a chat name with `-`, so the only `/` in a full label name is the single
hierarchy separator â€” which makes delimiter translation a clean single-token
swap.

**Test-suite note:** `tests/test_gmail_transport.py` imports
`googleapiclient.errors` at module top level, so phase 3's dependency strip will
break collection of that file until its Discovery tests are removed or guarded.
Plan for it in phase 3, not phase 1.
