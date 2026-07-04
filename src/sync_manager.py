"""
Sync orchestrator: incremental sync, deduplication, partial-sync recovery.

Flow (normal run):
  1. Recover any pending (interrupted) runs from a previous crash.
  2. For each .txt file in inbox/:
       a. Parse filename → chat_id, display_name.
       b. Upsert chat record; start a pending sync run.
       c. Parse messages; filter out duplicates and re-export overlap.
       d. Push new messages to Gmail in chunks.
       e. Insert hashes, mark run complete, move file to processed/.

Deduplication:
  - SHA-256 hash of (chat_id + timestamp_iso + sender + body) — exact dupe.
  - Timestamp comparison against last successful run — re-export overlap guard.

Partial-sync recovery:
  - Pending runs are detected on startup.
  - Messages whose hashes already exist in the DB are skipped.
  - Only the remaining messages are pushed; the run is then completed.
"""

import logging
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from src import config
from src.config import DEFAULT_CHUNK_SIZE
from src.gmail_client import ChunkSize, DiscoveryTransport, GmailTransport, push_chat
from src.parser import ParsedMessage, extract_chat_info, parse_file
from src.state import (
    complete_sync_run,
    compute_message_hash,
    fail_sync_run,
    get_chat,
    get_hashes_for_run,
    get_last_successful_run,
    get_pending_runs,
    hash_exists,
    init_db,
    insert_message_hashes,
    start_sync_run,
    update_chat_gmail_ids,
    upsert_chat,
)

log = logging.getLogger(__name__)


def _scrub_paths(text: str) -> str:
    """Replace absolute filesystem paths in error text with their basename only.

    Prevents full installation paths (e.g. C:\\Users\\alice\\...) leaking into
    user-facing error messages.
    """
    # Windows: C:\Users\... or \\UNC\...
    # Unix:    /home/alice/...  /tmp/...
    return re.sub(
        r'(?:[A-Za-z]:\\|\\\\)[^\s,\'")\]]+|(?:/[^/\s,\'")\]]+){2,}',
        lambda m: Path(m.group()).name,
        text,
    )


# ---------------------------------------------------------------------------
# Stats container (returned to CLI for display / dry-run reporting)
# ---------------------------------------------------------------------------


@dataclass
class SyncStats:
    files_found: int = 0
    files_synced: int = 0
    files_skipped: int = 0     # nothing new to push
    files_failed: int = 0
    messages_parsed: int = 0
    messages_synced: int = 0
    messages_skipped: int = 0  # deduped or filtered
    chats_recovered: int = 0
    errors: list[str] = field(default_factory=list)

    def __str__(self) -> str:
        lines = [
            f"Files   : found={self.files_found}  synced={self.files_synced}"
            f"  skipped={self.files_skipped}  failed={self.files_failed}",
            f"Messages: parsed={self.messages_parsed}  synced={self.messages_synced}"
            f"  skipped={self.messages_skipped}",
        ]
        if self.chats_recovered:
            lines.append(f"Recovered {self.chats_recovered} interrupted run(s)")
        if self.errors:
            lines.append("Errors:")
            for e in self.errors:
                lines.append(f"  - {e}")
        return "\n".join(lines)


# ---------------------------------------------------------------------------
# Main orchestrator
# ---------------------------------------------------------------------------


