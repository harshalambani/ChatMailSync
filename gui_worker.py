"""
Background thread bridge between the GUI and SyncManager.

Events posted to SyncWorker.q (all plain dicts):
  {"type": "log",         "msg": str}
  {"type": "files_total", "n": int}
  {"type": "syncing",     "name": str}
  {"type": "file_done",   "done": int, "total": int}
  {"type": "done",        "stats": SyncStats}
  {"type": "error",       "msg": str}
"""

import logging
import os
import queue
import re
import threading
from pathlib import Path
from typing import Optional

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

from src.config import CREDENTIALS_FILE, GMAIL_SCOPES, TOKEN_FILE
from src.gmail_client import ChunkSize, build_service
from src.sync_manager import ProgressSyncManager as _ProgressSyncManager
from src.sync_manager import SyncStats, _scrub_paths


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
        service,
        chunk_size: ChunkSize,
        dry_run: bool,
        db_path: Path,
        inbox_dir: Path,
        processed_dir: Path,
    ) -> None:
        self.q: queue.Queue = queue.Queue()
        self._service = service
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
                service=self._service,
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
# Auth helpers
# ---------------------------------------------------------------------------

def check_auth_status() -> tuple[bool, str]:
    """Return (is_valid, status_text) without opening a browser.

    Silently refreshes an expired token if a refresh_token is present.
    """
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


def connect_gmail(result_queue: queue.Queue) -> None:
    """Run the full OAuth2 browser flow in a thread; post result to result_queue."""
    try:
        service = build_service()
        result_queue.put({"type": "auth_ok", "service": service})
    except Exception as exc:
        result_queue.put({"type": "auth_error", "msg": str(exc)})
