"""The Windows half of the connection-status contract (Batch G).

The masthead used to have two states, and the green one meant "a credential
is stored" -- not "the mailbox answered". Connecting therefore looked
identical before and after the account had ever actually worked, which is the
feedback this exists to answer ("connected - feels like a non-event").

gui._auth_display is the whole of that judgement, kept pure so it can be
asserted without a Tk root. The rows below are the same rows
android/app/src/test/.../ConnectionStatusTest.kt asserts against
connectionStatusOf; if one side changes without the other, the two products
are telling the same user two different things about the same mailbox.

Status keys here are gui_theme.STATUS_COLOR keys, so a typo is a KeyError at
paint time rather than a wrong colour -- test_status_keys_are_real_theme_keys
catches it here instead.
"""

import json

import pytest

import gui
import gui_theme


# ---------------------------------------------------------------------------
# _auth_display -- the three-way judgement
# ---------------------------------------------------------------------------

def test_no_readable_credential_is_red_and_says_why():
    # The text is passed through untouched: it is already the explanation
    # ("Not connected", "Sign-in expired -- reconnect", "Credential error: ...")
    # and rewriting it would lose the reason.
    assert gui._auth_display(False, "Not connected", None) == ("failed", "Not connected")
    assert gui._auth_display(False, "Sign-in expired — reconnect", True) == (
        "failed",
        "Sign-in expired — reconnect",
    )


def test_saved_but_never_attempted_is_amber_not_green():
    # The state that did not exist before, and the one every existing install
    # lands in on upgrade: nothing prior to this release wrote the verdict down.
    status, label = gui._auth_display(True, "Connected (a@b.com)", None)
    assert status == "pending"
    assert label == "Not tested (a@b.com)"


def test_last_attempt_failed_is_red_even_though_the_credential_is_readable():
    status, label = gui._auth_display(True, "Connected (a@b.com)", False)
    assert status == "failed"
    assert label == "No connection (a@b.com)"


def test_last_attempt_succeeded_is_green_and_unchanged():
    assert gui._auth_display(True, "Connected (a@b.com)", True) == (
        "complete",
        "Connected (a@b.com)",
    )


def test_gmail_text_has_no_tail_and_still_reads():
    # _check_gmail_auth_status returns a bare "Connected", unlike the IMAP
    # side's "Connected (<address>)".
    assert gui._auth_display(True, "Connected", None) == ("pending", "Not tested")
    assert gui._auth_display(True, "Connected", False) == ("failed", "No connection")


def test_status_keys_are_real_theme_keys():
    for last_ok in (None, True, False):
        for valid in (True, False):
            status, _ = gui._auth_display(valid, "Connected", last_ok)
            assert status in gui_theme.STATUS_COLOR
            assert status in gui_theme.STATUS_COLOR_ON_BAND


# ---------------------------------------------------------------------------
# _relabel_connected -- keep the tail, replace the claim
# ---------------------------------------------------------------------------

def test_relabel_keeps_which_account_it_was_about():
    assert gui._relabel_connected("Connected (a@b.com)", "Not tested") == "Not tested (a@b.com)"


def test_relabel_leaves_anything_that_is_not_a_connected_claim():
    for text in ("Not connected", "Auth failed", "Credential error: bad blob", ""):
        assert gui._relabel_connected(text, "Not tested") == text


# ---------------------------------------------------------------------------
# Persistence -- the verdict survives a restart, the credential is not stored
# ---------------------------------------------------------------------------

def test_verdict_defaults_to_never_attempted(tmp_path, monkeypatch):
    monkeypatch.setattr(gui, "_SETTINGS_FILE", tmp_path / "data" / ".settings.json")
    settings = gui._load_settings()
    assert settings["last_connection_ok"] is None
    assert settings["last_connection_at"] == 0


def test_verdict_round_trips(tmp_path, monkeypatch):
    path = tmp_path / "data" / ".settings.json"
    monkeypatch.setattr(gui, "_SETTINGS_FILE", path)
    settings = gui._load_settings()
    settings["last_connection_ok"] = True
    settings["last_connection_at"] = 1_700_000_000
    gui._save_settings(settings)

    reloaded = gui._load_settings()
    assert reloaded["last_connection_ok"] is True
    assert reloaded["last_connection_at"] == 1_700_000_000


def test_a_settings_file_written_before_this_release_reads_as_never_attempted(
    tmp_path, monkeypatch
):
    # The upgrade path: an existing user's file has neither key, and must land
    # on amber "Not tested" rather than inheriting a green it never earned.
    path = tmp_path / "data" / ".settings.json"
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps({"chunk_size": "day", "imap_email": "a@b.com"}))
    monkeypatch.setattr(gui, "_SETTINGS_FILE", path)

    settings = gui._load_settings()
    assert settings["last_connection_ok"] is None
    assert gui._auth_display(True, "Connected (a@b.com)", settings["last_connection_ok"])[0] == (
        "pending"
    )


def test_nothing_about_the_credential_is_written_with_the_verdict(tmp_path, monkeypatch):
    # A verdict and a timestamp, nothing more -- no host, no address, and
    # emphatically no password.
    path = tmp_path / "data" / ".settings.json"
    monkeypatch.setattr(gui, "_SETTINGS_FILE", path)
    settings = gui._load_settings()
    settings["last_connection_ok"] = False
    settings["last_connection_at"] = 1_700_000_000
    gui._save_settings(settings)

    written = json.loads(path.read_text())
    assert isinstance(written["last_connection_ok"], bool)
    assert isinstance(written["last_connection_at"], int)
    assert "password" not in json.dumps(written).lower()
