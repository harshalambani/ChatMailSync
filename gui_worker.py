"""
Background thread bridge between the GUI (and CLI) and SyncManager.

Events posted to SyncWorker.q (all plain dicts):
  {"type": "log",         "msg": str}
  {"type": "files_total", "n": int}
  {"type": "syncing",     "name": str}
  {"type": "file_done",   "done": int, "total": int}
  {"type": "done",        "stats": SyncStats}
  {"type": "error",       "msg": str}

Events posted to the queue passed into connect_gmail()/connect_imap():
  {"type": "auth_ok",    "transport": GmailTransport}
  {"type": "auth_error", "msg": str}

This module has no tkinter/customtkinter import, so cli.py can import the
backend-selection helpers below (check_auth_status,
build_transport_for_active_backend) without pulling in the GUI stack.
"""

import json
import logging
import os
import queue
import re
import threading
from pathlib import Path
from typing import Optional

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

from src.config import (
    CREDENTIALS_FILE,
    GMAIL_SCOPES,
    IMAP_CREDENTIALS_FILE,
    MAIL_BACKEND_GMAIL_OAUTH,
    MAIL_BACKEND_IMAP,
    TOKEN_FILE,
)
from src.gmail_client import (
    ChunkSize,
    DiscoveryTransport,
    GmailTransport,
    _restrict_auth_dir_acl,
    _restrict_file_acl,
    build_imap_transport,
    build_service,
)
from src.sync_manager import ProgressSyncManager as _ProgressSyncManager
from src.sync_manager import SyncStats, _scrub_paths

# Shared with gui.py's _SETTINGS_FILE -- both modules live at the project's
# top level, so Path(__file__).parent resolves to the same "data" dir for
# both. Kept as a second constant (rather than importing gui.py, which would
# pull in customtkinter/tkinter here) so cli.py stays GUI-free.
_SETTINGS_FILE = Path(__file__).parent / "data" / ".settings.json"


# ---------------------------------------------------------------------------
# Logging → queue bridge
# ---------------------------------------------------------------------------

class _QueueHandler(logging.Handler):
    def __init__(self, q: queue.Queue) -> None:
        super().__init__()
        self.q = q

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.q.put({"type": "log", "msg": self.format(record)})
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Public sync worker
# ---------------------------------------------------------------------------

class SyncWorker:
    """Run _ProgressSyncManager in a daemon thread; surface events via self.q."""

    def __init__(
        self,
        transport,
        chunk_size: ChunkSize,
        dry_run: bool,
        db_path: Path,
        inbox_dir: Path,
        processed_dir: Path,
    ) -> None:
        self.q: queue.Queue = queue.Queue()
        self._transport = transport
        self._chunk_size = chunk_size
        self._dry_run = dry_run
        self._db_path = db_path
        self._inbox_dir = inbox_dir
        self._processed_dir = processed_dir
        self._stop_event = threading.Event()

    def stop(self) -> None:
        """Signal the worker to stop after the current file finishes."""
        self._stop_event.set()

    def start(self) -> None:
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self) -> None:
        handler = _QueueHandler(self.q)
        handler.setFormatter(logging.Formatter("%(levelname)-8s %(message)s"))
        root_log = logging.getLogger()
        root_log.addHandler(handler)
        try:
            mgr = _ProgressSyncManager(
                transport=self._transport,
                chunk_size=self._chunk_size,
                dry_run=self._dry_run,
                db_path=self._db_path,
                inbox_dir=self._inbox_dir,
                processed_dir=self._processed_dir,
                progress_queue=self.q,
                stop_event=self._stop_event,
            )
            stats = mgr.run()
            stopped = self._stop_event.is_set()
            self.q.put({"type": "done", "stats": stats, "stopped": stopped})
        except Exception as exc:
            self.q.put({"type": "error", "msg": _scrub_paths(str(exc))})
        finally:
            root_log.removeHandler(handler)


# ---------------------------------------------------------------------------
# Mail-backend selection (reads the same .settings.json gui.py writes)
# ---------------------------------------------------------------------------

def _load_mail_backend_settings() -> dict:
    """Read just the backend-selection fields from the shared .settings.json.

    Falls back to Gmail OAuth defaults if the file is missing/unreadable --
    callers here must never fail a sync over a settings-read problem; the
    downstream connect/transport call will surface the real error instead.
    """
    defaults = {
        "mail_backend": MAIL_BACKEND_GMAIL_OAUTH,
        "imap_provider": "gmail",
        "imap_host": "",
        "imap_port": 993,
        "imap_email": "",
    }
    try:
        if _SETTINGS_FILE.exists():
            saved = json.loads(_SETTINGS_FILE.read_text())
            for k in defaults:
                if k in saved:
                    defaults[k] = saved[k]
    except Exception:
        pass
    return defaults


