"""
SQLite state tracker: DB initialisation, schema, and all query helpers.

All timestamps stored here are ISO 8601 strings (naive local time — no UTC
conversion, matching the timezone limitation described in the architecture doc).
"""

import hashlib
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Iterator, Mapping, Optional

from src import config


# ---------------------------------------------------------------------------
# Schema DDL
# ---------------------------------------------------------------------------

_DDL = """
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS chats (
    chat_id           TEXT PRIMARY KEY,
    display_name      TEXT NOT NULL,
    -- gmail_* by history, not by meaning: these predate the IMAP backend and now
    -- hold whichever transport is active (IMAP stores its folder in the label
    -- column and its thread anchor in the thread column). Deliberately not
    -- renamed - it would need a migration on every existing install for a
    -- cosmetic gain. See PLATFORM-PARITY.md P2.
    gmail_thread_id   TEXT,
    gmail_label_id    TEXT,
    anchor_message_id TEXT,
    source_filename   TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_runs (
    run_id           INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id          TEXT    NOT NULL REFERENCES chats(chat_id),
    status           TEXT    NOT NULL CHECK(status IN ('pending', 'complete', 'failed')),
    trigger          TEXT    NOT NULL DEFAULT 'manual',
    last_synced_ts   TEXT,
    last_synced_hash TEXT,
    messages_parsed  INTEGER NOT NULL DEFAULT 0,
    messages_synced  INTEGER NOT NULL DEFAULT 0,
    messages_skipped INTEGER NOT NULL DEFAULT 0,
    error_message    TEXT,
    started_at       TEXT    NOT NULL,
    completed_at     TEXT
);

CREATE TABLE IF NOT EXISTS message_hashes (
    hash       TEXT    PRIMARY KEY,
    chat_id    TEXT    NOT NULL REFERENCES chats(chat_id),
    message_ts TEXT    NOT NULL,
    run_id     INTEGER NOT NULL REFERENCES sync_runs(run_id)
);

CREATE INDEX IF NOT EXISTS idx_message_hashes_chat  ON message_hashes(chat_id);
CREATE INDEX IF NOT EXISTS idx_sync_runs_chat       ON sync_runs(chat_id);
CREATE INDEX IF NOT EXISTS idx_sync_runs_status     ON sync_runs(status);
"""


# ---------------------------------------------------------------------------
# Connection context manager
# ---------------------------------------------------------------------------

@contextmanager
def _connect(db_path: Optional[Path] = None) -> Iterator[sqlite3.Connection]:
    db_path = db_path or config.STATE_DB_PATH
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Initialisation
# ---------------------------------------------------------------------------

def init_db(db_path: Optional[Path] = None) -> None:
    """Create the database and all tables/indexes if they don't exist yet."""
    db_path = db_path or config.STATE_DB_PATH
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with _connect(db_path) as conn:
        conn.executescript(_DDL)
        # Migration for DBs created before the `trigger` column existed —
        # CREATE TABLE IF NOT EXISTS above only helps fresh installs.
        try:
            conn.execute("ALTER TABLE sync_runs ADD COLUMN trigger TEXT NOT NULL DEFAULT 'manual'")
        except sqlite3.OperationalError:
            pass  # column already exists


# ---------------------------------------------------------------------------
# Hash helper
# ---------------------------------------------------------------------------

def compute_message_hash(chat_id: str, timestamp_iso: str, sender: str, body: str) -> str:
    """Return the canonical SHA-256 hash for a parsed message."""
    raw = f"{chat_id}\x00{timestamp_iso}\x00{sender}\x00{body}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Chat helpers
# ---------------------------------------------------------------------------

def get_chat(chat_id: str, db_path: Optional[Path] = None) -> Optional[sqlite3.Row]:
    with _connect(db_path) as conn:
        return conn.execute(
            "SELECT * FROM chats WHERE chat_id = ?", (chat_id,)
        ).fetchone()


def upsert_chat(
    chat_id: str,
    display_name: str,
    source_filename: str,
    gmail_thread_id: Optional[str] = None,
    gmail_label_id: Optional[str] = None,
    anchor_message_id: Optional[str] = None,
    db_path: Optional[Path] = None,
) -> None:
    """Insert a new chat row or update the mail IDs and updated_at on conflict."""
    now = _now()
    with _connect(db_path) as conn:
        conn.execute(
            """
            INSERT INTO chats (chat_id, display_name, gmail_thread_id, gmail_label_id,
                               anchor_message_id, source_filename, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET
                gmail_thread_id   = COALESCE(excluded.gmail_thread_id,   gmail_thread_id),
                gmail_label_id    = COALESCE(excluded.gmail_label_id,    gmail_label_id),
                anchor_message_id = COALESCE(excluded.anchor_message_id, anchor_message_id),
                updated_at        = excluded.updated_at
            """,
            (chat_id, display_name, gmail_thread_id, gmail_label_id,
             anchor_message_id, source_filename, now, now),
        )


