# WA Chat Sync to Gmail — Architecture Document

**Version:** 1.4 (Updated 2026-05-30)
**Status:** Phase 1 (CLI) complete. Phase 2 (GUI) complete. Phase 2.5 (HTML + media email format) approved and in progress. Phase 3 (PortableApps packaging) planned.

---

## 1. Project File/Folder Structure

```
wa-chat-sync/
├── src/
│   ├── __init__.py
│   ├── parser.py            # WhatsApp .txt file parsing engine
│   ├── gmail_client.py      # Gmail API wrapper (auth, insert, thread mgmt)
│   ├── sync_manager.py      # Orchestrator: incremental sync, dedup, recovery
│   ├── state.py             # SQLite state tracker (DB init, queries, updates)
│   └── config.py            # Constants, paths, label naming, chunk defaults
├── auth/
│   ├── credentials.json     # User-provided from Google Cloud Console
│   └── token.json           # Auto-generated after first OAuth2 flow
├── data/
│   ├── inbox/               # Drop zone: user places exported .txt files here
│   ├── processed/           # Files moved here after successful full sync
│   └── sync_state.db        # SQLite DB tracking per-chat sync state
├── cli.py                   # CLI entry point (argparse)
├── setup_auth.py            # Standalone OAuth2 initialization script
├── requirements.txt
├── .env.example             # Optional config template (label prefix, chunk size)
└── README.md                # Setup and usage documentation
```

### Design Rationale

- **`inbox/` → `processed/` flow:** Gives a clear visual indicator of sync status. Files only move after a fully successful sync run (not partial).
- **SQLite over JSON for state:** Handles concurrent reads, scales to hundreds of chats, supports atomic writes — critical if the script is interrupted mid-sync.
- **`auth/` isolation:** Keeps credentials separate from source code for clean `.gitignore` rules.
- **Separate `state.py`:** Decouples database logic from sync orchestration, making it testable independently.

---

## 2. Flexible Date Parsing Engine

### The Problem

WhatsApp exports have no formal timestamp schema. The format depends on the phone's OS, locale, WhatsApp version, and regional settings. A single export file uses one consistent format, but different files from different phones will vary.

### Strategy: Ranked Regex Cascade with Format Lock-In

On the first few lines of each file, the parser tries all known patterns. Once one matches, it "locks" to that pattern for the rest of the file (fast path — no re-evaluation per line).

### Known Format Variants

| # | Regex Pattern | Example | Typical Source |
|---|---|---|---|
| 1 | `\[(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}:\d{2})\]` | `[14/03/25, 09:41:23]` | Android, non-US locales |
| 2 | `\[(\d{1,2}/\d{1,2}/\d{4}),\s(\d{1,2}:\d{2}:\d{2})\]` | `[14/03/2025, 09:41:23]` | Android, 4-digit year |
| 3 | `(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}\s[APap][Mm])` | `3/14/25, 9:41 AM` | iOS, US locale (no brackets) |
| 4 | `(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}\s[APap][Mm])` | `14/03/25, 9:41 AM` | iOS, non-US locale |
| 5 | `(\d{1,2}-\d{1,2}-\d{4})\s(\d{1,2}:\d{2})` | `14-03-2025 09:41` | Some Android exports |
| 6 | `\[(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}:\d{2}\s[APap][Mm])\]` | `[3/4/25, 2:05:33 PM]` | US Android, non-zero-padded |

### Implementation Logic

```
For each .txt file:
  1. Read first 20 non-blank lines
  2. Skip known system-only lines (encryption notice, etc.)
  3. Try each regex pattern against lines until one matches
  4. Validate with dateutil.parser.parse() as sanity check
     - If day field > 12 → confirmed DD-first (DD/MM)
     - If month field > 12 → confirmed MM-first (MM/DD)
     - If ambiguous (both ≤ 12) → default to DD/MM with a logged warning
  5. Store the winning pattern as the file's `format_key`
  6. If no pattern matches after 20 lines → log warning, skip file

For subsequent lines in the same file:
  1. Try only the locked pattern (single regex match — fast)
  2. Match → new message (extract timestamp, sender, body)
  3. No match → continuation line, append to previous message body
```

### Multi-line Message Handling

