# Chat Mail Sync

Sync exported WhatsApp `.txt` (or `.zip`) chats into your own mailbox. Each chat
becomes an email thread under a `WhatsApp/<Chat Name>` label/folder, with messages
rendered as a readable, WhatsApp-style HTML conversation (inline images, attached
media). Mail is delivered over IMAP with an app password, which works with any
provider — Gmail, Outlook, Yahoo, iCloud, Fastmail, and more. A Gmail-only
Google sign-in path existed until v2.0.0; see
[Why Google sign-in was removed](#why-google-sign-in-was-removed).

Currently supports WhatsApp chat exports. The name does not box the app in —
other chat sources can be added without another rename.

It ships as a Windows desktop GUI (`gui.py`) and a command-line tool (`cli.py`).
A packaged, portable `.exe` build is also available (see [Building the portable exe](#building-the-portable-exe)).

> **Looking for the non-technical user guide?** See [docs/user-guide.md](docs/user-guide.md)
> or open `help.html` in a browser.

---

## Architecture at a glance

Messages flow from a drop folder, through a parser and deduplication layer, into
your mailbox over IMAP:

```
data/inbox/  →  parser  →  dedup (SQLite)  →  mail push (IMAP APPEND)  →  data/processed/
```

- Messages are added with `APPEND`, never `send()`. Nothing leaves your mailbox
  and no sending quota is consumed.
- Per-chat sync state lives in `data/sync_state.db`; re-running a sync only pushes
  new messages. **That state is per-instance, not per-mailbox** — it sits next to
  the instance that wrote it, and nothing about it reaches the mailbox. Use one
  instance per mailbox: any second instance pointed at the same account knows
  nothing about what the first sent and re-archives the same chats. That is *any*
  second instance — another PC, a phone, or a second copy of the portable app in
  another folder, since each copy carries its own `data/`. The app can add mail but
  never remove it, so the cleanup is manual. Replacing an instance is fine — carry
  `sync_state.db` across.
- Files move from `inbox/` to `processed/` only after a fully successful sync.
- Every provider caps the size of a single message (25 MB at Gmail/Outlook/Yahoo,
  20 MB at iCloud; RFC 7889 `APPENDLIMIT` is honoured when advertised, and a
  refusal in flight lowers the ceiling for the rest of the run). A chunk that
  would exceed it is split; MIME encoding inflates raw bytes by roughly ×1.37, so
  the projection is done on encoded size, not raw. One case cannot be split — a
  *single* media file larger than the cap on its own. That message is still
  archived, with a placeholder naming the file and its size in place of the
  media, and the file is reported in the sync summary under **media omitted** on
  both front-ends. The original stays in the WhatsApp export; nothing is lost,
  but it will never sync.
- An optional **watched folder** (`src/watch_folder.py` on Windows,
  `WatchFolderWorker.kt` on Android) copies new exports into `inbox/` on its own.
  The scan is non-recursive, each source is imported once and ledgered by path,
  and the *synced-file policy* — leave, move to `synced/`, or delete — is applied
  only once delivery is confirmed, never at import time. On Windows "delete"
  means the Recycle Bin, and refuses rather than falling back to a permanent
  delete. Windows polls from the GUI timer, so it only runs while the app is
  open; Android uses a WorkManager job with a 15-minute floor.

For the full design — date-parsing engine, state schema, dedup logic, HTML/media
email format, and packaging — see
[Completed/2026-05-27-architecture.md](Completed/2026-05-27-architecture.md).

There are two clients — Windows and Android — over this one shared core, and
they are kept **head to head in features**. Before adding anything user-visible,
read [PLATFORM-PARITY.md](PLATFORM-PARITY.md): it lists what is genuinely shared
(`src/`) and what has to be written twice (UI, settings storage, secret storage,
help text), which is most of a typical feature.

---

## Project structure

```
.
├── src/
│   ├── parser.py            # WhatsApp .txt parsing engine (timestamp formats, multi-line)
│   ├── mail_client.py       # IMAP transport (connect, append, folders, chunking)
│   ├── sync_manager.py      # Orchestrator: incremental sync, dedup, recovery
│   ├── state.py             # SQLite state tracker
│   ├── media_extractor.py   # Resolve attachment filename → bytes + mime type
│   ├── html_renderer.py     # Build HTML email body + inline/attached MIME parts
│   └── config.py            # Constants, paths, label naming, chunk defaults
├── auth/
│   └── imap_credentials.json  # Written on first connect; password DPAPI-encrypted
├── data/
│   ├── inbox/               # Drop zone: put exported .txt / .zip files here
│   ├── processed/           # Files land here after a successful sync
│   └── sync_state.db        # SQLite per-chat sync state
├── cli.py                   # Command-line entry point
├── gui.py                   # Desktop GUI entry point
├── gui_worker.py            # Background-thread bridge from GUI to SyncManager
├── requirements.txt
├── requirements-lock.txt    # Hash-pinned, reproducible install (used by build_portable.ps1)
├── chat-mail-sync.spec      # PyInstaller build spec
├── build_portable.ps1       # One-command portable build (Windows)
└── sign_exe.ps1             # Optional self-signed code-signing helper
```

All runtime paths derive from `PROJECT_ROOT` in `src/config.py`
(`Path(__file__).parent.parent`, or the `CHATMAILSYNC_ROOT` env var when set by
the portable launcher). There are no hardcoded absolute paths or registry writes.

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

`requirements.txt` covers both the CLI core (`python-dateutil`) and the GUI
(`customtkinter`, `tkinterdnd2`). Everything else — IMAP, MIME, zip
handling, DPAPI — is standard library. PyInstaller is only needed for building
the exe and is listed (commented) for dev use.

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

### 3. Connect a mailbox

There is nothing to obtain from a provider console. Create an app-specific
password at your mail provider (Gmail: <https://myaccount.google.com/apppasswords>),
then run the GUI:

```
cd "<repo root>"
python gui.py
```

Open **Settings › Mail account**, pick your provider (which fills in host and
port), enter the address and the app password, and click **Connect**. That
writes `auth/imap_credentials.json`, DPAPI-encrypted on Windows, and the CLI
uses it from then on.

There is deliberately no headless setup path: an app password can only come
from you, so `cli.py` refuses to invent one and points here instead.

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
| `--dry-run` | flag | off | Parse and report only — no mail calls, no state writes, no file moves. |
| `--chunk-size SIZE` | `day`, `hour`, `week`, or a positive integer | `day` | Messages per email. An integer means N messages per email. |
| `--chat NAME` | display name or `chat_id` | all chats | Sync only the matching chat. |

### `status` — show sync state

```
python cli.py status
```

Prints a table of tracked chats: status, last-synced time, messages synced, and
whether a mail thread exists.

### `reset` — re-sync a chat from scratch

```
python cli.py reset "John Doe"
python cli.py reset john_doe --yes
```

Takes a `chat_id` **or** display name. Clears local sync state so the next sync
rebuilds the chat into a new mail thread (emails already in your mailbox are untouched).
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

The window lets you connect your mailbox, drag-and-drop export files into the inbox,
choose chunk size / dry-run, run a sync with live progress, browse synced chats,
open a chat's mail thread, reset/re-sync, and export the chat list to CSV.
The **Help** button opens this project's user guide.

---

## Security and credential storage

Both mail backends keep their secrets in `auth/`, which is gitignored and never
travels with the code. What is stored, and how well it is protected, differs by
backend.

| Backend | Secret at rest | File |
|---|---|---|
| `imap` (the only backend) | App-specific password, **DPAPI-encrypted** on Windows | `auth/imap_credentials.json` |

On Android neither file holds a password at all — see *Android* below.

### Why Google sign-in was removed

Not because it was less secure — a scoped, revocable refresh token is still the
better *kind* of secret, whatever it is encrypted with. It went because it was
**practically** unusable:

The OAuth client never left Google's **Testing** publishing status. Publishing it
would require Google's verification for the restricted `gmail.insert` scope,
which hinges on an annual paid CASA security assessment — not worth it for a
personal tool. Testing status imposes two hard limits Google does not let you
tune:

- Sign-in works **only for accounts explicitly listed as test users**, capped at
  100.
- **Every consent expires 7 days after it is granted**, refresh token included.
  This applies even if the client is configured for a 30- or 180-day token
  duration ([Google Cloud Console Help](https://support.google.com/cloud/answer/15549945?hl=en)).

So Google sign-in meant reconnecting roughly weekly, for at most 100 people.
IMAP has neither limit.

It was **demoted** in v1.6.0 (hidden from anyone who had never used it) and
**removed** in v2.0.0. The trigger for removal was the Galaxy Store submission:
a live `GoogleAuthUtil` call forces a Google-account entry in the store's
data-safety declaration, and there is no "declared but dormant" category — so
the app would have had to declare a capability nobody could use.

**Anyone who was on it is told, once.** A saved `mail_backend` of
`gmail_oauth`, or a leftover `auth/token.json`, is evidence
(`config.is_legacy_oauth_user`); `config.resolve_mail_backend` hands back
`imap` rather than echoing a backend nothing can build, and a one-time notice
on each front-end explains why the app is asking for an app password.

Putting it back is documented in [docs/RESTORING-OAUTH.md](docs/RESTORING-OAUTH.md).

### How the IMAP app password is protected

Two independent layers, on both platforms.

**Windows: DPAPI encryption, on top of an NTFS ACL.**

The password is encrypted with Windows DPAPI (`CryptProtectData`, via
`src/secret_store.py`) *before* it reaches disk, and stored base64-encoded under
a `password_dpapi` key. DPAPI's key is derived from your Windows login through
the per-user master key, so the ciphertext is only meaningful to the same
Windows account on the same machine. Read the raw bytes anywhere else — another
OS, a restored backup, a VM snapshot, a forensic image — and you get nothing.

Underneath that, `auth/` and the credentials file both get an NTFS ACL stripped
of inherited entries and granting only the current user
(`icacls /inheritance:r /grant:r <user>:F`). The directory is hardened *before*
the file is created, so it is never briefly world-readable, and if the ACL
cannot be applied the password is **deleted and not saved**, with a loud error.
That ACL check is the fail-loud guarantee; DPAPI is defence in depth layered on
top of it. If DPAPI is ever unavailable (a locked-down Windows image with no
usable per-user profile), the save still succeeds with a plaintext `password`
key and a warning in the log, rather than taking away a working feature to
protect the layer that was never load-bearing.

Upgrading is automatic: a credentials file written by v0.2.1-beta or earlier
holds a plaintext `password`, and the first time a newer build reads it on a
DPAPI-capable machine it is silently re-saved encrypted. Nothing to do by hand.

On POSIX there is no DPAPI; the file is created via `os.open(..., 0o600)` so the
mode applies at creation.

**Android: AndroidKeyStore, and no password in the file at all.**

The Android build never writes the password to `auth/imap_credentials.json`. It
stays on the Kotlin side, encrypted with an AndroidKeyStore AES/GCM key that
never leaves secure storage (`SecretStore.kt`), and is handed to the sync engine
only at call time. It is also never pre-filled back into the password field, so
it does not sit in Compose's unencrypted UI state.

**What that does and does not defend against.** Be clear-eyed about this:

- ✅ Other **user accounts** on the same machine cannot read the file, and could
  not decrypt it even if they could.
- ✅ **Anyone with the disk.** An offline read — another OS, a pulled drive, a
  restored backup — yields ciphertext that DPAPI will not unwrap outside your
  account. This is the gap an ACL alone could never close, because an ACL is
  metadata the filesystem driver enforces, not a property of the bytes.
- ✅ **Backup and sync tools.** A copy inside OneDrive/Dropbox, or on a USB
  stick, carries only the ciphertext.
- ❌ **Other software running as you.** DPAPI binds to an *account*, not to an
  application, so anything in your Windows session can call `CryptUnprotectData`
  exactly as this app does. Windows has no app sandbox and nothing available to
  an unsigned portable app changes that. Android's per-app isolation is
  genuinely stronger here.
- ❌ **A compromised session generally.** This is at-rest protection, not a
  defence against malware already running as you.

**The portability cost, stated plainly.** DPAPI being per-user and per-machine
is the whole point, and it has a price: carry the portable bundle's `auth/`
folder to a different PC or a different Windows account and the saved password
**cannot be decrypted there**. The app says so explicitly and asks you to
re-enter it in Settings; the password itself is not lost, it is still valid at
your provider. Earlier versions of this document argued that this cost ruled
encryption out. That judgement was reversed: one re-entry after moving machines
is a small price for a credential that is useless to anyone reading the disk.

**Mitigations that are actually available to you.** Use an app-specific password,
never your account password — it reaches only the mail service rather than your
whole account, and it can be revoked at the provider without changing anything
else. Note what it is *not*: it is not scoped to a subset of mail operations.
See *How the write-only guarantee is enforced* below. Google sign-in, while it
lasted, was the stronger credential at rest — a refresh token is revocable and
genuinely scope-limited in a way a password is not — but the weekly expiry made
it unusable, so an app password with a small command surface is what is left.
On a shared machine, use separate Windows accounts.

### How the write-only guarantee is enforced

The app only ever adds mail — it does not read, delete, move or send. Since
v2.0.0 **the app's own code is what enforces that**, and it is worth stating
plainly rather than glossing: under the removed Google sign-in the scope was
checked server-side on every call, so the guarantee held even against a tampered
build of this app. It no longer does. What replaces it is a command surface
small enough to audit by eye.

An
app-specific password is a *bearer* credential — no provider lets you restrict
one to "append only". Anything holding it can, as far as the protocol is
concerned, read and delete freely. What backs the claim here is structural and
independently checkable: `ImapTransport` in `src/mail_client.py` issues exactly
four commands over its whole lifetime — `LIST`, `CREATE`, `SUBSCRIBE`, `APPEND`.
There is no `SELECT`, `FETCH`, `STORE`, `SEARCH`, `EXPUNGE`, `COPY` or `MOVE`
anywhere in the file, and without `SELECT` the connection never enters the IMAP
state in which a message can be read or flagged at all. The protocol itself
gates it; the source is public and it takes about a minute to verify.

So the guarantee is now a promise this code keeps, backed by a command surface
small enough to audit — not a promise the provider keeps on your behalf. That is
an honest description of the shipped behaviour, and the weaker of the two; it is
the price of a sign-in that does not expire every seven days.

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
| `.\build_portable.ps1` | Run PyInstaller, then assemble `dist\ChatMailSyncPortable\`. |
| `.\build_portable.ps1 -SkipBuild` | Re-assemble the layout only (skip PyInstaller). |
| `.\build_portable.ps1 -Sign` | Build, then code-sign the exe with a self-signed dev cert (`sign_exe.ps1`). |
| `.\build_portable.ps1 -Sign -InstallCert` | Also install the dev cert as trusted (run as admin). |

Build internals:

- `chat-mail-sync.spec` — PyInstaller spec. Bundles `customtkinter` and
  `googleapiclient` data files, the `tkinterdnd2` native DLL, and a few hidden
  imports PyInstaller's static analysis misses.
- The portable launcher (`ChatMailSyncPortable.exe`) sets `CHATMAILSYNC_ROOT` to the
  bundle's `Data\` folder so the frozen exe resolves `auth/` and `data/` correctly.
  It is the only variable honoured; the pre-rename `WAGMAIL_ROOT` fallback was
  removed on 2026-08-08.
- `Data\` is never wiped on rebuild, so saved credentials and synced state
  survive updates.
```

---

## Installing on Android

There is no store listing yet. The release APK is published on the
[Releases page](https://github.com/harshalambani/ChatMailSync/releases) and can be
installed directly, but a bare APK never tells you when it is out of date.

**Obtainium** solves that. It is a free, open-source app installer that tracks a
project's releases and offers you the update as soon as one is published — the part
of a store you actually want, without the store.

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) (itself available from
   F-Droid or its own releases page).
2. In Obtainium, tap **Add App** and paste
   `https://github.com/harshalambani/ChatMailSync`.
3. Install. Obtainium will notify you of every future release.

The APK is signed with the project's own release key. That key never changes, so
updates install cleanly over each other — but it also means an APK from anywhere
else will refuse to install over this one, which is the intended behaviour.

Requires 64-bit ARM (`arm64-v8a`). The build carries a Python runtime and ships no
other ABI, so it will not install on an emulator.

---

## Licence

Chat Mail Sync is free software under the **GNU General Public License, version 3**.
See [LICENSE](LICENSE) for the full text.

In short: you may use, study, modify and redistribute it. If you distribute a
modified version, that version must also be GPL-3.0 and its source must be
available.

That is a deliberate choice for an app of this kind rather than a default. Chat Mail
Sync asks for a mail password and reads an entire chat history, and the only real
reason to trust it is that you can read exactly what it does with both. A permissive
licence would allow someone to ship a closed, unverifiable fork under a similar name
and inherit that trust without earning it. Copyleft means every descendant of this
code stays as readable as this one.
