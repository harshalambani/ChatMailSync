"""
Sync orchestrator: incremental sync, deduplication, partial-sync recovery.

Flow (normal run):
  1. Recover any pending (interrupted) runs from a previous crash.
  2. For each .txt file in inbox/:
       a. Parse filename → chat_id, display_name.
       b. Upsert chat record; start a pending sync run.
       c. Parse messages; filter out duplicates and re-export overlap.
       d. Push new messages to the mailbox in chunks.
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
from src.mail_client import ChunkSize, DiscoveryTransport, MailTransport, push_chat
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


_URL_RE = re.compile(r"https?://\S+")
_FS_PATH_RE = re.compile(
    r'(?:[A-Za-z]:\\|\\\\)[^\s,\'")\]]+|(?:/[^/\s,\'")\]]+){2,}'
)


def _scrub_paths(text: str) -> str:
    """Replace absolute filesystem paths in error text with their basename only.

    Prevents full installation paths (e.g. C:\\Users\\alice\\...) leaking into
    user-facing error messages.

    URLs (e.g. a Gmail API error's "for url: https://gmail.googleapis.com/...")
    are left untouched — the filesystem-path pattern below would otherwise
    treat the URL's path segments as a Unix path and collapse the whole thing
    down to just its last segment (turning a diagnosable "401 Unauthorized
    for url: https://.../labels" into an untraceable "https:/labels").
    """
    # Windows: C:\Users\... or \\UNC\...
    # Unix:    /home/alice/...  /tmp/...
    out = []
    last = 0
    for m in _URL_RE.finditer(text):
        out.append(_FS_PATH_RE.sub(lambda mm: Path(mm.group()).name, text[last:m.start()]))
        out.append(m.group())
        last = m.end()
    out.append(_FS_PATH_RE.sub(lambda mm: Path(mm.group()).name, text[last:]))
    return "".join(out)


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
        transport: Optional[MailTransport] = None,
        trigger: str = "manual",
    ) -> None:
        """`service` (existing googleapiclient Resource) and `transport` (an
        already-built MailTransport, e.g. from mail_client.set_token() on
        Android) are mutually exclusive — pass at most one. `service` is
        wrapped in a DiscoveryTransport internally so callers below this
        class never touch a raw service object.

        `trigger` is recorded on each opened sync_runs row (e.g. "manual",
        "watched_folder") — purely descriptive, for the Android Sync log.
        """
        if service is not None and transport is not None:
            raise ValueError("Pass at most one of service= or transport=, not both")
        self.transport = transport if transport is not None else (
            DiscoveryTransport(service) if service is not None else None
        )
        self.chunk_size = chunk_size
        self.dry_run = dry_run
        self.trigger = trigger
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
            run_id = start_sync_run(chat_id, trigger=self.trigger, db_path=self.db_path)

        # Parse + dedup the file.
        try:
            all_messages, new_messages, n_skipped = self._parse_and_filter(
                filepath, chat_id, last_ts
            )
        except Exception as exc:
            msg = f"{filepath.name}: parse error — {_scrub_paths(str(exc))}"
            log.error(msg)
            stats.files_failed += 1
            stats.errors.append(msg)
            if run_id is not None:
                fail_sync_run(run_id, str(exc), self.db_path)
            return

        stats.messages_parsed += len(all_messages)
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

        # Retrieve cached mail IDs. The columns are still named gmail_* (renaming
        # them would need a migration for no functional gain), but on the IMAP
        # backend they hold that transport's folder and thread identifiers.
        chat_row = get_chat(chat_id, self.db_path)
        label_id = chat_row["gmail_label_id"] if chat_row else None
        thread_id = chat_row["gmail_thread_id"] if chat_row else None
        anchor_mid = chat_row["anchor_message_id"] if chat_row else None

        # Push to the mailbox. Hashes are recorded per-chunk as each one is
        # confirmed delivered (see _record_chunk() below) rather than in one
        # bulk insert after push_chat() returns — push_chat sends the file in
        # multiple separate mailbox writes, and if the process dies partway
        # through, a single end-of-call insert would leave the DB believing
        # nothing was pushed even though earlier chunks already landed in the
        # mailbox. On the next run, _recover_run()'s "already_pushed" lookup
        # would then come back empty and re-push the entire chat — a real
        # incident this has already caused. Recording incrementally means an
        # interruption leaves the DB matching exactly what reached the mailbox.
        def _record_chunk(i, total, done, tmsgs, chunk_msgs):
            insert_message_hashes(
                _build_hash_entries(chunk_msgs, run_id), self.db_path
            )
            self._on_chunk_progress(display_name, i, total, done, tmsgs)

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
                on_chunk=_record_chunk,
            )
        except Exception as exc:
            msg = f"{display_name}: Mail push failed — {_scrub_paths(str(exc))}"
            log.error(msg)
            stats.files_failed += 1
            stats.errors.append(msg)
            if run_id is not None:
                fail_sync_run(run_id, str(exc), self.db_path)
            return

        # Persist mail IDs (thread ID and anchor Message-ID from first chunk).
        new_anchor_mid = (
            results[0].message_id
            if (anchor_mid is None and results)
            else anchor_mid
        )
        update_chat_gmail_ids(
            chat_id, thread_id, label_id, new_anchor_mid, self.db_path
        )

        # Message hashes for every pushed chunk were already recorded
        # incrementally by _record_chunk() above as push_chat() progressed;
        # nothing left to insert here. (insert_message_hashes uses INSERT OR
        # IGNORE, so there would be no harm in a redundant call, but there's
        # also nothing left for it to do — every message in new_messages
        # belongs to exactly one chunk, and on_chunk fires for all of them.)

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

    def _on_chunk_progress(
        self, display_name: str, chunk_index: int, total_chunks: int,
        msgs_done: int, total_msgs: int,
    ) -> None:
        """Hook for subclasses (ProgressSyncManager) to observe within-file
        push progress. No-op on the plain CLI/GUI-facing SyncManager."""

    def _parse_and_filter(
        self, filepath: Path, chat_id: str, last_synced_ts: Optional[str]
    ) -> tuple[list[ParsedMessage], list[ParsedMessage], int]:
        """Parse a file and apply dedup. Raises on parse failure (caller
        handles). Hook point so ProgressSyncManager's pre-scan (which needs
        to parse + dedup every file upfront to size its progress denominator)
        can cache its results here instead of every file being parsed twice.
        """
        all_messages = list(parse_file(filepath, chat_id))
        new_messages, n_skipped = self._filter_messages(all_messages, chat_id, last_synced_ts)
        return all_messages, new_messages, n_skipped

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

        # Push remaining chunks. Same incremental-hash treatment as
        # _sync_file() above and for the same reason: this is itself a
        # recovery path, so it above all others must not repeat the original
        # bug — if *this* push gets interrupted too, the hashes for whatever
        # it did manage to deliver must already be recorded rather than lost
        # again pending a second recovery.
        def _record_chunk(i, total, done, tmsgs, chunk_msgs):
            insert_message_hashes(
                _build_hash_entries(chunk_msgs, run_id), self.db_path
            )

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
                on_chunk=_record_chunk,
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

        # Hashes for `remaining` were already recorded incrementally by
        # _record_chunk() above (see comment before the push_chat() call).

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
        self._total_new_messages = 0
        self._prior_msgs_done = 0
        self._prescan_cache: dict[str, tuple[list, list, int]] = {}

    def run(self, chat_filter: Optional[str] = None) -> SyncStats:
        try:
            files = [
                f for f in self.inbox_dir.iterdir()
                if f.is_file() and f.suffix in (".txt", ".zip", "")
            ]
            self._files_total = len(files)
        except Exception:
            files = []
            self._files_total = 0
        self._pq.put({"type": "files_total", "n": self._files_total})

        # Local-only pre-scan (parse + dedup, no network) so the live progress
        # screen can show one real percentage for the whole sync's actual
        # workload — the number of *new* messages left to push — instead of
        # a file-count that freezes for minutes while one large chat is mid
        # push, or a per-file chunk count that resets to 0 at every new file.
        self._total_new_messages = self._estimate_total_new_messages(files, chat_filter)
        self._prior_msgs_done = 0
        self._pq.put({"type": "total_messages", "n": self._total_new_messages})

        return super().run(chat_filter=chat_filter)

    def _estimate_total_new_messages(self, files, chat_filter: Optional[str]) -> int:
        """Parse + dedup every file upfront to size the progress denominator.

        Results are cached and consumed by _parse_and_filter() below, so
        _sync_file's own parse+dedup step reuses this instead of parsing
        each file a second time — one pass over the inbox, not two.
        """
        total = 0
        for filepath in files:
            chat_id, display_name = extract_chat_info(filepath.name)
            if chat_filter and chat_filter.lower() not in (
                chat_id, display_name.lower()
            ):
                continue
            last_run = get_last_successful_run(chat_id, self.db_path)
            last_ts = last_run["last_synced_ts"] if last_run else None
            try:
                all_messages, new_messages, n_skipped = super()._parse_and_filter(
                    filepath, chat_id, last_ts
                )
            except Exception:
                continue
            self._prescan_cache[str(filepath)] = (all_messages, new_messages, n_skipped)
            total += len(new_messages)
        return total

    def _parse_and_filter(self, filepath: Path, chat_id: str, last_synced_ts):
        cached = self._prescan_cache.pop(str(filepath), None)
        if cached is not None:
            return cached
        return super()._parse_and_filter(filepath, chat_id, last_synced_ts)

    def _sync_file(self, filepath, chat_id, display_name, stats) -> None:
        # Honour a stop request between files — current file is never started,
        # so no partial state is written to the DB.
        if self._stop_event.is_set():
            return
        self._pq.put({"type": "syncing", "name": display_name})
        super()._sync_file(filepath, chat_id, display_name, stats)
        self._files_done += 1
        self._prior_msgs_done = stats.messages_synced
        self._pq.put({
            "type": "file_done",
            "done": self._files_done,
            "total": self._files_total,
        })

    def _on_chunk_progress(
        self, display_name: str, chunk_index: int, total_chunks: int,
        msgs_done: int, total_msgs: int,
    ) -> None:
        self._pq.put({
            "type": "chunk",
            "name": display_name,
            "chunk": chunk_index,
            "total_chunks": total_chunks,
            "msgs_done": msgs_done,
            "total_msgs": total_msgs,
            "global_done": self._prior_msgs_done + msgs_done,
            "global_total": self._total_new_messages,
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
