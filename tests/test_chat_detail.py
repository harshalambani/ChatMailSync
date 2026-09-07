"""Tests for the chat detail screen the two front-ends now share (batch D3-D5).

Two things here are worth pinning:

  - The Windows GUI can sync a single chat at all. SyncManager.run() has always
    taken a chat_filter, and cli.py --chat and android_api.sync() have always
    passed one, but gui_worker.SyncWorker called run() bare -- so the chat
    detail panel's [Sync just this chat] would have silently synced everything.
    A regression there is invisible in the UI: the run looks normal, it just
    does far more than was asked.

  - The two front-ends say the same three words for a chat's state. Windows
    reads them from _CHAT_STATUS_HEADLINE, Android from the ChatStatus enum,
    and nothing but this test connects the two files.
"""

import re
from pathlib import Path

import gui
import gui_worker


REPO_ROOT = Path(__file__).resolve().parents[1]
CHATS_LIST_KT = (
    REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com"
    / "chatmailsync" / "app" / "ChatsListScreen.kt"
)


# ---------------------------------------------------------------------------
# SyncWorker carries the chat filter through to SyncManager.run()
# ---------------------------------------------------------------------------

def _run_worker_capturing_filter(monkeypatch, tmp_path, chat_filter):
    """Drive SyncWorker._run() on this thread with the manager stubbed out."""
    seen = {}

    class FakeManager:
        def __init__(self, **kwargs):
            seen["kwargs"] = kwargs

        def run(self, chat_filter=None):
            seen["chat_filter"] = chat_filter
            return {"chats": 1, "messages": 0}

    monkeypatch.setattr(gui_worker, "_ProgressSyncManager", FakeManager)

    worker = gui_worker.SyncWorker(
        transport=object(),
        chunk_size="year",
        dry_run=True,
        db_path=tmp_path / "state.db",
        inbox_dir=tmp_path / "inbox",
        processed_dir=tmp_path / "processed",
        chat_filter=chat_filter,
    )
    worker._run()
    return seen, worker.q


def test_sync_worker_passes_chat_filter(monkeypatch, tmp_path):
    seen, q = _run_worker_capturing_filter(monkeypatch, tmp_path, "Kartik Patel")
    assert seen["chat_filter"] == "Kartik Patel"
    assert q.get_nowait()["type"] == "done"


def test_sync_worker_without_a_filter_syncs_everything(monkeypatch, tmp_path):
    """The default has to stay None -- an empty string would filter to nothing."""
    seen, _ = _run_worker_capturing_filter(monkeypatch, tmp_path, None)
    assert seen["chat_filter"] is None


def test_sync_worker_defaults_to_no_filter(monkeypatch, tmp_path):
    """Callers that predate the parameter must be unaffected by it."""
    seen = {}

    class FakeManager:
        def __init__(self, **kwargs):
            pass

        def run(self, chat_filter=None):
            seen["chat_filter"] = chat_filter
            return {}

    monkeypatch.setattr(gui_worker, "_ProgressSyncManager", FakeManager)
    gui_worker.SyncWorker(
        transport=object(),
        chunk_size="year",
        dry_run=True,
        db_path=tmp_path / "state.db",
        inbox_dir=tmp_path / "inbox",
        processed_dir=tmp_path / "processed",
    )._run()
    assert seen["chat_filter"] is None


def test_sync_worker_still_reports_a_stop(monkeypatch, tmp_path):
    """The stop flag is read after run() returns; the filter must not shadow it."""
    class FakeManager:
        def __init__(self, **kwargs):
            self._stop = kwargs["stop_event"]

        def run(self, chat_filter=None):
            self._stop.set()
            return {}

    monkeypatch.setattr(gui_worker, "_ProgressSyncManager", FakeManager)
    worker = gui_worker.SyncWorker(
        transport=object(),
        chunk_size="year",
        dry_run=True,
        db_path=tmp_path / "state.db",
        inbox_dir=tmp_path / "inbox",
        processed_dir=tmp_path / "processed",
        chat_filter="Anyone",
    )
    worker._run()
    event = worker.q.get_nowait()
    assert event["type"] == "done" and event["stopped"] is True


# ---------------------------------------------------------------------------
# The chat's state is said in the same words on both front-ends
# ---------------------------------------------------------------------------

def test_every_chat_status_has_a_headline():
    """_chat_status_of's three returns, and nothing else, are headline keys."""
    produced = {
        gui._chat_status_of({"last_run_status": s})
        for s in ("complete", "failed", None, "", "pending", "anything-else")
    }
    assert produced == set(gui._CHAT_STATUS_HEADLINE)


