"""
GUI entry point for WA Mail Sync.

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

from gui_worker import (
    SyncWorker,
    check_auth_status,
    connect_gmail,
    connect_imap,
    resolve_imap_password,
)
from src.config import (
    CREDENTIALS_FILE,
    DEFAULT_CHUNK_SIZE,
    DEFAULT_MAIL_BACKEND,
    IMAP_CREDENTIALS_FILE,
    IMAP_PROVIDERS,
    INBOX_DIR,
    MAIL_BACKEND_GMAIL_OAUTH,
    MAIL_BACKEND_IMAP,
    PROCESSED_DIR,
    STATE_DB_PATH,
    TOKEN_FILE,
    is_gmail_mailbox,
    mailbox_clear_steps,
    resolve_mail_backend,
)
from src.mail_client import DiscoveryTransport, build_imap_transport, build_service
from src.mail_client import mailbox_folder_for
from src.state import (
    MailboxNotClearedError,
    count_archived_messages,
    delete_chat,
    get_sync_summary,
    init_db,
    reset_chat,
)

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
    "mail_backend":        DEFAULT_MAIL_BACKEND,
    "imap_provider":       "gmail",
    "imap_host":           "",
    "imap_port":           993,
    "imap_email":          "",
    # One-time "there's a new backend option" notice (Road B, phase 2). Never
    # a second file/key -- persisted in this same .settings.json. Password is
    # intentionally NOT in this dict; it only ever lives in
    # IMAP_CREDENTIALS_FILE (see gui_worker._save_imap_credentials).
    "backend_notice_shown": False,
}


def _load_settings() -> dict:
    """Return settings dict, merging saved values over defaults."""
    settings = dict(_DEFAULT_SETTINGS)
    saved = {}
    try:
        if _SETTINGS_FILE.exists():
            saved = json.loads(_SETTINGS_FILE.read_text())
            for k in _DEFAULT_SETTINGS:
                if k in saved:
                    settings[k] = saved[k]
    except Exception:
        pass
    # Resolved separately from the plain merge above because a settings file
    # written before mail_backend existed needs the token.json guard, not the
    # bare default. gui_worker calls the same helper on the same file.
    settings["mail_backend"] = resolve_mail_backend(saved)
    return settings


def _save_settings(settings: dict) -> None:
    try:
        _SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
        _SETTINGS_FILE.write_text(json.dumps(settings, indent=2))
    except Exception:
        pass


def _should_show_backend_notice(
    settings: dict, settings_file_exists: bool, token_file_exists: bool
) -> bool:
    """Decide whether to show the one-time "IMAP backend now available" notice.

    Per the human's explicit decision (Road B phase 2, §6a): existing users
    keep defaulting to gmail_oauth with zero behaviour change, but must see a
    one-time, purely informational notice pointing at the new option in
    Settings. A genuinely fresh install (no prior settings file, no prior
    token.json) must NOT see it -- there is nothing "new" relative to what it
    never had.

    Prior-state condition: settings_file_exists OR token_file_exists, checked
    against the raw pre-merge file state (not the post-merge settings dict,
    which always "exists" once defaults are applied). Either file alone is
    sufficient evidence of a prior install: a user could have a settings file
    without ever having connected, or (in principle) a token without a saved
    settings file.
    """
    if settings.get("backend_notice_shown", False):
        return False
    return settings_file_exists or token_file_exists


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

        self.title("WA Mail Sync")
        self.geometry("800x580")
        self.minsize(700, 500)

        # Ensure directories and DB exist.
        INBOX_DIR.mkdir(parents=True, exist_ok=True)
        PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
        init_db(STATE_DB_PATH)

        # Raw prior-state check, BEFORE loading settings (which always
        # "exists" once defaults are merged in) -- used only to decide
        # whether to show the one-time backend notice below.
        _had_settings_file = _SETTINGS_FILE.exists()
        _had_token_file = TOKEN_FILE.exists()

        # Load persisted settings.
        _settings = _load_settings()
        self._settings: dict = _settings
        self._auto_refresh_ms: int = _AUTO_REFRESH_OPTIONS.get(
            _settings.get("auto_refresh_label", "30 s"), 30_000
        )

        # Runtime state.
        self._transport     = None
        self._worker: SyncWorker | None = None
        self._log_lines: list[str] = []
        self._theme_mode    = _saved_theme

        # Build UI — footer must be packed before main so it pins to bottom.
        self._build_header()
        self._build_footer()
        self._build_main()

        # Apply saved settings to UI controls.
        self._chunk_var.set(_settings.get("chunk_size", "day"))
        self._update_signout_button_label()

        # Initial data load.
        self._check_auth()
        self._refresh_chat_list()
        self._refresh_inbox_count()
        self._maybe_show_backend_notice(_had_settings_file, _had_token_file)

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
            text="WA Mail Sync",
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
            opts, text="Dry run (no mailbox writes)",
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

        # ↗ Open Gmail thread button. Gated on the *backend*, not just on a
        # thread existing: under IMAP the stored gmail_thread_id column holds
        # the RFC 822 Message-ID we generated (that's what IMAP threads on via
        # References/In-Reply-To), so it is always populated and the mail.
        # google.com/#all/<id> deep link would always render — pointing at
        # Gmail for someone who archives to Outlook or Fastmail, and at a
        # thread id Gmail has never heard of. Hidden rather than disabled:
        # there is no cross-provider equivalent of this deep link, so on IMAP
        # there is nothing the user could do to enable it.
        gmail_thread_id = row.get("gmail_thread_id")
        if gmail_thread_id and self._settings.get("mail_backend") == MAIL_BACKEND_GMAIL_OAUTH:
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

        if not dry_run and self._transport is None:
            self._append_log("Not connected.  Connect first or enable Dry run.")
            return

        # Reset UI state.
        self._sync_btn.configure(state="disabled", text="Syncing…")
        self._stop_btn.configure(state="normal")
        self._progress_bar.set(0)
        self._progress_label.configure(text="Starting…")
        self._log_lines.clear()
        self._update_log_box()

        worker = SyncWorker(
            transport    = self._transport,
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
        if valid and self._transport is None:
            threading.Thread(target=self._silent_build_transport, daemon=True).start()

    def _silent_build_transport(self) -> None:
        """Build the transport object in the background after a valid auth-status check."""
        try:
            if self._settings.get("mail_backend") == MAIL_BACKEND_IMAP:
                if IMAP_CREDENTIALS_FILE.exists():
                    data = json.loads(IMAP_CREDENTIALS_FILE.read_text())
                    password = resolve_imap_password(data)
                    self._transport = build_imap_transport(
                        data["host"], data["port"], data["email"], password
                    )
            else:
                self._transport = DiscoveryTransport(build_service())
        except Exception:
            pass

    def _on_connect_click(self) -> None:
        if self._settings.get("mail_backend") == MAIL_BACKEND_IMAP:
            # IMAP connect is credential-entry based, not a browser flow --
            # route to Settings where the provider/host/email/password fields
            # live, rather than trying to run the OAuth dance.
            self._open_settings()
            return
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
            self._transport = event["transport"]
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
                "This only deletes the local record — emails already in your mailbox are not affected.",
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
        """Reset sync history for a chat and move its export file back to inbox.

        Gated, because this is the one action in the app that can duplicate
        mail. The old dialog said "emails already in your mailbox are not
        affected", which was true and badly misleading: they are not affected,
        which is exactly why re-syncing files a second copy of every one of
        them. The user has to clear the mailbox side by hand first - nothing
        here can do it for them, since the app never deletes mail.
        """
        archived = count_archived_messages(chat_id, STATE_DB_PATH)
        folder = mailbox_folder_for(display_name)

        noun = "message" if archived == 1 else "messages"

        if archived == 0:
            # Nothing has ever been sent for this chat, so there is nothing to
            # duplicate and no reason to make the user go and check.
            ok = messagebox.askyesno(
                "Reset this chat?",
                f"Reset sync history for '{display_name}'?\n\n"
                "Nothing has been archived for this chat yet, so no duplicate "
                "mail can result.\n\n"
                "A new mail thread is created the next time this chat is synced.",
                icon="warning",
                default=messagebox.NO,
            )
            if not ok:
                return
        else:
            # Gate 1 - the instruction. State the number and the exact folder.
            # Steps come from src.config so this and cli.py cannot drift into
            # giving different instructions for the same destructive action.
            steps = mailbox_clear_steps(folder, is_gmail_mailbox(self._settings))
            numbered = "\n".join(f"  {i}. {s}" for i, s in enumerate(steps, 1))
            ready = messagebox.askyesno(
                "Delete the old mail first",
                f"'{display_name}' already has {archived} {noun} archived in "
                f"your mailbox, in:\n\n    {folder}\n\n"
                "Resetting makes the app forget it sent them, so the next sync "
                "files a second copy. This app can never delete mail - only "
                "you can.\n\n"
                f"{numbered}\n\n"
                "Have you already deleted that mail?",
                icon="warning",
                default=messagebox.NO,
            )
            if not ready:
                self._append_log(
                    f"Reset cancelled for '{display_name}' - clear '{folder}' in your "
                    "mail client first, then reset."
                )
                return

            # Gate 2 - the commitment. Note it does NOT claim an immediate
            # re-archive: reset only clears local state and moves the export
            # back to the inbox, and it reaches this point only because the
            # user has just said the mailbox side is clear. Asserting duplicates
            # outright would contradict that answer; the risk belongs in the
            # conditional, where it is actually true.
            confirmed = messagebox.askyesno(
                "Confirm reset",
                f"You've said this folder is now empty:\n\n    {folder}\n\n"
                "Resetting clears the app's record of this chat. No mail is sent "
                f"now - the next sync re-archives all {archived} {noun} into a "
                "fresh thread.\n\n"
                "If any of the old mail is still there, that sync gives you a "
                "second copy of it, and only you can clean it up.",
                icon="warning",
                default=messagebox.NO,
            )
            if not confirmed:
                return

        try:
            # confirmed_mailbox_cleared is set only on the path where the user
            # answered both prompts; the archived == 0 path passes it because
            # there is provably nothing in the mailbox to clear.
            reset_chat(chat_id, STATE_DB_PATH, confirmed_mailbox_cleared=True)
        except MailboxNotClearedError as exc:
            self._append_log(f"Reset refused for '{display_name}': {exc}")
            return
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
        if self._settings.get("mail_backend") == MAIL_BACKEND_IMAP:
            self._on_forget_imap_password_click()
            return
        self._on_oauth_signout_click()

    def _on_oauth_signout_click(self) -> None:
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

        self._transport = None
        self._auth_dot.configure(text_color="#e74c3c")
        self._auth_label.configure(text="Not connected")
        self._auth_btn.configure(state="normal", text="Connect")
        self._signout_btn.configure(state="disabled")
        self._append_log("Signed out. Token revoked and deleted — connect again to re-authorise.")

    def _on_forget_imap_password_click(self) -> None:
        """Delete the saved IMAP app password locally. No network call.

        This does NOT revoke the app password at the provider -- an app
        password is a standalone credential that only the provider's own
        account-security page can revoke. The confirm dialog says so
        explicitly and points at where to do it, mirroring the existing
        destructive-action dialog pattern used by _on_delete_chat /
        _on_resync_chat (messagebox.askyesno with icon="warning").
        """
        ok = messagebox.askyesno(
            "Forget saved password?",
            "This removes the saved app password from this computer only.\n\n"
            "It does NOT revoke or delete the app password at your email "
            "provider — for Gmail, remove it under Google Account > Security > "
            "App passwords; for Outlook/Microsoft, under Security > Advanced "
            "security options. You'll need to generate a new one (or re-enter "
            "this one) to connect again.",
            icon="warning",
        )
        if not ok:
            return
        try:
            if IMAP_CREDENTIALS_FILE.exists():
                IMAP_CREDENTIALS_FILE.unlink()
        except Exception as exc:
            self._append_log(f"Could not forget saved password: {exc}")
            return

        self._transport = None
        self._auth_dot.configure(text_color="#e74c3c")
        self._auth_label.configure(text="Not connected")
        self._auth_btn.configure(state="normal", text="Connect")
        self._signout_btn.configure(state="disabled")
        self._append_log("Forgot saved app password. Connect again to reconnect.")

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
            "Your synced chats appear in your mailbox under the WhatsApp label.\n\n"
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
        old_backend = self._settings.get("mail_backend")
        self._settings = new_settings
        self._chunk_var.set(new_settings.get("chunk_size", "day"))
        self._auto_refresh_ms = _AUTO_REFRESH_OPTIONS.get(
            new_settings.get("auto_refresh_label", "30 s"), 30_000
        )
        _save_settings(new_settings)

        # Restart the auto-refresh timer if the interval changed.
        if self._auto_refresh_ms != old_refresh_ms and self._auto_refresh_ms > 0:
            self.after(self._auto_refresh_ms, self._auto_refresh_inbox)

        self._update_signout_button_label()
        if new_settings.get("mail_backend") != old_backend:
            # Switching backends invalidates whatever transport we had cached.
            self._transport = None
            self._check_auth()

    def _update_signout_button_label(self) -> None:
        """"Sign Out" (OAuth) vs "Forget saved password" (IMAP) -- wider button
        for the longer IMAP label so the text isn't clipped."""
        if self._settings.get("mail_backend") == MAIL_BACKEND_IMAP:
            self._signout_btn.configure(text="Forget saved password", width=170)
        else:
            self._signout_btn.configure(text="Sign Out", width=80)

    def _maybe_show_backend_notice(self, had_settings_file: bool, had_token_file: bool) -> None:
        """Show the one-time "IMAP backend now available" notice, if warranted.

        Informational only -- messagebox.showinfo, not askyesno/askquestion.
        Does not ask the user to pick a backend; dismissing it leaves
        mail_backend untouched (still gmail_oauth for anyone who had it
        before). See _should_show_backend_notice()'s docstring for the exact
        prior-state condition.
        """
        if not _should_show_backend_notice(self._settings, had_settings_file, had_token_file):
            return
        messagebox.showinfo(
            "New: IMAP / app-password option",
            "You can now optionally connect using an email provider's IMAP "
            "app password (Gmail, Outlook, Yahoo, iCloud, Fastmail, or a "
            "custom IMAP server) instead of signing in with Google.\n\n"
            "This is entirely optional -- you're still connected the same way "
            "as before, and nothing changes unless you choose to switch it "
            "in Settings (gear icon, top-right).",
        )
        self._settings["backend_notice_shown"] = True
        _save_settings(self._settings)

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

