"""
Thin façade module for Kotlin/Chaquopy calls (Phase A1+).

Deliberately minimal — see the Phase A0 plan for what's still intentionally
deferred: a wrapper around config.set_root() or mail_client.set_token()
(Kotlin calls those directly, one line each), and any threads.get/
messages.list wiring (no caller exists for those yet).

Every function returns plain JSON-serializable types (dict/list/str/int/
bool/None) since Chaquopy marshals those most cleanly to Kotlin.
"""

import platform
import threading
from dataclasses import asdict
from pathlib import Path
from typing import Callable, Optional

from src import config
from src.mail_client import ChunkSize, MailTransport
from src.parser import extract_chat_info, parse_file
from src.state import delete_chat as state_delete_chat
from src.state import get_recent_runs, get_sync_summary, init_db, reset_chat, resolve_chat
from src.sync_manager import ProgressSyncManager

# Mirrors gui_worker.py's existing {"type": ..., ...} event vocabulary
# (files_total / syncing / file_done / log / done / error) so a future
# Kotlin WorkManager progress bridge stays consistent with what the
# Windows GUI already emits.
ProgressCallback = Callable[[dict], None]


class _CallbackSink:
    """Queue-shaped adapter so ProgressSyncManager (src/sync_manager.py) can
    push progress events straight into a callback instead of a queue.Queue."""

    def __init__(self, on_progress: Optional[ProgressCallback]) -> None:
        self._on_progress = on_progress

    def put(self, event: dict) -> None:
        if self._on_progress is not None:
            self._on_progress(event)


# ---------------------------------------------------------------------------
# Poll-based progress (Phase A5 Sync progress screen).
#
# Chaquopy has no supported way for Python to call back into an arbitrary
# Kotlin object as if it were a function (confirmed the hard way in Phase A4:
# a Kotlin object with a __call__ method raised "is not callable" when Python
# tried to invoke it). So instead of a live callback, sync() appends every
# event to this module-level list, which Kotlin drains on a timer via
# get_progress_events() while the sync WorkManager job runs on another
# thread — mirroring how gui_worker's queue.Queue is fully drained on each
# Windows GUI poll tick, rather than only ever exposing the latest event
# (which silently dropped whole files' worth of "syncing"/"file_done"
# events whenever a sync ran faster than the poll interval).
# ---------------------------------------------------------------------------

_progress_lock = threading.Lock()
_progress_events: list = []


def get_progress_events() -> list:
    """Drain and return all progress events published since the last call
    (oldest first), for polling from Kotlin. Empty list if none yet."""
    with _progress_lock:
        events = list(_progress_events)
        _progress_events.clear()
        return events


def _publish_progress(event: dict) -> None:
    with _progress_lock:
        _progress_events.append(event)


def ping() -> str:
    """Phase A1 Chaquopy smoke test: prove the embedded Python runtime is
    alive and the shared core is importable from the Android side."""
    return f"android_api.ping() OK — Python {platform.python_version()}"


def list_inbox() -> list[dict]:
    """List files currently waiting in data/inbox/, for the Android import
    screen (§3 of the screen-guides doc). Returns
    [{"name": str, "size_bytes": int}, ...], sorted by name."""
    inbox = config.INBOX_DIR
    if not inbox.exists():
        return []
    return sorted(
        (
            {"name": f.name, "size_bytes": f.stat().st_size}
            for f in inbox.iterdir()
            if f.is_file()
        ),
        key=lambda d: d["name"],
    )


def remove_from_inbox(name: str) -> dict:
    """Delete one queued file from data/inbox/ (Home screen's "X" to unselect
    a picked/imported file before syncing). Returns {"ok", "error"}."""
    path = config.INBOX_DIR / Path(name).name
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    except OSError as exc:
        return {"ok": False, "error": str(exc)}
    return {"ok": True, "error": None}


def imap_providers() -> list[dict]:
    """Expose config.IMAP_PROVIDERS to Kotlin so the Android provider picker
    reads from the same preset table as the Windows GUI instead of
    duplicating hosts/ports in Kotlin. "custom" comes through with
    host == "" (None isn't JSON-clean for Chaquopy) so the Android field
    stays editable, matching gui.py's _apply_host_field_state."""
    return [
        {"key": key, "label": info["label"], "host": info["host"] or "", "port": info["port"]}
        for key, info in config.IMAP_PROVIDERS.items()
    ]


def preview(file_path: str) -> dict:
    """Parse one export file without touching the mailbox or the state DB — the
    quick local preview for the Android "Import review" screen (§4 of the
    screen-guides doc): chat name, participant count, message count, date
    range, media count.

    Returns {"ok", "display_name", "message_count", "participant_count",
    "media_count", "first_message_ts", "last_message_ts", "error"}.
    """
    path = Path(file_path)
    empty = {
        "ok": False,
        "display_name": None,
        "message_count": 0,
        "participant_count": 0,
        "media_count": 0,
        "first_message_ts": None,
        "last_message_ts": None,
        "error": None,
    }

    try:
        chat_id, display_name = extract_chat_info(path.name)
        messages = list(parse_file(path, chat_id))
    except Exception as exc:
        return {**empty, "error": str(exc)}

    if not messages:
        return {
            **empty,
            "ok": True,
            "display_name": display_name,
            "error": "No messages could be parsed from this file.",
        }

    return {
        "ok": True,
        "display_name": display_name,
        "message_count": len(messages),
        "participant_count": len({m.sender for m in messages}),
        "media_count": sum(1 for m in messages if m.attachment_filename),
        "first_message_ts": min(m.timestamp_iso for m in messages),
        "last_message_ts": max(m.timestamp_iso for m in messages),
        "error": None,
    }