class SyncManager:
    def __init__(
        self,
        service=None,                     # authenticated googleapiclient Gmail service (or None for dry-run)
        chunk_size: ChunkSize = DEFAULT_CHUNK_SIZE,
        dry_run: bool = False,
        db_path: Optional[Path] = None,
        inbox_dir: Optional[Path] = None,
        processed_dir: Optional[Path] = None,
        transport: Optional[GmailTransport] = None,
    ) -> None:
        """`service` (existing googleapiclient Resource) and `transport` (an
        already-built GmailTransport, e.g. from gmail_client.set_token() on
        Android) are mutually exclusive — pass at most one. `service` is
        wrapped in a DiscoveryTransport internally so callers below this
        class never touch a raw service object.
        """
        if service is not None and transport is not None:
            raise ValueError("Pass at most one of service= or transport=, not both")
        self.transport = transport if transport is not None else (
            DiscoveryTransport(service) if service is not None else None
        )
        self.chunk_size = chunk_size
        self.dry_run = dry_run
        self.db_path = db_path or config.STATE_DB_PATH
        self.inbox_dir = inbox_dir or config.INBOX_DIR
        self.processed_dir = processed_dir or config.PROCESSED_DIR

        # Ensure DB and directories exist.
        init_db(self.db_path)
        self.processed_dir.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------
    # Public entry point
    # ------------------------------------------------------------------

    def run(self, chat_filter: Optional[str] = None) -> SyncStats:
        """Run a full incremental sync.

        Args:
            chat_filter: If given, only process chats whose chat_id or
                         display_name matches this string (case-insensitive).

        Returns:
            SyncStats with counts for CLI display.
        """
        stats = SyncStats()

        # Step 1 — recover interrupted runs before starting new ones.
        recovered = self._recover_pending(stats)
        stats.chats_recovered = recovered

        # Step 2 — sync all .txt files currently in inbox/.
        txt_files = sorted(
            f for f in self.inbox_dir.iterdir()
            if f.is_file() and f.suffix in (".txt", ".zip", "")
        )
        stats.files_found = len(txt_files)

        if not txt_files:
            log.info("No chat files found in %s", self.inbox_dir)
            return stats

        for filepath in txt_files:
            chat_id, display_name = extract_chat_info(filepath.name)

            if chat_filter and chat_filter.lower() not in (
                chat_id, display_name.lower()
            ):
                log.debug("Skipping %s (filter active)", filepath.name)
                continue

            self._sync_file(filepath, chat_id, display_name, stats)

        return stats

    # ------------------------------------------------------------------
    # File-level sync
    # ------------------------------------------------------------------

    def _sync_file(
        self,
        filepath: Path,
        chat_id: str,
        display_name: str,
        stats: SyncStats,
    ) -> None:
        log.info("Processing: %s", filepath.name)

        # Ensure the chat row exists.
        if not self.dry_run:
            upsert_chat(chat_id, display_name, filepath.name, db_path=self.db_path)

        # Determine dedup baseline from the last successful run.
        last_run = get_last_successful_run(chat_id, self.db_path)
        last_ts: Optional[str] = last_run["last_synced_ts"] if last_run else None

        # Open a pending sync run (skipped in dry-run; no state written).
        run_id: Optional[int] = None
        if not self.dry_run:
            run_id = start_sync_run(chat_id, self.db_path)

        # Parse the file.
        try:
            all_messages = list(parse_file(filepath, chat_id))
        except Exception as exc:
            msg = f"{filepath.name}: parse error — {_scrub_paths(str(exc))}"
            log.error(msg)
            stats.files_failed += 1
            stats.errors.append(msg)
            if run_id is not None:
                fail_sync_run(run_id, str(exc), self.db_path)
            return

        stats.messages_parsed += len(all_messages)

        # Deduplicate.
        new_messages, n_skipped = self._filter_messages(all_messages, chat_id, last_ts)
        stats.messages_skipped += n_skipped

        log.info(
            "%s: parsed=%d  new=%d  skipped=%d",
            display_name, len(all_messages), len(new_messages), n_skipped,
        )

        # Nothing new to push.
        if not new_messages:
            log.info("%s: already up to date", display_name)
            stats.files_skipped += 1
            if run_id is not None:
                complete_sync_run(
                    run_id, last_ts, None,
                    len(all_messages), 0, n_skipped,
                    self.db_path,
                )
            if not self.dry_run:
                self._move_to_processed(filepath, run_id)
            return

        # Dry-run: report and stop here.
        if self.dry_run:
            label = f"WhatsApp/{display_name}"
            log.info(
                "[dry-run] %s: would push %d messages → label=%r thread=%s",
                display_name, len(new_messages), label,
                get_chat(chat_id, self.db_path)["gmail_thread_id"]
                if get_chat(chat_id, self.db_path) else "(new)",
            )
            stats.files_synced += 1
            stats.messages_synced += len(new_messages)
            return

        # Retrieve cached Gmail IDs.
        chat_row = get_chat(chat_id, self.db_path)
        label_id = chat_row["gmail_label_id"] if chat_row else None
        thread_id = chat_row["gmail_thread_id"] if chat_row else None
        anchor_mid = chat_row["anchor_message_id"] if chat_row else None

        # Push to Gmail.
        try:
            results, label_id, thread_id = push_chat(
                transport=self.transport,
                display_name=display_name,
                messages=new_messages,
                chunk_size=self.chunk_size,
                label_id=label_id,
                anchor_message_id=anchor_mid,
                gmail_thread_id=thread_id,
                dry_run=False,
                source_path=filepath,
            )
        except Exception as exc:
            msg = f"{display_name}: Gmail push failed — {_scrub_paths(str(exc))}"
            log.error(msg)
            stats.files_failed += 1
            stats.errors.append(msg)
            if run_id is not None:
                fail_sync_run(run_id, str(exc), self.db_path)
            return

        # Persist Gmail IDs (thread ID and anchor Message-ID from first chunk).
        new_anchor_mid = (
            results[0].message_id
            if (anchor_mid is None and results)
            else anchor_mid
        )
        update_chat_gmail_ids(
            chat_id, thread_id, label_id, new_anchor_mid, self.db_path
        )

        # Persist message hashes.
        hash_entries = _build_hash_entries(new_messages, run_id)
        insert_message_hashes(hash_entries, self.db_path)

        # Mark run complete.
        last_msg = new_messages[-1]
        last_synced_hash = compute_message_hash(
            last_msg.chat_id, last_msg.timestamp_iso, last_msg.sender, last_msg.body
        )
        complete_sync_run(
            run_id, last_msg.timestamp_iso, last_synced_hash,
            len(all_messages), len(new_messages), n_skipped,
            self.db_path,
        )

        # Move file to processed/ (only after everything succeeded).
        self._move_to_processed(filepath, run_id)

        stats.files_synced += 1
        stats.messages_synced += len(new_messages)
        log.info("%s: done — %d messages synced", display_name, len(new_messages))

    # ------------------------------------------------------------------
    # Deduplication
    # ------------------------------------------------------------------

    def _filter_messages(
        self,
        messages: list[ParsedMessage],
        chat_id: str,
        last_synced_ts: Optional[str],
    ) -> tuple[list[ParsedMessage], int]:
        """Return (new_messages, n_skipped) after applying dedup rules.

        Rules (applied in order):
          1. Hash already in message_hashes → skip (exact duplicate).
          2. message_ts <= last_synced_ts   → skip (re-export overlap).
        """
        new: list[ParsedMessage] = []
        skipped = 0
        for msg in messages:
            h = compute_message_hash(
                msg.chat_id, msg.timestamp_iso, msg.sender, msg.body
            )
            if hash_exists(h, self.db_path):
                skipped += 1
                continue
            if last_synced_ts and msg.timestamp_iso <= last_synced_ts:
                skipped += 1
                continue
            new.append(msg)
        return new, skipped

    # ------------------------------------------------------------------
    # Partial-sync recovery
    # ------------------------------------------------------------------

    def _recover_pending(self, stats: SyncStats) -> int:
        """Resume all interrupted sync runs. Returns the count recovered."""
        pending = get_pending_runs(self.db_path)
        if not pending:
            return 0

        log.info("Found %d interrupted run(s) — recovering…", len(pending))
        recovered = 0
        for run_row in pending:
            run = dict(run_row)
            if self._recover_run(run, stats):
                recovered += 1
        return recovered

    def _recover_run(self, run: dict, stats: SyncStats) -> bool:
        """Recover a single pending run. Returns True on success."""
        run_id = run["run_id"]
        chat_id = run["chat_id"]

        chat_row = get_chat(chat_id, self.db_path)
        if chat_row is None:
            log.warning("Recovery: no chat record for run_id=%d — marking failed", run_id)
            fail_sync_run(run_id, "chat record missing", self.db_path)
            return False

        chat = dict(chat_row)
        source_file = self.inbox_dir / chat["source_filename"]

        if not source_file.exists():
            log.warning(
                "Recovery: source file missing (%s) for run_id=%d — marking failed",
                source_file.name, run_id,
            )
            fail_sync_run(
                run_id, f"source file not found: {chat['source_filename']}", self.db_path
            )
            return False

        log.info(
            "Recovering run_id=%d for '%s'", run_id, chat["display_name"]
        )

        # Hashes already pushed in this interrupted run.
        already_pushed = get_hashes_for_run(run_id, self.db_path)

        # Re-parse file.
        try:
            all_messages = list(parse_file(source_file, chat_id))
        except Exception as exc:
            log.error("Recovery parse failed for %s: %s", source_file.name, exc)
            fail_sync_run(run_id, f"parse error: {exc}", self.db_path)
            stats.files_failed += 1
            return False

        # Remaining = not yet pushed in this run AND not in any earlier run.
        remaining: list[ParsedMessage] = []
        for msg in all_messages:
            h = compute_message_hash(
                msg.chat_id, msg.timestamp_iso, msg.sender, msg.body
            )
            if h in already_pushed or hash_exists(h, self.db_path):
                continue
            remaining.append(msg)

        prior_synced = run.get("messages_synced", 0)
        log.info(
            "Recovery '%s': total=%d  already_pushed=%d  remaining=%d",
            chat["display_name"], len(all_messages), len(already_pushed), len(remaining),
        )

        # If nothing left, just close the run and move the file.
        if not remaining:
            complete_sync_run(
                run_id,
                run.get("last_synced_ts"),
                run.get("last_synced_hash"),
                len(all_messages),
                prior_synced,
                len(all_messages) - prior_synced,
                self.db_path,
            )
            self._move_to_processed(source_file, run_id)
            log.info("Recovery complete (nothing left to push) for run_id=%d", run_id)
            return True

        if self.dry_run:
            log.info(
                "[dry-run] Recovery would push %d remaining messages for '%s'",
                len(remaining), chat["display_name"],
            )
            return True

        # Push remaining chunks.
        try:
            results, label_id, thread_id = push_chat(
                transport=self.transport,
                display_name=chat["display_name"],
                messages=remaining,
                chunk_size=self.chunk_size,
                label_id=chat.get("gmail_label_id"),
                anchor_message_id=chat.get("anchor_message_id"),
                gmail_thread_id=chat.get("gmail_thread_id"),
                dry_run=False,
                source_path=source_file,
            )
        except Exception as exc:
            log.error(
                "Recovery push failed for '%s': %s", chat["display_name"], exc
            )
            fail_sync_run(run_id, f"recovery push failed: {exc}", self.db_path)
            stats.files_failed += 1
            return False

        # Persist.
        new_anchor_mid = (
            results[0].message_id
            if (not chat.get("anchor_message_id") and results)
            else chat.get("anchor_message_id")
        )
        update_chat_gmail_ids(
            chat_id, thread_id, label_id, new_anchor_mid, self.db_path
        )

        hash_entries = _build_hash_entries(remaining, run_id)
        insert_message_hashes(hash_entries, self.db_path)

        total_synced = prior_synced + len(remaining)
        last_msg = remaining[-1]
        last_hash = compute_message_hash(
            last_msg.chat_id, last_msg.timestamp_iso, last_msg.sender, last_msg.body
        )
        complete_sync_run(
            run_id,
            last_msg.timestamp_iso,
            last_hash,
            len(all_messages),
            total_synced,
            len(all_messages) - total_synced,
            self.db_path,
        )

        self._move_to_processed(source_file, run_id)
        log.info(
            "Recovery complete for run_id=%d ('%s'): pushed %d remaining messages",
            run_id, chat["display_name"], len(remaining),
        )
        stats.messages_synced += len(remaining)
        return True

    # ------------------------------------------------------------------
    # File lifecycle
    # ------------------------------------------------------------------

    def _move_to_processed(self, filepath: Path, run_id: Optional[int]) -> None:
        """Move a file from inbox/ to processed/ after a successful sync."""
        from datetime import datetime as _dt
        dest = self.processed_dir / filepath.name
        if dest.exists():
            # Collision: use a timestamp suffix so the renamed file is clearly
            # artificial and cannot be mistaken for a real chat name if moved
            # back to inbox (e.g. _dup_20250531_143022 vs the old _{run_id}
            # which produced names like "John Doe_11").
            suffix = _dt.now().strftime("_dup_%Y%m%d_%H%M%S")
            dest = self.processed_dir / f"{filepath.stem}{suffix}{filepath.suffix}"
        shutil.move(str(filepath), str(dest))
        log.info("Moved %s → processed/%s", filepath.name, dest.name)


