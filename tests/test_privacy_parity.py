"""The privacy policy is written three times, and unlike the FAQ the words
themselves have to match.

The FAQ's answers are deliberately different per surface -- Recycle Bin versus
a plain delete, DPAPI versus the Android Keystore. A privacy policy is not like
that. It is a statement about what the software does with someone's messages,
and two front-ends of the same application must not make different promises
about it. So:

* Android's ``PrivacyScreen.kt`` and Windows' ``gui.py`` are compared in full
  -- headings, order, and every paragraph, character for character. ``gui.py``'s
  list is generated from the Kotlin rather than typed, so any difference here
  means someone edited one copy by hand and did not regenerate the other.
* ``docs/privacy.html`` is the hosted page. It carries links, ``<strong>`` and
  entities that have no equivalent in either app, so only its **headings**,
  their **order** and the **"Last updated" line** are compared. That is the
  limit of this test and it is deliberate: prose-level comparison against
  marked-up HTML produces noise, not signal.

Why this exists at all: Indus Appstore put the app on hold twice over the
privacy policy. The remedy was to stop linking to it and start carrying it, on
both platforms. Carrying it means three copies, and three copies drift.
"""

from __future__ import annotations

import ast
import html
import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
GUI = REPO / "gui.py"
PRIVACY_HTML = REPO / "docs" / "privacy.html"
PRIVACY_SCREEN = (
    REPO
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "chatmailsync"
    / "app"
    / "PrivacyScreen.kt"
)

# The policy has ten sections. Stated here as a floor rather than left implied,
# because every comparison below is between two parsed structures -- and two
# parsers that both silently return nothing would agree perfectly. A test that
# cannot fail is worse than no test, and this is the line that stops that.
EXPECTED_SECTIONS = 10


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def _normalise(text: str) -> str:
    """Compare meaning, not typography.

    The hosted page is written with em-dashes and HTML entities; the two apps
    carry plain ASCII, because the Kotlin and Python sources are read by people
    in editors that do not all agree about encodings. None of that is drift.
    """
    text = html.unescape(text)
    for dash in ("—", "–"):
        text = text.replace(dash, "-")
    text = text.replace("→", "->")
    for quote in ("“", "”"):
        text = text.replace(quote, '"')
    for quote in ("‘", "’"):
        text = text.replace(quote, "'")
    return re.sub(r"\s+", " ", text).strip()


def _parse_kotlin() -> tuple[str, list[tuple[str, list[str]]]]:
    """Read PRIVACY_LAST_UPDATED and PRIVACY_POLICY out of the Kotlin source.

    Kotlin has no literal this can be handed to a parser, so the list is read
    the way it is written: a run of string literals, where the one followed by
    ``to listOf(`` is a heading, and a literal preceded by ``+`` continues the
    paragraph before it rather than starting a new one.
    """
    src = PRIVACY_SCREEN.read_text(encoding="utf-8")

    match = re.search(r'PRIVACY_LAST_UPDATED\s*=\s*\n?\s*"([^"]*)"', src)
    assert match, "PRIVACY_LAST_UPDATED not found in PrivacyScreen.kt"
    last_updated = match.group(1)

    body = src.split("internal val PRIVACY_POLICY", 1)[1].split("\n)\n", 1)[0]

    sections: list[tuple[str, list[str]]] = []
    current: tuple[str, list[str]] | None = None
    pending: str | None = None
    literal = re.compile(r'"((?:[^"\\]|\\.)*)"')

    index = 0
    while True:
        match = literal.search(body, index)
        if not match:
            break
        text = match.group(1).replace('\\"', '"').replace("\\\\", "\\")
        before = body[: match.start()].rstrip()
        after = body[match.end() :].lstrip()
        index = match.end()

        if after.startswith("to listOf("):
            if pending is not None and current is not None:
                current[1].append(pending)
            pending = None
            current = (text, [])
            sections.append(current)
        elif before.endswith("+"):
            pending = (pending or "") + text
        else:
            if pending is not None and current is not None:
                current[1].append(pending)
            pending = text

    if pending is not None and current is not None:
        current[1].append(pending)

    return last_updated, sections


