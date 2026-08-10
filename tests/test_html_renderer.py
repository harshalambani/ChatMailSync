from datetime import datetime

import base64

from src.html_renderer import (
    encoded_part_bytes,
    max_raw_bytes_for,
    render_chunk,
)
from src.parser import ParsedMessage


def _msg(sender, body, attachment_filename=None, ts=datetime(2025, 3, 14, 9, 41, 0)):
    return ParsedMessage(
        chat_id="test_chat",
        timestamp=ts,
        sender=sender,
        body=body,
        attachment_filename=attachment_filename,
    )


class _FakeExtractor:
    """Minimal stand-in for MediaExtractor.resolve()."""

    def __init__(self, files: dict):
        self._files = files  # filename -> (bytes, mime_type)

    def resolve(self, filename):
        return self._files.get(filename)


def test_render_plain_text_message_no_attachments():
    messages = [_msg("Alice", "Hello world")]
    rendered = render_chunk(messages, "Alice", extractor=None)

    assert "Hello world" in rendered.html_body
    assert "14 March 2025" in rendered.html_body
    assert rendered.inline_parts == []
    assert rendered.attachments == []
    assert rendered.total_bytes == len(rendered.html_body.encode("utf-8"))


def test_outgoing_vs_incoming_bubble_styling():
    messages = [_msg("You", "outgoing msg"), _msg("Alice", "incoming msg")]
    rendered = render_chunk(messages, "Alice", extractor=None)

    # Outgoing bubbles are right-aligned (flex-end) with the green background;
    # incoming bubbles are left-aligned (flex-start) with white.
    assert "justify-content:flex-end" in rendered.html_body
    assert "justify-content:flex-start" in rendered.html_body
    assert "#DCF8C6" in rendered.html_body  # outgoing bubble color


def test_html_escaping_of_user_content():
    messages = [_msg("Alice", '<script>alert("x")</script> & friends')]
    rendered = render_chunk(messages, "Alice", extractor=None)

    assert "<script>" not in rendered.html_body
    assert "&lt;script&gt;" in rendered.html_body
    assert "&amp;" in rendered.html_body


def test_inline_image_embedding_produces_cid_reference():
    png_bytes = b"\x89PNG\r\n\x1a\n" + b"fake-png-data"
    extractor = _FakeExtractor({"photo.jpg": (png_bytes, "image/jpeg")})
    messages = [_msg("Alice", "photo.jpg (file attached)", attachment_filename="photo.jpg")]

    rendered = render_chunk(messages, "Alice", extractor)

    assert len(rendered.inline_parts) == 1
    part = rendered.inline_parts[0]
    assert part.data == png_bytes
    assert part.mime_type == "image/jpeg"
    assert f'src="cid:{part.cid}"' in rendered.html_body
    assert rendered.attachments == []


def test_non_image_attachment_routes_to_attachments_not_inline():
    pdf_bytes = b"%PDF-1.4 fake"
    extractor = _FakeExtractor({"doc.pdf": (pdf_bytes, "application/pdf")})
    messages = [_msg("Alice", "doc.pdf (file attached)", attachment_filename="doc.pdf")]

    rendered = render_chunk(messages, "Alice", extractor)

    assert rendered.inline_parts == []
    assert len(rendered.attachments) == 1
    assert rendered.attachments[0].filename == "doc.pdf"
    assert rendered.attachments[0].data == pdf_bytes


def test_attachment_marker_stripped_from_display_text():
    extractor = _FakeExtractor({"photo.jpg": (b"data", "image/jpeg")})
    messages = [_msg("Alice", "photo.jpg (file attached)", attachment_filename="photo.jpg")]

    rendered = render_chunk(messages, "Alice", extractor)

    assert "(file attached)" not in rendered.html_body


# ---------------------------------------------------------------------------
# Encoded-size projection
#
# These exist because the live "[TOOBIG] Message too large" failure of
# 2026-08-10 came from measuring raw bytes and comparing them to a limit the
# provider applies to the *encoded* message. Every assertion below is really
# the same one: our projection must never come out under what the wire
# actually carries.
# ---------------------------------------------------------------------------

