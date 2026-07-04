"""
GUI entry point for WA Chat Sync to Gmail.

Run with:
    python gui.py

Dependencies (in addition to Phase 1 requirements):
    pip install customtkinter tkinterdnd2
"""

import csv
import json
import os
import queue
import re
import shutil
import sys
import threading
import webbrowser
from datetime import datetime
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk
from tkinterdnd2 import DND_FILES, TkinterDnD

from gui_worker import SyncWorker, check_auth_status, connect_gmail
from src.config import (
    CREDENTIALS_FILE,
    DEFAULT_CHUNK_SIZE,
    INBOX_DIR,
    PROCESSED_DIR,
    STATE_DB_PATH,
    TOKEN_FILE,
)
from src.state import delete_chat, get_sync_summary, init_db, reset_chat

# ---------------------------------------------------------------------------
# Appearance
# ---------------------------------------------------------------------------

# Read saved theme preference (data/.theme) before any widgets are created.
_THEME_FILE = Path(__file__).parent / "data" / ".theme"
_saved_theme = "dark"
if _THEME_FILE.exists():
    _t = _THEME_FILE.read_text().strip()
    if _t in ("dark", "light"):
        _saved_theme = _t

ctk.set_appearance_mode(_saved_theme)
ctk.set_default_color_theme("blue")

_STATUS_COLOR = {
    "complete": "#2ecc71",
    "failed":   "#e74c3c",
    "pending":  "#f39c12",
    None:       "#7f8c8d",
}

_LOG_MAX_LINES    = 200
_POLL_MS          = 150    # queue poll interval (ms)
_AUTH_POLL_MS     = 250    # auth queue poll interval (ms)
_AUTO_REFRESH_MS  = 30_000 # inbox auto-refresh interval (ms) — overridden by settings

_SETTINGS_FILE = Path(__file__).parent / "data" / ".settings.json"
_AUTO_REFRESH_OPTIONS = {
    "Off":    0,
    "15 s":   15_000,
    "30 s":   30_000,
    "1 min":  60_000,
    "5 min":  300_000,
}
_DEFAULT_SETTINGS = {
    "chunk_size":          "day",
    "auto_refresh_label":  "30 s",   # key into _AUTO_REFRESH_OPTIONS
}


def _load_settings() -> dict:
    """Return settings dict, merging saved values over defaults."""
    settings = dict(_DEFAULT_SETTINGS)
    try:
        if _SETTINGS_FILE.exists():
            saved = json.loads(_SETTINGS_FILE.read_text())
            for k in _DEFAULT_SETTINGS:
                if k in saved:
                    settings[k] = saved[k]
    except Exception:
        pass
    return settings


def _save_settings(settings: dict) -> None:
    try:
        _SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _SETTINGS_FILE.write_text(json.dumps(settings, indent=2))
    except Exception:
        pass


def _help_html_path() -> "Path | None":
    """Locate help.html for both source runs and the frozen PyInstaller bundle."""
    candidates = []
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:                                   # PyInstaller onedir bundle
        candidates.append(Path(meipass) / "help.html")
        candidates.append(Path(sys.executable).parent / "help.html")
    candidates.append(Path(__file__).parent / "help.html")  # running from source
    for c in candidates:
        if c.exists():
            return c
    return None


# ---------------------------------------------------------------------------
# App — mixes CTk (customtkinter) with TkinterDnD drag-and-drop support
# ---------------------------------------------------------------------------