# Module-level so request_stop() (called from a Compose "Cancel sync" button,
# on a different thread than the sync itself) can reach the in-flight run —
# mirrors gui_worker.SyncWorker.stop(), which sets a stop_event the Windows
# GUI's Stop button triggers the same way. Honoured between files, same as
# Windows: the file in progress finishes (nothing left half-written), no
# further files are started.
_stop_event = threading.Event()


def request_stop() -> None:
    """Request the in-flight sync() call to stop after its current file."""
    _stop_event.set()


def sync(
    transport: Optional[MailTransport] = None,
    chunk_size: Optional[ChunkSize] = None,
    dry_run: bool = False,
    chat_filter: Optional[str] = None,
    on_progress: Optional[ProgressCallback] = None,
    trigger: str = "manual",
) -> dict:
    """Run one full sync pass over data/inbox/ (or the caller's configured root).

    `transport` is an already-built MailTransport (e.g. from
    mail_client.set_token() on Android), not a raw service or bearer token —
    this keeps the façade decoupled from how the transport was constructed.

    `trigger` is recorded on each sync_runs row for the Android Sync log
    screen (e.g. "manual" for Home's Sync now, "watched_folder" for an
    auto-synced watched-folder import).
    """
    def _relay(event: dict) -> None:
        _publish_progress(event)
        if on_progress is not None:
            on_progress(event)

    with _progress_lock:
        _progress_events.clear()
    _stop_event.clear()
    mgr = ProgressSyncManager(
        transport=transport,
        chunk_size=chunk_size or config.DEFAULT_CHUNK_SIZE,
        dry_run=dry_run,
        trigger=trigger,
        progress_queue=_CallbackSink(_relay),
        stop_event=_stop_event,
    )
    stats = mgr.run(chat_filter=chat_filter)
    stopped = _stop_event.is_set()
    _publish_progress({"type": "done"})
    return {**asdict(stats), "stopped": stopped}


def status() -> list[dict]:
    """Return a per-chat sync summary as plain dicts (state.get_sync_summary())."""
    init_db(config.STATE_DB_PATH)
    return get_sync_summary(config.STATE_DB_PATH)


def sync_log(days: int = 90) -> list[dict]:
    """Return sync runs from the last `days` days as plain dicts, newest
    first, for the Android Sync log screen. See state.get_recent_runs() for
    why this is a display window rather than a deletion/retention policy."""
    init_db(config.STATE_DB_PATH)
    return [dict(row) for row in get_recent_runs(days, config.STATE_DB_PATH)]


def reset(chat_id_or_name: str) -> dict:
    """Reset sync state for one chat (accepts chat_id or display_name).

    Mirrors gui.py's _on_resync_chat: also moves the chat's export file back
    from processed/ to inbox/ (when found) so the very next sync naturally
    re-imports it, instead of requiring the user to manually re-pick the
    original file.

    Returns {"ok": bool, "chat_id": str | None, "display_name": str | None,
    "file_restored": bool, "error": str | None}.
    """
    init_db(config.STATE_DB_PATH)
    chat = resolve_chat(chat_id_or_name, config.STATE_DB_PATH)
    if chat is None:
        return {
            "ok": False,
            "chat_id": None,
            "display_name": None,
            "file_restored": False,
            "error": f"No chat found matching {chat_id_or_name!r}",
        }

    reset_chat(chat["chat_id"], config.STATE_DB_PATH)

    file_restored = False
    source_filename = chat["source_filename"]
    if source_filename:
        src = config.PROCESSED_DIR / source_filename
        dest = config.INBOX_DIR / source_filename
        if src.exists() and not dest.exists():
            src.rename(dest)
            file_restored = True

    return {
        "ok": True,
        "chat_id": chat["chat_id"],
        "display_name": chat["display_name"],
        "file_restored": file_restored,
        "error": None,
    }


def delete_chat(chat_id_or_name: str) -> dict:
    """Fully remove a chat and its sync history (accepts chat_id or
    display_name) — distinct from reset(): the entry disappears from the
    list entirely rather than being kept for re-sync. Mirrors gui.py's
    _on_delete_chat / state.delete_chat().

    Returns {"ok": bool, "chat_id": str | None, "display_name": str | None,
    "error": str | None}.
    """
    init_db(config.STATE_DB_PATH)
    chat = resolve_chat(chat_id_or_name, config.STATE_DB_PATH)
    if chat is None:
        return {
            "ok": False,
            "chat_id": None,
            "display_name": None,
            "error": f"No chat found matching {chat_id_or_name!r}",
        }

    state_delete_chat(chat["chat_id"], config.STATE_DB_PATH)
    return {
        "ok": True,
        "chat_id": chat["chat_id"],
        "display_name": chat["display_name"],
        "error": None,
    }
