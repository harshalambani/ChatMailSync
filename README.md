# Chat Mail Sync

Sync exported WhatsApp `.txt` (or `.zip`) chats into your own mailbox. Each chat
becomes an email thread under a `WhatsApp/<Chat Name>` label/folder, with messages
rendered as a readable, WhatsApp-style HTML conversation (inline images, attached
media). Mail is delivered over IMAP with an app password, which works with any
provider — Gmail, Yahoo, iCloud, Fastmail, and more. A Gmail-only
Google sign-in path existed until v2.0.0; see
[Why Google sign-in was removed](#why-google-sign-in-was-removed).

Currently supports WhatsApp chat exports. The name does not box the app in —
other chat sources can be added without another rename.

It ships as an Android app (Kotlin/Compose, embedding the Python sync engine
in `src/`). A Windows desktop app existed alongside it and ended at v2.1.5
(tag `windows-final`); the repo is Android-only from here on.

> **Looking for the non-technical user guide?** See [docs/user-guide.md](docs/user-guide.md).

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
  second instance — another phone, or a reinstall that did not restore its data.
  The app can add mail but never remove it, so the cleanup is manual. Replacing
  an instance is fine — carry `sync_state.db` across.
- Files move from `inbox/` to `processed/` only after a fully successful sync.
- Every provider caps the size of a single message (25 MB at Gmail/Yahoo,
  20 MB at iCloud; RFC 7889 `APPENDLIMIT` is honoured when advertised, and a
  refusal in flight lowers the ceiling for the rest of the run). A chunk that
  would exceed it is split; MIME encoding inflates raw bytes by roughly ×1.37, so
  the projection is done on encoded size, not raw. One case cannot be split — a
  *single* media file larger than the cap on its own. That message is still
  archived, with a placeholder naming the file and its size in place of the
  media, and the file is reported in the sync summary under **media omitted**.
  The original stays in the WhatsApp export; nothing is lost, but it will never
  sync.
- An optional **watched folder** (`WatchFolderWorker.kt`) copies new exports
  into `inbox/` on its own. The scan is non-recursive, each source is imported
  once and ledgered by path, and the *synced-file policy* — leave, move to
  `synced/`, or delete — is applied only once delivery is confirmed, never at
  import time. It runs as a WorkManager job with a 15-minute floor.

For the full design — date-parsing engine, state schema, dedup logic, HTML/media
email format, and packaging — see
[Completed/2026-05-27-architecture.md](Completed/2026-05-27-architecture.md).

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
│   └── imap_credentials.json  # Written on first connect; no password stored here on Android
├── data/
│   ├── inbox/               # Drop zone: put exported .txt / .zip files here
│   ├── processed/           # Files land here after a successful sync
│   └── sync_state.db        # SQLite per-chat sync state
├── android/                 # Kotlin/Compose Android app
├── tools/                   # Dev/build helper scripts
├── tests/                   # Python test suite
├── docs/                    # User guide, release process, credential docs
├── requirements.txt
└── requirements-lock.txt    # Hash-pinned, reproducible install; used by CI
```

All runtime paths derive from `PROJECT_ROOT` in `src/config.py`
(`Path(__file__).parent.parent`). There are no hardcoded absolute paths.

---

## Setup from source

### 1. Prerequisites

- **Python 3.10 or later** (the code uses `X | None` type-union syntax).
- **Python 3.10 or later**, for running the Python test suite (the sync engine
  itself ships embedded in the Android app; you don't need Python to use the app).
- Android Studio / the Gradle wrapper, for building or testing the Android app.

### 2. Install dependencies

```
cd "<repo root>"
pip install -r requirements.txt
```

`requirements.txt` covers the sync engine (`python-dateutil`) and the test
suite. Everything else — IMAP, MIME, zip handling — is standard library.

`requirements.txt` is the human-edited source of truth. For a reproducible install,
use the hash-pinned lockfile instead:

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
then open **Settings › Mail account** in the app, pick your provider (which
fills in host and port), enter the address and the app password, and tap
**Connect**.

### 4. Running the tests

```
cd "<repo root>"
python -m pytest tests -q
```

```
cd "<repo root>\android"
.\gradlew.bat :app:testDebugUnitTest
```

> **Timezone note:** WhatsApp exports carry no timezone information; timestamps are
> stored as naive local times. If your phone's timezone changed between exports,
> some timestamps may appear shifted.

---

## Security and credential storage

The IMAP backend keeps its secrets in `auth/`, which is gitignored and never
travels with the code.

| Backend | Secret at rest | File |
|---|---|---|
| `imap` (the only backend) | App-specific password, **Android KeyStore** (not in the file) | `auth/imap_credentials.json` |

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
in the app explains why it is asking for an app password.

Putting it back is documented in [docs/RESTORING-OAUTH.md](docs/RESTORING-OAUTH.md).

### How the IMAP app password is protected

**Android: AndroidKeyStore, and no password in the file at all.**

The Android build never writes the password to `auth/imap_credentials.json`. It
stays on the Kotlin side, encrypted with an AndroidKeyStore AES/GCM key that
never leaves secure storage (`SecretStore.kt`), and is handed to the sync engine
only at call time. It is also never pre-filled back into the password field, so
it does not sit in Compose's unencrypted UI state.

**What that does and does not defend against.** Be clear-eyed about this:

- ✅ **Per-app sandboxing.** Android isolates the app's files from every other
  app on the device; nothing else installed can read `SecretStore.kt`'s
  KeyStore-backed ciphertext or the plaintext it protects.
- ✅ **The KeyStore key is not extractable.** The AES/GCM key backing the
  ciphertext never leaves secure hardware/OS-backed storage, so even a copy of
  the app's files does not hand over a usable key.
- ✅ **Offline disk reads.** Pulling the device's storage (a backup, a forensic
  image) yields ciphertext, not the password.
- ❌ **A compromised or rooted device.** Root access, or a device already
  compromised at the OS level, can defeat app sandboxing and the KeyStore
  guarantee alike. This is at-rest protection, not a defence against a device
  that is no longer trustworthy.

**Mitigations that are actually available to you.** Use an app-specific password,
never your account password — it reaches only the mail service rather than your
whole account, and it can be revoked at the provider without changing anything
else. Note what it is *not*: it is not scoped to a subset of mail operations.
See *How the write-only guarantee is enforced* below. Google sign-in, while it
lasted, was the stronger credential at rest — a refresh token is revocable and
genuinely scope-limited in a way a password is not — but the weekly expiry made
it unusable, so an app password with a small command surface is what is left.

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

- On Android, the password lives only in `SecretStore` (KeyStore-encrypted) —
  never in `auth/imap_credentials.json`, never in `.settings.json`, never to a
  log line, never echoed back into the UI, and never into an exception message
  (`_strip_secret` is a backstop over the transport's error text; the real
  control is not passing it to a log or UI call at all).
- IMAP connections use `ssl.create_default_context()`, so certificates and
  hostnames are verified, and carry a socket timeout. A failed verification
  refuses to send credentials rather than falling back to an unverified session.
- Credentials are persisted only *after* a login and a real `LIST` call succeed.

---

## Building the APK

```
cd "<repo root>\android"
.\gradlew.bat :app:assembleRelease
```

The output APK is at `android/app/build/outputs/apk/release/app-release.apk`.

---

## Installing on Android

**Xiaomi GetApps** carries the app, so on a Xiaomi, Redmi or POCO phone — or on
any other phone with GetApps installed — you can install and update it like any
other store app: [global.app.mi.com/details?id=com.chatmailsync.app](https://global.app.mi.com/details?id=com.chatmailsync.app).

Everywhere else, the release APK is published on the
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

The APK also embeds several third-party components under their own (non-GPL)
licences — Chaquopy, the CPython interpreter it bundles, python-dateutil, six,
AndroidX/Jetpack Compose, the Kotlin stdlib and kotlinx.coroutines. See
[NOTICE](NOTICE) for the full list and licence texts; the same content is
bundled into the app itself, reachable from Settings → Help & About →
Open-source licences.
