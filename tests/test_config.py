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
        assert config.LEGACY_TOKEN_FILE == tmp_path / "auth" / "token.json"
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_sync_manager_picks_up_root_set_before_construction(tmp_path):
    """The exact scenario Android needs: set_root() runs, then a module is
    constructed with no explicit path args and must resolve against the new
    root, not whatever was current at src.sync_manager's import time."""
    config.set_root(tmp_path)
    try:
        mgr = SyncManager(dry_run=True)
        assert mgr.db_path == tmp_path / "data" / "sync_state.db"
        assert mgr.inbox_dir == tmp_path / "data" / "inbox"
        assert mgr.processed_dir == tmp_path / "data" / "processed"
    finally:
        config.set_root(Path(__file__).parent.parent)


# ---------------------------------------------------------------------------
# Legacy Google sign-in (removed in v2.0.0)
#
# Google sign-in is gone, but installs that used it are not. The two helpers
# below are the whole of what survives, and the rows here are deliberately the
# SAME rows the Kotlin twin asserts against AppPrefs.isLegacyOauthUser and
# AppPrefs.resolveMailBackend. If a row changes here without the matching row
# changing there, the two platforms disagree about who gets an explanation and
# exactly one of them is wrong.
# ---------------------------------------------------------------------------


def test_a_fresh_user_is_not_treated_as_a_legacy_oauth_user(tmp_path):
    config.set_root(tmp_path)
    try:
        assert config.is_legacy_oauth_user({}) is False
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_a_saved_gmail_oauth_backend_is_evidence(tmp_path):
    config.set_root(tmp_path)
    try:
        saved = {"mail_backend": config.LEGACY_MAIL_BACKEND_GMAIL_OAUTH}
        assert config.is_legacy_oauth_user(saved) is True
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_a_leftover_token_json_is_evidence_on_its_own(tmp_path):
    """Someone who signed out, or whose settings file was reset, still has the
    file OAuth left in auth/ — and still deserves the explanation."""
    config.set_root(tmp_path)
    try:
        config.LEGACY_TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        config.LEGACY_TOKEN_FILE.write_text("{}", encoding="utf-8")
        assert config.is_legacy_oauth_user({}) is True
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_a_saved_gmail_oauth_backend_resolves_to_imap_not_itself(tmp_path):
    """The deliberate reversal of the v1.6.0 behaviour.

    Before the strip, a gmail_oauth user resolved to gmail_oauth so they were
    never silently migrated. Nothing can build that transport now, so honouring
    it would hand the sync worker a backend name it cannot serve and the user
    would meet a crash instead of an explanation. is_legacy_oauth_user() is
    what gets them the explanation."""
    config.set_root(tmp_path)
    try:
        saved = {"mail_backend": config.LEGACY_MAIL_BACKEND_GMAIL_OAUTH}
        assert config.resolve_mail_backend(saved) == config.MAIL_BACKEND_IMAP
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_resolve_mail_backend_defaults_to_imap_and_honours_anything_else(tmp_path):
    config.set_root(tmp_path)
    try:
        assert config.resolve_mail_backend({}) == config.MAIL_BACKEND_IMAP
        assert (
            config.resolve_mail_backend({"mail_backend": config.MAIL_BACKEND_IMAP})
            == config.MAIL_BACKEND_IMAP
        )
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_default_root_falls_back_to_project_dir_when_no_override():
    # No set_root() call in this test — exercises the untouched Windows
    # default path (CHATMAILSYNC_ROOT env var / __file__-relative fallback).
    assert config.PROJECT_ROOT == Path(config.__file__).parent.parent


def test_env_root_uses_chatmailsync_root_and_ignores_the_legacy_name(
    tmp_path, monkeypatch
):
    """CHATMAILSYNC_ROOT is the only env var honoured.

    A WAGMAIL_ROOT fallback lived in _compute_root() until 2026-08-08, for
    portable installs predating the WAGmailSync -> WA Mail Sync rename. It was
    dropped deliberately (see the comment there), so the interesting assertion
    is now the *negative* one: an install still exporting only the old name
    must fall through to the __file__-relative default rather than silently
    reading a stale root.
    """
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

    # The current var works.
    monkeypatch.delenv("WAGMAIL_ROOT", raising=False)
    monkeypatch.setenv("CHATMAILSYNC_ROOT", str(new_root))
    assert config._compute_root() == new_root

    # The legacy name alone is ignored -- falls through to the default.
    monkeypatch.delenv("CHATMAILSYNC_ROOT", raising=False)
    monkeypatch.setenv("WAGMAIL_ROOT", str(old_root))
    assert config._compute_root() == Path(config.__file__).parent.parent

    # And it cannot override the current var when both are set.
    monkeypatch.setenv("CHATMAILSYNC_ROOT", str(new_root))
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


def test_is_gmail_mailbox_true_for_imap_pointed_at_gmail():
    """Host-only since v2.0.0: IMAP is the only backend, and many users here
    point it at imap.gmail.com with an app password."""
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
