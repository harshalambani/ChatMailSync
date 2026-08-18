import pytest

from src import state


def test_init_db_is_idempotent(db_path):
    state.init_db(db_path)  # second call, same db_path — must not raise
    assert db_path.exists()


def test_upsert_chat_round_trip_and_update(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    row = state.get_chat("chat1", db_path)
    assert row["display_name"] == "Chat One"
    assert row["gmail_thread_id"] is None

    # Second upsert for the same chat_id updates rather than duplicates.
    state.upsert_chat("chat1", "Chat One", "chat1.txt", gmail_thread_id="thread-1", db_path=db_path)
    rows = state.list_chats(db_path)
    assert len(rows) == 1
    assert rows[0]["gmail_thread_id"] == "thread-1"


def test_compute_message_hash_deterministic_and_sensitive():
    h1 = state.compute_message_hash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
    h2 = state.compute_message_hash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
    assert h1 == h2

    for changed in [
        ("chat2", "2025-03-14T09:41:00", "Alice", "Hello"),
        ("chat1", "2025-03-14T09:41:01", "Alice", "Hello"),
        ("chat1", "2025-03-14T09:41:00", "Bob", "Hello"),
        ("chat1", "2025-03-14T09:41:00", "Alice", "Hello!"),
    ]:
        assert state.compute_message_hash(*changed) != h1


def test_hash_exists_and_insert_round_trip(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    h = state.compute_message_hash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")

    assert not state.hash_exists(h, db_path)
    state.insert_message_hashes([(h, "chat1", "2025-03-14T09:41:00", run_id)], db_path)
    assert state.hash_exists(h, db_path)


def test_sync_run_lifecycle_complete(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    state.complete_sync_run(
        run_id,
        last_synced_ts="2025-03-14T09:41:00",
        last_synced_hash="deadbeef",
        messages_parsed=3,
        messages_synced=3,
        messages_skipped=0,
        db_path=db_path,
    )
    last_run = state.get_last_successful_run("chat1", db_path)
    assert last_run is not None
    assert last_run["run_id"] == run_id
    assert last_run["status"] == "complete"


def test_sync_run_lifecycle_failed_is_not_pending(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    state.fail_sync_run(run_id, "network error", db_path)

    assert state.get_last_successful_run("chat1", db_path) is None
    assert run_id not in [r["run_id"] for r in state.get_pending_runs(db_path)]


def test_get_pending_runs_finds_crashed_run(db_path):
    """Simulates a crash: a run left in 'pending' status (never completed or
    failed) must be surfaced so SyncManager's recovery path can find it."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)

    pending = state.get_pending_runs(db_path)
    assert run_id in [r["run_id"] for r in pending]


def test_get_recent_runs_excludes_runs_outside_window(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", trigger="watched_folder", db_path=db_path)

    recent = state.get_recent_runs(days=90, db_path=db_path)
    assert len(recent) == 1
    assert recent[0]["run_id"] == run_id
    assert recent[0]["trigger"] == "watched_folder"
    assert recent[0]["display_name"] == "Chat One"

    # A negative window (cutoff in the future) excludes a run that just started.
    assert state.get_recent_runs(days=-1, db_path=db_path) == []


def test_is_uneventful_run_never_hides_a_failure():
    """The whole point of folding no-op runs away is that the eventful ones
    stay findable -- so a failed run is never uneventful no matter how little
    it uploaded, and neither is one that has not finished."""
    assert state.is_uneventful_run({"status": "complete", "messages_synced": 0})
    assert state.is_uneventful_run({"status": "complete", "messages_synced": None})
    assert not state.is_uneventful_run({"status": "complete", "messages_synced": 3})
    assert not state.is_uneventful_run({"status": "failed", "messages_synced": 0})
    assert not state.is_uneventful_run({"status": "pending", "messages_synced": 0})


def test_summarize_recent_runs_is_empty_before_anything_runs(db_path):
    """Both home screens hide the status block on this shape rather than
    showing an outcome that does not exist yet."""
    summary = state.summarize_recent_runs(db_path=db_path)
    assert summary["total_runs"] == 0
    assert summary["failed_runs"] == 0
    assert summary["last_status"] is None
    assert summary["window_days"] == 90


def test_summarize_recent_runs_reports_the_last_finished_run(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    state.complete_sync_run(
        run_id,
        last_synced_ts="2025-03-14T09:41:00",
        last_synced_hash="deadbeef",
        messages_parsed=5,
        messages_synced=3,
        messages_skipped=2,
        db_path=db_path,
    )

    summary = state.summarize_recent_runs(db_path=db_path)
    assert summary["last_status"] == "complete"
    assert summary["last_display_name"] == "Chat One"
    assert summary["last_messages_synced"] == 3
    assert summary["last_messages_skipped"] == 2
    assert summary["failed_runs"] == 0
    assert summary["running_runs"] == 0


def test_summarize_recent_runs_counts_failures_and_keeps_the_last_outcome(db_path):
    """A run starting must not blank out the outcome of the one before it --
    the status block would flip to saying nothing every time a sync began."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    failed = state.start_sync_run("chat1", db_path=db_path)
    state.fail_sync_run(failed, "network error", db_path)
    state.start_sync_run("chat1", db_path=db_path)  # still pending

    summary = state.summarize_recent_runs(db_path=db_path)
    assert summary["total_runs"] == 2
    assert summary["failed_runs"] == 1
    assert summary["running_runs"] == 1
    # The pending run is newer, but it has no outcome to report yet.
    assert summary["last_status"] == "failed"


def test_reset_chat_isolates_other_chats(db_path):
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    state.upsert_chat("chat2", "Chat Two", "chat2.txt", db_path=db_path)
    state.update_chat_gmail_ids("chat1", gmail_thread_id="t1", gmail_label_id="l1", db_path=db_path)
    state.update_chat_gmail_ids("chat2", gmail_thread_id="t2", gmail_label_id="l2", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    h = state.compute_message_hash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
    state.insert_message_hashes([(h, "chat1", "2025-03-14T09:41:00", run_id)], db_path)

    state.reset_chat("chat1", db_path, confirmed_mailbox_cleared=True)

    chat1 = state.get_chat("chat1", db_path)
    assert chat1["gmail_thread_id"] is None
    assert not state.hash_exists(h, db_path)

    chat2 = state.get_chat("chat2", db_path)
    assert chat2["gmail_thread_id"] == "t2"


def test_reset_chat_refuses_while_mail_is_archived(db_path):
    """The gate: unconfirmed reset of a chat with sent mail must not proceed.

    Resetting clears the hash table, which is the only record that a message was
    ever sent - so an unconfirmed reset silently sets up a duplicate of every
    archived message on the next sync. Refusing is the point of the feature.
    """
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    state.update_chat_gmail_ids("chat1", gmail_thread_id="t1", gmail_label_id="l1", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    h = state.compute_message_hash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
    state.insert_message_hashes([(h, "chat1", "2025-03-14T09:41:00", run_id)], db_path)

    assert state.count_archived_messages("chat1", db_path) == 1

    with pytest.raises(state.MailboxNotClearedError) as excinfo:
        state.reset_chat("chat1", db_path)
    assert excinfo.value.archived_count == 1

    # Nothing was touched on the way out - a refused reset must leave the chat
    # exactly as it was, or the "safe" path would itself cause the duplication.
    assert state.hash_exists(h, db_path)
    assert state.get_chat("chat1", db_path)["gmail_thread_id"] == "t1"


def test_reset_chat_allows_reset_when_nothing_archived(db_path):
    """No mail sent means no duplicate possible, so no confirmation is demanded."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    state.update_chat_gmail_ids("chat1", gmail_thread_id="t1", gmail_label_id="l1", db_path=db_path)

    assert state.count_archived_messages("chat1", db_path) == 0
    state.reset_chat("chat1", db_path)

    assert state.get_chat("chat1", db_path)["gmail_thread_id"] is None
