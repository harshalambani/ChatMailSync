"""Windows watched-folder support: the desktop half of Android's WatchFolderWorker.

Same feature, different plumbing (see PLATFORM-PARITY.md). Android picks a tree
with SAF and polls it from a WorkManager periodic job with a platform-enforced
15-minute floor; Windows picks a plain directory and polls it from the GUI's
Tk ``after()`` timer, which has no floor and only runs while the app is open.
What is *not* allowed to differ is the behaviour the user sees, so the rules
below are deliberately the same ones WatchFolderWorker follows:

- the scan is non-recursive, so a "move to synced/" policy never re-scans its
  own destination folder;
- a source already imported is remembered by path and never imported twice,
  even if the copy in the inbox has since been synced and moved away;
- ``synced_file_policy`` is applied only once a file is *confirmed delivered*
  -- never at import time, which would move or erase the user's original even
  if delivery never happened (no mail account configured yet, say);
- "gone from inbox/" is that confirmation. ``sync_manager`` moves a file to
  processed/ only on confirmed delivery or a dedup-skip, never on a parse
  failure, so this is the same ground truth used elsewhere rather than a new
  invented signal. A pending entry whose file is still sitting in inbox/ is
  left alone and retried next time.

Everything here is deliberately free of tkinter and of the settings file: the
caller passes state in and gets new state back, so the whole feature is
testable without a GUI.
"""

from __future__ import annotations

import ctypes
import os
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

# Android's floor is imposed by WorkManager; ours is a judgement call. One
# minute is the shortest that is honest about being a *poll* rather than a
# filesystem watch, and the default matches Android's so the two products
# behave the same out of the box.
MIN_WATCH_INTERVAL_MINUTES = 1
DEFAULT_WATCH_INTERVAL_MINUTES = 15

#: What the watcher will pick up. Mirrors the drop-target and Browse filters --
#: a watched folder is not a licence to import file types a sync would only
#: choke on later.
WATCH_SUFFIXES = (".txt", ".zip")

SYNCED_FILE_POLICIES = ("leave", "move", "delete")

#: Subfolder created inside the watched folder by the "move" policy. Named to
#: match Android's, so a folder shared between a phone and a PC (a synced
#: cloud drive, say) does not end up with two differently-named piles.
SYNCED_SUBDIR = "synced"


@dataclass
class ScanResult:
    """Outcome of one pass over the watched folder."""

    #: Inbox filenames freshly copied in by this scan.
    imported: list[str] = field(default_factory=list)
    #: Sources skipped because a file of that name was already queued in the
    #: inbox. Ledgered as imported all the same -- the export *is* queued, and
    #: re-copying it on the next tick would achieve nothing.
    already_queued: list[str] = field(default_factory=list)
    #: Human-readable failures, one per source that could not be copied. The
    #: watcher never raises: a single unreadable file must not stop the rest.
    errors: list[str] = field(default_factory=list)
    #: The ledger of imported sources as it stands after this scan -- what the
    #: caller should persist. Returned rather than mutated in place because on
    #: Windows it lives in the settings file, which only the UI thread writes.
    ledger: list[str] = field(default_factory=list)

    @property
    def imported_count(self) -> int:
        return len(self.imported)


def scan_watch_folder(
    folder: Path,
    inbox_dir: Path,
    already_imported: Iterable[str],
    pending_synced: dict[str, str] | None = None,
) -> ScanResult:
    """Copy new exports from *folder* into *inbox_dir*.

    *already_imported* is the ledger of source paths seen on previous passes;
    *pending_synced* (mutated in place when given) maps an inbox filename to
    the source it came from, so the synced-file policy can find the original
    again once delivery is confirmed.

    The returned ScanResult tells the caller what happened; the ledger of
    imported sources is the caller's to persist, because on Windows it lives in
    the same settings file as everything else.
    """
    # Normalise the incoming ledger rather than trusting it: entries written by
    # an earlier build, or by a hand-edited settings file, may not be in the
    # form _ledger_key produces, and a ledger that fails to match is a ledger
    # that silently imports the same export twice.
    seen = {_ledger_key(Path(entry)) for entry in already_imported}
    result = ScanResult(ledger=sorted(seen))

    try:
        entries = sorted(folder.iterdir(), key=lambda p: p.name.lower())
    except Exception as exc:  # folder deleted, permission revoked, drive gone
        result.errors.append(f"Could not read watched folder: {exc}")
        return result

    for src in entries:
        # Not recursing is what keeps the "move" policy's own synced/ folder
        # out of the next scan without any extra filtering.
        if not src.is_file():
            continue
        if src.suffix.lower() not in WATCH_SUFFIXES:
            continue
        key = _ledger_key(src)
        if key in seen:
            continue

        dest = inbox_dir / src.name
        if dest.exists():
            # Already waiting to be synced. Ledger it so the next tick does not
            # keep re-examining it, but do not count it as newly imported.
            seen.add(key)
            result.already_queued.append(src.name)
            continue

        try:
            inbox_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(str(src), str(dest))
        except Exception as exc:
            # Left out of the ledger on purpose: a file that failed to copy
            # should be retried on the next pass, not written off forever.
            result.errors.append(f"Could not import {src.name}: {exc}")
            continue

        seen.add(key)
        result.imported.append(src.name)
        if pending_synced is not None:
            pending_synced[src.name] = str(src)

    result.ledger = sorted(seen)
    return result


