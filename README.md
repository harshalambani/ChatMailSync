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
email format, and packaging — see
[Completed/2026-05-27-architecture.md](Completed/2026-05-27-architecture.md).

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
cd "<repo root>"
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
cd "<repo root>"
python setup_auth.py
```

This opens a browser for consent and caches the token at `auth/token.json`.
Subsequent runs refresh it automatically. (The GUI can also trigger this flow via
its **Connect** button — `setup_auth.py` is just the headless equivalent.)

---

## Running the CLI

```
cd "<repo root>"
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
cd "<repo root>"
python gui.py
```

The window lets you connect to Gmail, drag-and-drop export files into the inbox,
choose chunk size / dry-run, run a sync with live progress, browse synced chats,
open a chat's Gmail thread, reset/re-sync, and export the chat list to CSV.
The **Help** button opens this project's user guide.

---

## Security and credential storage

Both mail backends keep their secrets in `auth/`, which is gitignored and never
travels with the code. What is stored, and how well it is protected, differs by
backend.

| Backend | Secret at rest | File |
|---|---|---|
| `imap` (default) | **App-specific password, in plaintext JSON** | `auth/imap_credentials.json` |
| `gmail_oauth` | OAuth refresh token | `auth/token.json` |

### Why IMAP is the default

Not because it is more secure — a scoped, revocable refresh token is the better
secret, and the section below is candid about the plaintext password. It is the
default because the OAuth path is **practically** limited:

The OAuth client stays in Google's **Testing** publishing status. Publishing it
would require Google's verification for the restricted `gmail.insert` scope,
which hinges on an annual paid CASA security assessment — not worth it for a
personal tool. Testing status imposes two hard limits Google does not let you
tune:

- Sign-in works **only for accounts explicitly listed as test users**, capped at
  100.
- **Every consent expires 7 days after it is granted**, refresh token included.
  This applies even if the client is configured for a 30- or 180-day token
  duration ([Google Cloud Console Help](https://support.google.com/cloud/answer/15549945?hl=en)).

So OAuth means reconnecting roughly weekly. IMAP has neither limit. `gmail_oauth`
remains fully supported and selectable in Settings, where choosing it shows a
notice explaining the above. **Existing users are not migrated:** a settings file
predating the backend setting is pinned back to `gmail_oauth` when an
`auth/token.json` is present (`config.resolve_mail_backend`).

### The IMAP app password is stored in plaintext

This is a deliberate, documented decision, not an oversight.

**Why not encrypt it.** The obvious Windows answers — DPAPI, Windows Credential
Manager, `keyring` — all bind the secret to one machine and one Windows profile.
That breaks the two things this app is built to be: a **PortableApps** bundle that
runs from a USB stick on any machine, and an app whose sync engine is intended to
run under **Chaquopy on Android**, where none of those APIs exist. Encrypting with
a key that ships next to the ciphertext would be obfuscation, not protection, and
would make the security posture harder to reason about rather than better.

**This matches how Android IMAP clients solve the same problem.** K-9 Mail /
Thunderbird for Android store account passwords base64-encoded inside the
`storeUri`/`transportUri` in app-private storage — encoding, not encryption;
[the request to move them into the Android KeyStore](https://github.com/k9mail/k-9/issues/1200)
was closed without being implemented. FairEmail states the position explicitly in
[its FAQ](https://github.com/M66B/FairEmail/blob/master/FAQ.md): because Android
already encrypts all user data, it deliberately does not add a keystore layer, and
it points users on shared devices at OS user profiles instead. The industry norm
for this exact problem is a plaintext credential in OS-protected private storage,
with the **operating system** as the control.

**What actually protects the file.** On Windows, `auth/` and the credentials file
both get an NTFS ACL stripped of inherited entries and granting only the current
user (`icacls /inheritance:r /grant:r <user>:F`). The directory is hardened
*before* the file is created, so the file is never briefly world-readable, and if
the ACL cannot be applied the password is **deleted and not saved**, with a loud
error — it is never silently left unprotected. On POSIX the file is created via
`os.open(..., 0o600)` so the mode applies at creation.

**What that does and does not defend against.** Be clear-eyed about this:

- ✅ Other **user accounts** on the same machine cannot read the file.
- ✅ Casual copying of the folder by another user, and inheritance from a
  permissive parent directory.
- ❌ **Other software running as you.** An NTFS ACL is a per-*user* boundary, not
  a per-*application* one. Windows has no app sandbox, so anything running in your
  session can read the file. This is where Android's guarantee is genuinely
  stronger than ours — its app-private storage isolates per app.
- ❌ **Anyone with the disk.** Unless BitLocker (or equivalent) is on — not
  guaranteed on Windows Home — the file is readable from another OS. FairEmail can
  rely on mandatory Android user-data encryption here; we cannot.
- ❌ **Backup and sync tools.** A USB bundle or a folder inside OneDrive/Dropbox
  carries the plaintext password with it. Keep the portable bundle off synced
  folders if that matters to you.

**Mitigations that are actually available to you.** Use an app-specific password,
never your account password — it is scoped to mail only and can be revoked at the
provider without touching your account. If you are willing to live with the
weekly reconnect described above, `gmail_oauth` is the stronger choice at rest: a
refresh token is revocable and scope-limited in a way a password is not. On a
shared machine, use separate Windows accounts.

### Other credential handling

- The password is written **only** to `auth/imap_credentials.json` — never to
  `.settings.json`, never to a log line, never echoed back into the UI, and never
  into an exception message (`_strip_secret` is a backstop over the transport's
  error text; the real control is not passing it to a log or UI call at all).
- IMAP connections use `ssl.create_default_context()`, so certificates and
  hostnames are verified, and carry a socket timeout. A failed verification
  refuses to send credentials rather than falling back to an unverified session.
- Credentials are persisted only *after* a login and a real `LIST` call succeed.

---

## Building the portable exe

The build uses PyInstaller (`--onedir`) and assembles a PortableApps-style layout.

```
cd "<repo root>"
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
