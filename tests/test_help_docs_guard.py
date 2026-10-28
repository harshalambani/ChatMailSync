"""DOC-02: keep the Help text, the user guide, the site and the README true.

Each phrase below was once published and was wrong against the code. These tests
fail if one of them comes back, and pin the corrected statements that matter most
(a Delete that warns of a second copy, a Reset that says to export again).
"""

from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
HELP = REPO / "android" / "app" / "src" / "main" / "java" / "com" / "chatmailsync" / "app" / "HelpScreen.kt"
GUIDE = REPO / "docs" / "user-guide.md"
SITE = REPO / "docs" / "index.html"
README = REPO / "README.md"


def _read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def _flat(p: Path) -> str:
    """Kotlin string concatenation and markdown line wraps, collapsed to one line."""
    text = _read(p)
    text = text.replace('" +\n', "").replace('        "', "")
    return " ".join(text.split())


def test_delete_never_tells_the_user_to_import_again_to_get_the_chat_back() -> None:
    for p in (HELP, GUIDE):
        assert "Import the export file again to bring" not in _flat(p), p.name
        assert "add the export file again to bring it back" not in _flat(p).lower(), p.name


def test_delete_answer_warns_of_a_second_copy() -> None:
    for p in (HELP, GUIDE):
        flat = _flat(p)
        start = flat.index("Delete from list")
        assert "second copy of every message" in flat, p.name
        assert start >= 0


def test_reset_answer_says_to_export_again_and_never_promises_the_file_back() -> None:
    assert "moves its file back to the inbox" not in _read(GUIDE)
    assert "moves its file back to the inbox" not in _flat(GUIDE)
    for p in (HELP, GUIDE):
        flat = _flat(p)
        assert "export the chat again from WhatsApp and share it to" in flat or (
            "export the chat" in flat and "again from WhatsApp and share it to" in flat
        ), p.name


def test_old_auto_import_label_is_gone() -> None:
    for p in (HELP, GUIDE):
        assert "Auto-import from this folder" not in _flat(p), p.name


def test_pull_to_refresh_is_not_promised() -> None:
    assert "Pull the Home list to refresh" not in _flat(HELP)
    assert "Pull the Home list" not in _flat(GUIDE)


def test_files_in_inbox_label_is_gone_from_the_guide() -> None:
    assert "Files in inbox" not in _read(GUIDE)


def test_backup_claim_is_not_stronger_than_the_code() -> None:
    for p in (HELP, GUIDE):
        flat = _flat(p)
        assert "brings the record back by itself" not in flat, p.name
        assert "do not rely on it" in flat, p.name


def test_new_questions_exist_on_both_surfaces() -> None:
    for q in (
        "Is this the same chat?",
        "What does \"Cancel sync\" do?",
        "The sync said an export could not be read and was kept.",
        "I am switching to the F-Droid build.",
    ):
        assert q in _read(GUIDE), q
        assert q.replace('"', '\\"') in _read(HELP), q


def test_site_does_not_mention_a_python_engine() -> None:
    assert "python" not in _read(SITE).lower()


def test_readme_does_not_claim_a_python_runtime_in_the_app() -> None:
    flat = " ".join(_read(README).split())
    assert "carries a Python runtime" not in flat
    assert "will not install on an emulator" not in flat
    assert "to `processed/`" not in flat
