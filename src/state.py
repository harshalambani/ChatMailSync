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
from typing import Iterator, Optional

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
    """Insert a new chat row or update gmail IDs and updated_at on conflict."""
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
    """Persist Gmail thread ID, label ID, and anchor Message-ID after a successful push."""
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


# ---------------------------------------------------------------------------
# Reset helper
# ---------------------------------------------------------------------------

def reset_chat(chat_id: str, db_path: Optional[Path] = None) -> None:
    """Delete all sync history for a chat so it will be re-synced from scratch.

    The chats row itself is kept (preserving display_name and source_filename)
    but gmail_thread_id and gmail_label_id are cleared so the next run creates
    a fresh thread.
    """
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
