"""Tests for the shared progress model (src/progress.py).

Both front-ends render from ProgressState now instead of each deriving its
own labels from the raw event stream, so the strings and the bar rules are
worth pinning here rather than only inside the Windows GUI tests: a change
that reads fine on one platform has to survive on the other, and this is the
file that says what both of them will show.
"""

import types

import pytest

from src.progress import (
    PHASE_DONE,
    PHASE_FAILED,
    PHASE_SCANNING,
    PHASE_SYNCING,
    UNKNOWN,
    MAX_MILESTONES,
    ProgressTracker,
)


def _chunk(**over):
    event = {
        "type": "chunk", "name": "Kartik Patel",
        "chunk": 2, "total_chunks": 9,
        "msgs_done": 120, "total_msgs": 540,
        "global_done": 120, "global_total": 900,
    }
    event.update(over)
    return event


def _fed(*events):
    tracker = ProgressTracker()
    for event in events:
        tracker.feed(event)
    return tracker.state


# ---------------------------------------------------------------------------
# what each event says
# ---------------------------------------------------------------------------

def test_a_fresh_tracker_claims_no_percentage():
    """Before the first file there is no honest number, and inventing one is
    how a bar ends up jumping backwards a second later."""
    state = ProgressTracker().state
    assert state.fraction == UNKNOWN
    assert state.percent == -1


def test_file_total_before_the_first_file():
    assert _fed({"type": "files_total", "n": 3}).headline == "Found 3 file(s)…"
    assert _fed({"type": "files_total", "n": 3}).phase == PHASE_SCANNING


def test_an_empty_inbox_says_so_rather_than_counting_zero():
    state = _fed({"type": "files_total", "n": 0})
    assert state.headline == "Inbox is empty"
    assert state.milestones == ["Inbox is empty"]


def test_syncing_names_the_chat_being_pushed():
    state = _fed({"type": "syncing", "name": "Alice"})
    assert state.phase == PHASE_SYNCING
    assert state.chat == "Alice"
    assert state.headline == "Syncing: Alice"
    assert state.milestones == ["Starting: Alice"]


def test_chunk_builds_the_one_line_both_front_ends_show():
    state = _fed(_chunk())
    assert state.line == "Syncing: Kartik Patel — 120 / 540 messages"
    assert state.fraction == pytest.approx(120 / 900)
    assert state.percent == 13


def test_file_done_reports_files_and_moves_the_bar():
    state = _fed({"type": "file_done", "done": 1, "total": 2})
    assert state.line == "1 / 2 files"
    assert state.fraction == pytest.approx(0.5)


# ---------------------------------------------------------------------------
# the bar
# ---------------------------------------------------------------------------

def test_the_fraction_never_goes_backwards_at_a_file_boundary():
    """chunk counts messages across the whole sync, file_done counts files.
    Finishing 1 of 3 files is 1/3 -- but if that file was most of the work the
    message count is already past half, and a bar that retreats reads as a
    bug. Whichever source is further along wins."""
    tracker = ProgressTracker()
    tracker.feed(_chunk(global_done=600, global_total=900))
    assert tracker.state.fraction == pytest.approx(600 / 900)

    tracker.feed({"type": "file_done", "done": 1, "total": 3})
    assert tracker.state.fraction == pytest.approx(600 / 900)
    assert tracker.state.headline == "1 / 3 files"  # text still tells the truth

    tracker.feed({"type": "file_done", "done": 3, "total": 3})
    assert tracker.state.fraction == pytest.approx(1.0)


def test_a_run_with_nothing_to_push_leaves_the_bar_unknown():
    """All-dedup-skip runs push zero messages, so the totals are zero. That is
    an indeterminate bar, not a division."""
    state = _fed(_chunk(global_done=0, global_total=0, msgs_done=0, total_msgs=0))
    assert state.fraction == UNKNOWN


