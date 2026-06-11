# WA Chat Sync to Gmail — Session Handoff
**Date:** 2026-05-31  
**Session scope:** Phase 2 (GUI), Phase 2.5 (HTML + media emails), testing & bug fixes  
**Next session:** Phase 3 (PortableApps packaging) + `setup_auth.py`

---

## Current project state

| Phase | Scope | Status |
|---|---|---|
| 1 | CLI core | ✅ Complete |
| 2 | Desktop GUI | ✅ Complete |
| 2.5 | HTML + media email format | ✅ Complete & live-tested |
| 3 | PortableApps packaging | ⏳ Next |

---

## What was built this session

### Phase 2 — GUI (`gui.py`, `gui_worker.py`)
- Framework: **CustomTkinter + tkinterdnd2** (dark mode, no licensing issues, PyInstaller-compatible)
- Single window: header (auth indicator), left panel (live chat list from DB), right panel (drag-and-drop zone + browse + options), footer (sync button + progress bar + scrolling log)
- `gui_worker.py` runs `SyncManager` in a daemon thread; bridges log output and per-file progress to the GUI via `queue.Queue`; GUI polls with `after(150ms)`
- Auth: silent token check on startup; Connect button triggers OAuth2 browser flow in a thread
- **Zero Phase 1 files modified**

### Phase 2.5 — HTML + media format
New files:
- `src/media_extractor.py` — opens export ZIP once, case-insensitive basename index, resolves attachment filenames to `(bytes, mime_type)`; falls back to sibling-folder for plain `.txt` exports
- `src/html_renderer.py` — WhatsApp Light theme, all inline `style=`, speech bubbles (incoming left / outgoing right), CID-inlined images, download cards for non-image media, missing-file placeholders; Windows-safe date formatting (`.strftime("%d %B %Y").lstrip("0")`)

Modified files:
- `src/config.py` — added `ATTACHMENT_PATTERNS` (2 regex strings), `MAX_EMAIL_SIZE_BYTES = 20 MiB`
- `src/parser.py` — `ParsedMessage` gains `attachment_filename: Optional[str]`; `_build_message` detects `(file attached)` / `<attached: …>` patterns; body kept verbatim for hash stability
- `src/gmail_client.py` — replaced plain-text MIME with `_build_html_mime_message` (multipart/related + optional multipart/mixed); added `_size_split_cached` + `_prepare_emails` for oversize-chunk auto-splitting; `push_chunks` and `push_chat` accept `source_path`
- `src/sync_manager.py` — passes `source_path=filepath` to `push_chat` in both `_sync_file` and `_recover_run`; `_move_to_processed` collision suffix changed from `_{run_id}` to `_dup_%Y%m%d_%H%M%S`

---

## Bugs found and fixed during testing

### 1. `--verbose` flag ordering
`-v` / `--verbose` is a global argparse flag — must precede the subcommand:  
✅ `python cli.py -v sync` ❌ `python cli.py sync --verbose`  
*(Not a code bug — just usage documentation.)*

### 2. `Part X/Y` subject suffix on all emails (not just oversize splits)
`_prepare_emails` was labelling every email when `total > 1`, including normal day-by-day chunks.  
**Fix:** Restructured `_prepare_emails` to only apply "Part k/N" within a single original chunk that was size-split — never across different time-period chunks.

### 3. Windows `%-d` strftime format
`"%-d %B %Y"` (remove zero-pad) is Linux/macOS only — crashes on Windows.  
**Fix:** `"%d %B %Y".lstrip("0")` in `html_renderer.py`.

### 4. `_move_to_processed` collision suffix using `run_id`
A file like `WhatsApp Chat with John Doe.zip` became `…John Doe_11.zip`. When moved back to inbox, the parser read `"John Doe_11"` as a new chat, creating a phantom Gmail label.  
**Fix:** Suffix changed to `_dup_%Y%m%d_%H%M%S` — unmistakably artificial, cannot be parsed as a contact name.

### 5. `reset` command doesn't move file back to inbox
`cli.py reset` clears DB state but leaves the source file in `processed/`. User must manually move it.  
**Status:** Spawned as a follow-up task (add a printed hint to `cmd_reset` if the file is found in processed/).

---

## Key learnings / gotchas