A new message starts ONLY when a line matches: `{timestamp_pattern} - {SenderName}: {body}`.

Everything else (including blank lines) is appended to the previous message's body with a newline. This correctly handles messages where the user pressed Enter mid-message.

### System Message Filtering

Lines matching the timestamp pattern but where the "sender" portion contains known system phrases are excluded from sync:

- "Messages and calls are end-to-end encrypted"
- "Media omitted"
- "This message was deleted"
- "You deleted this message"
- "{name} joined using this group's invite link"
- "{name} left"
- "{name} was added"
- "{name} changed the subject to"
- "{name} changed the group description"
- "{name} changed this group's icon"
- "Missed voice call" / "Missed video call"
- "security code changed"

These are matched via a configurable list in `config.py` so users can extend it.

### DD/MM vs MM/DD Ambiguity Resolution

This is the trickiest sub-problem. The parser uses a three-step approach:

1. **Definitive detection:** Scan the first 50 messages. If ANY message has a day value > 12 in the first position → the file uses DD/MM format. If ANY has a month value > 12 in the first position → it's MM/DD.
2. **Heuristic fallback:** If all values are ≤ 12 (fully ambiguous), check if sequential messages have incrementing values in position 1 that cross the 12 boundary — this reveals the date component.
3. **Configurable default:** If still ambiguous, fall back to a user-configurable default in `config.py` (`DATE_ORDER = "DMY"` by default, since most WhatsApp users globally use DD/MM). Log a warning.

### Timezone Limitation (NEW)

WhatsApp timestamps are in the phone's local timezone but the export includes NO timezone indicator. Consequences:

- If the user changes timezone between exports, the same message could parse to different absolute times.
- We store all timestamps as **naive local times** (no UTC conversion attempted).
- The state tracker compares timestamps only within the same chat file lineage, not across files from different devices.
- This limitation is documented in README.md and in CLI output on first run.

---

## 3. Local State Tracker Schema (SQLite)

### Database: `data/sync_state.db`

#### Table: `chats`

| Column | Type | Constraints | Purpose |
|---|---|---|---|
| `chat_id` | TEXT | PRIMARY KEY | Normalized chat name from filename (lowercase, spaces→underscores) |
| `display_name` | TEXT | NOT NULL | Original contact/group name as it appears in the export |
| `gmail_thread_id` | TEXT | NULLABLE | Gmail thread ID for this chat's thread (set after first push) |
| `gmail_label_id` | TEXT | NULLABLE | Gmail label ID (e.g., for `WhatsApp/John Doe`) |
| `source_filename` | TEXT | NOT NULL | Original filename this chat was first seen in |
| `created_at` | TEXT | NOT NULL | ISO 8601 timestamp when this chat was first synced |
| `updated_at` | TEXT | NOT NULL | ISO 8601 timestamp of last modification |

#### Table: `sync_runs`

| Column | Type | Constraints | Purpose |
|---|---|---|---|
| `run_id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Unique sync run identifier |
| `chat_id` | TEXT | FK → chats.chat_id | Which chat this run was for |
| `status` | TEXT | NOT NULL, CHECK IN ('pending','complete','failed') | **Sync run state for recovery (NEW)** |
| `last_synced_ts` | TEXT | NULLABLE | ISO 8601 of newest message in this run |
| `last_synced_hash` | TEXT | NULLABLE | SHA-256 hash of the last synced message |
| `messages_parsed` | INTEGER | DEFAULT 0 | Total messages parsed from file |
| `messages_synced` | INTEGER | DEFAULT 0 | Messages actually pushed to Gmail |
| `messages_skipped` | INTEGER | DEFAULT 0 | Messages skipped (dupes or system) |
| `error_message` | TEXT | NULLABLE | Error details if status = 'failed' |
| `started_at` | TEXT | NOT NULL | When this run started |
| `completed_at` | TEXT | NULLABLE | When this run finished (NULL if pending/failed) |

#### Table: `message_hashes`

| Column | Type | Constraints | Purpose |
|---|---|---|---|
| `hash` | TEXT | PRIMARY KEY | SHA-256 of (chat_id + timestamp_iso + sender + body) |
| `chat_id` | TEXT | FK → chats.chat_id | Which chat this message belongs to |
| `message_ts` | TEXT | NOT NULL | ISO 8601 parsed timestamp of the message |
| `run_id` | INTEGER | FK → sync_runs.run_id | Which sync run pushed this message |

### Deduplication Logic Flow

```
For each parsed message in a file:
  1. Compute hash = SHA-256(chat_id + timestamp_iso + sender + body)
  2. Query: does this hash exist in message_hashes?
     → Yes: skip (already synced)
     → No:  check if message_ts > last successful sync's last_synced_ts
            → Yes: add to sync batch
            → No:  skip (older than last sync, likely a re-export overlap)
  3. After successful Gmail push of the batch:
     a. Insert all new hashes into message_hashes
     b. Update sync_runs: status → 'complete', set last_synced_ts
     c. Move the .txt file from inbox/ to processed/