_BACKEND_LABELS = {
    MAIL_BACKEND_GMAIL_OAUTH: "Google sign-in (OAuth)",
    MAIL_BACKEND_IMAP:        "Email app password (IMAP)",
}
_BACKEND_LABELS_REV = {v: k for k, v in _BACKEND_LABELS.items()}

_PROVIDER_LABELS = {key: info["label"] for key, info in IMAP_PROVIDERS.items()}
_PROVIDER_LABELS_REV = {v: k for k, v in _PROVIDER_LABELS.items()}

# ---------------------------------------------------------------------------
# App-password help content -- ported from android/.../MailAccountScreen.kt
# (APP_PASSWORD_HELP_URLS / APP_PASSWORD_HELP_TEXT / APP_PASSWORD_STEPS_* /
# buildAppPasswordPrompt). Kept string-for-string identical to the Android
# copy so the two apps describe the same steps in the same words -- see
# PLATFORM-PARITY.md. Do not edit one side without the other.
# ---------------------------------------------------------------------------

# Official, human-verified "create an app password" pages, one per
# IMAP_PROVIDERS key. Verified by fetching each URL and confirming it is the
# provider's own current app-password help page -- do not swap in an
# unverified link, these go stale often as providers redesign support sites.
# "custom" has no entry: there's no provider to link to, so the UI falls back
# to generic guidance instead.
APP_PASSWORD_HELP_URLS = {
    "gmail": "https://support.google.com/accounts/answer/185833",
    "outlook": "https://support.microsoft.com/en-us/account-billing/using-app-passwords-with-apps-that-don-t-support-two-step-verification-5896ed9b-4263-e681-128a-a6f2979a7944",
    "yahoo": "https://help.yahoo.com/kb/SLN15241.html",
    "icloud": "https://support.apple.com/en-us/102654",
    "fastmail": "https://www.fastmail.help/hc/en-us/articles/360058752854-App-passwords",
}

