"""One definition of "what a sync looks like while it runs".

Both front-ends watch the same event stream (see ``ProgressSyncManager`` in
src/sync_manager.py for who emits what), and until now both *interpreted* it
separately: gui.py's ``_handle_sync_event`` built one set of labels and one
monotonic fraction, and SyncWorker.kt's ``progressText`` / ``eventFraction`` /
``milestoneText`` built another from the same events. They were written to
match, and they drifted anyway -- which is exactly what "the progress bar
functionality is not the same as the android app" was reporting.

So the interpretation lives here, once, in the shared core: feed raw events
in, read a ``ProgressState`` out. Kotlin reads the finished fields through
``android_api.progress_state()`` rather than restating the rules, and gui.py
drives its bar and label from the same object. A wording change now happens
in one place and lands on both platforms in the same commit, which is what
PLATFORM-PARITY.md asks for.

The vocabulary deliberately stays small -- phase, the chat being worked on,
how far along, and one line of text -- because that is all either front-end
needs to render, from a full-screen progress view down to Android's
collapsed one-line bar.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional

# Phases. A sync moves IDLE -> SCANNING -> SYNCING -> one of DONE/FAILED, and
# a stopped run ends at DONE with `stopped` set: it really did finish, it just
# finished early, and calling that a failure would bury the real failures.
PHASE_IDLE = "idle"
PHASE_SCANNING = "scanning"
PHASE_SYNCING = "syncing"
PHASE_DONE = "done"
PHASE_FAILED = "failed"

# Fraction sentinel for "no honest number yet" -- before the first file, or
# during a run that is all dedup-skips with nothing to push. Front-ends show
# an indeterminate bar rather than inventing a percentage.
UNKNOWN = -1.0

# How many milestone lines to keep. Android ships these through WorkManager
# Data, which has a ~10KB limit for the whole payload, so the ring buffer is
# bounded rather than "the whole run".
MAX_MILESTONES = 50


@dataclass
class ProgressState:
    """A renderable snapshot of an in-flight sync."""

    phase: str = PHASE_IDLE
    #: Display name of the chat currently being pushed ("" when none yet).
    chat: str = ""
    #: 0.0-1.0, or UNKNOWN.
    fraction: float = UNKNOWN
    #: Primary line: what is happening.
    headline: str = ""
    #: Secondary line: how far into it ("128 / 500 messages"). May be "".
    detail: str = ""
    #: Milestone log, oldest first. Excludes per-chunk ticks by design --
    #: those fire many times per file and would read as noise, not a log.
    milestones: list[str] = field(default_factory=list)

    @property
    def line(self) -> str:
        """headline and detail as the single line both front-ends have
        always shown ("Syncing: Alice — 128 / 500 messages")."""
        return f"{self.headline} — {self.detail}" if self.detail else self.headline

    @property
    def percent(self) -> int:
        """Rounded percentage, or -1 when the fraction is unknown."""
        return -1 if self.fraction < 0 else round(self.fraction * 100)

    def as_dict(self) -> dict:
        """Flat, JSON-ish shape for the Kotlin bridge -- Chaquopy reads
        strings and numbers out of a dict far more comfortably than it reads
        attributes off a dataclass."""
        return {
            "phase": self.phase,
            "chat": self.chat,
            "fraction": self.fraction,
            "percent": self.percent,
            "headline": self.headline,
            "detail": self.detail,
            "line": self.line,
            "milestones": list(self.milestones),
            "log": "\n".join(self.milestones),
        }


class ProgressTracker:
    """Folds the raw event stream into a ProgressState.

    One instance per run; ``reset()`` starts the next one. Feeding is
    order-independent in the sense that a missed event only costs detail, not
    correctness -- every field is derived from the newest event that carries
    it, and the fraction only ever moves forward (see ``_advance``).
    """

    def __init__(self) -> None:
        self.state = ProgressState()

    def reset(self) -> None:
        self.state = ProgressState()

    def feed(self, event: Mapping[str, Any]) -> Optional[str]:
        """Apply one event. Returns the milestone line it produced, if any."""
        etype = event.get("type")
        st = self.state
        milestone: Optional[str] = None

        if etype == "files_total":
            n = _int(event.get("n"))
            st.phase = PHASE_SCANNING
            st.headline = "Inbox is empty" if n == 0 else f"Found {n} file(s)…"
            st.detail = ""
            milestone = (
                "Inbox is empty" if n == 0 else f"Found {n} file(s) to sync"
            )

        elif etype == "syncing":
            name = str(event.get("name", ""))
            st.phase = PHASE_SYNCING
            st.chat = name
            st.headline = f"Syncing: {name}"
            st.detail = ""
            milestone = f"Starting: {name}"

        elif etype == "chunk":
            # The honest whole-sync percentage. The engine counts every *new*
            # message in a parse+dedup pre-scan before the first network call
            # (ProgressSyncManager._estimate_total_new_messages), so this
            # advances continuously while one large chat is still uploading,
            # instead of the bar sitting at a file-count fraction -- 0/1 for
            # the entire run when the inbox holds a single file -- and only
            # jumping at the very end.
            name = str(event.get("name", ""))
            st.phase = PHASE_SYNCING
            st.chat = name
            st.headline = f"Syncing: {name}"
            st.detail = (
                f"{_int(event.get('msgs_done'))} / "
                f"{_int(event.get('total_msgs'))} messages"
            )
            self._advance(_int(event.get("global_done")), _int(event.get("global_total")))

        elif etype == "file_done":
            done, total = _int(event.get("done")), _int(event.get("total"))
            st.headline = f"{done} / {total} files"
            st.detail = ""
            # Coarser than "chunk" and only used where chunk data does not
            # exist yet, because _advance never moves backwards.
            self._advance(done, total)
            milestone = f"Finished {done} / {total} files"

        elif etype == "done":
            st.phase = PHASE_DONE
            st.chat = ""
            st.fraction = 1.0
            st.detail = ""
            st.headline = _done_headline(event)

        elif etype == "error":
            st.phase = PHASE_FAILED
            st.chat = ""
            st.detail = ""
            st.headline = "Failed — see log"

        if milestone is not None:
            st.milestones.append(milestone)
            del st.milestones[:-MAX_MILESTONES]
        return milestone

    def _advance(self, done: int, total: int) -> None:
        """Move the bar to done/total, but never backwards within a run.

        Two sources drive it and they disagree in scale: "chunk" counts
        messages across the whole sync, "file_done" counts files. Finishing
        the first of three files is 1/3, but if that file was most of the
        work the message count may already be past half -- so taking each
        event at face value made the bar visibly retreat at every file
        boundary. Whichever source is further along is the honest answer; a
        bar that goes backwards just reads as a bug.
        """
        if total <= 0:
            return
        fraction = done / total
        if fraction > self.state.fraction:
            self.state.fraction = min(fraction, 1.0)


def _done_headline(event: Mapping[str, Any]) -> str:
    """"Done"/"Stopped" plus the count, when the event carries stats.

    android_api publishes a bare {"type": "done"} (the Kotlin side reads the
    real numbers off the worker's result Data), so the count is optional
    rather than assumed -- a KeyError here would take down the poll loop at
    the one moment the user is watching it.
    """
    stats = event.get("stats")
    synced = getattr(stats, "messages_synced", None)
    prefix = "Stopped" if event.get("stopped") else "Done"
    if synced is None:
        return prefix
    return f"{prefix} — {synced} msg{'s' if synced != 1 else ''} synced"


def _int(value: Any) -> int:
    """Events cross the Chaquopy boundary as whatever Python put in them, but
    a malformed one must not be able to kill a poll loop."""
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0
