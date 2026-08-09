"""Tests for the per-email traceability index.

The index exists so that an archived email can say which parsed messages it
carries. Two properties matter more than the rest and are tested hardest:

  * the attachment is valid JSON (it is assembled by hand, one message per
    line, rather than handed to json.dumps), and
  * the hashes in it are the same hashes the dedup path computes.

If the second ever drifted, a rebuild driven by these files would decide real
messages had never been archived and send them a second time - and it would do
so silently, which is the failure direction this whole feature is meant to
close.
"""

import base64
import email
import json
from datetime import datetime, timedelta

import pytest

from src.html_renderer import AttachmentPart, RenderedChunk, render_chunk
from src.mail_client import _build_html_mime_message, _build_mime_message
from src.mail_index import (
    HEADER_CHAT,
    HEADER_COUNT,
    HEADER_INDEX,
    HEADER_VERSION,
    INDEX_FILENAME,
    INDEX_SCHEMA,
    build_index,
    build_index_part,
    estimate_index_bytes,
    index_bytes,
)
from src.parser import ParsedMessage
from src.state import compute_message_hash


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _chunk(n=3, chat_id="test_chat", sender="Alice"):
    return [
        ParsedMessage(
            chat_id=chat_id,
            timestamp=datetime(2025, 3, 14, 9, 41, 0) + timedelta(seconds=i),
            sender=sender,
            body=f"message number {i}",
        )
        for i in range(n)
    ]


def _parse(built):
    """Turn a builder's {"raw": ...} dict back into an email.Message."""
    return email.message_from_bytes(base64.urlsafe_b64decode(built["raw"]))


def _index_from(msg):
    """Pull the index attachment out of a built message and parse it."""
    for part in msg.walk():
        if part.get_filename() == INDEX_FILENAME:
            return json.loads(part.get_payload(decode=True).decode("utf-8"))
    raise AssertionError(f"no {INDEX_FILENAME} attachment found")


def _html_message(chunk, attachments=None, display_name="Alice"):
    rendered = render_chunk(chunk, display_name, None, "")
    if attachments:
        rendered = RenderedChunk(
            html_body=rendered.html_body,
            inline_parts=rendered.inline_parts,
            attachments=attachments,
            total_bytes=rendered.total_bytes,
        )
    return _build_html_mime_message(
        display_name, chunk, "day", rendered, "WhatsApp/Alice", "<mid@local>"
    )


# ---------------------------------------------------------------------------
# The document
# ---------------------------------------------------------------------------

def test_index_records_every_message_in_order():
    chunk = _chunk(5)
    index = build_index("Alice", chunk, "day", "<mid@local>")

    assert index["schema"] == INDEX_SCHEMA
    assert index["chat_id"] == "test_chat"
    assert index["display_name"] == "Alice"
    assert index["message_id"] == "<mid@local>"
    assert index["chunk"] == "day"
    assert index["count"] == 5
    assert index["first_ts"] == chunk[0].timestamp_iso
    assert index["last_ts"] == chunk[-1].timestamp_iso
    assert [e["n"] for e in index["messages"]] == [1, 2, 3, 4, 5]


def test_index_hashes_match_the_dedup_hash():
    """The whole point: these must be the hashes _filter_messages() looks up."""
    chunk = _chunk(4)
    index = build_index("Alice", chunk, "day", "<mid@local>")

    for entry, msg in zip(index["messages"], chunk):
        assert entry["hash"] == compute_message_hash(
            msg.chat_id, msg.timestamp_iso, msg.sender, msg.body
        )


def test_index_is_a_pure_function_of_its_inputs():
    """No clock, no environment - two builds of the same chunk are identical."""
    chunk = _chunk(3)
    first = index_bytes(build_index("Alice", chunk, "day", "<mid@local>"))
    second = index_bytes(build_index("Alice", chunk, "day", "<mid@local>"))
    assert first == second


# ---------------------------------------------------------------------------
# Serialisation - hand-assembled, so prove it is really JSON
# ---------------------------------------------------------------------------

def test_serialised_index_is_valid_json():
    raw = index_bytes(build_index("Alice", _chunk(4), "day", "<mid@local>"))
    parsed = json.loads(raw)
    assert parsed["count"] == 4
    assert len(parsed["messages"]) == 4