def update_chat_gmail_ids(
    chat_id: str,
    gmail_thread_id: Optional[str] = None,
    gmail_label_id: Optional[str] = None,
    anchor_message_id: Optional[str] = None,
    db_path: Optional[Path] = None,
) -> None:
    """Persist the thread ID, label/folder ID, and anchor Message-ID after a successful push.

    Named for the columns, which are gmail_* for historical reasons; the values
    come from whichever transport ran (see the schema comment in _DDL).
    """
    now = _now()
    with _connect(db_path) as conn:
        conn.execute(
            """
            UPDATE chats
            SET gmail_thread_id   = COALESCE(?, gmail_thread_id),
                gmail_label_id    = COALESCE(?, gmail_label_id),
                anchor_message_id = COALESCE(?, anchor_message_id),
                updated_at        = ?
            WHERE chat_id = ?
            """,
            (gmail_thread_id, gmail_label_id, anchor_message_id, now, chat_id),
        )


def list_chats(db_path: Optional[Path] = None) -> list[sqlite3.Row]:
    with _connect(db_path) as conn:
        return conn.execute("SELECT * FROM chats ORDER BY display_name").fetchall()


def resolve_chat(target: str, db_path: Optional[Path] = None) -> Optional[sqlite3.Row]:
    """Look up a chat by chat_id first, falling back to a case-insensitive
    display_name match. Shared by cli.py's `reset` command and android_api.py
    so the two entry points can't drift on lookup behavior."""
    chat = get_chat(target, db_path)
    if chat is not None:
        return chat
    for row in list_chats(db_path):
        if row["display_name"].lower() == target.lower():
            return row
    return None


# ---------------------------------------------------------------------------
# Sync run helpers
# ---------------------------------------------------------------------------

def start_sync_run(chat_id: str, trigger: str = "manual", db_path: Optional[Path] = None) -> int:
    """Open a new sync run in 'pending' status; return the new run_id."""
    with _connect(db_path) as conn:
        cur = conn.execute(
            """
            INSERT INTO sync_runs (chat_id, status, trigger, started_at)
            VALUES (?, 'pending', ?, ?)
            """,
            (chat_id, trigger, _now()),
        )
        return cur.lastrowid


def complete_sync_run(
    run_id: int,
    last_synced_ts: Optional[str],
    last_synced_hash: Optional[str],
    messages_parsed: int,
    messages_synced: int,
    messages_skipped: int,
    db_path: Optional[Path] = None,
) -> None:
    with _connect(db_path) as conn:
        conn.execute(
            """
            UPDATE sync_runs
            SET status           = 'complete',
                last_synced_ts   = ?,
                last_synced_hash = ?,
                messages_parsed  = ?,
                messages_synced  = ?,
                messages_skipped = ?,
                completed_at     = ?
            WHERE run_id = ?
            """,
            (last_synced_ts, last_synced_hash,
             messages_parsed, messages_synced, messages_skipped,
             _now(), run_id),
        )


def fail_sync_run(run_id: int, error_message: str, db_path: Optional[Path] = None) -> None:
    with _connect(db_path) as conn:
        conn.execute(
            """
            UPDATE sync_runs
            SET status        = 'failed',
                error_message = ?,
                completed_at  = ?
            WHERE run_id = ?
            """,
            (error_message, _now(), run_id),
        )


def get_last_successful_run(chat_id: str, db_path: Optional[Path] = None) -> Optional[sqlite3.Row]:
    """Return the most recent completed sync run for a chat, or None."""
    with _connect(db_path) as conn:
        return conn.execute(
            """
            SELECT * FROM sync_runs
            WHERE chat_id = ? AND status = 'complete'
            ORDER BY run_id DESC
            LIMIT 1
            """,
            (chat_id,),
        ).fetchone()


def get_pending_runs(db_path: Optional[Path] = None) -> list[sqlite3.Row]:
    """Return all sync runs that were interrupted before completion."""
    with _connect(db_path) as conn:
        return conn.execute(
            "SELECT * FROM sync_runs WHERE status = 'pending' ORDER BY run_id"
        ).fetchall()


def get_run(run_id: int, db_path: Optional[Path] = None) -> Optional[sqlite3.Row]:
    with _connect(db_path) as conn:
        return conn.execute(
            "SELECT * FROM sync_runs WHERE run_id = ?", (run_id,)
        ).fetchone()


# ---------------------------------------------------------------------------
# Message hash helpers
# ---------------------------------------------------------------------------

