"""Tests for cli.py's --cutoff / --no-cutoff (CQ2 step 3).

The flag is thin, but every one of the four ways it can resolve is a
different promise to the user, and three of them are silent when wrong:
a mistyped date that parses as something else, a saved setting the CLI
ignores, and a --no-cutoff that does not actually override. So each
resolution path gets its own test, driven through the real build_parser()
rather than a hand-made Namespace -- a test that builds its own args
object cannot catch a flag that was never wired into the parser.
"""

import json

import pytest

import cli
import gui_worker


@pytest.fixture
def settings(tmp_path, monkeypatch):
    """Point gui_worker's settings file somewhere empty.

    cli._resolve_cutoff reads the desktop app's saved setting through
    gui_worker.load_saved_cutoff_date, so without this the answer would come
    from the developer's real data\\.settings.json.
    """
    path = tmp_path / "data" / ".settings.json"
    monkeypatch.setattr(gui_worker, "_SETTINGS_FILE", path)
    return path


def _write(path, **keys):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(keys))


def _sync_args(*argv):
    return cli.build_parser().parse_args(["sync", *argv])


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------

def test_the_flag_normalises_to_midnight_at_parse_time():
    """The value that reaches SyncManager is the instant, not the date the
    user typed, so the comparison downstream is never doing string surgery of
    its own."""
    assert _sync_args("--cutoff", "2026-01-01").cutoff == "2026-01-01T00:00:00"


def test_a_date_that_cannot_be_compared_is_refused_before_anything_runs():
    """argparse exits 2 with its own message rather than letting a bad date
    reach a sync that has already opened the mailbox."""
    with pytest.raises(SystemExit):
        _sync_args("--cutoff", "01-01-2026")


def test_asking_for_a_cutoff_and_for_none_at_once_is_refused():
    """They are mutually exclusive, so there is no precedence rule to get
    wrong -- and no run where the user thinks one won and the other did."""
    with pytest.raises(SystemExit):
        _sync_args("--cutoff", "2026-01-01", "--no-cutoff")


def test_neither_flag_leaves_both_defaults_alone():
    args = _sync_args()
    assert args.cutoff is None
    assert args.no_cutoff is False


# ---------------------------------------------------------------------------
# Resolution
# ---------------------------------------------------------------------------

def test_the_flag_wins_over_the_saved_setting(settings):
    _write(settings, cutoff_date="2020-06-06")
    assert cli._resolve_cutoff(_sync_args("--cutoff", "2026-01-01")) == (
        "2026-01-01T00:00:00"
    )


def test_the_saved_setting_applies_when_the_flag_is_absent(settings):
    """The CLI and the desktop app are two doors into one archive. A sync
    started from the command line that ignored the floor set in the app would
    deliver exactly the messages the user had asked not to have.
    """
    _write(settings, cutoff_date="2026-01-01")
    assert cli._resolve_cutoff(_sync_args()) == "2026-01-01T00:00:00"


def test_no_cutoff_overrides_the_saved_setting(settings):
    _write(settings, cutoff_date="2026-01-01")
    assert cli._resolve_cutoff(_sync_args("--no-cutoff")) is None


def test_no_saved_setting_and_no_flag_means_no_floor(settings):
    assert cli._resolve_cutoff(_sync_args()) is None


def test_a_blank_saved_setting_is_the_same_as_none(settings):
    """"" is what the desktop field holds when it has been cleared. It must
    not survive as a string that sorts below every real timestamp."""
    _write(settings, cutoff_date="")
    assert cli._resolve_cutoff(_sync_args()) is None


def test_an_unreadable_saved_setting_syncs_without_a_floor_not_with_a_wrong_one(
    settings, capsys
):
    """A hand-edited settings file. Guessing at what the date meant could
    withhold messages the user never asked to withhold, and this feature must
    never lose one by accident -- so it warns and applies nothing.
    """
    _write(settings, cutoff_date="last Tuesday")

    assert cli._resolve_cutoff(_sync_args()) is None
    assert "cutoff" in capsys.readouterr().err.lower()


def test_a_corrupt_settings_file_does_not_take_the_sync_down_with_it(settings):
    settings.parent.mkdir(parents=True, exist_ok=True)
    settings.write_text("{not json at all")
    assert cli._resolve_cutoff(_sync_args()) is None
