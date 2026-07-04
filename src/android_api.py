"""
Thin façade module for future Kotlin/Chaquopy calls (Phase A1+).

No Android caller exists yet; this module is built now so it has direct
unit-test coverage on Windows and a stable, documented contract before any
Kotlin code depends on it. Deliberately minimal — see the Phase A0 plan for
what's intentionally deferred: a wrapper around config.set_root() or
gmail_client.set_token() (Kotlin calls those directly, one line each), a
ping() smoke test (that's Phase A1's job), and any threads.get/messages.list
wiring (no caller exists for those yet either).

Every function returns plain JSON-serializable types (dict/list/str/int/
bool/None) since Chaquopy marshals those most cleanly to Kotlin.
"""

import threading
from dataclasses import asdict
from typing import Callable, Optional

from src import config
from src.gmail_client import ChunkSize, GmailTransport
from src.state import get_sync_summary, init_db, reset_chat, resolve_chat
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


def sync(
    transport: Optional[GmailTransport] = None,
    chunk_size: Optional[ChunkSize] = None,
    dry_run: bool = False,
    chat_filter: Optional[str] = None,
    on_progress: Optional[ProgressCallback] = None,
) -> dict:
    """Run one full sync pass over data/inbox/ (or the caller's configured root).

    `transport` is an already-built GmailTransport (e.g. from
    gmail_client.set_token() on Android), not a raw service or bearer token —
    this keeps the façade decoupled from how the transport was constructed.
    """
    mgr = ProgressSyncManager(
        transport=transport,
        chunk_size=chunk_size or config.DEFAULT_CHUNK_SIZE,
        dry_run=dry_run,
        progress_queue=_CallbackSink(on_progress),
        stop_event=threading.Event(),
    )
    stats = mgr.run(chat_filter=chat_filter)
    return asdict(stats)


def status() -> list[dict]:
    """Return a per-chat sync summary as plain dicts (state.get_sync_summary())."""
    init_db(config.STATE_DB_PATH)
    return get_sync_summary(config.STATE_DB_PATH)


def reset(chat_id_or_name: str) -> dict:
    """Reset sync state for one chat (accepts chat_id or display_name).

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

    reset_chat(chat["chat_id"], config.STATE_DB_PATH)
    return {
        "ok": True,
        "chat_id": chat["chat_id"],
        "display_name": chat["display_name"],
        "error": None,
    }