```

### Partial-Sync Recovery (NEW)

The `sync_runs.status` field enables crash recovery:

```
On script start:
  1. Check for any sync_runs with status = 'pending'
  2. For each pending run:
     a. The messages already in message_hashes for that run_id are confirmed pushed
     b. Re-parse the source file
     c. Skip messages whose hashes exist (already pushed before crash)
     d. Resume pushing only the remaining messages
     e. On success: status → 'complete'
     f. On failure: status → 'failed', log error, continue to next chat
```

This means a crash mid-sync never corrupts state — the next run picks up exactly where it left off.

---

## 4. Gmail Integration Strategy

### API Method: `gmail.users.messages.insert()` (NOT `send()`)

**Why insert over send:**

- `insert()` places messages directly into the mailbox without sending through SMTP
- No "sent mail" side effects, no forwarding rules triggered
- Doesn't count against Gmail's sending quotas (important for large initial syncs)
- More semantically correct — these are archived messages, not new outgoing mail

**Required OAuth2 Scope:** `https://www.googleapis.com/auth/gmail.insert` plus `https://www.googleapis.com/auth/gmail.labels` for label management.

### Label Hierarchy

```
WhatsApp/                      ← Parent label (auto-created)
├── WhatsApp/John Doe          ← One child label per contact
├── WhatsApp/Family Group      ← One child label per group
└── WhatsApp/Work Team         ← etc.
```

- Label names derived from the chat's `display_name`
- Characters not allowed in Gmail labels (`/` within the name portion, leading/trailing spaces) are sanitized
- Label IDs cached in `chats.gmail_label_id` to avoid repeated API lookups
- 225-character Gmail label name limit enforced with truncation + warning

### Threading Model

- First sync for a chat creates an anchor email (the thread root) with a generated `Message-ID`
- Subsequent sync batches reference the anchor via `In-Reply-To` and `References` headers
- The `gmail_thread_id` returned by the first insert is stored and reused
- All emails for a chat live under one Gmail thread

### Message Batching / Chunking (NEW)

Rather than one email per WhatsApp message (rate-limit suicide) or one giant email per sync (unreadable), messages are chunked:

- **Default chunk size:** 1 day of messages per email
- **Configurable via CLI:** `--chunk-size` flag accepts `day` (default), `hour`, `week`, or a number (messages per email)
- Each chunk becomes one email in the thread, with a subject like: `WhatsApp: John Doe — 2026-05-27`
- Within each email, messages are formatted as a readable text block:

```
[09:41] John: Hey, are you free today?
[09:42] You: Yeah, what's up?
[09:43] John: Let's grab lunch
        I know a great place nearby
```

### Rate Limit Handling

- Respect Gmail API quota: 250 units/second, `insert` costs 25 units
- Built-in exponential backoff on 429 (rate limit) and 5xx responses
- Default: 100ms pause between API calls, configurable in `config.py`

---

## 5. CLI Interface

### Entry Point: `cli.py`

```
Usage: python cli.py [command] [options]

Commands:
  sync        Run incremental sync for all files in inbox/
  status      Show sync state for all tracked chats
  reset       Reset sync state for a specific chat (re-sync from scratch)

Options:
  --dry-run           Parse and report what would be synced without touching Gmail
  --chunk-size SIZE   Messages per email: 'day' (default), 'hour', 'week', or integer
  --verbose / -v      Detailed logging output
  --chat NAME         Sync only the specified chat (by display name or chat_id)
```