def _parse_gui() -> tuple[str, list[tuple[str, list[str]]]]:
    """Read the same two constants out of gui.py without importing it.

    Importing would pull in CustomTkinter and build a theme, which is a great
    deal of machinery to stand up in order to read two literals -- and it would
    make this test fail for reasons that have nothing to do with the policy.
    """
    tree = ast.parse(GUI.read_text(encoding="utf-8"))
    found: dict[str, object] = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if isinstance(target, ast.Name) and target.id in (
                "PRIVACY_LAST_UPDATED",
                "PRIVACY_POLICY",
            ):
                found[target.id] = ast.literal_eval(node.value)

    assert "PRIVACY_LAST_UPDATED" in found, "PRIVACY_LAST_UPDATED not found in gui.py"
    assert "PRIVACY_POLICY" in found, "PRIVACY_POLICY not found in gui.py"

    sections = [(heading, list(paras)) for heading, paras in found["PRIVACY_POLICY"]]
    return str(found["PRIVACY_LAST_UPDATED"]), sections


def _parse_html() -> tuple[str, list[str]]:
    """Headings and the "Last updated" line from the hosted page."""
    src = PRIVACY_HTML.read_text(encoding="utf-8")
    headings = [
        _normalise(re.sub(r"<[^>]+>", "", h))
        for h in re.findall(r"<h2[^>]*>(.*?)</h2>", src, re.S)
    ]
    # Up to the closing tag, not to the first full stop -- the version number
    # in "version 2.0.0." is full of them.
    match = re.search(r"(Last updated:[^<]*)", src)
    assert match, "no 'Last updated' line in docs/privacy.html"
    return _normalise(re.sub(r"<[^>]+>", "", match.group(1))), headings


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


def test_parsers_actually_found_the_policy():
    """The guard the other tests rest on. See EXPECTED_SECTIONS above."""
    _, kotlin = _parse_kotlin()
    _, windows = _parse_gui()
    _, headings = _parse_html()

    assert len(kotlin) == EXPECTED_SECTIONS
    assert len(windows) == EXPECTED_SECTIONS
    assert len(headings) == EXPECTED_SECTIONS
    assert all(paras for _, paras in kotlin), "a Kotlin section parsed with no text"
    assert all(paras for _, paras in windows), "a Windows section parsed with no text"


def test_android_and_windows_carry_the_same_headings():
    _, kotlin = _parse_kotlin()
    _, windows = _parse_gui()
    assert [h for h, _ in kotlin] == [h for h, _ in windows]


def test_android_and_windows_carry_the_same_words():
    """Character for character. gui.py's list is generated from the Kotlin, so
    a failure here means one copy was hand-edited and the other was not."""
    _, kotlin = _parse_kotlin()
    _, windows = _parse_gui()

    for (kt_heading, kt_paras), (win_heading, win_paras) in zip(kotlin, windows):
        assert kt_heading == win_heading
        assert kt_paras == win_paras, f"section {kt_heading!r} differs between platforms"


def test_hosted_page_carries_the_same_headings_in_the_same_order():
    _, kotlin = _parse_kotlin()
    _, headings = _parse_html()
    assert [_normalise(h) for h, _ in kotlin] == headings


def test_last_updated_agrees_across_all_three():
    kotlin_updated, _ = _parse_kotlin()
    windows_updated, _ = _parse_gui()
    html_updated, _ = _parse_html()

    assert _normalise(kotlin_updated) == _normalise(windows_updated)

    # The two apps must agree exactly. The hosted page carries the same line
    # and then one sentence more -- a note that the app was previously called
    # "WhatsApp Chat Sync to Gmail" -- which is there for someone arriving on
    # an old link, and would be noise inside an app that has only ever shown
    # the current name. So: prefix, not equality.
    assert html_updated.startswith(_normalise(kotlin_updated))


def test_the_hosted_url_is_still_offered_as_a_secondary():
    """Carrying the policy did not mean dropping the link -- both apps still
    point at the canonical copy, they just no longer depend on it."""
    kotlin_src = PRIVACY_SCREEN.read_text(encoding="utf-8")
    gui_src = GUI.read_text(encoding="utf-8")
    assert "PRIVACY_POLICY_URL" in kotlin_src
    assert "PRIVACY_POLICY_URL" in gui_src


@pytest.mark.parametrize(
    "source",
    [
        pytest.param("android", id="android"),
        pytest.param("windows", id="windows"),
    ],
)
def test_no_unresolved_markup_leaked_into_the_apps(source: str):
    """Neither app renders HTML, so an entity or a tag that survived the move
    from the hosted page would be shown to the user literally."""
    sections = _parse_kotlin()[1] if source == "android" else _parse_gui()[1]
    for heading, paragraphs in sections:
        for text in [heading, *paragraphs]:
            assert "<" not in text, f"markup in {source} text: {text[:60]!r}"
            assert "&amp;" not in text and "&mdash;" not in text
