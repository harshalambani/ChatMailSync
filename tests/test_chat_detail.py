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