# ---------------------------------------------------------------------------
# SyncManager subclass — adds per-file progress events
#
# Lives in the shared core (not gui_worker.py, which is Windows-only and not
# bundled into the Android build) so both the Windows GUI and android_api.py
# can reuse the same event vocabulary. `progress_queue` only needs a `.put()`
# method (a real queue.Queue on Windows, a plain callback adapter on
# Android) and `stop_event` only needs `.is_set()` — duck-typed, not tied to
# threading.Event, so a caller with no cancellation support can pass anything
# that always answers False.
# ---------------------------------------------------------------------------


class ProgressSyncManager(SyncManager):
    def __init__(
        self,
        *args,
        progress_queue: Any,
        stop_event: Any,
        **kwargs,
    ) -> None:
        super().__init__(*args, **kwargs)
        self._pq = progress_queue
        self._stop_event = stop_event
        self._files_total = 0
        self._files_done = 0

    def run(self, chat_filter: Optional[str] = None) -> SyncStats:
        try:
            files = [
                f for f in self.inbox_dir.iterdir()
                if f.is_file() and f.suffix in (".txt", ".zip", "")
            ]
            self._files_total = len(files)
        except Exception:
            self._files_total = 0
        self._pq.put({"type": "files_total", "n": self._files_total})
        return super().run(chat_filter=chat_filter)

    def _sync_file(self, filepath, chat_id, display_name, stats) -> None:
        # Honour a stop request between files — current file is never started,
        # so no partial state is written to the DB.
        if self._stop_event.is_set():
            return
        self._pq.put({"type": "syncing", "name": display_name})
        super()._sync_file(filepath, chat_id, display_name, stats)
        self._files_done += 1
        self._pq.put({
            "type": "file_done",
            "done": self._files_done,
            "total": self._files_total,
        })


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _build_hash_entries(
    messages: list[ParsedMessage], run_id: int
) -> list[tuple[str, str, str, int]]:
    """Build (hash, chat_id, message_ts, run_id) tuples for bulk insert."""
    return [
        (
            compute_message_hash(m.chat_id, m.timestamp_iso, m.sender, m.body),
            m.chat_id,
            m.timestamp_iso,
            run_id,
        )
        for m in messages
    ]
