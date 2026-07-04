# Help Documentation — Implementation Plan (Claude Code handoff)

**Created:** 2026-06-29
**Status:** Approved, ready to execute in Claude Code
**Owner:** Harshal

---

## 1. Context & current state

WA Chat Sync to Gmail is a Windows app (GUI `gui.py` + CLI `cli.py`) that syncs
exported WhatsApp `.txt` chats into Gmail. The app is feature-complete and a
packaged `.exe` exists in `dist/WAGmailSync/`.

**Problem:** There is **no user-facing help documentation**. Verified absent:

- No `README.md` (the architecture doc lists one in its planned structure, but it
  was never created).
- No `docs/`, no user manual, no FAQ, no in-app Help.
- Only existing docs are internal/developer-facing: `2026-05-27-architecture.md`
  and the `*-session-handoff.md` notes.

**Goal:** Produce three help deliverables — a developer README, an end-user guide,
and an in-app Help entry point.

---

## 2. Deliverables

### Deliverable 1 — Developer README
- **File:** `README.md` (repo root, Markdown)
- **Audience:** Someone working on the source.
- **Sections:**
  - Project summary (what the app does, in 2–3 sentences)
  - Architecture at a glance (link to `2026-05-27-architecture.md` for detail)
  - Project structure (`src/` modules, `auth/`, `data/inbox` → `data/processed`)
  - Setup from source: Python version, `pip install -r requirements.txt`,
    obtaining `auth/credentials.json` from Google Cloud Console, first-run OAuth
    via `setup_auth.py`
  - Running the CLI: `sync` (with `--dry-run`, `--chunk`, `--chat`), `status`,
    `reset` — pull exact flags from `cli.py`
  - Running the GUI (`python gui.py`)
  - Building the portable `.exe` (`build_portable.ps1`, `wa-chat-sync.spec`,
    `sign_exe.ps1`)
- **Source of truth:** Read `2026-05-27-architecture.md`, `cli.py`, and
  `requirements.txt` so commands/flags are accurate, not invented.

### Deliverable 2 — End-user guide
- **Files:** `docs/user-guide.md` (Markdown source) + `help.html` (standalone page)
- **Audience:** Non-technical person running the packaged app.
- **Sections (plain language, no jargon):**
  1. What this app does
  2. One-time setup: authorizing Google access
  3. Exporting a WhatsApp chat to a `.txt` file (Android + iOS)
  4. Adding chat files (the inbox folder)
  5. Running a sync from the GUI
  6. Where your messages appear in Gmail (labels/threads)
  7. Troubleshooting & FAQ (auth fails, file not detected, duplicate handling,
     re-syncing a chat with `reset`)
- **`help.html` requirements:** single self-contained file — inline CSS, no
  external assets, no CDN — so it opens offline and can ship next to the `.exe`.
  Generate it from `docs/user-guide.md` content (keep the two in sync).

### Deliverable 3 — In-app Help
- **File:** edit `gui.py` (the only existing-code change).
- **Change:** Add a "Help" button or menu item that opens `help.html` in the
  user's default browser (Python `webbrowser.open` on a path resolved relative to
  the app/exe location). Fall back to a simple in-app text dialog if the HTML file
  is missing.
- **Caution:** Show the diff before applying. Confirm the bundled-resource path
  works both when run from source and from the PyInstaller `.exe`
  (use the `sys._MEIPASS` / frozen-path pattern already used elsewhere in the app,
  if present — check `gui.py`/`config.py`).

---

## 3. Suggested execution sequence

1. Read `gui.py`, `cli.py`, `config.py`, and `2026-05-27-architecture.md` in full
   to ground all content in the real codebase.
2. Draft `README.md`.
3. Draft `docs/user-guide.md`.
4. Generate `help.html` from the user guide (self-contained).
5. Wire the Help button/menu into `gui.py` (show diff first).
6. Verify (see below).

## 4. Verification

- README commands/flags match `cli.py` exactly.
- `help.html` opens standalone in a browser with correct styling and no broken
  links/missing assets.
- GUI still launches; the Help button opens `help.html`; missing-file fallback works.
- If practical, confirm help path resolves inside the packaged `.exe`, not just
  from source.

## 5. Constraints (from CLAUDE.md)

- New files only here — but before editing the existing `gui.py`, show the diff and
  wait for confirmation.
- Keep all examples generic: **no real names, emails, or phone numbers** (this repo
  had a prior PII/secrets cleanup — do not reintroduce sensitive data).
- Use `YYYY-MM-DD-descriptive-name` for any additional new docs.
- At the end, list all files created/modified with their locations.

## 6. Files this plan will create/modify

- Create: `README.md`
- Create: `docs/user-guide.md`
- Create: `help.html`
- Modify: `gui.py` (Help button/menu — diff approval required)
