"""UX-09: the user guide's "Where your messages appear" section no longer splits
by connection type (every connection is IMAP since 2.0.0)."""

from pathlib import Path

GUIDE = Path(__file__).resolve().parents[1] / "docs" / "user-guide.md"


def _text() -> str:
    return GUIDE.read_text(encoding="utf-8")


def test_guide_has_no_connection_type_split():
    assert "If you connected with" not in _text()


def test_section_7_points_at_the_whatsapp_folder():
    text = _text()
    start = text.index("## 7. Where your messages appear")
    end = text.index("## 8.", start)
    section = text[start:end]
    assert "look for a **WhatsApp** folder" in section
    assert "**On Gmail**, folders show as labels" in section


def test_mailbox_question_is_third_in_the_guide():
    text = _text()
    qs = [line for line in text.splitlines() if line.startswith("### Q. ")]
    assert qs[2] == "### Q. Where do my chats show up in my mailbox?"
    assert "Settings > Advanced >" in text