def test_serialised_index_puts_one_message_per_line():
    """Readability by eye and by grep is the reason this is an attachment."""
    raw = index_bytes(build_index("Alice", _chunk(6), "day", "<mid@local>")).decode()
    entry_lines = [ln for ln in raw.splitlines() if '"hash"' in ln]
    assert len(entry_lines) == 6


@pytest.mark.parametrize(
    "hostile",
    [
        'quote " and backslash \\',
        "newline\nin body",
        "unicode ✅ 你好 emoji 🎉",
        "comma, brace } bracket ]",
    ],
)
def test_serialised_index_survives_hostile_content(hostile):
    """Structure is assembled here, but escaping is json.dumps' job."""
    chunk = [
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2025, 3, 14, 9, 41, 0),
            sender=hostile,
            body=hostile,
        )
    ]
    parsed = json.loads(index_bytes(build_index(hostile, chunk, "day", "<mid@local>")))
    assert parsed["messages"][0]["sender"] == hostile
    assert parsed["display_name"] == hostile


def test_single_message_index_has_no_trailing_comma():
    """The one-element case is where hand-assembled JSON usually breaks."""
    parsed = json.loads(index_bytes(build_index("Alice", _chunk(1), "day", "<mid@local>")))
    assert parsed["count"] == 1


# ---------------------------------------------------------------------------
# Size estimate - the split path relies on this never being too small
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("count", [1, 10, 250])
def test_estimate_is_an_upper_bound_on_the_real_part(count):
    part = build_index_part("Alice", _chunk(count), "day", "<mid@local>")
    assert len(part.as_bytes()) <= estimate_index_bytes(count)


# ---------------------------------------------------------------------------
# The HTML path - what production actually sends
# ---------------------------------------------------------------------------

def test_html_message_carries_the_index_attachment():
    chunk = _chunk(3)
    msg = _parse(_html_message(chunk))
    index = _index_from(msg)

    assert index["count"] == 3
    assert [e["hash"] for e in index["messages"]] == [
        compute_message_hash(m.chat_id, m.timestamp_iso, m.sender, m.body)
        for m in chunk
    ]


def test_html_message_carries_the_identity_headers():
    msg = _parse(_html_message(_chunk(3)))

    assert msg[HEADER_VERSION] == str(INDEX_SCHEMA)
    assert msg[HEADER_CHAT] == "test_chat"
    assert msg[HEADER_COUNT] == "3"
    assert msg[HEADER_INDEX] == INDEX_FILENAME


def test_index_does_not_displace_the_chats_own_attachments():
    """Adding our file must not cost the user theirs."""
    chunk = _chunk(2)
    attachment = AttachmentPart(
        filename="report.pdf", data=b"%PDF-1.4 fake", mime_type="application/pdf"
    )
    msg = _parse(_html_message(chunk, attachments=[attachment]))

    names = [p.get_filename() for p in msg.walk() if p.get_filename()]
    assert "report.pdf" in names
    assert INDEX_FILENAME in names


def test_html_body_is_still_present_alongside_the_index():
    msg = _parse(_html_message(_chunk(2)))
    html_parts = [p for p in msg.walk() if p.get_content_type() == "text/html"]
    assert len(html_parts) == 1


# ---------------------------------------------------------------------------
# The plain-text path
# ---------------------------------------------------------------------------

def test_plain_text_message_carries_the_index_and_its_body():
    chunk = _chunk(2)
    msg = _parse(
        _build_mime_message("Alice", chunk, "day", "WhatsApp/Alice", "<mid@local>")
    )

    assert _index_from(msg)["count"] == 2
    assert msg[HEADER_CHAT] == "test_chat"

    text_parts = [p for p in msg.walk() if p.get_content_type() == "text/plain"]
    assert len(text_parts) == 1
    assert "message number 0" in text_parts[0].get_payload(decode=True).decode("utf-8")


# ---------------------------------------------------------------------------
# Header safety
# ---------------------------------------------------------------------------

def test_chat_id_cannot_inject_a_header():
    """chat_id comes from a filename, so it is user-influenced."""
    chunk = _chunk(1, chat_id="evil\r\nX-Injected: yes")
    msg = _parse(_html_message(chunk))

    assert msg["X-Injected"] is None
    assert "\n" not in msg[HEADER_CHAT]
    # The authoritative, properly quoted copy still round-trips in the JSON.
    assert _index_from(msg)["chat_id"] == "evil\r\nX-Injected: yes"