def test_chat_status_headlines_match_androids_wording():
    """Windows' headline words are Android's ChatStatus descriptions.

    Read out of the Kotlin source rather than restated here: a copy of the
    strings in the test would go stale in exactly the same way as a copy in
    the source, and prove nothing.
    """
    source = CHATS_LIST_KT.read_text(encoding="utf-8")
    kotlin_to_windows = {"SYNCED": "synced", "FAILED": "failed", "NOT_SYNCED": "never"}
    found = {}
    for name, key in kotlin_to_windows.items():
        match = re.search(rf'^\s*{name}\("([^"]*)"', source, re.MULTILINE)
        assert match, f"ChatStatus.{name} not found in {CHATS_LIST_KT.name}"
        found[key] = match.group(1)
    assert found == gui._CHAT_STATUS_HEADLINE


def test_android_status_helpers_stay_reachable_from_the_detail_screen():
    """chatStatusOf/StatusDot/ChatStatus are shared, not file-private.

    ChatDetailScreen calls all three. Kotlin's `private` on a top-level
    declaration is file-private, so re-adding it would break the build --
    which the Kotlin compiler catches, but only if someone runs it.
    """
    source = CHATS_LIST_KT.read_text(encoding="utf-8")
    for decl in (
        "internal enum class ChatStatus(",
        "internal fun chatStatusOf(",
        "internal fun StatusDot(",
    ):
        assert decl in source, f"expected `{decl}` in {CHATS_LIST_KT.name}"


# ---------------------------------------------------------------------------
# The per-chat cutoff line
#
# gui._chat_cutoff_hint and CutoffDate.chatHint are one sentence written
# twice, in two languages, in two files. Nothing but this test connects them,
# and the failure is not a crash -- it is the two editions telling the same
# user two different stories about which floor is in force.
# ---------------------------------------------------------------------------

CUTOFF_DATE_KT = (
    REPO_ROOT / "android" / "app" / "src" / "main" / "java" / "com"
    / "chatmailsync" / "app" / "CutoffDate.kt"
)


def test_an_empty_field_names_the_floor_that_is_still_in_force():
    """The state that matters: no override of this chat's own, but an
    app-wide floor quietly applying to it anyway."""
    assert gui._chat_cutoff_hint("", "1 January 2026") == (
        "Using the app-wide cutoff, 1 January 2026. A date here applies to "
        "this chat only."
    )


def test_an_override_says_the_app_wide_date_no_longer_applies():
    assert gui._chat_cutoff_hint("1 March 2026", "1 January 2026") == (
        "This chat stops at 1 March 2026. The app-wide cutoff does not apply "
        "to it."
    )


def test_with_no_floor_anywhere_it_says_so_rather_than_staying_silent():
    assert gui._chat_cutoff_hint("", "") == (
        "No cutoff, so every message in this chat is sent. A date here "
        "applies to this chat only."
    )


def test_both_editions_say_the_same_three_sentences():
    # Kotlin interpolates the day where Python formats it, and wraps its long
    # strings with `" + "`. Undo the wrapping, then feed the Kotlin
    # placeholder names through the Python function: what comes out is the
    # exact literal CutoffDate.kt has to contain.
    kotlin = re.sub(r'"\s*\+\s*"', "", CUTOFF_DATE_KT.read_text(encoding="utf-8"))
    assert gui._chat_cutoff_hint("$ownDay", "") in kotlin
    assert gui._chat_cutoff_hint("", "$appDay") in kotlin
    assert gui._chat_cutoff_hint("", "") in kotlin


def test_the_windows_panel_reads_and_writes_the_per_chat_floor():
    """Two halves of one control, and either missing is silent: without the
    read the field sits blank over a floor that is applying, without the
    write the date the user typed is never stored."""
    source = (REPO_ROOT / "gui.py").read_text(encoding="utf-8")
    assert "get_chat_cutoff(self._chat_id, STATE_DB_PATH)" in source
    assert "set_chat_cutoff(self._chat_id, text or None, STATE_DB_PATH)" in source
    assert "set_chat_cutoff(self._chat_id, None, STATE_DB_PATH)" in source


def test_the_run_detail_keeps_the_held_back_count_off_the_skipped_line():
    """Skipped means the mailbox already has it; held back means it was never
    offered. Folding one into the other is how a forgotten cutoff reads as an
    app that is dropping messages."""
    source = (REPO_ROOT / "gui.py").read_text(encoding="utf-8")
    assert 'if run.get("messages_cutoff"):' in source
    assert '"Held back by your cutoff date"' in source
