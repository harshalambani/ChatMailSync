"""Windows watched-folder rules.

These are the desktop half of what WatchFolderWorker.kt does on Android, and
the tests are written against the *behaviour* both platforms promise rather
than the Tk plumbing: nothing here imports gui.py, and no folder outside
tmp_path is ever touched.
"""

import os
from pathlib import Path

import pytest

from src.watch_folder import (
    SYNCED_SUBDIR,
    ScanResult,
    apply_pending_synced_file_policies,
    scan_watch_folder,
)


@pytest.fixture()
def folders(tmp_path: Path) -> tuple[Path, Path]:
    watched = tmp_path / "watched"
    inbox = tmp_path / "inbox"
    watched.mkdir()
    inbox.mkdir()
    return watched, inbox


def _export(folder: Path, name: str, body: str = "chat") -> Path:
    path = folder / name
    path.write_text(body, encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------

def test_new_exports_are_copied_into_the_inbox(folders):
    watched, inbox = folders
    _export(watched, "Chat A.txt")
    _export(watched, "Chat B.zip")

    result = scan_watch_folder(watched, inbox, [])

    assert sorted(result.imported) == ["Chat A.txt", "Chat B.zip"]
    assert (inbox / "Chat A.txt").read_text(encoding="utf-8") == "chat"
    # The original is left alone -- the synced-file policy is the only thing
    # allowed to move or remove it, and only after delivery.
    assert (watched / "Chat A.txt").exists()


def test_only_whatsapp_export_types_are_picked_up(folders):
    watched, inbox = folders
    _export(watched, "Chat.txt")
    _export(watched, "holiday.jpg")
    _export(watched, "notes.pdf")

    result = scan_watch_folder(watched, inbox, [])

    assert result.imported == ["Chat.txt"]


def test_subfolders_are_not_scanned(folders):
    """This is what keeps the "move to synced/" policy from feeding its own
    destination straight back into the next scan."""
    watched, inbox = folders
    nested = watched / SYNCED_SUBDIR
    nested.mkdir()
    _export(nested, "Already Synced.txt")

    result = scan_watch_folder(watched, inbox, [])

    assert result.imported == []


def test_a_source_is_never_imported_twice(folders):
    watched, inbox = folders
    _export(watched, "Chat.txt")

    first = scan_watch_folder(watched, inbox, [])
    # Simulate the sync having taken the file away to processed/.
    (inbox / "Chat.txt").unlink()
    second = scan_watch_folder(watched, inbox, first.ledger)

    assert first.imported == ["Chat.txt"]
    assert second.imported == []


def test_case_differences_in_the_path_follow_the_filesystem(folders):
    """Windows reaches the same folder under several spellings, so comparing
    raw strings there would let one export import twice. On Linux the same two
    spellings are two genuinely different files, and folding case would do the
    opposite damage -- silently refusing to import a real export. The ledger
    therefore follows the platform (os.path.normcase), and so does this test;
    asserting the Windows answer everywhere is what made it fail on CI."""
    watched, inbox = folders
    source = _export(watched, "Chat.txt")

    first = scan_watch_folder(watched, inbox, [])
    (inbox / "Chat.txt").unlink()
    shouty = [str(source).upper()]

    again = scan_watch_folder(watched, inbox, shouty).imported
    if os.path.normcase("A") == "a":       # case-insensitive: Windows, macOS
        assert again == []
    else:
        assert again == ["Chat.txt"]
    assert first.ledger  # and the normal path really did ledger something


def test_a_file_already_queued_is_ledgered_but_not_counted(folders):
    watched, inbox = folders
    _export(watched, "Chat.txt", "from the watched folder")
    _export(inbox, "Chat.txt", "dropped in by hand earlier")

    result = scan_watch_folder(watched, inbox, [])

    assert result.imported == []
    assert result.already_queued == ["Chat.txt"]
    # The hand-dropped copy is not overwritten by the watcher.
    assert (inbox / "Chat.txt").read_text(encoding="utf-8") == "dropped in by hand earlier"
    # ...but the source is remembered, so the next tick leaves it alone.
    assert result.ledger


def test_a_source_that_could_not_be_copied_is_retried_next_time(folders, monkeypatch):
    watched, inbox = folders
    _export(watched, "Chat.txt")

    def _boom(*_args, **_kwargs):
        raise OSError("the drive went away")

    monkeypatch.setattr("src.watch_folder.shutil.copy2", _boom)
    failed = scan_watch_folder(watched, inbox, [])

    assert failed.imported == []
    assert failed.errors and "Chat.txt" in failed.errors[0]
    assert failed.ledger == []  # deliberately not written off

    monkeypatch.undo()
    assert scan_watch_folder(watched, inbox, failed.ledger).imported == ["Chat.txt"]


def test_an_unreadable_watched_folder_is_reported_not_raised(tmp_path):
    missing = tmp_path / "not-there"
    inbox = tmp_path / "inbox"
    inbox.mkdir()

    result = scan_watch_folder(missing, inbox, [])

    assert result.imported == []
    assert len(result.errors) == 1


def test_pending_map_records_where_each_import_came_from(folders):
    watched, inbox = folders
    source = _export(watched, "Chat.txt")
    pending: dict[str, str] = {}

    scan_watch_folder(watched, inbox, [], pending)

    assert pending == {"Chat.txt": str(source)}


# ---------------------------------------------------------------------------
# The synced-file policy
# ---------------------------------------------------------------------------

def test_a_file_still_in_the_inbox_is_left_pending(folders):
    """The sync failed, was stopped, or has not run. "Still queued" must never
    be mistaken for "must have gone through by now"."""
    watched, inbox = folders
    source = _export(watched, "Chat.txt")
    _export(inbox, "Chat.txt")
    pending = {"Chat.txt": str(source)}

    remaining, messages = apply_pending_synced_file_policies(pending, inbox, "move")

    assert remaining == pending
    assert messages == []
    assert source.exists()


def test_delivered_file_is_moved_to_the_synced_subfolder(folders):
    watched, inbox = folders
    source = _export(watched, "Chat.txt")
    pending = {"Chat.txt": str(source)}  # never copied into inbox == delivered

    remaining, messages = apply_pending_synced_file_policies(pending, inbox, "move")

    assert remaining == {}
    assert not source.exists()
    assert (watched / SYNCED_SUBDIR / "Chat.txt").exists()
    assert messages


def test_leave_policy_touches_nothing_but_still_clears_the_ledger(folders):
    watched, inbox = folders
    source = _export(watched, "Chat.txt")

    remaining, messages = apply_pending_synced_file_policies(
        {"Chat.txt": str(source)}, inbox, "leave"
    )

    assert remaining == {}
    assert source.exists()
    assert messages == []


def test_a_resolved_entry_is_cleared_even_if_the_move_fails(folders, monkeypatch):
    """Otherwise every future sync would re-attempt the same doomed move and
    log the same error for the rest of time. The file is already delivered."""
    watched, inbox = folders
    source = _export(watched, "Chat.txt")

    def _boom(*_args, **_kwargs):
        raise OSError("read-only folder")

    monkeypatch.setattr("src.watch_folder.shutil.move", _boom)
    remaining, messages = apply_pending_synced_file_policies(
        {"Chat.txt": str(source)}, inbox, "move"
    )

    assert remaining == {}
    assert source.exists()  # left where it is, not lost
    assert messages and "Chat.txt" in messages[0]


def test_a_source_that_has_already_gone_is_simply_forgotten(folders):
    _watched, inbox = folders
    remaining, messages = apply_pending_synced_file_policies(
        {"Chat.txt": str(inbox.parent / "watched" / "gone.txt")}, inbox, "delete"
    )

    assert remaining == {}
    assert messages == []


def test_delete_policy_never_permanently_erases_when_recycling_is_unavailable(
    folders, monkeypatch
):
    """The "delete" rule removes a file the user did not pick individually, so
    it goes to the Recycle Bin. If the shell API is not available the file is
    left in place and reported -- never unlinked as a fallback."""
    watched, inbox = folders
    source = _export(watched, "Chat.txt")
    monkeypatch.setattr("src.watch_folder._recycle", lambda _p: False)

    remaining, messages = apply_pending_synced_file_policies(
        {"Chat.txt": str(source)}, inbox, "delete"
    )

    assert remaining == {}
    assert source.exists()
    assert messages and "recycle" in messages[0].lower()


def test_delete_policy_recycles_the_source(folders, monkeypatch):
    watched, inbox = folders
    source = _export(watched, "Chat.txt")
    recycled: list[Path] = []

    def _fake_recycle(path: Path) -> bool:
        recycled.append(path)
        path.unlink()
        return True

    monkeypatch.setattr("src.watch_folder._recycle", _fake_recycle)
    remaining, _messages = apply_pending_synced_file_policies(
        {"Chat.txt": str(source)}, inbox, "delete"
    )

    assert remaining == {}
    assert recycled == [source]


def test_an_empty_pending_map_is_a_no_op(folders):
    _watched, inbox = folders
    assert apply_pending_synced_file_policies({}, inbox, "delete") == ({}, [])


def test_scan_result_counts_only_fresh_imports():
    assert ScanResult(imported=["a", "b"], already_queued=["c"]).imported_count == 2