def _ledger_key(path: Path) -> str:
    """Normalised form of a source path, for ledger comparisons.

    Windows paths are case-insensitive and users reach the same folder by more
    than one route (mapped drive, UNC, different capitalisation), so comparing
    the raw string would let one export import twice.
    """
    try:
        resolved = path.resolve()
    except Exception:
        resolved = path
    return os.path.normcase(str(resolved))


def apply_pending_synced_file_policies(
    pending: dict[str, str],
    inbox_dir: Path,
    policy: str,
) -> tuple[dict[str, str], list[str]]:
    """Act on sources whose inbox copy has since been delivered.

    Returns the pending map with the resolved entries removed, plus a list of
    log lines describing what was done. Mirrors
    ``WatchFolderWorker.applyPendingSyncedFilePolicies``: call it after every
    real (non-dry-run) sync attempt, success or failure.

    Failures here are reported but never raised. By this point the file is
    already safely in the user's mailbox, so a folder that has gone read-only
    is a nuisance, not a sync failure.
    """
    if not pending:
        return dict(pending), []

    remaining = dict(pending)
    messages: list[str] = []

    for name, source in pending.items():
        if (inbox_dir / name).exists():
            # Still queued: the sync failed, was stopped, or has not run yet.
            # Leave it pending rather than guessing it must have gone through.
            continue

        # Delivered. Clear the entry whatever happens next -- re-attempting a
        # move or delete against a source that may no longer exist would only
        # produce a fresh error every sync from here on.
        remaining.pop(name, None)

        if policy == "leave":
            continue
        src = Path(source)
        if not src.exists():
            continue

        try:
            if policy == "delete":
                if _recycle(src):
                    messages.append(f"Moved {src.name} to the Recycle Bin.")
                else:
                    messages.append(
                        f"Could not recycle {src.name}; left it in the watched folder."
                    )
            elif policy == "move":
                # The source's own folder, not "the watched folder": the scan
                # does not recurse, so for anything imported those are the same
                # directory -- and if the user has since pointed the watch
                # somewhere else, a file still pending from the old folder
                # belongs in the old folder's synced/, not the new one's.
                target_dir = src.parent / SYNCED_SUBDIR
                target_dir.mkdir(parents=True, exist_ok=True)
                shutil.move(str(src), str(target_dir / src.name))
                messages.append(f"Moved {src.name} to {SYNCED_SUBDIR}\\.")
        except Exception as exc:
            messages.append(f"Could not apply the synced-file rule to {src.name}: {exc}")

    return remaining, messages


def _recycle(path: Path) -> bool:
    """Send *path* to the Recycle Bin. Returns False if that was not possible.

    The "delete" policy erases a file the user has not chosen individually, so
    it goes to the Recycle Bin rather than being unlinked -- an unlink has no
    undo, and the file is the user's only copy of a WhatsApp export. If the
    shell API is unavailable (non-Windows, or a network/UNC path where it
    silently falls back to a permanent delete) this refuses rather than
    quietly deleting the file for good; the caller reports it and leaves the
    file where it is.
    """
    if os.name != "nt":
        return False
    try:
        absolute = str(path.resolve())
    except Exception:
        return False
    # SHFileOperationW documents the from-path as double-NUL terminated.
    if absolute.startswith("\\\\"):  # UNC: FOF_ALLOWUNDO is not honoured
        return False

    class _SHFILEOPSTRUCTW(ctypes.Structure):
        _fields_ = [
            ("hwnd", ctypes.c_void_p),
            ("wFunc", ctypes.c_uint),
            ("pFrom", ctypes.c_wchar_p),
            ("pTo", ctypes.c_wchar_p),
            ("fFlags", ctypes.c_uint16),
            ("fAnyOperationsAborted", ctypes.c_int),
            ("hNameMappings", ctypes.c_void_p),
            ("lpszProgressTitle", ctypes.c_wchar_p),
        ]

    FO_DELETE = 0x0003
    FOF_ALLOWUNDO = 0x0040
    FOF_NOCONFIRMATION = 0x0010
    FOF_SILENT = 0x0004
    FOF_NOERRORUI = 0x0400

    op = _SHFILEOPSTRUCTW()
    op.wFunc = FO_DELETE
    op.pFrom = absolute + "\0\0"
    op.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI
    try:
        rc = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op))
    except Exception:
        return False
    return rc == 0 and not op.fAnyOperationsAborted