class App(ctk.CTk, TkinterDnD.DnDWrapper):

    def __init__(self) -> None:
        super().__init__()
        self.TkdndVersion = TkinterDnD._require(self)

        self.title("WA Chat Sync  →  Gmail")
        self.geometry("800x580")
        self.minsize(700, 500)

        # Ensure directories and DB exist.
        INBOX_DIR.mkdir(parents=True, exist_ok=True)
        PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
        init_db(STATE_DB_PATH)

        # Load persisted settings.
        _settings = _load_settings()
        self._settings: dict = _settings
        self._auto_refresh_ms: int = _AUTO_REFRESH_OPTIONS.get(
            _settings.get("auto_refresh_label", "30 s"), 30_000
        )

        # Runtime state.
        self._service       = None
        self._worker: SyncWorker | None = None
        self._log_lines: list[str] = []
        self._theme_mode    = _saved_theme

        # Build UI — footer must be packed before main so it pins to bottom.
        self._build_header()
        self._build_footer()
        self._build_main()

        # Apply saved settings to UI controls.
        self._chunk_var.set(_settings.get("chunk_size", "day"))

        # Initial data load.
        self._check_auth()
        self._refresh_chat_list()
        self._refresh_inbox_count()

        # Schedule periodic inbox refresh (0 = Off).
        if self._auto_refresh_ms > 0:
            self.after(self._auto_refresh_ms, self._auto_refresh_inbox)

    # ------------------------------------------------------------------
    # Header
    # ------------------------------------------------------------------

    def _build_header(self) -> None:
        hdr = ctk.CTkFrame(self, height=52, corner_radius=0)
        hdr.pack(fill="x", side="top")
        hdr.pack_propagate(False)

        ctk.CTkLabel(
            hdr,
            text="WA Chat Sync  →  Gmail",
            font=ctk.CTkFont(size=16, weight="bold"),
        ).pack(side="left", padx=16)

        # Auth section (right-aligned inside header).
        auth_frame = ctk.CTkFrame(hdr, fg_color="transparent")
        auth_frame.pack(side="right", padx=12)

        self._auth_dot = ctk.CTkLabel(
            auth_frame, text="●", text_color="#e74c3c",
            font=ctk.CTkFont(size=16), width=20,
        )
        self._auth_dot.pack(side="left", padx=(0, 4))

        self._auth_label = ctk.CTkLabel(
            auth_frame, text="Not connected",
            font=ctk.CTkFont(size=12),
        )
        self._auth_label.pack(side="left", padx=(0, 10))

        self._auth_btn = ctk.CTkButton(
            auth_frame, text="Connect", width=90, height=30,
            command=self._on_connect_click,
        )
        self._auth_btn.pack(side="left", padx=(0, 6))

        self._signout_btn = ctk.CTkButton(
            auth_frame, text="Sign Out", width=80, height=30,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            state="disabled",
            command=self._on_signout_click,
        )
        self._signout_btn.pack(side="left", padx=(0, 8))

        # Icon shows what mode you'll switch TO: ☀ = "go light", ☽ = "go dark"
        _theme_icon = "☽" if _saved_theme == "light" else "☀"
        self._theme_btn = ctk.CTkButton(
            auth_frame, text=_theme_icon, width=32, height=30,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            font=ctk.CTkFont(size=14),
            command=self._on_toggle_theme,
        )
        self._theme_btn.pack(side="left", padx=(0, 4))

        ctk.CTkButton(
            auth_frame, text="⚙", width=32, height=30,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            font=ctk.CTkFont(size=14),
            command=self._open_settings,
        ).pack(side="left")

        ctk.CTkButton(
            auth_frame, text="?", width=32, height=30,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            font=ctk.CTkFont(size=14, weight="bold"),
            command=self._open_help,
        ).pack(side="left", padx=(4, 0))

    # ------------------------------------------------------------------
    # Footer  (packed before main so it stays pinned to the bottom)
    # ------------------------------------------------------------------

    def _build_footer(self) -> None:
        footer = ctk.CTkFrame(self, corner_radius=0, height=198)
        footer.pack(fill="x", side="bottom", padx=8, pady=(4, 8))
        footer.pack_propagate(False)

        # ── Sync button + progress bar row ────────────────────────────
        ctrl = ctk.CTkFrame(footer, fg_color="transparent")
        ctrl.pack(fill="x", pady=(8, 4), padx=6)

        self._sync_btn = ctk.CTkButton(
            ctrl, text="▶  Sync Now", width=126, height=36,
            font=ctk.CTkFont(size=13, weight="bold"),
            command=self._on_sync_click,
        )
        self._sync_btn.pack(side="left", padx=(0, 6))

        self._stop_btn = ctk.CTkButton(
            ctrl, text="⏹  Stop", width=90, height=36,
            font=ctk.CTkFont(size=13),
            fg_color="#c0392b", hover_color="#922b21",
            state="disabled",
            command=self._on_stop_click,
        )
        self._stop_btn.pack(side="left", padx=(0, 10))

        self._progress_bar = ctk.CTkProgressBar(ctrl, height=14)
        self._progress_bar.pack(side="left", fill="x", expand=True)
        self._progress_bar.set(0)

        self._progress_label = ctk.CTkLabel(
            ctrl, text="", font=ctk.CTkFont(size=11),
            width=160, anchor="w",
        )
        self._progress_label.pack(side="left", padx=(8, 0))

        # ── Stats bar ─────────────────────────────────────────────────
        self._footer_stats_label = ctk.CTkLabel(
            footer, text="",
            font=ctk.CTkFont(size=11),
            text_color=("gray40", "gray60"),
            anchor="w",
        )
        self._footer_stats_label.pack(fill="x", padx=10, pady=(0, 2))

        # ── Log textbox ───────────────────────────────────────────────
        self._log_box = ctk.CTkTextbox(
            footer, height=118,
            font=ctk.CTkFont(family="Courier New", size=11),
            wrap="word",
        )
        self._log_box.pack(fill="x", padx=6, pady=(0, 6))
        self._log_box.configure(state="disabled")

    # ------------------------------------------------------------------
    # Main area (chat list | drop zone + options)
    # ------------------------------------------------------------------

    def _build_main(self) -> None:
        main = ctk.CTkFrame(self, corner_radius=0, fg_color="transparent")
        main.pack(fill="both", expand=True, padx=8, pady=(6, 0))

        self._build_chat_panel(main)
        self._build_right_panel(main)

    # ── Left panel: chat list ──────────────────────────────────────────

    def _build_chat_panel(self, parent: ctk.CTkFrame) -> None:
        left = ctk.CTkFrame(parent, width=236, corner_radius=8)
        left.pack(side="left", fill="y", padx=(0, 6))
        left.pack_propagate(False)

        # Filter entry + refresh button on the same row.
        filter_row = ctk.CTkFrame(left, fg_color="transparent")
        filter_row.pack(fill="x", padx=8, pady=(8, 4))

        self._filter_var = ctk.StringVar()
        self._filter_var.trace_add("write", lambda *_: self._refresh_chat_list())
        ctk.CTkEntry(
            filter_row, placeholder_text="Filter chats…",
            textvariable=self._filter_var, height=32,
        ).pack(side="left", fill="x", expand=True, padx=(0, 4))

        ctk.CTkButton(
            filter_row, text="⟳", width=32, height=32,
            font=ctk.CTkFont(size=15),
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            command=self._refresh_all,
        ).pack(side="left", padx=(0, 4))

        ctk.CTkButton(
            filter_row, text="CSV", width=38, height=32,
            font=ctk.CTkFont(size=10, weight="bold"),
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            command=self._on_export_csv_click,
        ).pack(side="left")

        self._chat_scroll = ctk.CTkScrollableFrame(left, label_text="Synced chats")
        self._chat_scroll.pack(fill="both", expand=True, padx=4, pady=(0, 6))

    # ── Right panel: drop zone + options ──────────────────────────────

    def _build_right_panel(self, parent: ctk.CTkFrame) -> None:
        right = ctk.CTkFrame(parent, corner_radius=0, fg_color="transparent")
        right.pack(side="left", fill="both", expand=True)

        # Drop zone.
        drop = ctk.CTkFrame(right, corner_radius=10, border_width=2, border_color="#3b82f6")
        drop.pack(fill="both", expand=True, pady=(0, 6))

        drop.drop_target_register(DND_FILES)
        drop.dnd_bind("<<Drop>>", self._on_files_dropped)

        ctk.CTkLabel(
            drop, text="⬇   Drop  .txt  or  .zip  export files here",
            font=ctk.CTkFont(size=13), text_color="#6b7280",
        ).pack(pady=(14, 4))

        btn_row = ctk.CTkFrame(drop, fg_color="transparent")
        btn_row.pack(pady=(0, 6))

        ctk.CTkButton(
            btn_row, text="Browse Files…", width=126, height=30,
            command=self._browse_files,
        ).pack(side="left", padx=6)

        ctk.CTkButton(
            btn_row, text="Open Inbox Folder", width=148, height=30,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            command=lambda: os.startfile(str(INBOX_DIR)),
        ).pack(side="left", padx=6)

        # File list — shows filenames currently sitting in the inbox folder.
        self._file_list_frame = ctk.CTkScrollableFrame(
            drop, label_text="Files in inbox", height=100,
        )
        self._file_list_frame.pack(fill="x", expand=False, padx=10, pady=(0, 6))

        self._inbox_label = ctk.CTkLabel(
            drop, text="", font=ctk.CTkFont(size=11), text_color="#6b7280",
        )
        self._inbox_label.pack(pady=(0, 10))

        # Options row.
        opts = ctk.CTkFrame(right, corner_radius=8, height=46)
        opts.pack(fill="x")
        opts.pack_propagate(False)

        self._dry_run_var = ctk.BooleanVar(value=False)
        ctk.CTkCheckBox(
            opts, text="Dry run (no Gmail writes)",
            variable=self._dry_run_var, height=28,
        ).pack(side="left", padx=14, pady=8)

        ctk.CTkLabel(opts, text="Chunk size:", font=ctk.CTkFont(size=12)).pack(side="left", padx=(20, 4))
        self._chunk_var = ctk.StringVar(value="day")
        ctk.CTkOptionMenu(
            opts, values=["day", "hour", "week"],
            variable=self._chunk_var, width=96, height=28,
        ).pack(side="left")

    # ------------------------------------------------------------------
    # Chat list
    # ------------------------------------------------------------------

    def _refresh_all(self) -> None:
        """Refresh both the chat list (from DB) and the inbox file count.

        Called by the ⟳ button so the user can pick up files added to
        the inbox folder after the app was already open.
        """
        self._refresh_chat_list()
        self._refresh_inbox_count()

    def _on_export_csv_click(self) -> None:
        """Export the chat sync summary to a CSV file chosen by the user."""
        try:
            rows = get_sync_summary(STATE_DB_PATH)
        except Exception as exc:
            messagebox.showerror("Export failed", str(exc))
            return

        if not rows:
            messagebox.showinfo("Export", "No chats to export yet.")
            return

        dest = filedialog.asksaveasfilename(
            title="Save chat list as CSV",
            defaultextension=".csv",
            filetypes=[("CSV files", "*.csv"), ("All files", "*.*")],
            initialfile="wa_chat_sync_export.csv",
        )
        if not dest:
            return  # user cancelled

        try:
            with open(dest, "w", newline="", encoding="utf-8") as fh:
                writer = csv.DictWriter(fh, fieldnames=[
                    "chat_name", "status", "last_synced", "messages_synced", "source_file",
                ])
                writer.writeheader()
                for r in rows:
                    writer.writerow({
                        "chat_name":       r.get("display_name", ""),
                        "status":          r.get("last_run_status") or "",
                        "last_synced":     r.get("last_run_at") or "",
                        "messages_synced": r.get("messages_synced") or 0,
                        "source_file":     r.get("source_filename") or "",
                    })
            self._append_log(f"Exported {len(rows)} chat(s) to {dest}")
        except Exception as exc:
            messagebox.showerror("Export failed", str(exc))

    def _auto_refresh_inbox(self) -> None:
        """Timer-based inbox refresh — fires every self._auto_refresh_ms when idle."""
        if self._worker is None:
            self._refresh_inbox_count()
        if self._auto_refresh_ms > 0:
            self.after(self._auto_refresh_ms, self._auto_refresh_inbox)

    def _refresh_chat_list(self) -> None:
        for w in self._chat_scroll.winfo_children():
            w.destroy()

        try:
            rows = get_sync_summary(STATE_DB_PATH)
        except Exception:
            rows = []

        filt = self._filter_var.get().strip().lower()
        if filt:
            rows = [r for r in rows if filt in r["display_name"].lower()]

        # Update footer stats using the full (unfiltered) summary.
        try:
            all_rows = get_sync_summary(STATE_DB_PATH)
        except Exception:
            all_rows = []
        total_chats = len(all_rows)
        total_msgs  = sum(r.get("messages_synced") or 0 for r in all_rows)

        # Most recent sync timestamp across all chats.
        last_sync_str = ""
        last_run_timestamps = [r.get("last_run_at") for r in all_rows if r.get("last_run_at")]
        if last_run_timestamps:
            try:
                latest = max(last_run_timestamps)
                dt = datetime.fromisoformat(latest)
                # Use dt.day (int) to avoid platform-specific %-d strftime flag.
                last_sync_str = f"  ·  last sync {dt.strftime('%b')} {dt.day}, {dt.strftime('%H:%M')}"
            except Exception:
                pass

        self._footer_stats_label.configure(
            text=f"{total_chats} chat{'s' if total_chats != 1 else ''}  ·  "
                 f"{total_msgs} message{'s' if total_msgs != 1 else ''} synced"
                 + last_sync_str
        )

        for row in rows:
            self._add_chat_row(row)

    def _add_chat_row(self, row: dict) -> None:
        status = row.get("last_run_status")
        color  = _STATUS_COLOR.get(status, _STATUS_COLOR[None])
        chat_id        = row["chat_id"]
        display_name   = row["display_name"]
        source_filename= row.get("source_filename", "")
        synced         = status is not None

        frame = ctk.CTkFrame(self._chat_scroll, corner_radius=6)
        frame.pack(fill="x", pady=2, padx=2)

        # ── Top row: status dot + name + action buttons ────────────────
        top = ctk.CTkFrame(frame, fg_color="transparent")
        top.pack(fill="x", padx=6, pady=(5, 1))

        ctk.CTkLabel(
            top, text="●", text_color=color,
            font=ctk.CTkFont(size=12), width=16,
        ).pack(side="left")

        ctk.CTkLabel(
            top, text=display_name,
            font=ctk.CTkFont(size=12, weight="bold"),
            anchor="w",
        ).pack(side="left", fill="x", expand=True, padx=(4, 4))

        # ↗ Open Gmail thread button (only when a thread exists).
        gmail_thread_id = row.get("gmail_thread_id")
        if gmail_thread_id:
            url = f"https://mail.google.com/mail/u/0/#all/{gmail_thread_id}"
            ctk.CTkButton(
                top, text="↗", width=22, height=20,
                font=ctk.CTkFont(size=11),
                fg_color="transparent", hover_color="#14532d",
                text_color="#6b7280",
                command=lambda u=url: webbrowser.open(u),
            ).pack(side="right", padx=(0, 2))

        # Resync button (only for chats that have been processed before).
        if synced:
            ctk.CTkButton(
                top, text="↺", width=22, height=20,
                font=ctk.CTkFont(size=11),
                fg_color="transparent", hover_color="#1e3a5f",
                text_color="#6b7280",
                command=lambda cid=chat_id, dn=display_name, sf=source_filename:
                    self._on_resync_chat(cid, dn, sf),
            ).pack(side="right", padx=(0, 2))

        # Delete button.
        ctk.CTkButton(
            top, text="✕", width=22, height=20,
            font=ctk.CTkFont(size=11),
            fg_color="transparent", hover_color="#7f1d1d",
            text_color="#6b7280",
            command=lambda cid=chat_id, dn=display_name, s=synced:
                self._on_delete_chat(cid, dn, s),
        ).pack(side="right", padx=(0, 2))

        # ── Bottom row: last-sync date, message count, status ──────────
        bot = ctk.CTkFrame(frame, fg_color="transparent")
        bot.pack(fill="x", padx=(24, 4), pady=(0, 5))

        parts: list[str] = []
        last_run_at = row.get("last_run_at")
        if last_run_at:
            try:
                dt = datetime.fromisoformat(last_run_at)
                parts.append(f"{dt.strftime('%b')} {dt.day}")
            except Exception:
                pass
        msgs = row.get("messages_synced") or 0
        if msgs:
            parts.append(f"{msgs} msgs")
        if status:
            parts.append(status)

        ctk.CTkLabel(
            bot,
            text="  ·  ".join(parts) if parts else "new",
            font=ctk.CTkFont(size=10), text_color="#6b7280", anchor="w",
        ).pack(side="left")

    # ------------------------------------------------------------------
    # Inbox / file handling
    # ------------------------------------------------------------------

    def _refresh_inbox_count(self) -> None:
        try:
            files = sorted(
                (f for f in INBOX_DIR.iterdir()
                 if f.is_file() and f.suffix in (".txt", ".zip", "")),
                key=lambda f: f.name.lower(),
            )
            n = len(files)
        except Exception:
            files = []
            n = 0

        # Update the count label.
        text = f"{n} file{'s' if n != 1 else ''} ready to sync" if n else "Inbox is empty — drop files above"
        self._inbox_label.configure(text=text)

        # Repopulate the file list.
        for w in self._file_list_frame.winfo_children():
            w.destroy()
        if not files:
            ctk.CTkLabel(
                self._file_list_frame,
                text="No files in inbox.",
                font=ctk.CTkFont(size=11), text_color="#6b7280",
            ).pack(anchor="w", padx=4, pady=2)
        else:
            for f in files:
                ctk.CTkLabel(
                    self._file_list_frame,
                    text=f.name,
                    font=ctk.CTkFont(size=11),
                    anchor="w",
                ).pack(fill="x", anchor="w", padx=4, pady=1)

    def _on_files_dropped(self, event) -> None:
        paths = self._parse_dnd_paths(event.data)
        self._copy_to_inbox(paths)

    @staticmethod
    def _parse_dnd_paths(raw: str) -> list[Path]:
        """Parse tkinterdnd2's brace-quoted path string into Path objects.

        On Windows, paths with spaces are wrapped in {braces}; plain paths
        are space-separated.
        """
        raw = raw.strip()
        if "{" in raw:
            return [Path(m.group(1)) for m in re.finditer(r"\{([^}]+)\}", raw)]
        return [Path(p) for p in raw.split()]

    def _browse_files(self) -> None:
        chosen = filedialog.askopenfilenames(
            title="Select WhatsApp export files",
            filetypes=[
                ("WhatsApp exports", "*.txt *.zip"),
                ("All files", "*.*"),
            ],
        )
        if chosen:
            self._copy_to_inbox([Path(f) for f in chosen])

    def _copy_to_inbox(self, paths: list[Path]) -> None:
        copied = 0
        skipped = 0
        for src in paths:
            if src.suffix.lower() not in (".txt", ".zip", ""):
                continue
            dest = INBOX_DIR / src.name
            if dest.exists():
                skipped += 1
                continue
            try:
                shutil.copy2(str(src), str(dest))
                copied += 1
            except Exception as exc:
                self._append_log(f"Could not copy {src.name}: {exc}")

        parts = []
        if copied:
            parts.append(f"Copied {copied} file{'s' if copied != 1 else ''} to inbox")
        if skipped:
            parts.append(f"{skipped} already present (skipped)")
        if parts:
            self._append_log(". ".join(parts) + ".")

        self._refresh_inbox_count()

    # ------------------------------------------------------------------
    # Sync
    # ------------------------------------------------------------------

    def _on_sync_click(self) -> None:
        if self._worker is not None:
            return  # already running

        dry_run    = self._dry_run_var.get()
        chunk_size = self._chunk_var.get()

        if not dry_run and self._service is None:
            self._append_log("Not connected to Gmail.  Connect first or enable Dry run.")
            return

        # Reset UI state.
        self._sync_btn.configure(state="disabled", text="Syncing…")
        self._stop_btn.configure(state="normal")
        self._progress_bar.set(0)
        self._progress_label.configure(text="Starting…")
        self._log_lines.clear()
        self._update_log_box()

        worker = SyncWorker(
            service      = self._service,
            chunk_size   = chunk_size,
            dry_run      = dry_run,
            db_path      = STATE_DB_PATH,
            inbox_dir    = INBOX_DIR,
            processed_dir= PROCESSED_DIR,
        )
        self._worker = worker
        worker.start()
        self.after(_POLL_MS, self._poll_sync_queue)

    def _poll_sync_queue(self) -> None:
        if self._worker is None:
            return
        try:
            while True:
                self._handle_sync_event(self._worker.q.get_nowait())
        except queue.Empty:
            pass
        if self._worker is not None:
            self.after(_POLL_MS, self._poll_sync_queue)

    def _handle_sync_event(self, event: dict) -> None:
        t = event["type"]

        if t == "log":
            self._append_log(event["msg"])

        elif t == "files_total":
            n = event["n"]
            if n == 0:
                self._progress_label.configure(text="Inbox is empty")

        elif t == "syncing":
            self._progress_label.configure(text=f"Syncing: {event['name']}")

        elif t == "file_done":
            done, total = event["done"], event["total"]
            if total:
                self._progress_bar.set(done / total)
            self._progress_label.configure(text=f"{done} / {total} files")

        elif t == "done":
            stats = event["stats"]
            stopped = event.get("stopped", False)
            self._progress_bar.set(1.0)
            summary = (
                f"Stopped — {stats.messages_synced} msg{'s' if stats.messages_synced != 1 else ''} synced"
                if stopped else
                f"Done — {stats.messages_synced} msg{'s' if stats.messages_synced != 1 else ''} synced"
            )
            self._progress_label.configure(text=summary)
            self._append_log("─" * 48)
            self._append_log(str(stats))
            self._sync_btn.configure(state="normal", text="▶  Sync Now")
            self._stop_btn.configure(state="disabled")
            self._worker = None
            self._refresh_chat_list()
            self._refresh_inbox_count()

        elif t == "error":
            self._append_log(f"ERROR: {event['msg']}")
            self._sync_btn.configure(state="normal", text="▶  Sync Now")
            self._stop_btn.configure(state="disabled")
            self._progress_label.configure(text="Failed — see log")
            self._worker = None
            self._refresh_chat_list()

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    def _check_auth(self) -> None:
        valid, text = check_auth_status()
        self._auth_dot.configure(text_color="#2ecc71" if valid else "#e74c3c")
        self._auth_label.configure(text=text)
        self._auth_btn.configure(text="Reconnect" if valid else "Connect")
        self._signout_btn.configure(state="normal" if valid else "disabled")
        if valid and self._service is None:
            threading.Thread(target=self._silent_build_service, daemon=True).start()

    def _silent_build_service(self) -> None:
        """Build the Gmail service object in the background after a valid token check."""
        try:
            from src.gmail_client import build_service
            self._service = build_service()
        except Exception:
            pass

    def _on_connect_click(self) -> None:
        self._auth_btn.configure(state="disabled", text="Connecting…")
        self._auth_label.configure(text="Opening browser…")
        auth_q: queue.Queue = queue.Queue()
        threading.Thread(target=connect_gmail, args=(auth_q,), daemon=True).start()
        self.after(_AUTH_POLL_MS, lambda: self._poll_auth_queue(auth_q))

    def _poll_auth_queue(self, auth_q: queue.Queue) -> None:
        try:
            event = auth_q.get_nowait()
        except queue.Empty:
            self.after(_AUTH_POLL_MS, lambda: self._poll_auth_queue(auth_q))
            return

        if event["type"] == "auth_ok":
            self._service = event["service"]
            self._auth_dot.configure(text_color="#2ecc71")
            self._auth_label.configure(text="Connected")
            self._auth_btn.configure(state="normal", text="Reconnect")
            self._signout_btn.configure(state="normal")
            self._append_log("Gmail authentication successful.")
        else:
            self._auth_dot.configure(text_color="#e74c3c")
            self._auth_label.configure(text="Auth failed")
            self._auth_btn.configure(state="normal", text="Connect")
            self._signout_btn.configure(state="disabled")
            self._append_log(f"Auth error: {event['msg']}")

    def _on_delete_chat(self, chat_id: str, display_name: str, synced: bool) -> None:
        """Remove a chat entry from the DB. Confirms first if it was ever synced."""
        if synced:
            ok = messagebox.askyesno(
                "Remove chat?",
                f"Remove '{display_name}' from the list?\n\n"
                "This only deletes the local record — emails already in Gmail are not affected.",
                icon="warning",
            )
            if not ok:
                return
        try:
            delete_chat(chat_id, STATE_DB_PATH)
            self._append_log(f"Removed '{display_name}' from chat list.")
        except Exception as exc:
            self._append_log(f"Could not remove '{display_name}': {exc}")
        self._refresh_chat_list()

    def _on_resync_chat(self, chat_id: str, display_name: str, source_filename: str) -> None:
        """Reset sync history for a chat and move its export file back to inbox."""
        ok = messagebox.askyesno(
            "Reset and re-sync?",
            f"Reset sync history for '{display_name}'?\n\n"
            "All local sync records will be cleared.\n"
            "Emails already in Gmail are not affected.",
            icon="warning",
        )
        if not ok:
            return
        try:
            reset_chat(chat_id, STATE_DB_PATH)
        except Exception as exc:
            self._append_log(f"Could not reset '{display_name}': {exc}")
            return

        # Move the export file back from processed/ to inbox/ if found.
        src = PROCESSED_DIR / source_filename
        if src.exists():
            dest = INBOX_DIR / source_filename
            if dest.exists():
                self._append_log(f"Reset '{display_name}'. Export file is already in inbox.")
            else:
                try:
                    shutil.move(str(src), str(dest))
                    self._append_log(f"Reset '{display_name}'. Export file moved back to inbox.")
                except Exception as exc:
                    self._append_log(f"Reset '{display_name}' (DB cleared) but could not move file: {exc}")
        else:
            self._append_log(
                f"Reset '{display_name}'. Drop the export file in inbox to re-sync."
            )
        self._refresh_chat_list()
        self._refresh_inbox_count()

    def _on_stop_click(self) -> None:
        if self._worker is None:
            return
        self._stop_btn.configure(state="disabled", text="Stopping…")
        self._progress_label.configure(text="Stopping after current file…")
        self._worker.stop()

    def _on_signout_click(self) -> None:
        """Revoke the OAuth2 token on Google's servers, then delete the local token.json."""
        import json as _json
        import urllib.parse
        import urllib.request

        # ── Step 1: revoke on Google's side ───────────────────────────────────
        if TOKEN_FILE.exists():
            try:
                token_data = _json.loads(TOKEN_FILE.read_text())
                # Prefer refresh_token (revokes entire grant); fall back to access token.
                revoke_token = token_data.get("refresh_token") or token_data.get("token")
                if revoke_token:
                    params = urllib.parse.urlencode({"token": revoke_token}).encode()
                    req = urllib.request.Request(
                        "https://oauth2.googleapis.com/revoke",
                        data=params,
                        headers={"Content-Type": "application/x-www-form-urlencoded"},
                        method="POST",
                    )
                    urllib.request.urlopen(req, timeout=10)
            except Exception as exc:
                # Network failure is non-fatal — we still delete the local file.
                self._append_log(f"Token revocation warning (continuing sign-out): {exc}")

        # ── Step 2: delete local token regardless of revocation outcome ────────
        try:
            if TOKEN_FILE.exists():
                TOKEN_FILE.unlink()
        except Exception as exc:
            self._append_log(f"Sign out error: {exc}")
            return

        self._service = None
        self._auth_dot.configure(text_color="#e74c3c")
        self._auth_label.configure(text="Not connected")
        self._auth_btn.configure(state="normal", text="Connect")
        self._signout_btn.configure(state="disabled")
        self._append_log("Signed out. Token revoked and deleted — connect again to re-authorise.")

    def _open_help(self) -> None:
        """Open help.html in the default browser; fall back to a brief dialog."""
        path = _help_html_path()
        if path is not None:
            webbrowser.open(path.as_uri())
            return
        messagebox.showinfo(
            "Help",
            "Quick start:\n\n"
            "1. Click Connect (top-right) and sign in to Google.\n"
            "2. Drag a WhatsApp .txt or .zip export onto the window.\n"
            "3. Click Sync Now.\n\n"
            "Your synced chats appear in Gmail under the WhatsApp label.\n\n"
            "(The full help file, help.html, was not found next to the app.)"
        )

    def _open_settings(self) -> None:
        """Open the settings modal. Only one instance allowed at a time."""
        if hasattr(self, "_settings_win") and self._settings_win.winfo_exists():
            self._settings_win.focus()
            return
        self._settings_win = _SettingsWindow(self)

    def _apply_settings(self, new_settings: dict) -> None:
        """Called by _SettingsWindow on Save."""
        old_refresh_ms = self._auto_refresh_ms
        self._settings = new_settings
        self._chunk_var.set(new_settings.get("chunk_size", "day"))
        self._auto_refresh_ms = _AUTO_REFRESH_OPTIONS.get(
            new_settings.get("auto_refresh_label", "30 s"), 30_000
        )
        _save_settings(new_settings)

        # Restart the auto-refresh timer if the interval changed.
        if self._auto_refresh_ms != old_refresh_ms and self._auto_refresh_ms > 0:
            self.after(self._auto_refresh_ms, self._auto_refresh_inbox)

    def _on_toggle_theme(self) -> None:
        new_mode = "light" if self._theme_mode == "dark" else "dark"
        self._theme_mode = new_mode
        try:
            _THEME_FILE.write_text(new_mode)
        except Exception:
            pass
        ctk.set_appearance_mode(new_mode)
        icon = "☽" if new_mode == "light" else "☀"
        self._theme_btn.configure(text=icon)
        # Rebuild dynamic sections so chat rows pick up the new theme colors.
        self._refresh_chat_list()
        self._refresh_inbox_count()

    # ------------------------------------------------------------------
    # Log helpers
    # ------------------------------------------------------------------

    def _append_log(self, msg: str) -> None:
        ts = datetime.now().strftime("%H:%M:%S")
        self._log_lines.append(f"[{ts}]  {msg}")
        if len(self._log_lines) > _LOG_MAX_LINES:
            self._log_lines = self._log_lines[-_LOG_MAX_LINES:]
        self._update_log_box()

    def _update_log_box(self) -> None:
        self._log_box.configure(state="normal")
        self._log_box.delete("1.0", "end")
        self._log_box.insert("end", "\n".join(self._log_lines))
        self._log_box.see("end")
        self._log_box.configure(state="disabled")


