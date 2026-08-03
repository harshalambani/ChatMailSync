from pathlib import Path

from src import config
from src.sync_manager import SyncManager


def test_set_root_rebinds_all_derived_paths(tmp_path):
    config.set_root(tmp_path)
    try:
        assert config.PROJECT_ROOT == tmp_path
        assert config.DATA_DIR == tmp_path / "data"
        assert config.AUTH_DIR == tmp_path / "auth"
        assert config.INBOX_DIR == tmp_path / "data" / "inbox"
        assert config.PROCESSED_DIR == tmp_path / "data" / "processed"
        assert config.STATE_DB_PATH == tmp_path / "data" / "sync_state.db"
        assert config.CREDENTIALS_FILE == tmp_path / "auth" / "credentials.json"
        assert config.TOKEN_FILE == tmp_path / "auth" / "token.json"
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_sync_manager_picks_up_root_set_before_construction(tmp_path):
    """The exact scenario Android needs: set_root() runs, then a module is
    constructed with no explicit path args and must resolve against the new
    root, not whatever was current at src.sync_manager's import time."""
    config.set_root(tmp_path)
    try:
        mgr = SyncManager(service=None, dry_run=True)
        assert mgr.db_path == tmp_path / "data" / "sync_state.db"
        assert mgr.inbox_dir == tmp_path / "data" / "inbox"
        assert mgr.processed_dir == tmp_path / "data" / "processed"
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_default_root_falls_back_to_project_dir_when_no_override():
    # No set_root() call in this test — exercises the untouched Windows
    # default path (WAMAILSYNC_ROOT env var / __file__-relative fallback).
    assert config.PROJECT_ROOT == Path(config.__file__).parent.parent


def test_env_root_fallback_prefers_new_var_over_legacy(tmp_path, monkeypatch):
    """WAMAILSYNC_ROOT is the current env var; WAGMAIL_ROOT is kept as a
    fallback for portable installs built before the WAGmailSync -> WAMailSync
    rename whose launcher .bat still exports the old name. Covers: old var
    alone still works, new var alone works, and new wins when both are set."""
    new_root = tmp_path / "new"
    old_root = tmp_path / "old"
    new_root.mkdir()
    old_root.mkdir()

    # Other tests in this module call set_root() and restore it to a
    # non-None path in their `finally`, which leaves _explicit_root set at
    # module scope. _compute_root() checks _explicit_root before env vars,
    # so it must be forced back to None here or this test would just see
    # whatever earlier tests left behind, regardless of the env vars below.
    # monkeypatch reverts this automatically at teardown.
    monkeypatch.setattr(config, "_explicit_root", None)

    # Old var alone still works.
    monkeypatch.delenv("WAMAILSYNC_ROOT", raising=False)
    monkeypatch.setenv("WAGMAIL_ROOT", str(old_root))
    assert config._compute_root() == old_root

    # New var alone works.
    monkeypatch.delenv("WAGMAIL_ROOT", raising=False)
    monkeypatch.setenv("WAMAILSYNC_ROOT", str(new_root))
    assert config._compute_root() == new_root

    # New wins when both are set.
    monkeypatch.setenv("WAGMAIL_ROOT", str(old_root))
    monkeypatch.setenv("WAMAILSYNC_ROOT", str(new_root))
    assert config._compute_root() == new_root
