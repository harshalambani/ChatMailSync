# Road B Phase 1 â€” build brief for the Sonnet subagent

**Give this whole file to the build session.** Everything below the line is the
prompt; it is self-contained and assumes no prior conversation.

Companion doc (read it, don't re-derive it):
`2026-07-30-road-b-imap-append-plan.md` â€” especially **Â§9, the code-verified
findings**, which correct Â§2 and Â§4.

---

## Your role

You are implementing **Phase 1 only** of the Road B IMAP refactor in this repo:

```
cd "<repo root>"
```

Phase 1 is the **headless, fully unit-testable core**: a new `ImapTransport`, a
transport factory, provider-preset config, and tests. **No UI work, no
dependency removal, no doc rewrites** â€” those are phases 2-4 and are explicitly
out of scope.

**The Gmail API / OAuth backend is staying.** IMAP is an *additional* backend the
user chooses, not a replacement (plan Â§7a). So your work is **purely additive**:
a 4th transport alongside `DiscoveryTransport` and `RestTransport`. Nothing
OAuth-related gets removed, deprecated, or edited â€” not `GMAIL_SCOPES`, not the
five Google dependencies, not `setup_auth.py`, not
`tests/test_gmail_transport.py`. If a change you're about to make would degrade
the Gmail-API path in any way, stop and ask.

## Escalation protocol â€” READ THIS FIRST

**When you hit a question, a judgment call you can't ground in the code, or an
ambiguity in this brief: STOP and ask the human directly.** Do not ask the
orchestrating/master agent, do not guess and proceed, and do not silently pick a
default and bury the choice in a code comment.

- Use the `AskUserQuestion` tool for anything with discrete options.
- If it needs prose, stop your turn and state the question plainly as your final
  output, prefixed **`QUESTION FOR HUMAN:`**, with your recommended answer and
  why.
- Batch questions when you can â€” one interruption with three questions beats
  three interruptions.
- **Never invent a fact about a mail provider's behaviour.** If you don't know
  whether a server does X, say so and mark it as needing a live smoke test.

**All four open questions are now ANSWERED** (by the human, 2026-07-30). They are
recorded here as decisions, not questions:

1. **Target accounts â€” largely personal, but it varies user to user.** So no
   code path may assume personal-Gmail behaviour. "App passwords unavailable /
   basic auth disabled by tenant policy" is an *expected* runtime condition: map
   it to a clear, **permanent (non-retryable)** `GmailTransportError` naming the
   cause, rather than surfacing a raw `imaplib` string or burning five backoff
   retries first. Not a phase-1 viability blocker.
2. **Credential storage â€” ACL-locked file in `auth/`.** Decided because this is
   a dual Android + Windows app and both must offer the same functionality, so a
   Windows-only machine-bound store (DPAPI) is out. Caveat to carry forward:
   Android has neither NTFS ACLs nor DPAPI, so "ACL-locked" is a *Windows-side
   hardening of a common file-based scheme*, not a cross-platform mechanism â€”
   the scheme is what's shared, the protection under it differs per platform.
   Phase 1 persists nothing; record the decision, leave phase 2 a clean seam.
3. **Set `\Seen` on APPEND â€” yes.** Attached to it is a hard requirement: **the
   date/time logic must stay identical to the current Gmail-scope behaviour.**
   Today the Gmail path makes the message's own `Date:` header authoritative
   (`RestTransport` sends `internaldatesource=dateheader`; see
   `test_rest_transport_messages_insert_merges_thread_id_and_query_param`). IMAP
   APPEND must reproduce that exactly â€” pass an explicit internaldate parsed
   from the `Date:` header and never let the server default it to upload time,
   or a years-old archive lands dated today. Verify against the current code
   rather than assuming, and test that the internaldate derives from the header,
   not the clock.
4. **~~Keep or delete `RestTransport`?~~ Keep.** Both Gmail-API transports stay;
   the OAuth backend is supported, not retired. Touch neither.

## Hard constraints

- **Do not modify anything above the Protocol boundary.** `get_or_create_label`,
  `chunk_messages`, `_build_html_mime_message`, `_prepare_emails`,
  `_insert_with_backoff`, `push_chunks`, `push_chat`, all of
  `src/sync_manager.py`, `src/state.py`, `src/parser.py`,
  `src/html_renderer.py`. If you believe one of these *must* change, that is a
  `QUESTION FOR HUMAN`, not a decision.
- **Do not remove or weaken OAuth, the Google deps, `GMAIL_SCOPES`,
  `setup_auth.py`, `DiscoveryTransport`, `RestTransport`, or their tests.** The
  OAuth backend is a permanently supported choice, not a legacy path.
- **No new runtime dependencies.** `imaplib`, `email`, `base64`, `re`, `ssl` are
  all stdlib. Test-only additions to `requirements-dev.txt` are fine if
  justified.
- **The existing test suite must stay green.** Run bare `pytest` from the repo
  root before you start (record the baseline) and again at the end.
- Any `.ps1` you touch: **ASCII only** â€” no em-dashes, arrows, curly quotes, or
  ellipses. PowerShell 5.1 reads scripts as Windows-1252 and non-ASCII causes
  parse errors.
- Match the surrounding code's style: module-level `log = logging.getLogger`,
  lazy imports for optional heavy deps, docstrings that explain *why*, and the
  same test idiom as `tests/test_gmail_transport.py`.

## What to read before writing code

- `src/gmail_client.py` â€” lines 179-320 (the Protocol, `GmailTransportError`,
  both existing transports, `build_transport`, `set_token`); 324-382
  (`_sanitise_label_name`, `_full_label_name`, `get_or_create_label`); 486-503
  (`_format_sender`); 542-608 (`_build_html_mime_message` â€” note the
  `base64.urlsafe_b64encode(root.as_bytes())` at the end and the returned
  `{"raw", "labelIds"}` shape); 700-747 (`_insert_with_backoff` retry policy).
- `src/config.py` â€” the `_apply_root` path machinery (lines 22-63) and
  `GMAIL_SCOPES` (line 69).
- `tests/test_gmail_transport.py` â€” the test idiom you must match.
- `tests/conftest.py`.

## The build

### 1. `ImapTransport` in `src/gmail_client.py`

Place it after `RestTransport`. Implement exactly the 3-method `GmailTransport`
Protocol (line 195). Constructor takes host, port, email, password (and
optionally an injectable connection factory â€” see testing).

| Method | Behaviour |
|---|---|
| `labels_list()` | IMAP `LIST` â†’ `{"labels": [{"name": f, "id": f}, ...]}`. **`id` must equal `name`** â€” `get_or_create_label` uses the returned `id` verbatim as `body["labelIds"][0]`, so the folder path *is* the ID (plan Â§9.5). |
| `labels_create(body)` | IMAP `CREATE` on `body["name"]`. **"already exists" is success, not an error** (`[ALREADYEXISTS]`, or a `NO` whose text says so). Return `{"id": <name>}`. Also `SUBSCRIBE` the new folder so it shows up in clients that honour subscriptions. |
| `messages_insert(body, thread_id=None)` | Decode `body["raw"]` (urlsafe base64 â†’ RFC822 bytes), **normalize LF â†’ CRLF** (plan Â§9.1 â€” this is mandatory and easy to miss), `APPEND` to folder `body["labelIds"][0]` with flags per open question 3 and an internaldate parsed from the message's `Date:` header. Return `{"id": ..., "threadId": ...}`. |

**`threadId` contract.** `push_chunks` (lines 854-871) reads
`response["threadId"]`, persists it, and passes it back on the next chunk; it
must be non-`None` and stable for the whole chat. Derive it from the message's
own `Message-ID` header on the first insert (`thread_id` is `None`), and echo
`thread_id` back unchanged on every subsequent insert. `state.py` stores it as
an opaque `TEXT` column (`gmail_thread_id`), so a synthetic value is fine â€”
**but verify** that `src/state.py`'s `gmail_thread_id` / `anchor_message_id` /
`gmail_message_id` handling has no Gmail-ID-shaped assumptions, and report what
you find. For `"id"`, prefer the `APPENDUID` from the server's response when
`UIDPLUS` is available, else fall back to the `Message-ID`.

**Hierarchy delimiter.** Read the delimiter from the `LIST` response (the
untagged `* LIST (\HasNoChildren) "/" "INBOX"` form) and translate the single
`/` in `WhatsApp/<Chat>` to it. `_sanitise_label_name` already replaced any `/`
*inside* a chat name with `-`, so exactly one separator token exists (plan
Â§9.5). Cache the delimiter per connection. If a server reports `NIL`, that's a
flat namespace â€” `QUESTION FOR HUMAN` if you meet one.

**Error normalization.** Every method raises only
`GmailTransportError(message, status=...)`. Map transient conditions
(`IMAP4.abort`, dropped socket, `[SERVERBUG]`, `[UNAVAILABLE]`, `[INUSE]`) to
`status=503` so `_insert_with_backoff` retries; map permanent ones (auth
failure, `[OVERQUOTA]`, `[TRYCREATE]`, `NO` on a bad mailbox) to `401`/`403`/
`400` so it fails fast. **Do not blanket-retry bare `imaplib.IMAP4.error`.** See
plan Â§9.2 â€” without this mapping the app's entire retry policy silently
evaporates on the IMAP path. Never let the password reach a log line or an
exception message.

**Connection lifetime.** A sync pushes many messages in a loop; do not connect
per call. Connect lazily on first use, reuse, and reconnect once transparently
on `IMAP4.abort` (servers drop idle connections). Provide a `close()`. Decide
whether re-`SELECT`/`CREATE` state needs re-establishing after a reconnect and
say so in a comment.

### 2. Factory + config

- `build_imap_transport(...)` in `src/gmail_client.py`, alongside
  `build_transport` / `set_token`. Do not touch the existing two.
- In `src/config.py`, add an `IMAP_PROVIDERS` preset table â€” Gmail
  `imap.gmail.com`, Outlook/365 `outlook.office365.com`, Yahoo
  `imap.mail.yahoo.com`, iCloud `imap.mail.me.com`, Fastmail
  `imap.fastmail.com`, all port 993 â€” plus a "Custom" escape hatch (arbitrary
  host/port). Leave `GMAIL_SCOPES` in place. Any new credential path constant
  must go through `_apply_root` (config.py:39-47) or it will break the
  PortableApps root override and Android's `set_root()`.
- Add the backend-selection *constant* only: `MAIL_BACKEND_GMAIL_OAUTH` /
  `MAIL_BACKEND_IMAP` and a `DEFAULT_MAIL_BACKEND = MAIL_BACKEND_GMAIL_OAUTH`
  (defaulting to today's behaviour, so nothing changes for an existing user).
  **Wiring the selector into settings persistence and the UI is phase 2** â€” do
  not build it here, just establish the vocabulary so phase 2 has a seam.

### 3. Tests â€” `tests/test_imap_transport.py`

Mock `imaplib` (inject a fake connection via the constructor rather than
monkeypatching the module, if you can do it without contorting the API). Cover
at minimum:

- `labels_list` parses a real-shaped `LIST` response, including a quoted folder
  name with a space, and returns `id == name`.
- Delimiter detection and `/` â†’ `.` translation.
- `labels_create` treats already-exists as success.
- **`messages_insert` produces CRLF-terminated bytes** â€” assert no lone `\n`
  survives. Feed it output from the real `_build_html_mime_message` so the test
  breaks if that function's encoding ever changes.
- `messages_insert` returns a stable non-`None` `threadId` on first insert and
  echoes the passed `thread_id` on the second.
- Error mapping: a transient failure yields `status=503`, an auth failure yields
  a non-retryable status, and **the password appears in no exception message**.
- A round-trip through `_insert_with_backoff` proving a 503-mapped transient
  error is actually retried and then succeeds.
- A conformance test asserting `isinstance(transport, GmailTransport)` for all
  three transports (the Protocol is `@runtime_checkable`).
- `end-to-end-ish`: drive `get_or_create_label` with the mocked `ImapTransport`
  and assert it returns the folder path â€” this is the real proof that the
  `id == name` contract holds.

### Definition of done

1. Bare `pytest` from the repo root is green, with no fewer passing tests than
   the baseline you recorded.
2. `ImapTransport` satisfies the Protocol and `get_or_create_label` /
   `_insert_with_backoff` / `push_chunks` work against it **unmodified**.
3. Nothing above the Protocol boundary changed; no deps added; no OAuth removed.
4. A short written summary of: what you built, every open question you raised
   and its answer, anything you found that contradicts the plan doc, and â€” kept
   separate and explicit â€” **the list of things only a live smoke test against a
   real mailbox can confirm** (`From: <whatsapp-sync@local>` acceptance,
   Gmail's All-Mail duplication behaviour, real delimiter values, whether
   internaldate is honoured). Do not claim any of those as verified.

Do not commit or push unless the human asks. Work on a branch if you do.