def test_encoded_part_bytes_is_never_under_real_base64_output():
    # Sizes chosen to straddle the 3-byte and 76-char boundaries, where an
    # off-by-one in the line-break arithmetic would hide.
    for raw_length in (1, 2, 3, 4, 56, 57, 58, 1000, 65_536, 1_048_576):
        payload = b"x" * raw_length
        real = len(base64.encodebytes(payload))
        projected = encoded_part_bytes(raw_length)
        assert projected >= real, (
            f"projection {projected} under-counts {real} real bytes "
            f"for a {raw_length}-byte payload"
        )


def test_encoded_part_bytes_expands_by_roughly_37_percent():
    # 4 chars per 3 bytes (1.3333) plus a CRLF every 76 chars (x1.0263).
    one_mib = 1_048_576
    ratio = encoded_part_bytes(one_mib) / one_mib
    assert 1.36 < ratio < 1.38


def test_max_raw_bytes_for_round_trips_within_its_budget():
    for budget in (1_000_000, 22_500_000, 25_000_000, 63_000_000):
        raw = max_raw_bytes_for(budget)
        assert encoded_part_bytes(raw) <= budget
        # And it is not needlessly pessimistic: one more 3-byte group would
        # not fit either way, so the answer is tight to within a group.
        assert encoded_part_bytes(raw + 3_000) > budget - 3_000


def test_max_raw_bytes_for_refuses_a_budget_smaller_than_the_headers():
    assert max_raw_bytes_for(10) == 0


def test_wire_bytes_exceeds_raw_total_bytes():
    payload = b"\x89PNG\r\n\x1a\n" + b"z" * 200_000
    extractor = _FakeExtractor({"big.jpg": (payload, "image/jpeg")})
    messages = [_msg("Alice", "big.jpg (file attached)", attachment_filename="big.jpg")]

    rendered = render_chunk(messages, "Alice", extractor)

    assert rendered.wire_bytes > rendered.total_bytes
    assert rendered.wire_bytes >= encoded_part_bytes(len(payload))


# ---------------------------------------------------------------------------
# Media too large for any single email
# ---------------------------------------------------------------------------

def test_oversized_media_is_omitted_with_a_visible_placeholder():
    payload = b"\x89PNG\r\n\x1a\n" + b"z" * 300_000
    extractor = _FakeExtractor({"huge.jpg": (payload, "image/jpeg")})
    messages = [_msg("Alice", "huge.jpg (file attached)", attachment_filename="huge.jpg")]

    rendered = render_chunk(messages, "Alice", extractor, max_media_bytes=100_000)

    # The bytes did not travel...
    assert rendered.inline_parts == []
    assert rendered.attachments == []
    # ...but the message did, and the reader is told what is missing.
    assert "huge.jpg" in rendered.html_body
    assert "too large to email" in rendered.html_body
    # ...and so is the user, via the sync report.
    assert len(rendered.omissions) == 1
    omission = rendered.omissions[0]
    assert omission.filename == "huge.jpg"
    assert omission.size_bytes == len(payload)
    assert omission.limit_bytes == 100_000


def test_media_within_the_cap_is_untouched():
    payload = b"\x89PNG\r\n\x1a\n" + b"z" * 1_000
    extractor = _FakeExtractor({"ok.jpg": (payload, "image/jpeg")})
    messages = [_msg("Alice", "ok.jpg (file attached)", attachment_filename="ok.jpg")]

    rendered = render_chunk(messages, "Alice", extractor, max_media_bytes=100_000)

    assert len(rendered.inline_parts) == 1
    assert rendered.omissions == []


def test_no_cap_means_no_omission_however_large_the_file():
    # The default path must stay exactly as it was: a cap is something the
    # pusher opts into once it knows the provider's limit.
    payload = b"\x89PNG\r\n\x1a\n" + b"z" * 500_000
    extractor = _FakeExtractor({"huge.jpg": (payload, "image/jpeg")})
    messages = [_msg("Alice", "huge.jpg (file attached)", attachment_filename="huge.jpg")]

    rendered = render_chunk(messages, "Alice", extractor)

    assert len(rendered.inline_parts) == 1
    assert rendered.omissions == []
