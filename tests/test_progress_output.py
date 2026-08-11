"""The terminal progress bar must stay on terminals.

`_print_progress` redraws one line with a carriage return. That is meaningful
on a console and meaningless anywhere else -- and "anywhere else" is not a
hypothetical: on Android, Chaquopy replaces sys.stderr with a stream that
forwards every write to logcat at warning level, so each redraw became its own
log entry. A single 451-message chat produced ~67 of them, each carrying the
contact's display name, and they buried any genuine stderr warning.

So these tests are weighted towards the *non*-terminal cases, which are the
ones that were wrong.
"""

import io

import pytest

from src.mail_client import _print_progress, _stderr_is_terminal


class _FakeStream(io.StringIO):
    """A StringIO that can claim, or refuse, to be a terminal."""

    def __init__(self, tty: bool):
        super().__init__()
        self._tty = tty

    def isatty(self) -> bool:
        return self._tty


class _RaisingStream(io.StringIO):
    """Stands in for a closed stream, whose isatty() raises ValueError."""

    def isatty(self):
        raise ValueError("I/O operation on closed file")


class _NoIsatty:
    """A minimal stand-in that never claimed to be a file at all."""

    def __init__(self):
        self.written = []

    def write(self, text):
        self.written.append(text)

    def flush(self):
        pass


def test_terminal_gets_the_bar(monkeypatch):
    stream = _FakeStream(tty=True)
    monkeypatch.setattr("sys.stderr", stream)
    assert _stderr_is_terminal() is True
    _print_progress("Some Chat", 3, 10, 30, 100)
    written = stream.getvalue()
    assert written.startswith("\r")
    assert "Some Chat" in written
    assert "30%" in written


def test_non_terminal_gets_nothing(monkeypatch):
    """The Android/logcat case: a stream, but not one worth redrawing."""
    stream = _FakeStream(tty=False)
    monkeypatch.setattr("sys.stderr", stream)
    assert _stderr_is_terminal() is False
    _print_progress("Some Chat", 3, 10, 30, 100)
    assert stream.getvalue() == ""


def test_display_name_does_not_reach_a_non_terminal(monkeypatch):
    """The contact's name is the part that must not land in a log."""
    stream = _FakeStream(tty=False)
    monkeypatch.setattr("sys.stderr", stream)
    _print_progress("Bijal Ambani", 1, 1, 1, 1)
    assert "Bijal" not in stream.getvalue()


def test_none_stderr_is_not_a_terminal(monkeypatch):
    """PyInstaller GUI bundle, console=False -- the original guard."""
    monkeypatch.setattr("sys.stderr", None)
    assert _stderr_is_terminal() is False
    _print_progress("Some Chat", 1, 2, 5, 10)  # must not raise


def test_stream_without_isatty_is_not_a_terminal(monkeypatch):
    stream = _NoIsatty()
    monkeypatch.setattr("sys.stderr", stream)
    assert _stderr_is_terminal() is False
    _print_progress("Some Chat", 1, 2, 5, 10)
    assert stream.written == []


def test_closed_stream_is_not_a_terminal(monkeypatch):
    """isatty() raising must be a "no", not a crash mid-sync."""
    monkeypatch.setattr("sys.stderr", _RaisingStream())
    assert _stderr_is_terminal() is False
    _print_progress("Some Chat", 1, 2, 5, 10)


@pytest.mark.parametrize(
    "msgs_done,total_msgs,expected",
    [
        (0, 100, "  0%"),
        (50, 100, " 50%"),
        (100, 100, "100%"),
        # No messages at all must not divide by zero.
        (0, 0, "  0%"),
    ],
)
def test_percentage(monkeypatch, msgs_done, total_msgs, expected):
    stream = _FakeStream(tty=True)
    monkeypatch.setattr("sys.stderr", stream)
    _print_progress("C", 1, 1, msgs_done, total_msgs)
    assert expected in stream.getvalue()


def test_line_is_capped_to_terminal_width(monkeypatch):
    """A very long chat name must not wrap, or the redraw leaves debris."""
    stream = _FakeStream(tty=True)
    monkeypatch.setattr("sys.stderr", stream)
    _print_progress("X" * 200, 1, 1, 1, 2)
    assert len(stream.getvalue()) <= 79


def test_a_failed_chat_still_counts_toward_the_whole_sync_fraction():
    """Reported live alongside the [TRYCREATE] APPEND failures: the bar read
    ~72% while the last of four files was already in flight, three of the four
    having failed outright.

    The whole-sync numerator was stats.messages_synced, which a failed push
    never increments -- while the denominator, _total_new_messages, counted
    that chat's messages in the pre-scan. So every failure left the bar
    permanently short by that chat's size, and it could never approach 100%
    however much work the run actually got through. _prior_msgs_done now tracks
    messages *accounted for*: delivered or given up on.
    """
    import queue as _queue
    from src.sync_manager import ProgressSyncManager, SyncStats

    mgr = ProgressSyncManager.__new__(ProgressSyncManager)
    mgr._pq = _queue.Queue()
    mgr._stop_event = type("E", (), {"is_set": lambda self: False})()
    mgr._files_done = 0
    mgr._files_total = 2
    mgr._prior_msgs_done = 0
    mgr._total_new_messages = 100
    mgr._prescan_cache = {"chatA.txt": ([], [object()] * 60, 0)}

    stats = SyncStats()

    # A file whose push fails: messages_synced never moves.
    def _fail(self, filepath, chat_id, display_name, s):
        pass

    import src.sync_manager as sm
    orig = sm.SyncManager._sync_file
    sm.SyncManager._sync_file = _fail
    try:
        mgr._sync_file("chatA.txt", "chatA", "Bijal Ambani", stats)
    finally:
        sm.SyncManager._sync_file = orig

    assert stats.messages_synced == 0
    # The 60 messages that chat was going to send are behind us either way.
    assert mgr._prior_msgs_done == 60
    mgr._on_chunk_progress("Next Chat", 1, 1, 10, 40)
    events = []
    while not mgr._pq.empty():
        events.append(mgr._pq.get_nowait())
    chunk = [e for e in events if e["type"] == "chunk"][-1]
    assert chunk["global_done"] == 70
    assert chunk["global_total"] == 100