def _save_imap_credentials(host: str, port: int, email: str, password: str) -> None:
    """Persist IMAP connection details to the ACL-hardened auth/ file.

    Reuses gmail_client._restrict_auth_dir_acl -- the exact same hardening
    OAuth's token.json already gets -- rather than inventing a second
    mechanism. The password lives ONLY here, never in .settings.json, never
    logged, never echoed back into the UI after saving.
    """
    IMAP_CREDENTIALS_FILE.parent.mkdir(parents=True, exist_ok=True)

    # Harden the directory BEFORE the file exists, so the file inherits a
    # restricted ACL from the moment it is created. Writing first and
    # hardening afterwards left a window in which a world-readable file
    # containing a live password sat on disk.
    if os.name == "nt":
        _restrict_auth_dir_acl(IMAP_CREDENTIALS_FILE.parent)

    # O_CREAT|O_EXCL-free but mode-carrying open: on POSIX the 0o600 applies
    # at creation rather than after the content is already there. Truncate an
    # existing file rather than leaving a stale longer password tail behind.
    fd = os.open(IMAP_CREDENTIALS_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w") as fh:
            json.dump({
                "host": host,
                "port": port,
                "email": email,
                "password": password,
            }, fh)
    except Exception:
        # Never leave a half-written credentials file behind.
        IMAP_CREDENTIALS_FILE.unlink(missing_ok=True)
        raise

    # Now confirm the file itself really is protected. This must fail loud:
    # a plaintext password readable by every local account is exactly the
    # thing the auth/ folder exists to prevent, and silently carrying on
    # would leave the user believing it was stored safely.
    if os.name == "nt":
        protected = _restrict_file_acl(IMAP_CREDENTIALS_FILE)
    else:
        try:
            os.chmod(IMAP_CREDENTIALS_FILE, 0o600)
            protected = True
        except OSError:
            protected = False

    if not protected:
        IMAP_CREDENTIALS_FILE.unlink(missing_ok=True)
        raise RuntimeError(
            "Refusing to store the app password: its file permissions could "
            "not be restricted to your user account, so it would be readable "
            "by other accounts on this machine. The password was NOT saved "
            "(it is still valid at your provider). See the log for details."
        )


# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------

def check_auth_status() -> tuple[bool, str]:
    """Return (is_valid, status_text) for whichever backend is active.

    Never opens a browser and never makes a network call for IMAP (a stored,
    parseable credentials file counts as "connected" -- a bad password only
    surfaces on the next actual connect/sync attempt, same as an expired
    OAuth token without a refresh_token would need a real reconnect).
    """
    settings = _load_mail_backend_settings()
    if settings["mail_backend"] == MAIL_BACKEND_IMAP:
        return _check_imap_auth_status()
    return _check_gmail_auth_status()


def _check_gmail_auth_status() -> tuple[bool, str]:
    """Silently refreshes an expired token if a refresh_token is present."""
    if not CREDENTIALS_FILE.exists():
        return False, "No credentials.json"
    if not TOKEN_FILE.exists():
        return False, "Not connected"
    try:
        creds = Credentials.from_authorized_user_file(str(TOKEN_FILE), GMAIL_SCOPES)
        if creds.valid:
            return True, "Connected"
        if creds.expired and creds.refresh_token:
            creds.refresh(Request())
            TOKEN_FILE.write_text(creds.to_json())
            # Restrict token file to owner read/write only (skip on Windows).
            if os.name != "nt":
                try:
                    os.chmod(TOKEN_FILE, 0o600)
                except OSError:
                    pass
            return True, "Connected"
        return False, "Token invalid — reconnect"
    except Exception as exc:
        return False, f"Auth error: {exc}"


def _check_imap_auth_status() -> tuple[bool, str]:
    if not IMAP_CREDENTIALS_FILE.exists():
        return False, "Not connected"
    try:
        data = json.loads(IMAP_CREDENTIALS_FILE.read_text())
        email = data.get("email", "")
        return True, (f"Connected ({email})" if email else "Connected")
    except Exception as exc:
        return False, f"Credential error: {_scrub_paths(str(exc))}"


def connect_gmail(result_queue: queue.Queue) -> None:
    """Run the full OAuth2 browser flow in a thread; post result to result_queue."""
    try:
        service = build_service()
        result_queue.put({"type": "auth_ok", "transport": DiscoveryTransport(service)})
    except Exception as exc:
        result_queue.put({"type": "auth_error", "msg": str(exc)})


def connect_imap(
    result_queue: queue.Queue,
    host: str,
    port: int,
    email: str,
    password: str,
) -> None:
    """Validate IMAP credentials (login + a LIST call), then persist them.

    Mirrors connect_gmail()'s queue-event contract so gui.py's poll loop can
    treat both the same way. The password is never persisted unless the
    validation call succeeds, and it is never included in the posted event
    or in the error message (gmail_client's ImapTransport already scrubs the
    password out of any login/connection error text before it gets here).
    """
    try:
        transport = build_imap_transport(host, port, email, password)
        transport.labels_list()  # forces a real login; raises on bad creds
        _save_imap_credentials(host, port, email, password)
        result_queue.put({"type": "auth_ok", "transport": transport})
    except Exception as exc:
        result_queue.put({"type": "auth_error", "msg": _scrub_paths(str(exc))})


def build_transport_for_active_backend() -> Optional[GmailTransport]:
    """Build a transport for whichever backend .settings.json says is active.

    Used by cli.py (headless) and available to gui.py too. For Gmail OAuth
    this runs the normal build_service() flow (may raise FileNotFoundError
    if credentials.json is missing, same as before). For IMAP there is no
    CLI-driven setup flow -- if no saved app password exists yet, this
    raises RuntimeError telling the user to configure it once via the
    desktop app's Settings panel.
    """
    settings = _load_mail_backend_settings()
    if settings["mail_backend"] == MAIL_BACKEND_IMAP:
        if not IMAP_CREDENTIALS_FILE.exists():
            raise RuntimeError(
                "IMAP backend selected but no saved app password found. "
                "Open the desktop app's Settings to connect an IMAP account first."
            )
        data = json.loads(IMAP_CREDENTIALS_FILE.read_text())
        return build_imap_transport(data["host"], data["port"], data["email"], data["password"])
    service = build_service()
    return DiscoveryTransport(service)
