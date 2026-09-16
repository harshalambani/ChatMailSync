"""The FAQ is written twice -- in the app's Help screen and in the user guide --
and hand-kept in step. These tests hold the two lists to the same questions in
the same order, so a question added to one surface and forgotten on the other
fails here rather than drifting for months.

Only the *questions* are compared; the answers may be worded differently.
Until 2.1.5 the Windows app's help.html was a third surface (tag windows-final).
"""

from __future__ import annotations

import html
import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
USER_GUIDE = REPO / "docs" / "user-guide.md"
HELP_SCREEN = (
    REPO
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "chatmailsync"
    / "app"
    / "HelpScreen.kt"
)


def _normalise(question: str) -> str:
    """Compare meaning, not typography.

    The same question is spelt with an em-dash on one surface and a hyphen on
    another, or with curly or straight quotes. None of that is a parity
    failure.
    """
    text = html.unescape(question)
    for dash in ("—", "–"):
        text = text.replace(dash, "-")
    for quote in ("‘", "’"):
        text = text.replace(quote, "'")
    for quote in ("“", "”"):
        text = text.replace(quote, '"')
    text = re.sub(r"<[^>]+>", "", text)
    return " ".join(text.split()).casefold()


def user_guide_questions() -> list[str]:
    body = USER_GUIDE.read_text(encoding="utf-8")
    return [
        _normalise(m) for m in re.findall(r"^### Q\.\s*(.+?)\s*$", body, re.M)
    ]


def help_screen_questions() -> list[str]:
    body = HELP_SCREEN.read_text(encoding="utf-8")
    # Matched without the visibility modifier: `private` became `internal`
    # when HelpLinkTest needed to read the table, and anchoring on the old
    # spelling made this parser silently find nothing. The guard test above
    # is what caught that, and it is the reason it exists.
    start = re.search(r"^\w* ?val FAQ = listOf\($", body, re.M)
    assert start is not None, "HelpScreen.kt no longer declares `val FAQ = listOf(`"
    faq = body[start.start() :]
    faq = faq[: faq.index("\n)\n")]
    # A question is the string literal on a line ending in `" to`; answers are
    # concatenated with `+` and never end that way.
    raw = re.findall(r'^\s{4}"((?:[^"\\]|\\.)*)" to$', faq, re.M)
    return [_normalise(q.replace('\\"', '"')) for q in raw]


ALL_SURFACES = {
    "docs/user-guide.md": user_guide_questions,
    "HelpScreen.kt": help_screen_questions,
}


@pytest.mark.parametrize("name", sorted(ALL_SURFACES))
def test_surface_parses_to_a_non_empty_faq(name: str) -> None:
    """Guard the parsers themselves.

    A regex that silently matches nothing would make every parity test below
    pass by comparing empty lists against each other.
    """
    questions = ALL_SURFACES[name]()
    assert len(questions) >= 10, f"{name} parsed to only {len(questions)} questions"


def test_no_surface_repeats_a_question() -> None:
    for name, getter in ALL_SURFACES.items():
        questions = getter()
        duplicates = {q for q in questions if questions.count(q) > 1}
        assert not duplicates, f"{name} asks the same question twice: {duplicates}"


def test_both_surfaces_ask_the_same_questions_in_the_same_order() -> None:
    assert user_guide_questions() == help_screen_questions(), (
        "docs/user-guide.md has drifted from HelpScreen.kt. Every FAQ question "
        "must appear on both surfaces, in the same order."
    )
