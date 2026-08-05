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


# ---------------------------------------------------------------------------
# is_gmail_mailbox / mailbox_clear_steps
#
# These two exist so gui.py and cli.py cannot give contradictory instructions
# for the same destructive action (reset), and because getting the Gmail case
# wrong is worse than saying nothing: Gmail has no folders, only labels, so
# "delete the folder" unlabels every message and leaves it in All Mail. The
# user then truthfully answers "yes, I deleted it" and the next sync
# duplicates the lot.
# ---------------------------------------------------------------------------


def test_is_gmail_mailbox_true_for_oauth_regardless_of_host():
    assert config.is_gmail_mailbox({"mail_backend": config.MAIL_BACKEND_GMAIL_OAUTH})


def test_is_gmail_mailbox_true_for_imap_pointed_at_gmail():
    """The case the OAuth-only check missed: IMAP is the default backend now
    and most users here point it at imap.gmail.com with an app password."""
    for host in ("imap.gmail.com", "IMAP.GMAIL.COM", "imap.googlemail.com"):
        assert config.is_gmail_mailbox(
            {"mail_backend": config.MAIL_BACKEND_IMAP, "imap_host": host}
        ), host


def test_is_gmail_mailbox_false_for_other_imap_hosts():
    for host in ("imap.fastmail.com", "outlook.office365.com", ""):
        assert not config.is_gmail_mailbox(
            {"mail_backend": config.MAIL_BACKEND_IMAP, "imap_host": host}
        ), host


def test_is_gmail_mailbox_tolerates_missing_and_null_host():
    assert not config.is_gmail_mailbox({"mail_backend": config.MAIL_BACKEND_IMAP})
    assert not config.is_gmail_mailbox(
        {"mail_backend": config.MAIL_BACKEND_IMAP, "imap_host": None}
    )


def test_mailbox_clear_steps_gmail_never_says_delete_the_folder():
    steps = config.mailbox_clear_steps("WhatsApp/Alice", gmail=True)
    joined = " ".join(steps).lower()
    assert "delete the folder" not in joined
    assert "all mail" in joined          # says why unlabelling is not enough
    assert any("WhatsApp/Alice" in s for s in steps)


def test_mailbox_clear_steps_non_gmail_names_the_folder():
    steps = config.mailbox_clear_steps("WhatsApp/Alice", gmail=False)
    assert any("delete the folder 'WhatsApp/Alice'" in s for s in steps)