def test_the_fraction_is_clamped_to_one():
    state = _fed({"type": "file_done", "done": 5, "total": 3})
    assert state.fraction == pytest.approx(1.0)


def test_a_malformed_event_does_not_kill_the_poll_loop():
    """Events cross the Chaquopy boundary; a bad field costs detail, not the
    250ms loop that Android's notification rides on."""
    state = _fed(_chunk(global_done=None, global_total="lots", msgs_done=None))
    assert state.fraction == UNKNOWN
    assert state.headline == "Syncing: Kartik Patel"


# ---------------------------------------------------------------------------
# how a run ends
# ---------------------------------------------------------------------------

def test_done_fills_the_bar_and_counts_what_was_synced():
    stats = types.SimpleNamespace(messages_synced=42)
    state = _fed({"type": "done", "stats": stats})
    assert state.phase == PHASE_DONE
    assert state.fraction == pytest.approx(1.0)
    assert state.headline == "Done — 42 msgs synced"


def test_one_message_is_not_pluralised():
    stats = types.SimpleNamespace(messages_synced=1)
    assert _fed({"type": "done", "stats": stats}).headline == "Done — 1 msg synced"


def test_a_stopped_run_says_stopped_not_done():
    """It really did finish -- it just finished early. Calling it a failure
    would bury the real failures; calling it Done would misreport it."""
    state = _fed({"type": "done", "stopped": True})
    assert state.phase == PHASE_DONE
    assert state.headline == "Stopped"


def test_done_without_stats_still_renders():
    """android_api publishes a bare done event -- SyncStats does not marshal
    across Chaquopy, and Kotlin reads the numbers off the worker's Data."""
    assert _fed({"type": "done"}).headline == "Done"


def test_error_points_at_the_log_rather_than_the_exception():
    state = _fed(_chunk(), {"type": "error", "msg": "smtp exploded"})
    assert state.phase == PHASE_FAILED
    assert state.headline == "Failed — see log"
    assert state.chat == ""


# ---------------------------------------------------------------------------
# the milestone log
# ---------------------------------------------------------------------------

def test_chunks_stay_out_of_the_milestone_log():
    """They fire many times per file; in the log they read as noise, not
    history."""
    tracker = ProgressTracker()
    for _ in range(5):
        assert tracker.feed(_chunk()) is None
    assert tracker.state.milestones == []


def test_the_milestone_log_is_bounded():
    """Android ships it through WorkManager Data, which caps the whole payload
    at ~10KB."""
    tracker = ProgressTracker()
    for i in range(MAX_MILESTONES + 20):
        tracker.feed({"type": "file_done", "done": i, "total": 500})
    assert len(tracker.state.milestones) == MAX_MILESTONES
    assert tracker.state.milestones[-1] == "Finished 69 / 500 files"


def test_the_rendered_dict_carries_everything_kotlin_reads():
    tracker = ProgressTracker()
    tracker.feed({"type": "syncing", "name": "Alice"})
    tracker.feed(_chunk(name="Alice"))
    snapshot = tracker.state.as_dict()
    assert snapshot["line"] == "Syncing: Alice — 120 / 540 messages"
    assert snapshot["log"] == "Starting: Alice"
    assert snapshot["chat"] == "Alice"
    assert snapshot["percent"] == 13


def test_polling_the_state_consumes_nothing():
    """The old get_progress_events() drained on read, so whoever looked first
    won and a collapsed bar that appeared late could never catch up. A
    snapshot has to survive being read twice."""
    tracker = ProgressTracker()
    tracker.feed({"type": "syncing", "name": "Alice"})
    assert tracker.state.as_dict() == tracker.state.as_dict()


def test_reset_starts_the_next_run_clean():
    tracker = ProgressTracker()
    tracker.feed(_chunk())
    tracker.feed({"type": "done"})
    tracker.reset()
    assert tracker.state.fraction == UNKNOWN
    assert tracker.state.milestones == []
    assert tracker.state.headline == ""
