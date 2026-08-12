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


# ---------------------------------------------------------------------------
# Gmail OAuth visibility (v1.6.0 demotion)
#
# The truth table below is the contract, and it is deliberately the SAME four
# rows the Kotlin twin asserts against AppPrefs.oauthIsVisible. If a row here
# changes without the matching row there changing, the two platforms disagree
# about who is offered Google sign-in and exactly one of them is wrong.
# ---------------------------------------------------------------------------


def test_a_fresh_user_is_never_offered_oauth(tmp_path, monkeypatch):
    """The whole point of the demotion. No saved choice, no prior token, no
    unlock -> the option does not exist for this person."""
    monkeypatch.delenv(config.OAUTH_UNLOCK_ENV, raising=False)
    config.set_root(tmp_path)
    try:
        assert config.oauth_visible({}) is False
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_the_unlock_flag_reveals_oauth(tmp_path, monkeypatch):
    monkeypatch.delenv(config.OAUTH_UNLOCK_ENV, raising=False)
    config.set_root(tmp_path)
    try:
        assert config.oauth_visible({config.SETTING_OAUTH_UNLOCKED: True}) is True
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_an_existing_token_reveals_oauth_and_still_resolves_to_it(tmp_path, monkeypatch):
    """Case (c): someone already signed in with Google must be untouched.

    Both halves matter. The option has to stay visible, AND the backend has to
    still resolve to gmail_oauth -- hiding the option is a demotion, resolving
    them onto IMAP would be a silent migration that demands an app password
    they have never created."""
    monkeypatch.delenv(config.OAUTH_UNLOCK_ENV, raising=False)
    config.set_root(tmp_path)
    try:
        config.TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        config.TOKEN_FILE.write_text("{}")
        assert config.oauth_visible({}) is True
        assert config.resolve_mail_backend({}) == config.MAIL_BACKEND_GMAIL_OAUTH
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_switching_a_latched_oauth_user_to_imap_does_not_hide_the_option(
    tmp_path, monkeypatch
):
    """The one-way trap the latch exists to close.

    An OAuth user tries IMAP. Their saved backend is now imap and -- on Android,
    where signing out clears the connected email -- the evidence of prior OAuth
    use is gone for good. Without the latched flag they could never switch
    back, because the option that would let them has disappeared."""
    monkeypatch.delenv(config.OAUTH_UNLOCK_ENV, raising=False)
    config.set_root(tmp_path)
    try:
        saved = {"mail_backend": config.MAIL_BACKEND_GMAIL_OAUTH}
        assert config.should_latch_oauth(saved) is True
        saved[config.SETTING_OAUTH_UNLOCKED] = True

        # Already latched -- no second write needed.
        assert config.should_latch_oauth(saved) is False

        saved["mail_backend"] = config.MAIL_BACKEND_IMAP
        assert config.oauth_visible(saved) is True
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_the_env_var_unlocks_but_only_for_truthy_spellings(tmp_path, monkeypatch):
    """`=0` and `=false` read to a human as "off". Honouring them as "on"
    merely because they are non-empty is a surprise worth an afternoon."""
    config.set_root(tmp_path)
    try:
        for value in ("1", "true", "YES", "On"):
            monkeypatch.setenv(config.OAUTH_UNLOCK_ENV, value)
            assert config.oauth_visible({}) is True, value
        for value in ("", "0", "false", "no"):
            monkeypatch.setenv(config.OAUTH_UNLOCK_ENV, value)
            assert config.oauth_visible({}) is False, value
    finally:
        config.set_root(Path(__file__).parent.parent)


def test_oauth_is_visible_is_pure_and_needs_no_files_or_settings():
    """Guards the property that makes the Kotlin twin testable at all.

    AppPrefs.oauthIsVisible has to be callable without a Context, because this
    repo has no Kotlin test source set and adding an Android test runtime to
    assert one boolean is out of proportion. Keeping the Python side the same
    three-argument shape is what lets both platforms check the same rows."""
    assert config.oauth_is_visible(None, False, False) is False
    assert config.oauth_is_visible(config.MAIL_BACKEND_GMAIL_OAUTH, False, False) is True
    assert config.oauth_is_visible(None, True, False) is True
    assert config.oauth_is_visible(None, False, True) is True
    assert config.oauth_is_visible(config.MAIL_BACKEND_IMAP, False, False) is False


def test_default_root_falls_back_to_project_dir_when_no_override():
    # No set_root() call in this test — exercises the untouched Windows
    # default path (WAMAILSYNC_ROOT env var / __file__-relative fallback).
    assert config.PROJECT_ROOT == Path(config.__file__).parent.parent


def test_env_root_uses_wamailsync_root_and_ignores_the_legacy_name(
    tmp_path, monkeypatch
):
    """WAMAILSYNC_ROOT is the only env var honoured.

    A WAGMAIL_ROOT fallback lived in _compute_root() until 2026-08-08, for
    portable installs predating the WAGmailSync -> WAMailSync rename. It was
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
    monkeypatch.setenv("WAMAILSYNC_ROOT", str(new_root))
    assert config._compute_root() == new_root

    # The legacy name alone is ignored -- falls through to the default.
    monkeypatch.delenv("WAMAILSYNC_ROOT", raising=False)
    monkeypatch.setenv("WAGMAIL_ROOT", str(old_root))
    assert config._compute_root() == Path(config.__file__).parent.parent

    # And it cannot override the current var when both are set.
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
