# WA Chat Sync to Gmail

Sync exported WhatsApp `.txt` (or `.zip`) chats into your Gmail account. Each chat
becomes a Gmail thread under a `WhatsApp/<Chat Name>` label, with messages rendered
as a readable, WhatsApp-style HTML conversation (inline images, attached media).

It ships as a Windows desktop GUI (`gui.py`) and a command-line tool (`cli.py`).
A packaged, portable `.exe` build is also available (see [Building the portable exe](#building-the-portable-exe)).

> **Looking for the non-technical user guide?** See [docs/user-guide.md](docs/user-guide.md)
> or open `help.html` in a browser.

---

## Architecture at a glance

Messages flow from a drop folder, through a parser and deduplication layer, into
Gmail via the API:

```
data/inbox/  →  parser  →  dedup (SQLite)  →  Gmail insert  →  data/processed/
```

- Messages are pushed with `gmail.users.messages.insert()` (not `send()`), so
  nothing leaves your mailbox and no sending quota is consumed.
- Per-chat sync state lives in `data/sync_state.db`; re-running a sync only pushes
  new messages.
- Files move from `inbox/` to `processed/` only after a fully successful sync.

For the full design — date-parsing engine, state schema, dedup logic, HTML/media
email format, and packaging — see [2026-05-27-architecture.md](2026-05-27-architecture.md).

---

## Project structure

```
.
├── src/
│   ├── parser.py            # WhatsApp .txt parsing engine (timestamp formats, multi-line)
│   ├── gmail_client.py      # Gmail API wrapper (auth, insert, threads, labels)
│   ├── sync_manager.py      # Orchestrator: incremental sync, dedup, recovery
│   ├── state.py             # SQLite state tracker
│   ├── media_extractor.py   # Resolve attachment filename → bytes + mime type
│   ├── html_renderer.py     # Build HTML email body + inline/attached MIME parts
│   └── config.py            # Constants, paths, label naming, chunk defaults
├── auth/
│   ├── credentials.json     # You provide this (from Google Cloud Console)
│   └── token.json           # Auto-generated after first OAuth2 flow
├── data/
│   ├── inbox/               # Drop zone: put exported .txt / .zip files here
│   ├── processed/           # Files land here after a successful sync
│   └── sync_state.db        # SQLite per-chat sync state
├── cli.py                   # Command-line entry point
├── gui.py                   # Desktop GUI entry point
├── gui_worker.py            # Background-thread bridge from GUI to SyncManager
├── setup_auth.py            # One-time OAuth2 setup helper
├── requirements.txt
├── requirements-lock.txt    # Hash-pinned, reproducible install (used by build_portable.ps1)
├── wa-chat-sync.spec        # PyInstaller build spec
├── build_portable.ps1       # One-command portable build (Windows)
└── sign_exe.ps1             # Optional self-signed code-signing helper
```

All runtime paths derive from `PROJECT_ROOT` in `src/config.py`
(`Path(__file__).parent.parent`, or the `WAGMAIL_ROOT` env var when set by the
portable launcher). There are no hardcoded absolute paths or registry writes.

---

## Setup from source

### 1. Prerequisites

- **Python 3.10 or later** (the code uses `X | None` type-union syntax).
- Windows (drag-and-drop and the portable build target Windows; the CLI itself is
  cross-platform).

### 2. Install dependencies

```
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
pip install -r requirements.txt
```

`requirements.txt` covers both the CLI core (`google-auth`,
`google-auth-oauthlib`, `google-api-python-client`, `python-dateutil`) and the GUI
(`customtkinter`, `tkinterdnd2`). PyInstaller is only needed for building the exe
and is listed (commented) for dev use.

`requirements.txt` is the human-edited source of truth. For a reproducible install
(matching exactly what the portable build ships), use the hash-pinned lockfile
instead:

```
pip install --require-hashes -r requirements-lock.txt
```

Regenerate it after changing `requirements.txt` with:

```
pip install pip-tools
pip-compile --generate-hashes --output-file=requirements-lock.txt requirements.txt
```

### 3. Obtain `auth/credentials.json`

1. Go to <https://console.cloud.google.com/>.
2. Create a project (or select an existing one).
3. Enable the **Gmail API**.
4. Create **OAuth 2.0 credentials** of type **Desktop app**.
5. Download the JSON and save it as `auth/credentials.json` in the project root.

The app requests these scopes (see `src/config.py`):

- `https://www.googleapis.com/auth/gmail.insert`
- `https://www.googleapis.com/auth/gmail.labels`

### 4. First-run OAuth2

```
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
python setup_auth.py
```

This opens a browser for consent and caches the token at `auth/token.json`.
Subsequent runs refresh it automatically. (The GUI can also trigger this flow via
its **Connect** button — `setup_auth.py` is just the headless equivalent.)

---

## Running the CLI

```
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
python cli.py <command> [options]
```

Global option: `-v` / `--verbose` — debug-level logging.

### `sync` — sync everything in `inbox/`

```
python cli.py sync
python cli.py sync --dry-run
python cli.py sync --chunk-size hour
python cli.py sync --chat "John Doe"
python cli.py sync --dry-run --chunk-size week --chat john_doe -v
```

| Option | Values | Default | Meaning |
|---|---|---|---|
| `--dry-run` | flag | off | Parse and report only — no Gmail calls, no state writes, no file moves. |
| `--chunk-size SIZE` | `day`, `hour`, `week`, or a positive integer | `day` | Messages per email. An integer means N messages per email. |
| `--chat NAME` | display name or `chat_id` | all chats | Sync only the matching chat. |

### `status` — show sync state

```
python cli.py status
```

Prints a table of tracked chats: status, last-synced time, messages synced, and
whether a Gmail thread exists.

### `reset` — re-sync a chat from scratch

```
python cli.py reset "John Doe"
python cli.py reset john_doe --yes
```

Takes a `chat_id` **or** display name. Clears local sync state so the next sync
rebuilds the chat into a new Gmail thread (emails already in Gmail are untouched).
`-y` / `--yes` skips the confirmation prompt. After resetting, move the export file
from `processed/` back to `inbox/` to re-sync — the command prints the exact
`Move-Item` line.

> **Timezone note:** WhatsApp exports carry no timezone information; timestamps are
> stored as naive local times. If your phone's timezone changed between exports,
> some timestamps may appear shifted. The CLI prints this notice once on first sync.

---

## Running the GUI

```
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
python gui.py
```

The window lets you connect to Gmail, drag-and-drop export files into the inbox,
choose chunk size / dry-run, run a sync with live progress, browse synced chats,
open a chat's Gmail thread, reset/re-sync, and export the chat list to CSV.
The **Help** button opens this project's user guide.

---

## Building the portable exe

The build uses PyInstaller (`--onedir`) and assembles a PortableApps-style layout.

```
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
pip install pyinstaller
.\build_portable.ps1
```

| Invocation | Effect |
|---|---|
| `.\build_portable.ps1` | Run PyInstaller, then assemble `dist\WAGmailSyncPortable\`. |
| `.\build_portable.ps1 -SkipBuild` | Re-assemble the layout only (skip PyInstaller). |
| `.\build_portable.ps1 -Sign` | Build, then code-sign the exe with a self-signed dev cert (`sign_exe.ps1`). |
| `.\build_portable.ps1 -Sign -InstallCert` | Also install the dev cert as trusted (run as admin). |

Build internals:

- `wa-chat-sync.spec` — PyInstaller spec. Bundles `customtkinter` and
  `googleapiclient` data files, the `tkinterdnd2` native DLL, and a few hidden
  imports PyInstaller's static analysis misses.
- The portable launcher (`WAGmailSyncPortable.bat`) sets `WAGMAIL_ROOT` to the
  bundle's `Data\` folder so the frozen exe resolves `auth/` and `data/` correctly.
- `Data\` is never wiped on rebuild, so OAuth tokens and synced state survive
  updates. Place `credentials.json` in `Data\auth\` before first run.
```
