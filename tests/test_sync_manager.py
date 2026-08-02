"""Integration tests for the partial-sync-interruption bug: a crash partway
through push_chat() must leave message_hashes reflecting exactly what
reached the mailbox, and a subsequent recovery run must push only what's
left — never zero (would leave a gap) and never the whole chat again
(would duplicate already-delivered messages, which is exactly what
happened in production before this fix)."""

from pathlib import Path
from typing import Optional

import pytest

from src import config, sync_manager as sync_manager_module
from src.sync_manager import SyncManager
from src.state import get_hashes_for_run, get_pending_runs


class FakeTransport:
    """MailTransport double: labels always resolve trivially, and
    messages_insert() succeeds for every chunk up to (but not including)
    `fail_at` (1-based call count across the whole test, i.e. counting
    only message inserts, not label calls), then raises a plain
    RuntimeError — a stand-in for the process being killed mid-push,
    not a retryable transport error."""

    def __init__(self, fail_at: Optional[int] = None):
        self.fail_at = fail_at
        self.insert_calls = 0

    def labels_list(self) -> dict:
        return {"labels": []}

    def labels_create(self, body: dict) -> dict:
        return {"id": "Label_WA", "name": body.get("name", "")}

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict:
        self.insert_calls += 1
        if self.fail_at is not None and self.insert_calls == self.fail_at:
            raise RuntimeError("simulated crash mid-push")
        return {
            "id": f"m{self.insert_calls}",
            "threadId": thread_id or f"t{self.insert_calls}",
        }


# A 5-message, 5-distinct-day WhatsApp export using the "plain_24h" format
# (no brackets, 24h time, no seconds — the common iPhone/India-export shape).
# Days are chosen > 12 (20-24) so DD/MM/YY date-order detection is
# unambiguous, and each message lands on its own calendar day so the
# default "day" chunk size naturally yields one chunk per message — no
# need to override chunk_size to get predictable, individually-addressable
# chunks for these tests.
_CHAT_TEXT = "\n".join(
    f"{20 + i}/03/25, 09:00 - Alice: message {i}" for i in range(5)
) + "\n"


def _write_chat_file(inbox_dir: Path) -> Path:
    inbox_dir.mkdir(parents=True, exist_ok=True)
    path = inbox_dir / "WhatsApp Chat with Alice.txt"
    path.write_text(_CHAT_TEXT, encoding="utf-8")
    return path


def _make_manager(tmp_root: Path, db_path: Path, transport: FakeTransport) -> SyncManager:
    return SyncManager(
        transport=transport,
        db_path=db_path,
        inbox_dir=tmp_root / "inbox",
        processed_dir=tmp_root / "processed",
        trigger="test",
    )


def test_interrupted_push_records_only_the_chunks_that_actually_landed(
    tmp_root, db_path, monkeypatch
):
    inbox_dir = tmp_root / "inbox"
    _write_chat_file(inbox_dir)

    # Fail on the 3rd chunk of 5 — 2 chunks (10 total: label create + inserts,
    # but fail_at counts message inserts only) must have already landed.
    transport = FakeTransport(fail_at=3)
    manager = _make_manager(tmp_root, db_path, transport)

    # A caught Python exception normally reaches fail_sync_run(), which marks
    # the run 'failed' — but get_pending_runs() only recovers rows still
    # 'pending'. A real process kill never gets that far, so to faithfully
    # simulate "the process died before it could even record the failure",
    # make fail_sync_run itself blow up instead of updating the row. The
    # run then stays exactly where start_sync_run() left it: 'pending'.
    def _boom(*args, **kwargs):
        raise RuntimeError("process died before recording failure")

    monkeypatch.setattr(sync_manager_module, "fail_sync_run", _boom)

    with pytest.raises(RuntimeError):
        manager.run()

    pending = get_pending_runs(db_path)
    assert len(pending) == 1
    run_id = pending[0]["run_id"]

    # Exactly 2 chunks (messages 0 and 1) were confirmed delivered before
    # chunk 3 raised — the DB must show precisely that, not 0 and not 5.
    hashes = get_hashes_for_run(run_id, db_path)
    assert len(hashes) == 2

    # The source file must still be sitting in inbox/ — it's only moved to
    # processed/ once the whole push (and complete_sync_run) succeeds.
    assert (inbox_dir / "WhatsApp Chat with Alice.txt").exists()
    assert not (tmp_root / "processed" / "WhatsApp Chat with Alice.txt").exists()


def test_recovery_pushes_only_the_remaining_messages(tmp_root, db_path, monkeypatch):
    inbox_dir = tmp_root / "inbox"
    _write_chat_file(inbox_dir)

    crashy_transport = FakeTransport(fail_at=3)
    manager = _make_manager(tmp_root, db_path, crashy_transport)

    def _boom(*args, **kwargs):
        raise RuntimeError("process died before recording failure")

    monkeypatch.setattr(sync_manager_module, "fail_sync_run", _boom)

    with pytest.raises(RuntimeError):
        manager.run()

    pending = get_pending_runs(db_path)
    run_id = pending[0]["run_id"]
    assert len(get_hashes_for_run(run_id, db_path)) == 2

    # Recovery runs with a healthy transport (no monkeypatched fail_sync_run
    # this time — a real recovery should succeed outright).
    monkeypatch.undo()
    healthy_transport = FakeTransport(fail_at=None)
    recovery_manager = _make_manager(tmp_root, db_path, healthy_transport)

    stats = recovery_manager.run()

    assert stats.chats_recovered == 1
    # Only the 3 undelivered messages (days 2, 3, 4) should have been pushed
    # during recovery — not all 5, which is exactly the duplication bug this
    # fix prevents.
    assert healthy_transport.insert_calls == 3

    # The run is now fully resolved: all 5 messages have hashes recorded,
    # and the file has been moved out of inbox/ into processed/.
    all_hashes = get_hashes_for_run(run_id, db_path)
    assert len(all_hashes) == 5
    assert not (inbox_dir / "WhatsApp Chat with Alice.txt").exists()
    assert (tmp_root / "processed" / "WhatsApp Chat with Alice.txt").exists()
    assert get_pending_runs(db_path) == []
