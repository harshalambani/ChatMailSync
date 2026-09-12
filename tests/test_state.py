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


def _pretend_db_predates_the_sweep(db_path):
    """The fixture builds its database through init_db, which stamps the
    schema version -- so a test has to wind it back to look like an install
    made before the sweep existed, which is the only kind that has duplicates.
    """
    import sqlite3

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA user_version = 0")
    conn.commit()
    conn.close()


def _clone_run(db_path, run_id):
    """Append a byte-identical copy of an existing run, the way the pre-fix
    bundle merge did."""
    import sqlite3

    cols = ", ".join(state.RUN_NATURAL_KEY)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute(
        f"SELECT {cols} FROM sync_runs WHERE run_id = ?", (run_id,)
    ).fetchone()
    cur = conn.execute(
        f"INSERT INTO sync_runs ({cols}) VALUES ({', '.join('?' * len(state.RUN_NATURAL_KEY))})",
        tuple(row[c] for c in state.RUN_NATURAL_KEY),
    )
    new_id = int(cur.lastrowid)
    conn.commit()
    conn.close()
    return new_id


def test_init_db_sweeps_runs_an_old_restore_duplicated(db_path):
    """A restore onto the phone the backup came from doubled the whole sync
    log, and nothing in the app could tell a copy from the original. Merging
    no longer does that, but the rows it already wrote have to go -- once, on
    the next start, without taking any hashes down with them."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    h = state.compute_message_hash("chat1", "2025-03-14T09:41:00", "Alice", "Hello")
    state.insert_message_hashes([(h, "chat1", "2025-03-14T09:41:00", run_id)], db_path)
    state.complete_sync_run(
        run_id, last_synced_ts="2025-03-14T09:41:00", last_synced_hash=h,
        messages_parsed=1, messages_synced=1, messages_skipped=0, db_path=db_path,
    )

    copy_id = _clone_run(db_path, run_id)
    # A hash hanging off the copy must survive, repointed -- a sweep that
    # silently dropped archived messages would re-send them.
    h2 = state.compute_message_hash("chat1", "2025-03-14T09:42:00", "Alice", "Again")
    state.insert_message_hashes([(h2, "chat1", "2025-03-14T09:42:00", copy_id)], db_path)
    assert len(state.get_recent_runs(db_path=db_path)) == 2

    _pretend_db_predates_the_sweep(db_path)
    state.init_db(db_path)

    runs = state.get_recent_runs(db_path=db_path)
    assert len(runs) == 1
    assert int(runs[0]["run_id"]) == run_id      # the original is the one kept
    assert state.hash_exists(h, db_path)
    assert state.hash_exists(h2, db_path)
    assert state.get_hashes_for_run(run_id, db_path) == {h, h2}


def test_a_real_second_run_is_not_mistaken_for_a_duplicate(db_path):
    """Two runs of the same chat that genuinely happened differ in their
    timestamps, and the sweep must never collapse them -- the sync log is the
    only record the user has of what the app did."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    first = state.start_sync_run("chat1", db_path=db_path)
    state.complete_sync_run(
        first, last_synced_ts="2025-03-14T09:41:00", last_synced_hash="a",
        messages_parsed=1, messages_synced=1, messages_skipped=0, db_path=db_path,
    )
    second = state.start_sync_run("chat1", db_path=db_path)
    state.complete_sync_run(
        second, last_synced_ts="2025-03-15T09:41:00", last_synced_hash="b",
        messages_parsed=2, messages_synced=1, messages_skipped=1, db_path=db_path,
    )

    _pretend_db_predates_the_sweep(db_path)
    state.init_db(db_path)

    assert len(state.get_recent_runs(db_path=db_path)) == 2


def test_the_sweep_runs_once_not_on_every_start(db_path):
    """It is a repair, not a rule. A duplicate written after the sweep has run
    is a different bug, and quietly eating it would hide that."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    state.complete_sync_run(
        run_id, last_synced_ts="2025-03-14T09:41:00", last_synced_hash="a",
        messages_parsed=1, messages_synced=1, messages_skipped=0, db_path=db_path,
    )
    state.init_db(db_path)                        # sweep happens here

    _clone_run(db_path, run_id)
    state.init_db(db_path)

    assert len(state.get_recent_runs(db_path=db_path)) == 2


# ---------------------------------------------------------------------------
# The cutoff date
# ---------------------------------------------------------------------------

_V1_DDL = """
PRAGMA journal_mode = WAL;

CREATE TABLE chats (
    chat_id          TEXT PRIMARY KEY,
    display_name     TEXT NOT NULL,
    source_filename  TEXT,
    gmail_thread_id  TEXT,
    gmail_label_id   TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL
);