APP_PASSWORD_HELP_TEXT = {
    "gmail": "Gmail app passwords are generated from your Google Account's security settings (requires 2-Step Verification to be on).",
    "outlook": "Outlook / Microsoft app passwords are generated from your Microsoft account's security settings (requires two-step verification to be on).",
    "yahoo": "Yahoo app passwords are generated from your Yahoo Account security page.",
    "icloud": "iCloud app-specific passwords are generated at appleid.apple.com, under Sign-In and Security.",
    "fastmail": "Fastmail app passwords are generated from Settings > Password & Security in your Fastmail account.",
}

# Bump this string (to the month/year you actually re-checked the steps
# below) any time APP_PASSWORD_STEPS_GMAIL or APP_PASSWORD_STEPS_OUTLOOK is
# edited. It's rendered next to the steps so a user whose provider has since
# changed its menus knows to trust the "Search for steps" / help-page button
# over this in-app text rather than assume the app is simply wrong.
APP_PASSWORD_STEPS_REVIEWED = "August 2026"

# Derived from support.google.com/accounts/answer/185833. That page does not
# itself enumerate numbered steps; it states the 2-Step Verification
# prerequisite and links myaccount.google.com/apppasswords as the place app
# passwords are created and managed. The steps below are written from those
# confirmed facts only -- nothing here is invented UI copy that wasn't on
# the page.
APP_PASSWORD_STEPS_GMAIL = [
    "Turn on 2-Step Verification for your Google Account first — the app password option stays hidden until it's on.",
    "Go to myaccount.google.com/apppasswords (in a browser) and sign in.",
    "Create a new app password there — Google gives you a 16-character code.",
    "Paste that 16-character code into the \"App password\" field below (not your normal Google password).",
]

