# WA Chat Sync to Gmail (Android) — App Definition

**Version:** 1.0 (2026-07-04)
**Applies to:** the planned Android app. The Windows app's behavior is documented in `README.md` / `docs/user-guide.md`.

---

## 1. Purpose

Turn exported WhatsApp chats into a permanent, searchable archive inside the user's own Gmail account.

Each chat becomes a Gmail thread under a `WhatsApp/<Chat Name>` label, with messages rendered as a readable, WhatsApp-style HTML conversation, including inline images and attached media. Because the archive lives in Gmail, it inherits Gmail's search, storage durability, cross-device access, and independence from the phone that created it.

One sentence for the future store listing: *"Archive your WhatsApp chats into your own Gmail — searchable, permanent, and private, with no third-party server in between."*

## 2. How it works (user's mental model)

1. In WhatsApp: open a chat → **⋮ → More → Export chat** (with or without media).
2. In the share sheet, pick **WA Chat Sync to Gmail** (or save the file and use the app's file picker).
3. In the app: tap **Sync**. The app parses the export, skips everything already synced, and inserts only new messages into Gmail under the chat's label.
4. Open Gmail anytime, on any device, and read or search the chat like email.

Repeat with a fresh export whenever more history should be archived — only the new messages are pushed (incremental sync with deduplication).

## 3. What the app CAN do

- Import WhatsApp `.txt` and `.zip` chat exports (individual and group chats), received via Android share sheet or picked from storage.
- Parse the many regional timestamp formats WhatsApp uses (ranked-pattern engine with per-file format lock-in).
- Render messages as WhatsApp-style HTML emails; attach or inline exported media (images, and other media as attachments).
- Insert messages into Gmail via `messages.insert` — mail appears *inside* the mailbox without being sent; no sending quota is used, no recipient exists.
- Organize chats under `WhatsApp/<Chat Name>` labels and keep each chat to its own thread(s), chunked by day/hour/week or N messages.
- Sync incrementally: re-importing an overlapping export pushes only new messages (SQLite state + content hashing). Dedup holds even across devices (phone + PC syncing the same chat won't double-post).
- Dry-run: parse and report what *would* be synced, with zero Gmail writes.
- Show sync history/status per chat; open a chat's Gmail thread; reset a chat's local sync state for a from-scratch re-sync.
- Work with the user's own Google Cloud OAuth consent — the token never leaves the device; scopes are minimal (`gmail.insert`, `gmail.labels`).

## 4. What the app CANNOT do (and will not claim to)

- **No live WhatsApp access.** It cannot read WhatsApp's database, receive messages in real time, or sync automatically in the background as chats happen. WhatsApp offers no public API for that; anything claiming otherwise violates WhatsApp ToS. Input is always a manual "Export chat" file.
- **No reading of the user's email.** Scopes allow inserting mail and managing labels only. It cannot read, search, modify, send, or delete existing mail.
- **No cloud middleman.** No server of ours ever sees chats, tokens, or metadata. Parsing happens on-device; the only network calls are device → Google.
- **No message editing/deleting in Gmail.** Resetting a chat clears *local* state only; already-inserted emails stay in Gmail (user deletes them in Gmail if desired).
- **No recovery of media WhatsApp didn't export.** "Without media" exports produce `<Media omitted>` placeholders; the app can't fetch what isn't in the zip.
- **No timezone reconstruction.** WhatsApp exports carry naive local timestamps; if the phone's timezone changed between exports, some times may appear shifted (same caveat as Windows).
- **No iOS version.** Out of scope.

## 5. Android vs Windows — behavioral differences

| Aspect | Windows | Android |
|---|---|---|
| Getting exports in | Drag-and-drop / copy into `data/inbox/` folder | Share sheet directly from WhatsApp, or SAF file picker; lands in app-private inbox |
| OAuth | Desktop flow: localhost redirect + browser; `credentials.json` file | Native flow: Custom Tab + Android OAuth client (no credentials file); token in encrypted storage |
| Long-running sync | GUI worker thread | WorkManager foreground job with notification; survives screen-off |
| Storage | Visible project `data/` folders | App-private storage (no permissions needed; removed on uninstall) |
| Gmail transport | Google API client library (moving to direct REST per plan A0) | Direct REST calls |
| Interface | Desktop GUI + CLI | Compose UI; no CLI |
| Everything else (parser, dedup, chunking, labels, HTML format, state schema) | **Identical — same shared code** | **Identical — same shared code** |

## 6. Privacy posture (feeds the future Play data-safety form)

- Chat content: processed on-device only; transmitted only to Google's Gmail API over TLS, into the user's own account.
- Data collected by the developer: none. No analytics in the personal-use phase (revisit explicitly if ads are activated — ads SDKs change this answer and the data-safety form).
- Credentials: OAuth tokens in EncryptedSharedPreferences; no passwords ever seen.
- Uninstall: removes all local data (inbox files, processed files, state DB, tokens); Gmail archive remains.
