from datetime import datetime

from src.html_renderer import render_chunk
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