def hash_exists(msg_hash: str, db_path: Optional[Path] = None) -> bool:
    with _connect(db_path) as conn:
        row = conn.execute(
            "SELECT 1 FROM message_hashes WHERE hash = ?", (msg_hash,)
        ).fetchone()
        return row is not None


def insert_message_hashes(
    entries: list[tuple[str, str, str, int]],
    db_path: Optional[Path] = None,
) -> None:
    """Bulk-insert (hash, chat_id, message_ts, run_id) tuples."""
    with _connect(db_path) as conn:
        conn.executemany(
            "INSERT OR IGNORE INTO message_hashes (hash, chat_id, message_ts, run_id) VALUES (?, ?, ?, ?)",
            entries,
        )


def get_hashes_for_run(run_id: int, db_path: Optional[Path] = None) -> set[str]:
    """Return the set of message hashes already committed under a given run.

    Used during partial-sync recovery to skip messages that were pushed before
    a crash.
    """
    with _connect(db_path) as conn:
        rows = conn.execute(
            "SELECT hash FROM message_hashes WHERE run_id = ?", (run_id,)
        ).fetchall()
        return {row["hash"] for row in rows}


# ---------------------------------------------------------------------------
# Status / reporting helpers
# ---------------------------------------------------------------------------

def get_sync_summary(db_path: Optional[Path] = None) -> list[dict]:
    """Return a per-chat summary for the `status` CLI command."""
    with _connect(db_path) as conn:
        rows = conn.execute(
            """
            SELECT
                c.chat_id,
                c.display_name,
                c.source_filename,
                c.gmail_thread_id,
                c.gmail_thread_id IS NOT NULL AS has_thread,
                r.status            AS last_run_status,
                r.last_synced_ts,
                r.messages_synced,
                r.started_at        AS last_run_at
            FROM chats c
            LEFT JOIN sync_runs r ON r.run_id = (
                SELECT run_id FROM sync_runs
                WHERE chat_id = c.chat_id
                ORDER BY run_id DESC LIMIT 1
            )
            ORDER BY c.display_name
            """
        ).fetchall()
        return [dict(row) for row in rows]


def get_recent_runs(days: int = 90, db_path: Optional[Path] = None) -> list[sqlite3.Row]:
    """Return sync runs started within the last `days` days, newest first,
    joined with the chat's display name, for the Android Sync log screen.

    This is a display-window filter, not a retention/deletion policy — old
    `sync_runs` rows are never physically deleted (see module docstring on
    `reset_chat`/`delete_chat` for the only paths that do delete rows, both
    scoped to a single chat). Deleting old runs in bulk would either violate
    the `message_hashes.run_id` foreign key or, if hashes were deleted too,
    re-open already-synced messages for duplicate re-send.
    """
    cutoff = (datetime.now() - timedelta(days=days)).isoformat(timespec="seconds")
    with _connect(db_path) as conn:
        return conn.execute(
            """
            SELECT sync_runs.*, chats.display_name
            FROM sync_runs
            JOIN chats ON chats.chat_id = sync_runs.chat_id
            WHERE sync_runs.started_at >= ?
            ORDER BY sync_runs.started_at DESC
            """,
            (cutoff,),
        ).fetchall()


def is_uneventful_run(row: Mapping[str, Any]) -> bool:
    """True when a run finished cleanly and changed nothing in the mailbox.

    A watched folder re-scans every chat on every tick, and each chat gets its
    own `sync_runs` row whether or not the export moved. On a 40-chat inbox
    checked daily that is ~1,200 rows a month, of which the handful that
    actually uploaded something are the only ones anyone opens the log to
    find. The log therefore folds these away by default -- see the Windows
    _SyncLogPanel and Android's SyncLogScreen, both of which show a count and
    a way to unfold rather than hiding them outright.

    Deliberately *not* "messages_synced == 0": a failed run also uploads
    nothing, and burying failures is the one thing this must never do. A
    still-`pending` run is not uneventful either -- it hasn't finished, so
    there is nothing yet to judge.

    The rule lives here, in the shared core, so the two front-ends cannot
    drift into folding away different runs: Windows calls this directly and
    Android reads the `uneventful` flag that android_api.sync_log() stamps on
    each row from it.
    """
    return row["status"] == "complete" and not (row["messages_synced"] or 0)