### `--dry-run` Mode (NEW)

When `--dry-run` is active:

- Full parsing runs normally
- Dedup checks run against the state DB
- Output shows: files found, messages parsed, messages that would be synced, target labels
- **No Gmail API calls are made**
- **No state DB writes occur**
- **No files are moved to processed/**

This is critical for a tool that writes to your Gmail — always preview before pushing.

---

## 6. Dependencies

### `requirements.txt`

```
google-auth>=2.20.0
google-auth-oauthlib>=1.0.0
google-api-python-client>=2.90.0
python-dateutil>=2.8.2
```

### What's NOT included (and why)

- **Pandas:** Removed. No tabular data transformation needed. Parsed messages are represented as Python `dataclasses` — lighter, faster, no 30MB dependency.
- **requests:** Not needed. The Google API client handles HTTP internally.

### Standard Library (no install needed)

- `re` — regex for timestamp parsing
- `hashlib` — SHA-256 for message hashing
- `sqlite3` — state database
- `email` — MIME message construction for Gmail insert
- `argparse` — CLI argument parsing
- `pathlib` — cross-platform path handling
- `dataclasses` — structured message representation
- `logging` — configurable log output

---

## 7. Contact Name Change Handling

WhatsApp users change display names, and group members join/leave. The same contact might appear as "+91 98765 43210" in one export and "John" in a later one.

**v1 approach:** The **filename** is the canonical identity, not the sender names inside the file. `WhatsApp Chat with John Doe.txt` always maps to `chat_id = john_doe`, regardless of what sender names appear in the message lines. This keeps threading stable.

**Future enhancement (v2):** A contact alias table mapping phone numbers to display names.

---

## 8. Filename Parsing Strategy

WhatsApp export filenames follow predictable patterns by OS:

| OS | Pattern | Example |
|---|---|---|
| Android | `WhatsApp Chat with {Name}.txt` | `WhatsApp Chat with John Doe.txt` |
| iOS | `{Name}.txt` or `_chat.txt` | `John Doe.txt` |
| Android (group) | `WhatsApp Chat with {Group Name}.txt` | `WhatsApp Chat with Family Group.txt` |

### Extraction logic:

1. Strip the `.txt` extension
2. Remove the `WhatsApp Chat with ` prefix (case-insensitive) if present
3. Remaining string = `display_name`
4. Normalize to `chat_id`: lowercase, strip non-alphanumeric except spaces, replace spaces with underscores

### Collision handling:

If two files produce the same `chat_id`, the second file is treated as a re-export of the same chat (dedup handles the overlap).

---

---

## 9. GUI Layer (Phase 2)

### Framework: CustomTkinter + tkinterdnd2

CustomTkinter (built on tkinter) was chosen over PyQt/PySide6 and Dear PyGui for:
- No licensing concerns (MIT)
- Ships as a thin layer over the stdlib `tkinter` — minimal extra dependencies
- Drag-and-drop via `tkinterdnd2`
- Compatible with PyInstaller for Phase 3 packaging (see §10)

### New files added in Phase 2

```
wa-chat-sync/
├── gui.py               # Main window — entry point for GUI mode
├── gui_worker.py        # Background thread bridge to SyncManager
└── assets/
    └── icon.ico         # App icon (used by GUI and PyInstaller)
```

### GUI does NOT modify Phase 1 files

`SyncManager`, `GmailClient`, `state.py`, `parser.py`, `config.py`, and `cli.py` are untouched. The GUI wraps `SyncManager` exactly as the CLI does.

### Screen layout

Single window (~700×500 px), three horizontal bands:

```
┌─────────────────────────────────────────────────────────────────┐
│  HEADER BAR                                                     │
│  [Gmail icon]  WA Chat Sync          ● Connected  [Disconnect] │
├─────────────────────────────────────────────────────────────────┤
│  LEFT PANEL (~240px)          RIGHT PANEL (fills remaining)     │
│  ┌────────────────────┐       ┌─────────────────────────────┐  │
│  │ 🔍 Filter chats…  │       │  Drop .txt / .zip here      │  │
│  ├────────────────────┤       │  [Browse Files]  [Open Dir] │  │
│  │ ● John Doe  done  │       │  3 files in inbox            │  │
│  │ ● Family   done   │       └─────────────────────────────┘  │
│  │ ○ Work     pending│       OPTIONS ROW                       │
│  │ ✕ Old      failed │       [ ] Dry run  Chunk: [day▼]       │
│  └────────────────────┘                                        │
├─────────────────────────────────────────────────────────────────┤
│  FOOTER / PROGRESS                                              │
│  [▶ Sync Now]  ████████░░░░  67%   Syncing: John Doe…         │
│  [14:23] Pushed 142 messages · [14:23] Processing Family…      │
└─────────────────────────────────────────────────────────────────┘
```

### Threading model

`SyncManager.run()` executes in a `threading.Thread`. Progress is surfaced to the GUI via a `queue.Queue`; the main thread polls with `widget.after(100, ...)`. This keeps the UI responsive during long syncs.

### Auth status indicator

- Green dot + "Connected" = `auth/token.json` exists and is valid.
- Red dot + "Not connected" = missing or expired token.
- Clicking the indicator triggers the OAuth2 browser flow (calls `build_service()` from `gmail_client.py`).

---

## 9.5 HTML + Media Email Format (Phase 2.5)

### Goal

Replace the plain-text email body with a self-contained HTML rendering that visually mirrors a WhatsApp conversation — speech bubbles, inline images, attached media for non-image types. One email per chunk still represents one day (or hour / week / N-message bucket) of the chat.

### Visual specification

- **Theme:** WhatsApp Light — white page background (`#ECE5DD`), white incoming bubbles, light green outgoing bubbles (`#DCF8C6`), dark text. Renders cleanly in both light and dark Gmail themes.
- **Layout:** Incoming messages left-aligned, outgoing (sender == "You" / sender matches account owner heuristic) right-aligned.
- **Per-bubble:** Sender name (small, bold, above), message body (wrap), timestamp (small, bottom-right, gray).
- **Day separator:** Centered pill with the date at the top of each email.
- **Inline media:** Images / stickers / GIFs rendered inline via `cid:` references to MIME parts.
- **Non-image media (video / audio / document):** Rendered as a "card" bubble with a file-type icon, filename, and size. The file is attached to the email as a normal MIME part so the user can download it from Gmail.
- **Missing media:** If a referenced file isn't in the source ZIP, a placeholder bubble shows `[image not included — exported without media]`.

### New files

```
wa-chat-sync/
├── src/
│   ├── media_extractor.py    # Resolve attachment filename → bytes + mime_type
│   └── html_renderer.py      # Build HTML body + collect inline/attached parts
```

### Modified files (Phase 2.5)

| File | Change |
|---|---|
| `src/parser.py` | Recognize attachment patterns and capture the filename on `ParsedMessage.attachment_filename`. Stop filtering attachment lines as system messages. |
| `src/config.py` | New `ATTACHMENT_PATTERNS` regex list; `MAX_EMAIL_SIZE_BYTES` (default 20 MB to stay under Gmail's 25 MB cap); `HTML_THEME` constant. |
| `src/gmail_client.py` | Replace `MIMEText` with `MIMEMultipart("related")` containing the HTML body + inline `MIMEImage` parts referenced by CID, plus `MIMEMultipart("mixed")` wrapper when non-inline attachments are present. |
| `src/sync_manager.py` | Pass the source file path (ZIP or .txt) through to `push_chat` so the renderer can read media. |

### Attachment recognition patterns

```
Android — body suffix:  "<filename> (file attached)"
                        e.g. "IMG-20250314-WA0001.jpg (file attached)"
iOS     — body form:    "<attached: <filename>>"
                        e.g. "<attached: 00000123-PHOTO-2025-03-14-09-41-23.jpg>"
```

These were previously dropped by `SYSTEM_BODY_PHRASES` ("media omitted" etc.). Phase 2.5 distinguishes:
- "media omitted" → user exported without media → keep as text placeholder
- "<file> (file attached)" → media was exported → resolve from ZIP and embed

### Media resolution

`media_extractor.py` opens the source `.zip` once per chat sync and caches the namelist. Lookup is case-insensitive on basename. For `.txt`-only exports (rare), it falls back to a same-folder lookup.

### MIME structure per email

```
multipart/mixed                        ← only if any non-inline attachments
├── multipart/related
│   ├── text/html  (the body)
│   ├── image/jpeg  (Content-ID: <cid-1>)   ← inline images
│   └── image/png   (Content-ID: <cid-2>)
└── application/pdf  (Content-Disposition: attachment)   ← downloadable files
└── video/mp4        (Content-Disposition: attachment)
```

### Oversize chunk handling

A chunk's total payload size (HTML + all media) is measured before sending. If it would exceed `MAX_EMAIL_SIZE_BYTES`, the renderer sub-splits the chunk by message boundary into N parts and emits them as separate emails in the same thread. Subjects gain a `(Part k/N)` suffix.

### Backwards compatibility

Chats already synced under the plain-text format are not retroactively re-rendered. Users wanting the new format on an existing chat run `cli.py reset <chat>` then re-sync.

### Gmail rendering caveats addressed

- `<video>` and `<audio>` tags do not render reliably in Gmail's webmail. Phase 2.5 does NOT use them — those file types are attached, with a static card bubble in the body.
- Inline images use `cid:` references (RFC 2392), which Gmail supports.
- HTML/CSS uses only inline `style="…"` attributes — no `<style>` blocks (Gmail strips them in some contexts) and no external CSS.

---

## 10. PortableApps Packaging (Phase 3)

### Portability basis

The app is already portable-safe because `config.py` derives all paths from `PROJECT_ROOT = Path(__file__).parent.parent` — no hardcoded absolute paths, no registry writes, no writes to `AppData` or `%USERPROFILE%`. OAuth tokens are stored in `auth/token.json` inside the app folder.

### Packaging approach: PyInstaller → PortableApps launcher

```
Phase 3 build output:
  WAGmailSyncPortable/
  ├── App/
  │   ├── WAGmailSync/          ← PyInstaller --onedir output
  │   │   ├── WAGmailSync.exe
  │   │   ├── _internal/        ← bundled Python + all wheels
  │   │   └── ...
  │   └── AppInfo/
  │       ├── appinfo.ini       ← PA metadata (name, version, publisher)
  │       └── appicon.ico
  ├── Data/                     ← maps to auth/ and data/ at runtime
  └── WAGmailSyncPortable.exe   ← PortableApps launcher
```

### Path remapping at launch

The PortableApps launcher sets an env var (e.g. `WAGMAIL_ROOT`) pointing to the `App/WAGmailSync/` directory. `config.py` will be updated in Phase 3 to prefer `os.environ["WAGMAIL_ROOT"]` over `__file__`-relative detection when the env var is present. This allows the PyInstaller bundle (which freezes `__file__`) to still find the correct data directory.

### PyInstaller considerations

| Issue | Mitigation |
|---|---|
| `tkinterdnd2` native `.dll` not auto-collected | Add `--add-binary` in `.spec` file |
| Google API discovery JSON not bundled | Add `--add-data` for `googleapiclient/discovery_cache` |
| `customtkinter` theme assets not bundled | Add `--add-data` for the `customtkinter/` asset folder |
| Single-file (`--onefile`) vs folder (`--onedir`) | Use `--onedir` — faster startup, easier PortableApps pathing |

### New files added in Phase 3

```
wa-chat-sync/
├── wa-chat-sync.spec        # PyInstaller build spec
├── build_portable.ps1       # One-command build script (Windows)
└── portable/
    └── App/
        └── AppInfo/
            ├── appinfo.ini
            └── appicon.ico
```

---

## Implementation Phases

| Phase | Scope | Status |
|---|---|---|
| **Phase 1** | CLI: parser, state DB, Gmail client, sync orchestrator, `cli.py` | **Complete** |
| **Phase 2** | GUI: `gui.py`, `gui_worker.py`, drag-and-drop, progress, auth indicator | **Complete** |
| **Phase 2.5** | HTML + media email format: `html_renderer.py`, `media_extractor.py`, parser/gmail updates | In progress |
| **Phase 3** | PortableApps packaging: PyInstaller spec, launcher, `build_portable.ps1` | Planned |