# Derived from support.microsoft.com's "Using app passwords with apps that
# don't support two-step verification" page, which describes: two-step
# verification must be on; go to Advanced security options; scroll to the
# App passwords section; select the option to create one; use it wherever
# the app would normally ask for your Microsoft account password.
APP_PASSWORD_STEPS_OUTLOOK = [
    "Turn on two-step verification for your Microsoft account first — app passwords are only offered once it's on.",
    "Go to your Microsoft account's Advanced security options (account.microsoft.com) and sign in.",
    "Scroll to the \"App passwords\" section and choose to create one.",
    "Paste the generated app password into the \"App password\" field below (not your normal Microsoft password).",
    "If this is a work or school (Microsoft 365) account, see the note below — IMAP may be disabled by the admin regardless.",
]


def _build_app_password_prompt(provider_key: str, provider_label: str, host: str) -> str:
    """Builds the provider-specific question a user can copy into an AI
    assistant or paste into a web search to get current, provider-specific
    app-password steps. Deliberately takes only provider_key/provider_label/
    host -- the email address and app password must NEVER be interpolated
    into this string. It gets copied to the clipboard and/or opened in a
    browser search, both of which are effectively public once triggered, so
    leaking either credential here would be a real exposure, not a cosmetic
    one. If you're editing this function to add more context, keep that
    boundary -- provider name and host only. Mirrors Android's
    buildAppPasswordPrompt() in MailAccountScreen.kt; keep both in sync.
    """
    year = datetime.now().year
    if provider_key == "custom":
        provider_phrase = f"my email provider at {host}" if host.strip() else "my email provider"
    else:
        provider_phrase = provider_label
    return (
        f"How do I create an app password for {provider_phrase} in {year} to use with a third-party IMAP "
        "email app? Tell me whether I need to turn on two-factor authentication first, the exact page or "
        "menu path where I generate the app password, and the IMAP server name and port to use. Give me "
        "the current steps and link the official help page."
    )