def summarize_recent_runs(
    days: int = 90, db_path: Optional[Path] = None
) -> dict[str, Any]:
    """Answer "where do things stand?" in one read, for both home screens.

    The sync log already holds this, but reaching it costs a navigation, and
    the two questions someone asks on arriving -- did the last sync work, and
    is anything broken -- deserve an answer before that. The summary lives
    here rather than in either front-end for the same reason
    `is_uneventful_run` does: two hand-written summaries over the same table
    would eventually disagree, and a home screen quietly claiming a different
    history than the log is worse than no summary at all.

    `last_*` describes the newest run that has *finished*, which is what "the
    last sync" means to a reader. A run still in flight is reported separately
    as `running_runs`, so a sync starting does not blank out the outcome of
    the one before it -- both front-ends show live progress elsewhere.
    """
    runs = get_recent_runs(days, db_path)
    finished = [r for r in runs if r["status"] in ("complete", "failed")]
    last = finished[0] if finished else None
    return {
        "window_days": days,
        "total_runs": len(runs),
        "failed_runs": sum(1 for r in runs if r["status"] == "failed"),
        "running_runs": sum(1 for r in runs if r["status"] == "pending"),
        "last_run_id": last["run_id"] if last else None,
        "last_status": last["status"] if last else None,
        "last_display_name": last["display_name"] if last else None,
        "last_started_at": last["started_at"] if last else None,
        "last_completed_at": last["completed_at"] if last else None,
        "last_messages_synced": (last["messages_synced"] or 0) if last else 0,
        "last_messages_skipped": (last["messages_skipped"] or 0) if last else 0,
    }


# ---------------------------------------------------------------------------
# Reset helper
# ---------------------------------------------------------------------------

class MailboxNotClearedError(RuntimeError):
    """Raised when a chat with archived messages is reset without confirmation.

    Carries the count so callers can put a real number in front of the user
    instead of a vague warning.
    """

    def __init__(self, chat_id: str, archived_count: int) -> None:
        self.chat_id = chat_id
        self.archived_count = archived_count
        super().__init__(
            f"{chat_id!r} has {archived_count} message(s) already archived in the "
            "mailbox. Delete them there first, then reset with "
            "confirmed_mailbox_cleared=True."
        )


def count_archived_messages(chat_id: str, db_path: Optional[Path] = None) -> int:
    """How many of this chat's messages this app has already put in the mailbox.

    One row per message actually appended, so this is the number of duplicates
    a reset-then-resync would create if the mailbox copy is left in place.
    """
    with _connect(db_path) as conn:
        row = conn.execute(
            "SELECT COUNT(*) FROM message_hashes WHERE chat_id = ?", (chat_id,)
        ).fetchone()
    return int(row[0]) if row else 0


def reset_chat(
    chat_id: str,
    db_path: Optional[Path] = None,
    *,
    confirmed_mailbox_cleared: bool = False,
) -> None:
    """Delete all sync history for a chat so it will be re-synced from scratch.

    The chats row itself is kept (preserving display_name and source_filename)
    but the gmail_thread_id and gmail_label_id columns are cleared so the next run creates
    a fresh thread.

    This is the duplicate-creating operation in the whole app, so it is gated.
    The local hash table is the only record that these messages were ever sent;
    clearing it makes the next sync append a second copy of every one of them,
    into a brand-new thread, and this app has no delete path that could undo
    that - it is write-only by design and never removes mail.

    So the caller must first have the user delete the chat's existing mail by
    hand, then pass confirmed_mailbox_cleared=True. Without it, a chat that has
    anything archived raises MailboxNotClearedError. The flag is keyword-only
    and defaults to False so that no existing or future caller can arm this by
    position or by forgetting it; a chat with nothing archived resets freely,
    since there is nothing to duplicate.
    """
    if not confirmed_mailbox_cleared:
        archived = count_archived_messages(chat_id, db_path)
        if archived > 0:
            raise MailboxNotClearedError(chat_id, archived)

    with _connect(db_path) as conn:
        # Delete hashes first (FK constraint)
        conn.execute("DELETE FROM message_hashes WHERE chat_id = ?", (chat_id,))
        conn.execute("DELETE FROM sync_runs WHERE chat_id = ?", (chat_id,))
        conn.execute(
            """
            UPDATE chats
            SET gmail_thread_id   = NULL,
                gmail_label_id    = NULL,
                anchor_message_id = NULL,
                updated_at        = ?
            WHERE chat_id = ?
            """,
            (_now(), chat_id),
        )


# ---------------------------------------------------------------------------
# Delete helper
# ---------------------------------------------------------------------------

def delete_chat(chat_id: str, db_path: Optional[Path] = None) -> None:
    """Fully remove a chat and all its sync history from the database.

    Unlike reset_chat(), this deletes the chats row itself so the entry
    disappears from the UI entirely.  Use for phantom / defunct entries.
    """
    with _connect(db_path) as conn:
        conn.execute("DELETE FROM message_hashes WHERE chat_id = ?", (chat_id,))
        conn.execute("DELETE FROM sync_runs WHERE chat_id = ?", (chat_id,))
        conn.execute("DELETE FROM chats WHERE chat_id = ?", (chat_id,))


# ---------------------------------------------------------------------------
# Internal
# ---------------------------------------------------------------------------

def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")