# ---------------------------------------------------------------------------
# Settings modal
# ---------------------------------------------------------------------------

class _SettingsWindow(ctk.CTkToplevel):
    """Modal settings panel — chunk size + auto-refresh interval."""

    def __init__(self, app: "App") -> None:
        super().__init__(app)
        self._app = app

        self.title("Settings")
        self.geometry("320x200")
        self.resizable(False, False)
        self.grab_set()          # modal: block input to main window
        self.lift()
        self.focus()

        pad = {"padx": 20, "pady": 8}

        # ── Chunk size ───────────────────────────────────────────────
        row1 = ctk.CTkFrame(self, fg_color="transparent")
        row1.pack(fill="x", **pad)
        ctk.CTkLabel(row1, text="Chunk size:", width=130, anchor="w").pack(side="left")
        self._chunk_var = ctk.StringVar(
            value=app._settings.get("chunk_size", "day")
        )
        ctk.CTkOptionMenu(
            row1, values=["day", "hour", "week"],
            variable=self._chunk_var, width=120, height=30,
        ).pack(side="left")

        # ── Auto-refresh interval ────────────────────────────────────
        row2 = ctk.CTkFrame(self, fg_color="transparent")
        row2.pack(fill="x", **pad)
        ctk.CTkLabel(row2, text="Auto-refresh:", width=130, anchor="w").pack(side="left")
        self._refresh_var = ctk.StringVar(
            value=app._settings.get("auto_refresh_label", "30 s")
        )
        ctk.CTkOptionMenu(
            row2, values=list(_AUTO_REFRESH_OPTIONS.keys()),
            variable=self._refresh_var, width=120, height=30,
        ).pack(side="left")

        # ── Buttons ──────────────────────────────────────────────────
        btn_row = ctk.CTkFrame(self, fg_color="transparent")
        btn_row.pack(fill="x", padx=20, pady=(12, 8))

        ctk.CTkButton(
            btn_row, text="Save", width=100, height=32,
            command=self._on_save,
        ).pack(side="right", padx=(6, 0))

        ctk.CTkButton(
            btn_row, text="Cancel", width=80, height=32,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            command=self.destroy,
        ).pack(side="right")

    def _on_save(self) -> None:
        new_settings = {
            "chunk_size":         self._chunk_var.get(),
            "auto_refresh_label": self._refresh_var.get(),
        }
        self._app._apply_settings(new_settings)
        self.destroy()


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
