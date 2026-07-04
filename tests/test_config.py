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
    # default path (WAGMAIL_ROOT env var / __file__-relative fallback).
    assert config.PROJECT_ROOT == Path(config.__file__).parent.parent
