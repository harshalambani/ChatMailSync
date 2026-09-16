"""The privacy policy is written twice, and unlike the FAQ's answers the words
themselves are a promise about what the software does with someone's messages.

* ``PrivacyScreen.kt`` is the copy the app carries.
* ``docs/privacy.html`` is the hosted page. It carries links, ``<strong>`` and
  entities that have no equivalent in the app, so only its **headings**, their
  **order** and the **"Last updated" line** are compared. That is the limit of
  this test and it is deliberate: prose-level comparison against marked-up HTML
  produces noise, not signal.

Why this exists at all: Indus Appstore put the app on hold twice over the
privacy policy. The remedy was to stop linking to it and start carrying it.
Carrying it means two copies, and two copies drift.

Until 2.1.5 there was a third copy in the Windows desktop app (tag
``windows-final``), compared here character for character.
"""

from __future__ import annotations

import html
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
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

    The hosted page is written with em-dashes and HTML entities; the app
    carries plain ASCII, because the Kotlin source is read by people in
    editors that do not all agree about encodings. None of that is drift.
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
    _, headings = _parse_html()

    assert len(kotlin) == EXPECTED_SECTIONS
    assert len(headings) == EXPECTED_SECTIONS
    assert all(paras for _, paras in kotlin), "a Kotlin section parsed with no text"


def test_hosted_page_carries_the_same_headings_in_the_same_order():
    _, kotlin = _parse_kotlin()
    _, headings = _parse_html()
    assert [_normalise(h) for h, _ in kotlin] == headings


def test_hosted_page_last_updated_starts_with_the_apps():
    kotlin_updated, _ = _parse_kotlin()
    html_updated, _ = _parse_html()

    # The hosted page carries the app's line and then one sentence more -- a
    # note that the app was previously called "WhatsApp Chat Sync to Gmail" --
    # which is there for someone arriving on an old link, and would be noise
    # inside an app that has only ever shown the current name. So: prefix,
    # not equality.
    assert html_updated.startswith(_normalise(kotlin_updated))


def test_the_hosted_url_is_still_offered_as_a_secondary():
    """Carrying the policy did not mean dropping the link -- the app still
    points at the canonical copy, it just no longer depends on it."""
    kotlin_src = PRIVACY_SCREEN.read_text(encoding="utf-8")
    assert "PRIVACY_POLICY_URL" in kotlin_src


def test_no_unresolved_markup_leaked_into_the_app():
    """The app does not render HTML, so an entity or a tag that survived the
    move from the hosted page would be shown to the user literally."""
    for heading, paragraphs in _parse_kotlin()[1]:
        for text in [heading, *paragraphs]:
            assert "<" not in text, f"markup in app text: {text[:60]!r}"
            assert "&amp;" not in text and "&mdash;" not in text
