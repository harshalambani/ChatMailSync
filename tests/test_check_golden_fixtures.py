"""PAR-07 hygiene: the golden-fixture checker must never write into the tree it checks."""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "android" / "core" / "src" / "test" / "resources" / "golden"
CHECKER = ROOT / "tools" / "check_golden_fixtures.py"
SIDE_SUFFIXES = ("-wal", "-shm", "-journal")


def _fixture_copy(dest: Path) -> Path:
    """A copy of the committed fixtures without any side files a local run left behind."""
    dest.mkdir()
    for p in GOLDEN.iterdir():
        if p.is_file() and not p.name.endswith(SIDE_SUFFIXES):
            shutil.copy2(p, dest / p.name)
    return dest


def _listing(d: Path) -> dict[str, tuple[int, int]]:
    return {p.name: (p.stat().st_size, p.stat().st_mtime_ns) for p in d.iterdir()}


def _run(fresh: Path, committed: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(fresh), str(committed)],
        capture_output=True, text=True, check=False,
    )


def test_checker_leaves_no_side_files_and_changes_nothing(tmp_path: Path) -> None:
    committed = _fixture_copy(tmp_path / "committed")
    fresh = _fixture_copy(tmp_path / "fresh")
    assert any(p.suffix == ".db" for p in committed.iterdir()), "the sqlite golden must be in the check"
    before_c, before_f = _listing(committed), _listing(fresh)

    result = _run(fresh, committed)

    assert result.returncode == 0, result.stdout + result.stderr
    # NEGATIVE: the wrong behaviour (mode=ro alone) leaves -wal/-shm next to the db.
    for d in (committed, fresh):
        leftovers = [p.name for p in d.iterdir() if p.name.endswith(SIDE_SUFFIXES)]
        assert leftovers == [], f"side files appeared in {d}: {leftovers}"
    assert _listing(committed) == before_c
    assert _listing(fresh) == before_f


def test_count_line_lists_only_fixture_files(tmp_path: Path) -> None:
    committed = _fixture_copy(tmp_path / "committed")
    fresh = _fixture_copy(tmp_path / "fresh")
    # A stray side file must neither be counted nor compared.
    (committed / "state_fixture_python_written.db-wal").write_bytes(b"")
    (committed / "state_fixture_python_written.db-shm").write_bytes(b"\0" * 32)
    n = len([p for p in committed.iterdir() if not p.name.endswith(SIDE_SUFFIXES)])
    result = _run(fresh, committed)
    assert result.returncode == 0, result.stdout
    assert f"All {n} golden fixtures" in result.stdout


def test_checker_still_fails_on_a_real_difference(tmp_path: Path) -> None:
    committed = _fixture_copy(tmp_path / "committed")
    fresh = _fixture_copy(tmp_path / "fresh")
    target = fresh / "cutoff_golden.json"
    data = bytearray(target.read_bytes())
    data[50] ^= 1
    target.write_bytes(bytes(data))
    result = _run(fresh, committed)
    assert result.returncode == 1
    assert "cutoff_golden.json" in result.stdout


# ---------------------------------------------------------------------------
# KT-08: .cmsbackup bundles are compared by decoded content, not raw bytes
# ---------------------------------------------------------------------------

def _rewrite_bundle(path: Path, *, date=(2000, 1, 1, 0, 0, 0), settings=None, db_edit=None) -> None:
    """Rewrite [path] with other entry dates, and optionally other settings or a changed db row."""
    import sqlite3
    import tempfile
    import zipfile

    with zipfile.ZipFile(path) as z:
        members = {n: z.read(n) for n in z.namelist()}
    if settings is not None:
        members["settings.json"] = settings.encode("utf-8")
    if db_edit is not None:
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "sync_state.db"
            db.write_bytes(members["sync_state.db"])
            con = sqlite3.connect(db)
            con.execute(db_edit)
            con.commit()
            con.close()
            members["sync_state.db"] = db.read_bytes()
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for n, b in members.items():
            z.writestr(zipfile.ZipInfo(n, date_time=date), b)


def test_bundle_with_other_entry_dates_and_compression_is_equal(tmp_path: Path) -> None:
    committed = _fixture_copy(tmp_path / "committed")
    fresh = _fixture_copy(tmp_path / "fresh")
    _rewrite_bundle(fresh / "migration_python_written.cmsbackup")
    assert (fresh / "migration_python_written.cmsbackup").read_bytes() != (
        committed / "migration_python_written.cmsbackup"
    ).read_bytes()
    result = _run(fresh, committed)
    assert result.returncode == 0, result.stdout + result.stderr


def test_bundle_with_different_settings_or_rows_is_reported(tmp_path: Path) -> None:
    # NEGATIVE: ignoring the container must not mean ignoring the content.
    committed = _fixture_copy(tmp_path / "committed")
    fresh = _fixture_copy(tmp_path / "fresh")
    _rewrite_bundle(fresh / "migration_python_written.cmsbackup", settings="{}")
    result = _run(fresh, committed)
    assert result.returncode == 1
    assert "migration_python_written.cmsbackup" in result.stdout

    fresh2 = _fixture_copy(tmp_path / "fresh2")
    _rewrite_bundle(
        fresh2 / "migration_python_written.cmsbackup",
        db_edit="UPDATE chats SET display_name = 'Someone Else' WHERE chat_id = 'chat-meera'",
    )
    result = _run(fresh2, committed)
    assert result.returncode == 1
    assert "migration_python_written.cmsbackup" in result.stdout