CREATE TABLE sync_runs (
    run_id           INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id          TEXT NOT NULL REFERENCES chats(chat_id),
    status           TEXT NOT NULL CHECK(status IN ('pending','complete','failed')),
    trigger          TEXT NOT NULL DEFAULT 'manual',
    last_synced_ts   TEXT,
    last_synced_hash TEXT,
    messages_parsed  INTEGER NOT NULL DEFAULT 0,
    messages_synced  INTEGER NOT NULL DEFAULT 0,
    messages_skipped INTEGER NOT NULL DEFAULT 0,
    error_message    TEXT,
    started_at       TEXT NOT NULL,
    completed_at     TEXT
);

CREATE TABLE message_hashes (
    hash        TEXT PRIMARY KEY,
    chat_id     TEXT NOT NULL,
    message_ts  TEXT NOT NULL,
    run_id      INTEGER NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
"""


def _build_a_v1_database(path):
    """A database shaped the way version 1 shipped it: no chat_cutoffs table,
    no messages_cutoff column, and one completed run already in the log.

    Written out here rather than taken from state._DDL on purpose. The point of
    the test is that today's code can open yesterday's file, and reading the
    schema out of today's module would make that assertion vacuous the moment
    the DDL changed again.
    """
    import sqlite3

    conn = sqlite3.connect(path)
    conn.executescript(_V1_DDL)
    conn.execute(
        "INSERT INTO chats (chat_id, display_name, source_filename, created_at, updated_at) "
        "VALUES ('chat1', 'Chat One', 'chat1.txt', '2025-03-01T00:00:00', '2025-03-01T00:00:00')"
    )
    cur = conn.execute(
        "INSERT INTO sync_runs (chat_id, status, trigger, last_synced_ts, last_synced_hash, "
        "messages_parsed, messages_synced, messages_skipped, started_at, completed_at) "
        "VALUES ('chat1', 'complete', 'manual', '2025-03-14T09:41:00', 'deadbeef', "
        "3, 3, 0, '2025-03-14T09:40:00', '2025-03-14T09:42:00')"
    )
    run_id = int(cur.lastrowid)
    conn.execute("PRAGMA user_version = 1")
    conn.commit()
    conn.close()
    return run_id


def test_a_version_1_database_gains_the_cutoff_table_and_column(tmp_path):
    """Plan test 9. Opening an existing install must add both, and must not
    disturb the run log while doing it -- that log is the only record the user
    has of what the app has already sent, and a migration that dropped or
    rewrote it would be indistinguishable from data loss.
    """
    import sqlite3

    db_path = tmp_path / "v1.db"
    run_id = _build_a_v1_database(db_path)

    state.init_db(db_path)

    # get_run, not get_recent_runs: the latter is a 90-day display window and
    # this fixture's run is deliberately older than that.
    run = state.get_run(run_id, db_path)
    assert run["last_synced_ts"] == "2025-03-14T09:41:00"
    assert run["messages_parsed"] == 3
    # The column exists and the pre-existing row reads back as zero rather
    # than NULL: nothing was ever filtered by a cutoff before it existed.
    assert run["messages_cutoff"] == 0

    # And the new table is there and usable.
    state.set_chat_cutoff("chat1", "2026-01-01", db_path=db_path)
    assert state.get_chat_cutoff("chat1", db_path) == "2026-01-01T00:00:00"

    conn = sqlite3.connect(db_path)
    version = int(conn.execute("PRAGMA user_version").fetchone()[0])
    conn.close()
    assert version == state._SCHEMA_VERSION


def test_a_cutoff_normalises_to_midnight_on_the_day_asked_for(db_path):
    """A message stamped 00:00:00 on the cutoff day is on the day the user
    asked for, so the stored instant is the start of that day and the filter
    compares with `<`. Store noon instead and the first message of the day
    disappears with nothing to show for it.
    """
    assert state.normalise_cutoff("2026-01-01") == "2026-01-01T00:00:00"
    # A full timestamp is truncated to its day, not kept as given.
    assert state.normalise_cutoff("2026-01-01T17:30:00") == "2026-01-01T00:00:00"
    assert state.normalise_cutoff("  2026-01-01  ") == "2026-01-01T00:00:00"


def test_blank_and_missing_mean_the_same_thing(db_path):
    """A cleared date field hands back "", and an unset one hands back None.
    They must arrive downstream as the same thing: "" is a string that sorts
    below every real timestamp, so a cutoff of "" that survived would be a
    cutoff that silently matches nothing while looking set.
    """
    assert state.normalise_cutoff(None) is None
    assert state.normalise_cutoff("") is None
    assert state.normalise_cutoff("   ") is None

    state.set_chat_cutoff("chat1", "2026-01-01", db_path=db_path)
    state.set_chat_cutoff("chat1", "", db_path=db_path)
    # Clearing through the setter leaves no row, so the chat inherits the
    # app-wide cutoff again rather than carrying an empty one of its own.
    assert state.get_chat_cutoff("chat1", db_path) is None


def test_a_date_the_app_cannot_compare_is_refused(db_path):
    """Stored unchecked, "01/01/2026" would sort below every ISO timestamp in
    the database and quietly filter out the entire history."""
    with pytest.raises(ValueError):
        state.normalise_cutoff("01/01/2026")
    with pytest.raises(ValueError):
        state.set_chat_cutoff("chat1", "next tuesday", db_path=db_path)


def test_a_cutoff_can_be_set_on_a_chat_that_has_never_synced(db_path):
    """No foreign key to chats, deliberately. The chats table is what both
    front-ends and the CLI status command list, so writing a row there to hang
    a date off would put a chat with no runs and no mail folder into all three
    -- when all the user did was set a date in the import preview.
    """
    state.set_chat_cutoff("never-synced", "2026-01-01", db_path=db_path)

    assert state.get_chat_cutoff("never-synced", db_path) == "2026-01-01T00:00:00"
    assert state.list_chats(db_path) == []


def test_setting_a_cutoff_twice_replaces_rather_than_duplicates(db_path):
    state.set_chat_cutoff("chat1", "2026-01-01", db_path=db_path)
    state.set_chat_cutoff("chat1", "2026-06-01", db_path=db_path)

    assert state.get_chat_cutoff("chat1", db_path) == "2026-06-01T00:00:00"
    assert state.list_chat_cutoffs(db_path) == {"chat1": "2026-06-01T00:00:00"}


def test_deleting_a_chat_takes_its_cutoff_with_it(db_path):
    """Otherwise the row outlives the chat, and re-adding that chat later
    would silently inherit a floor the user set and then deleted."""
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    state.set_chat_cutoff("chat1", "2026-01-01", db_path=db_path)

    state.delete_chat("chat1", db_path)

    assert state.get_chat_cutoff("chat1", db_path) is None


def test_the_cutoff_count_is_stored_apart_from_the_skipped_count(db_path):
    """Two different facts. messages_skipped is what the deduplicator decided
    it had already sent; messages_cutoff is what the user asked not to have.
    Added together they would make a cutoff set by mistake look exactly like a
    run that found nothing new.
    """
    state.upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    run_id = state.start_sync_run("chat1", db_path=db_path)
    state.complete_sync_run(
        run_id,
        last_synced_ts="2026-03-14T09:41:00",
        last_synced_hash="deadbeef",
        messages_parsed=10,
        messages_synced=3,
        messages_skipped=2,
        messages_cutoff=5,
        db_path=db_path,
    )

    run = state.get_run(run_id, db_path)
    assert run["messages_skipped"] == 2
    assert run["messages_cutoff"] == 5


def test_the_cutoff_count_stays_out_of_the_run_natural_key():
    """migration.py SELECTs exactly these columns out of an incoming bundle.
    A backup written before the column existed has no such column, so adding
    it here would turn every older bundle into a restore that fails on its
    first query.
    """
    assert "messages_cutoff" not in state.RUN_NATURAL_KEY


# ---------------------------------------------------------------------------
# app_state: small shared key/value facts that are not per-chat
# ---------------------------------------------------------------------------

def test_app_state_round_trip(db_path):
    assert state.get_app_state(state.SELF_SENDER_LEARNED, db_path) is None
    state.set_app_state(state.SELF_SENDER_LEARNED, "Sam Iyer", db_path)
    assert state.get_app_state(state.SELF_SENDER_LEARNED, db_path) == "Sam Iyer"


def test_app_state_writes_replace_rather_than_duplicate(db_path):
    state.set_app_state(state.SELF_SENDER_LEARNED, "Sam Iyer", db_path)
    state.set_app_state(state.SELF_SENDER_LEARNED, "Samir Iyer", db_path)
    assert state.get_app_state(state.SELF_SENDER_LEARNED, db_path) == "Samir Iyer"


@pytest.mark.parametrize("blank", ["", "   ", None])
def test_a_blank_value_clears_the_key(db_path, blank):
    """Emptying the override box has to mean "no override", not "an owner whose
    name is the empty string" -- otherwise every sender would match it."""
    state.set_app_state(state.SELF_SENDER_OVERRIDE, "Sam Iyer", db_path)
    state.set_app_state(state.SELF_SENDER_OVERRIDE, blank, db_path)
    assert state.get_app_state(state.SELF_SENDER_OVERRIDE, db_path) is None


def test_app_state_keys_do_not_collide(db_path):
    state.set_app_state(state.SELF_SENDER_OVERRIDE, "Sam", db_path)
    state.set_app_state(state.SELF_SENDER_LEARNED, "Sam Iyer", db_path)
    assert state.get_app_state(state.SELF_SENDER_OVERRIDE, db_path) == "Sam"
    assert state.get_app_state(state.SELF_SENDER_LEARNED, db_path) == "Sam Iyer"