### Export file management
- WhatsApp can export the same chat multiple times. If two exports have the same filename, `_move_to_processed` collision-renames the second one. When bulk-moving `processed/*` back to `inbox/`, BOTH files return — the second now with an artificial suffix that looks like a real name.
- **Mitigation already applied:** timestamp suffix. But the real lesson: never do `Move-Item processed\* inbox\` without checking what's in there first. Always use `Get-ChildItem processed\` before moving.

### Recovery semantics
- The partial-sync recovery (`_recover_pending`) uses `get_hashes_for_run(run_id)` to find what was already pushed in an interrupted run. But hashes are only inserted **after** a full `push_chunks` call returns — so a `KeyboardInterrupt` mid-push leaves `already_pushed=0`, and the full chat re-syncs from scratch.
- This is safe (dedup prevents duplicates) but means very large chats (500+ emails) always restart from zero after a crash. A future improvement would be to insert hashes incrementally per email, not per chat.

### Gmail threading
- `gmail.users.messages.insert()` with `threadId` appends to an existing thread. A reset chat (cleared `gmail_thread_id`) creates a fresh thread on next sync — old emails in the previous thread are orphaned but not deleted. Users should manually delete old threads after a reset if they want a clean inbox.

### File extensions
- The test exports had no file extension at all (just `WhatsApp Chat with Jane Roe`, no `.zip`). `zipfile.is_zipfile()` correctly detects ZIPs by magic bytes regardless of extension. The inbox scanner accepts `suffix in (".txt", ".zip", "")` — the empty string covers extensionless ZIPs.

### CLI argument ordering
- Always `python cli.py [global flags] <command> [command flags]`
- Global flags: `-v` / `--verbose`
- Command flags: `--dry-run`, `--chunk-size`, `--chat`

---

## File inventory (complete)

```
wa-chat-sync/
├── src/
│   ├── __init__.py
│   ├── config.py           ← ATTACHMENT_PATTERNS, MAX_EMAIL_SIZE_BYTES added
│   ├── state.py            ← unchanged from Phase 1
│   ├── parser.py           ← ParsedMessage.attachment_filename added
│   ├── media_extractor.py  ← NEW (Phase 2.5)
│   ├── html_renderer.py    ← NEW (Phase 2.5)
│   ├── gmail_client.py     ← HTML MIME + size-splitting added
│   └── sync_manager.py     ← source_path pass-through + _dup timestamp fix
├── gui.py                  ← NEW (Phase 2)
├── gui_worker.py           ← NEW (Phase 2)
├── cli.py                  ← unchanged from Phase 1
├── auth/
│   ├── credentials.json    ← user-supplied
│   └── token.json          ← auto-generated after OAuth
├── data/
│   ├── inbox/              ← drop zone
│   ├── processed/          ← post-sync archive
│   └── sync_state.db       ← SQLite state
├── requirements.txt        ← customtkinter, tkinterdnd2 added
├── 2026-05-27-architecture.md  ← updated to v1.4 with §9, §9.5, §10
└── 2026-05-31-session-handoff.md  ← this file
```

---

## Next session — Phase 3 checklist

### 1. `setup_auth.py` (outstanding loose end)
Standalone OAuth2 initialisation script for headless / CLI-first use. Calls `build_service()` and prints confirmation. Simple — ~20 lines. Needed before packaging so first-time auth can be done without launching the GUI.

### 2. `src/config.py` — `WAGMAIL_ROOT` env var override
PyInstaller freezes `__file__`, so `PROJECT_ROOT = Path(__file__).parent.parent` points inside the binary. Fix: check `os.environ.get("WAGMAIL_ROOT")` first; fall back to `__file__`-relative only if not set. The PortableApps launcher sets `WAGMAIL_ROOT` to the `App/WAGmailSync/` directory at startup.

### 3. `wa-chat-sync.spec` — PyInstaller build spec
Known `--add-data` / `--add-binary` requirements:
- `tkinterdnd2` native `.dll` — needs `--add-binary`
- `customtkinter` theme assets — needs `--add-data` for the `customtkinter/` asset folder
- Google API discovery JSON cache — needs `--add-data` for `googleapiclient/discovery_cache`
- Use `--onedir` (not `--onefile`) — faster startup, easier PortableApps pathing

### 4. `build_portable.ps1`
One-command build script: runs PyInstaller, assembles PortableApps folder layout, produces a ready-to-distribute folder.

### 5. `portable/App/AppInfo/appinfo.ini`
PortableApps metadata: name, version, publisher, icon reference.

### Pending follow-up tasks (spawned as chips)
- **Add inbox-move reminder to `cli.py reset`** — if source file is in `processed/`, print a hint to move it back
- **`_move_to_processed` timestamp suffix** — already applied this session ✅

---

## Commands reference

```powershell
# Launch GUI
python gui.py

# CLI sync (dry run)
python cli.py -v sync --dry-run

# CLI sync (real)
python cli.py -v sync

# Check status
python cli.py status

# Reset a chat (clears DB; does NOT move file back to inbox)
python cli.py reset "Contact Name"

# Check inbox
Get-ChildItem "...\data\inbox\"

# Check processed
Get-ChildItem "...\data\processed\"

# Move processed back to inbox (carefully — check first!)
Move-Item "...\data\processed\<filename>" "...\data\inbox\"
```