class _SettingsWindow(ctk.CTkToplevel):
    """Modal settings panel — chunk size, auto-refresh interval, and mail
    backend (Gmail OAuth vs IMAP app password)."""

    def __init__(self, app: "App") -> None:
        super().__init__(app)
        self._app = app

        self.title("Settings")
        self.geometry("380x200")
        self.resizable(False, True)
        self.grab_set()          # modal: block input to main window
        self.lift()
        self.focus()

        pad = {"padx": 20, "pady": 8}
        settings = app._settings

        # ── Chunk size ───────────────────────────────────────────────
        row1 = ctk.CTkFrame(self, fg_color="transparent")
        row1.pack(fill="x", **pad)
        ctk.CTkLabel(row1, text="Chunk size:", width=130, anchor="w").pack(side="left")
        self._chunk_var = ctk.StringVar(value=settings.get("chunk_size", "day"))
        ctk.CTkOptionMenu(
            row1, values=["day", "hour", "week"],
            variable=self._chunk_var, width=120, height=30,
        ).pack(side="left")

        # ── Auto-refresh interval ────────────────────────────────────
        row2 = ctk.CTkFrame(self, fg_color="transparent")
        row2.pack(fill="x", **pad)
        ctk.CTkLabel(row2, text="Auto-refresh:", width=130, anchor="w").pack(side="left")
        self._refresh_var = ctk.StringVar(value=settings.get("auto_refresh_label", "30 s"))
        ctk.CTkOptionMenu(
            row2, values=list(_AUTO_REFRESH_OPTIONS.keys()),
            variable=self._refresh_var, width=120, height=30,
        ).pack(side="left")

        # ── Mail backend ─────────────────────────────────────────────
        row3 = ctk.CTkFrame(self, fg_color="transparent")
        row3.pack(fill="x", **pad)
        ctk.CTkLabel(row3, text="Connect via:", width=130, anchor="w").pack(side="left")
        current_backend = settings.get("mail_backend", DEFAULT_MAIL_BACKEND)
        self._backend_var = ctk.StringVar(
            value=_BACKEND_LABELS.get(current_backend, _BACKEND_LABELS[MAIL_BACKEND_GMAIL_OAUTH])
        )
        ctk.CTkOptionMenu(
            row3, values=list(_BACKEND_LABELS.values()),
            variable=self._backend_var, width=190, height=30,
            command=lambda _v: self._on_backend_changed(),
        ).pack(side="left")

        # ── IMAP fields (shown only when backend == imap) ──────────────
        self._imap_frame = ctk.CTkFrame(self, fg_color="transparent")

        prow = ctk.CTkFrame(self._imap_frame, fg_color="transparent")
        prow.pack(fill="x", padx=20, pady=(0, 8))
        ctk.CTkLabel(prow, text="Provider:", width=130, anchor="w").pack(side="left")
        current_provider = settings.get("imap_provider", "gmail")
        self._provider_var = ctk.StringVar(
            value=_PROVIDER_LABELS.get(current_provider, _PROVIDER_LABELS["gmail"])
        )
        ctk.CTkOptionMenu(
            prow, values=list(_PROVIDER_LABELS.values()),
            variable=self._provider_var, width=190, height=30,
            command=lambda _v: self._on_provider_changed(),
        ).pack(side="left")

        hrow = ctk.CTkFrame(self._imap_frame, fg_color="transparent")
        hrow.pack(fill="x", padx=20, pady=(0, 8))
        ctk.CTkLabel(hrow, text="Host:", width=130, anchor="w").pack(side="left")
        self._host_entry = ctk.CTkEntry(hrow, width=190, height=30)
        self._host_entry.insert(0, settings.get("imap_host", "") or "")
        self._host_entry.pack(side="left")

        prow2 = ctk.CTkFrame(self._imap_frame, fg_color="transparent")
        prow2.pack(fill="x", padx=20, pady=(0, 8))
        ctk.CTkLabel(prow2, text="Port:", width=130, anchor="w").pack(side="left")
        self._port_entry = ctk.CTkEntry(prow2, width=190, height=30)
        self._port_entry.insert(0, str(settings.get("imap_port", 993)))
        self._port_entry.pack(side="left")

        erow = ctk.CTkFrame(self._imap_frame, fg_color="transparent")
        erow.pack(fill="x", padx=20, pady=(0, 8))
        ctk.CTkLabel(erow, text="Email address:", width=130, anchor="w").pack(side="left")
        self._email_entry = ctk.CTkEntry(erow, width=190, height=30)
        self._email_entry.insert(0, settings.get("imap_email", "") or "")
        self._email_entry.pack(side="left")

        pwrow = ctk.CTkFrame(self._imap_frame, fg_color="transparent")
        pwrow.pack(fill="x", padx=20, pady=(0, 8))
        ctk.CTkLabel(pwrow, text="App password:", width=130, anchor="w").pack(side="left")
        self._password_entry = ctk.CTkEntry(pwrow, width=190, height=30, show="*")
        self._password_entry.pack(side="left")

        note_text = (
            "Leave blank to keep the currently saved password. "
            "The password is never shown or logged."
        )
        ctk.CTkLabel(
            self._imap_frame, text=note_text, wraplength=340,
            justify="left", text_color=("gray40", "gray60"), font=("", 11),
        ).pack(fill="x", padx=20, pady=(0, 4))

        self._status_label = ctk.CTkLabel(self._imap_frame, text="", text_color=("gray40", "gray60"))
        self._status_label.pack(fill="x", padx=20, pady=(0, 4))

        self._apply_host_field_state()
        if current_backend == MAIL_BACKEND_IMAP:
            self._imap_frame.pack(fill="x")

        # ── Buttons ──────────────────────────────────────────────────
        # Kept on self because _on_backend_changed re-packs _imap_frame
        # against it -- see the `before=` note there.
        btn_row = self._btn_row = ctk.CTkFrame(self, fg_color="transparent")
        btn_row.pack(fill="x", padx=20, pady=(12, 8))

        self._save_btn = ctk.CTkButton(
            btn_row, text="Save", width=100, height=32,
            command=self._on_save,
        )
        self._save_btn.pack(side="right", padx=(6, 0))

        ctk.CTkButton(
            btn_row, text="Cancel", width=80, height=32,
            fg_color="transparent", border_width=1,
            text_color=("gray10", "gray90"),
            command=self.destroy,
        ).pack(side="right")

        # ── App-password help (collapsed by default) ───────────────────
        # This used to sit inside _imap_frame, between the password field
        # and the Save/Cancel row -- and got rejected for exactly the
        # problem Android hit first: expanded, it runs to more text than
        # this whole window, and it pushed the one control every user
        # needs (Save) off the bottom. It now lives below btn_row instead,
        # behind a toggle, mirroring where Android ended up after the same
        # correction. See _on_backend_changed for how it shows/hides with
        # _imap_frame, and _toggle_help/_sync_window_height for how the
        # window grows to fit it without ever hiding Save/Cancel.
        self._help_expanded = False
        self._help_container = ctk.CTkFrame(self, fg_color="transparent")

        self._help_toggle_btn = ctk.CTkButton(
            self._help_container, text="Not sure how to get an app password?",
            fg_color="transparent", hover_color=("gray85", "gray25"),
            text_color=("gray10", "gray90"), font=("", 11),
            anchor="w", height=24,
            command=self._toggle_help,
        )
        self._help_toggle_btn.pack(fill="x", padx=20, pady=(0, 4))

        # Content frame -- left unpacked (collapsed) until _toggle_help
        # packs it; its children are rebuilt by _render_help_content each
        # time it's shown or the provider changes, since the steps/notes/
        # links are all provider-specific.
        self._help_frame = ctk.CTkFrame(self._help_container, fg_color="transparent")

        if current_backend == MAIL_BACKEND_IMAP:
            self._help_container.pack(fill="x")

        # The 380x200 above is only right for the OAuth backend, where the
        # IMAP form is hidden. IMAP is the default now, so without this the
        # window opens clipped at the Host field with Save below the bottom
        # edge -- the same "Save is off-screen" failure that got the help
        # block moved down here in the first place.
        self._sync_window_height()

    # ------------------------------------------------------------------
    # IMAP field show/hide + provider-driven host/port autofill
    # ------------------------------------------------------------------

    def _on_backend_changed(self) -> None:
        if self._backend_var.get() == _BACKEND_LABELS[MAIL_BACKEND_IMAP]:
            # before=self._btn_row is load-bearing, not tidiness: pack_forget
            # drops a widget out of the packing order entirely, and a later
            # bare pack() *appends* it. So OAuth -> IMAP -> back would have
            # re-packed the IMAP fields below Save/Cancel, leaving the form
            # underneath its own buttons. The help block genuinely belongs
            # last, so it can pack bare.
            self._imap_frame.pack(fill="x", before=self._btn_row)
            self._help_container.pack(fill="x")
        else:
            self._imap_frame.pack_forget()
            self._help_container.pack_forget()
            self._warn_oauth_is_limited()
        self._sync_window_height()

    def _warn_oauth_is_limited(self) -> None:
        """Warn, once per Settings window, that the OAuth path is limited.

        The Google Cloud project this app's OAuth client lives in is in
        "Testing" publishing status and is staying there: publishing it would
        require Google's verification for the restricted gmail.insert scope,
        which now hinges on an annual paid CASA security assessment. Testing
        status has two consequences a user will otherwise hit as unexplained
        breakage -- sign-in only works for accounts pre-listed as test users
        (100 max), and Google expires every consent, refresh token included,
        7 days after it is granted. That 7-day expiry is a property of Testing
        status itself: it applies even when the client is configured for a 30-
        or 180-day token duration, so it cannot be tuned away.

        Fires only when the user actively picks OAuth from the dropdown (the
        OptionMenu command does not fire on initial render), and is not
        blocking -- OAuth remains fully supported and selectable, and anyone
        already signed in is unaffected.
        """
        if getattr(self, "_oauth_warning_shown", False):
            return
        self._oauth_warning_shown = True
        messagebox.showinfo(
            "Google sign-in is limited",
            "Google sign-in still works, but this app has not completed "
            "Google's app verification, so it stays in Google's \"Testing\" "
            "mode. That means:\n\n"
            "  -  Only accounts added as test users in the Google Cloud "
            "project can sign in (100 maximum).\n"
            "  -  Google expires the sign-in about every 7 days, so you will "
            "have to reconnect roughly weekly.\n\n"
            "The 7-day limit is set by Google for unverified apps and cannot "
            "be extended from here.\n\n"
            "If you would rather not deal with that, choose \"Email app "
            "password (IMAP)\" instead -- it has neither limit.",
            parent=self,
        )

    def _on_provider_changed(self) -> None:
        self._apply_host_field_state()
        if self._help_expanded:
            # Steps/notes/links are all keyed off the provider, so re-render
            # rather than leaving the previous provider's help on screen.
            self._render_help_content()
            self._sync_window_height()

    def _apply_host_field_state(self) -> None:
        provider_key = _PROVIDER_LABELS_REV.get(self._provider_var.get(), "gmail")
        info = IMAP_PROVIDERS.get(provider_key, IMAP_PROVIDERS["custom"])
        if provider_key == "custom":
            self._host_entry.configure(state="normal")
        else:
            # state="normal" first is required, not defensive: a disabled Tk
            # entry silently drops delete/insert. Coming from another
            # non-custom provider the field is already disabled, so this used
            # to no-op and leave the previous provider's host in place --
            # every non-Gmail user got imap.gmail.com, and _on_save reads
            # straight from this widget, so the wrong host was saved too.
            self._host_entry.configure(state="normal")
            self._host_entry.delete(0, "end")
            self._host_entry.insert(0, info["host"] or "")
            self._host_entry.configure(state="disabled")
            self._port_entry.delete(0, "end")
            self._port_entry.insert(0, str(info["port"]))

    # ------------------------------------------------------------------
    # App-password help (collapsible, below Save/Cancel)
    # ------------------------------------------------------------------

    def _toggle_help(self) -> None:
        self._help_expanded = not self._help_expanded
        if self._help_expanded:
            self._help_toggle_btn.configure(text="Hide app password help")
            self._render_help_content()
            self._help_frame.pack(fill="x", padx=20, pady=(0, 8))
        else:
            self._help_toggle_btn.configure(text="Not sure how to get an app password?")
            self._help_frame.pack_forget()
        self._sync_window_height()

    def _render_help_content(self) -> None:
        """Rebuild _help_frame's children for the currently selected
        provider. Called on expand and again whenever the provider changes
        while expanded -- simplest to throw the old widgets away and
        rebuild rather than track per-provider diffs for what is, at most,
        a handful of labels and two button rows."""
        for child in self._help_frame.winfo_children():
            child.destroy()

        provider_key = _PROVIDER_LABELS_REV.get(self._provider_var.get(), "gmail")
        provider_label = _PROVIDER_LABELS.get(provider_key, provider_key)
        host = self._host_entry.get().strip()
        help_url = APP_PASSWORD_HELP_URLS.get(provider_key)
        help_text = APP_PASSWORD_HELP_TEXT.get(provider_key)

        def secondary(text: str) -> None:
            # anchor="w" as well as justify="left": justify only aligns lines
            # within the text block, while anchor places that block inside the
            # label, which fill="x" has stretched to the full frame width. With
            # the default centre anchor, every step whose longest line is
            # shorter than the frame got its own indent, so a numbered list
            # rendered as a ragged zig-zag.
            ctk.CTkLabel(
                self._help_frame, text=text, wraplength=340, anchor="w",
                justify="left", text_color=("gray40", "gray60"), font=("", 11),
            ).pack(fill="x", pady=(0, 4))

        if provider_key == "custom":
            secondary(
                "Turn on two-factor authentication in your email account first, then look "
                "for \"App passwords\" or \"App-specific passwords\" in its security settings."
            )
        elif help_text:
            secondary(help_text)

        # Inline numbered steps -- only for the two providers whose official
        # pages were actually read and translated into steps here (Gmail,
        # Outlook). Every other provider relies on the help-page link and
        # the prompt buttons below instead of guessed steps.
        inline_steps = {"gmail": APP_PASSWORD_STEPS_GMAIL, "outlook": APP_PASSWORD_STEPS_OUTLOOK}.get(provider_key)
        if inline_steps:
            for i, step in enumerate(inline_steps, start=1):
                secondary(f"{i}. {step}")
            secondary(
                f"Steps checked {APP_PASSWORD_STEPS_REVIEWED}. If they don't match what you "
                "see, use the buttons below to get the current version."
            )

        # Provider-specific gotchas that aren't obvious from the generic
        # help text above, surfaced only when they're relevant.
        if provider_key == "outlook":
            secondary(
                "Work or school Microsoft 365 accounts often have IMAP access disabled by "
                "the organisation's administrator — if so, even a correct app password "
                "will be rejected."
            )
        if provider_key == "icloud":
            secondary(
                "This must be an app-specific password generated at appleid.apple.com, not "
                "your main Apple ID password."
            )

        # A live-current fallback (and the primary path for providers with
        # no inline steps) that doesn't depend on any URL staying valid.
        # The prompt text itself never contains the email/password -- see
        # _build_app_password_prompt's own doc comment for why that
        # boundary matters here specifically.
        link_row = ctk.CTkFrame(self._help_frame, fg_color="transparent")
        link_row.pack(fill="x", pady=(4, 0))
        ctk.CTkButton(
            link_row, text="Copy question", width=110, height=26,
            fg_color="transparent", border_width=1, text_color=("gray10", "gray90"),
            command=lambda: self._copy_prompt(provider_key, provider_label, host),
        ).pack(side="left", padx=(0, 6))
        ctk.CTkButton(
            link_row, text="Search for steps", width=120, height=26,
            fg_color="transparent", border_width=1, text_color=("gray10", "gray90"),
            command=lambda: self._search_prompt(provider_key, provider_label, host),
        ).pack(side="left")

        self._help_copied_label = ctk.CTkLabel(
            self._help_frame, text="", text_color=("gray40", "gray60"), font=("", 11),
        )
        self._help_copied_label.pack(fill="x", pady=(2, 0))

        # Lower-emphasis third option: the static, pre-verified link.
        # Precise when current, but only as fresh as the last time someone
        # re-verified it -- the two buttons above don't have that expiry
        # problem.
        if help_url:
            ctk.CTkButton(
                self._help_frame, text=f"Open {provider_label}'s help page",
                height=26, fg_color="transparent", border_width=1,
                text_color=("gray10", "gray90"),
                command=lambda: webbrowser.open(help_url),
            ).pack(fill="x", pady=(4, 0))

    def _copy_prompt(self, provider_key: str, provider_label: str, host: str) -> None:
        prompt = _build_app_password_prompt(provider_key, provider_label, host)
        # clipboard_clear/append + update (not update_idletasks) is the Tk
        # idiom for a clipboard write that survives after this window --
        # and the app itself, in the "Copy question" case -- closes.
        self.clipboard_clear()
        self.clipboard_append(prompt)
        self.update()
        if hasattr(self, "_help_copied_label"):
            self._help_copied_label.configure(text="Copied — paste it into an AI assistant.")

    def _search_prompt(self, provider_key: str, provider_label: str, host: str) -> None:
        import urllib.parse
        prompt = _build_app_password_prompt(provider_key, provider_label, host)
        webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote_plus(prompt))

    def _sync_window_height(self) -> None:
        """Grow/shrink the window to fit the help block instead of letting
        it clip content or, worse, push Save/Cancel out of reach -- the
        exact failure this whole feature was moved below the button row to
        fix. Relies on pack's default propagate behaviour: winfo_reqheight()
        reports the height the currently packed children actually need,
        whether that's the collapsed 380x200 footprint or the expanded help
        block, so this works for both toggling and provider changes."""
        self.update_idletasks()
        self.geometry(f"{self.winfo_width()}x{self.winfo_reqheight()}")

    # ------------------------------------------------------------------
    # Save
    # ------------------------------------------------------------------

    def _on_save(self) -> None:
        # Start from a full copy of the existing settings so keys this
        # dialog doesn't manage (backend_notice_shown, etc.) are preserved
        # rather than dropped on save.
        new_settings = dict(self._app._settings)
        new_settings["chunk_size"] = self._chunk_var.get()
        new_settings["auto_refresh_label"] = self._refresh_var.get()

        backend = _BACKEND_LABELS_REV.get(self._backend_var.get(), MAIL_BACKEND_GMAIL_OAUTH)
        new_settings["mail_backend"] = backend

        password = self._password_entry.get()

        if backend == MAIL_BACKEND_IMAP:
            provider_key = _PROVIDER_LABELS_REV.get(self._provider_var.get(), "gmail")
            info = IMAP_PROVIDERS.get(provider_key, IMAP_PROVIDERS["custom"])
            host = self._host_entry.get().strip() or (info["host"] or "")
            try:
                port = int(self._port_entry.get().strip())
            except ValueError:
                port = info["port"]
            email = self._email_entry.get().strip()

            if provider_key == "custom" and not host:
                messagebox.showerror("Settings", "Enter a host for a custom IMAP server.")
                return
            if not email:
                messagebox.showerror("Settings", "Enter the email address to connect with.")
                return

            new_settings["imap_provider"] = provider_key
            new_settings["imap_host"] = host
            new_settings["imap_port"] = port
            new_settings["imap_email"] = email

            if password:
                # A password was typed -- validate it before persisting
                # anything, so a bad password never silently overwrites a
                # working saved credential. Runs in a background thread;
                # the password itself never gets logged or echoed back.
                self._save_btn.configure(state="disabled")
                self._status_label.configure(text="Testing connection…")
                result_q: queue.Queue = queue.Queue()
                threading.Thread(
                    target=connect_imap,
                    args=(result_q, host, port, email, password),
                    daemon=True,
                ).start()
                self.after(150, lambda: self._poll_imap_test(result_q, new_settings))
                return
            # No password typed: keep whatever credentials file already
            # exists (if any) and just persist the non-secret fields.

        self._app._apply_settings(new_settings)
        self.destroy()

    def _poll_imap_test(self, result_q: "queue.Queue", new_settings: dict) -> None:
        try:
            event = result_q.get_nowait()
        except queue.Empty:
            self.after(150, lambda: self._poll_imap_test(result_q, new_settings))
            return

        if event["type"] == "auth_ok":
            self._status_label.configure(text="")
            self._app._transport = event["transport"]
            self._app._apply_settings(new_settings)
            self._app._check_auth()
            self.destroy()
        else:
            self._save_btn.configure(state="normal")
            self._status_label.configure(text="")
            messagebox.showerror(
                "Could not connect",
                f"Could not connect with those details:\n\n{event['msg']}",
            )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
