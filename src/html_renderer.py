"""
WhatsApp-light HTML email renderer.

Converts a list of ParsedMessage objects into a self-contained HTML email
body that visually mimics the WhatsApp chat interface:

  • Speech bubbles  — incoming left (white), outgoing right (light green).
  • Inline images   — embedded via CID references (RFC 2392).
  • Media cards     — non-image attachments (video/audio/docs) rendered as
                      a download card; file is attached to the MIME message.
  • Placeholders    — shown when a referenced file is not found in the export.

Design constraints:
  • Inline style="…" only — no <style> blocks (Gmail strips them in some
    rendering contexts).
  • No external CSS, no web fonts, no JavaScript.
  • Gmail clips raw HTML bodies > ~102 KB; content is kept compact.
"""

import hashlib
import html as _html
import mimetypes
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, TYPE_CHECKING

from src.parser import ParsedMessage

if TYPE_CHECKING:
    from src.media_extractor import MediaExtractor


# ---------------------------------------------------------------------------
# Public data structures
# ---------------------------------------------------------------------------

@dataclass
class InlinePart:
    """An image embedded inline via cid: reference."""
    cid: str
    data: bytes
    mime_type: str  # e.g. "image/jpeg"


@dataclass
class AttachmentPart:
    """A non-image file attached to the email (download card in body)."""
    filename: str
    data: bytes
    mime_type: str


@dataclass
class MediaOmission:
    """One media file left out because no single email could ever carry it.

    Not an error and not a silent drop: the message itself is archived, the
    body text survives, and the HTML carries a visible placeholder in the
    file's place. This record exists so the omission is also reported back to
    the user rather than only being visible to whoever opens that email.
    """
    filename: str
    size_bytes: int
    limit_bytes: int


@dataclass
class RenderedChunk:
    """Complete render output for one email."""
    html_body: str
    inline_parts: list[InlinePart] = field(default_factory=list)
    attachments: list[AttachmentPart] = field(default_factory=list)
    total_bytes: int = 0   # HTML + all media, RAW — for logs and diagnostics
    wire_bytes: int = 0    # what the provider will actually receive — see below
    omissions: list[MediaOmission] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Wire-size projection
# ---------------------------------------------------------------------------
#
# total_bytes above counts raw payload. Nothing is sent raw: inline images and
# attachments are base64-encoded by _build_html_mime_message(), and so is the
# HTML body, because MIMEText(..., charset="utf-8") uses base64 as its
# transfer encoding. Comparing raw bytes against a provider's limit therefore
# understates the real size by ~37% - see the long note in config.py for the
# failure that produced.

_B64_LINE_LENGTH = 76   # chars per line, per RFC 2045
_B64_LINE_ENDING = 2    # CRLF

# Rough per-part cost of a MIME boundary plus Content-Type/Transfer-Encoding/
# Disposition headers. Approximate on purpose; MESSAGE_SIZE_SAFETY_FACTOR is
# what actually covers modelling error, this just keeps the estimate honest
# for chunks made of many small media files.
_PART_HEADER_BYTES = 220


def encoded_part_bytes(raw_length: int) -> int:
    """Bytes one payload of `raw_length` occupies once base64-encoded.

    4 chars per 3 bytes, rounded up, plus a CRLF every 76 chars, plus the
    part's own headers and boundary.
    """
    if raw_length <= 0:
        return _PART_HEADER_BYTES
    b64_chars = ((raw_length + 2) // 3) * 4
    line_breaks = (b64_chars // _B64_LINE_LENGTH) * _B64_LINE_ENDING
    return b64_chars + line_breaks + _PART_HEADER_BYTES


def max_raw_bytes_for(wire_budget: int) -> int:
    """Largest raw payload that still fits inside `wire_budget` once encoded.

    The inverse of encoded_part_bytes(), used to decide whether a single media
    file can ever be delivered - a question splitting cannot answer, because a
    message with one 40 MB video does not get smaller when you halve the chunk
    around it.
    """
    usable = wire_budget - _PART_HEADER_BYTES
    if usable <= 0:
        return 0
    # Undo the CRLF padding first, then the 4/3 expansion.
    b64_chars = int(usable / (1 + _B64_LINE_ENDING / _B64_LINE_LENGTH))
    return max(0, (b64_chars // 4) * 3)


# ---------------------------------------------------------------------------
# MIME helpers
# ---------------------------------------------------------------------------

# Images that Gmail renders inline via cid: references.
_INLINE_IMAGE_TYPES = frozenset({
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
})

# Emoji icons for the media download card.
_TYPE_ICONS: dict[str, str] = {
    "video":       "🎥",
    "audio":       "🎵",
    "application": "📄",
    "text":        "📝",
}


def _is_inline_image(mime_type: str) -> bool:
    return mime_type in _INLINE_IMAGE_TYPES


def _type_icon(mime_type: str) -> str:
    return _TYPE_ICONS.get(mime_type.split("/")[0], "📎")


def _guess_mime(filename: str) -> str:
    t, _ = mimetypes.guess_type(filename)
    return t or "application/octet-stream"


# ---------------------------------------------------------------------------
# Sender colour palette (group chats — deterministic per sender name)
# ---------------------------------------------------------------------------

_SENDER_COLORS = [
    "#128C7E", "#7B68EE", "#E91E63", "#FF9800",
    "#4CAF50", "#2196F3", "#9C27B0", "#00BCD4",
]


def _sender_color(sender: str) -> str:
    idx = int(hashlib.md5(sender.encode("utf-8")).hexdigest(), 16) % len(_SENDER_COLORS)
    return _SENDER_COLORS[idx]


# ---------------------------------------------------------------------------
# Attachment marker stripping
# ---------------------------------------------------------------------------

_ATTACHMENT_MARKER_RES = [
    re.compile(r"\s*\(file attached\)\s*$", re.IGNORECASE),
    re.compile(r"^<attached:\s*.+?>\s*$",   re.IGNORECASE),
]


def _strip_attachment_markers(text: str) -> str:
    """Remove '(file attached)' / '<attached: …>' suffixes from display text."""
    for pattern in _ATTACHMENT_MARKER_RES:
        text = pattern.sub("", text).strip()
    return text


# ---------------------------------------------------------------------------
# Inline styles — all hardcoded; no <style> blocks
# ---------------------------------------------------------------------------

_PAGE   = "margin:0;padding:0;background:#f0f2f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif"
_WRAP   = "max-width:640px;margin:0 auto;padding:8px 14px 14px 14px"
_SEP_ROW  = "text-align:center;margin:12px 0 14px"
_SEP_PILL = "background:#ffffff;padding:4px 14px;border-radius:8px;font-size:12px;color:#667781;box-shadow:0 1px 1px rgba(0,0,0,.13)"

_TIME = "font-size:11px;color:#667781;text-align:right;margin-top:3px"
_BODY = "font-size:14px;color:#111b21;white-space:pre-wrap;line-height:1.4;word-break:break-word"
_IMG  = "max-width:240px;max-height:240px;border-radius:4px;display:block;margin-bottom:4px"

_PLACEHOLDER = (
    "font-size:12px;color:#667781;font-style:italic;"
    "background:#f0f2f5;padding:4px 8px;border-radius:4px;margin-bottom:4px"
)
_CARD       = "display:flex;align-items:center;gap:8px;background:#f0f2f5;border-radius:6px;padding:8px 10px;margin-bottom:4px"
_CARD_ICON  = "font-size:22px;line-height:1"
_CARD_NAME  = "font-size:12px;font-weight:600;color:#111b21"
_CARD_META  = "font-size:11px;color:#667781;margin-top:1px"


def _row_style(outgoing: bool) -> str:
    align = "flex-end" if outgoing else "flex-start"
    return f"display:flex;justify-content:{align};margin-bottom:2px"


def _bubble_style(outgoing: bool) -> str:
    bg     = "#DCF8C6" if outgoing else "#ffffff"
    radius = "8px 0 8px 8px" if outgoing else "0 8px 8px 8px"
    return (
        f"background:{bg};border-radius:{radius};"
        "padding:7px 10px 5px 10px;max-width:72%;"
        "box-shadow:0 1px 1px rgba(0,0,0,.13)"
    )


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def render_chunk(
    messages: list[ParsedMessage],
    display_name: str,
    extractor: Optional["MediaExtractor"],
    label: str = "",
    max_media_bytes: Optional[int] = None,
) -> RenderedChunk:
    """Render messages into a WhatsApp-light HTML email.

    Args:
        messages:      Messages for this chunk (already time-bounded by caller).
        display_name:  Chat name, shown in the day-separator pill.
        extractor:     Open MediaExtractor for this chat's source file, or None.
        label:         Optional suffix added to the separator (e.g. "Part 2/3").
        max_media_bytes: Largest single media file that can be carried, raw. A
                       file over this is replaced by a visible placeholder and
                       recorded in `omissions`. This is the one size problem
                       splitting cannot solve - halving a chunk around a 40 MB
                       video still leaves the 40 MB video - so it has to be
                       decided here, at render time, per file. None disables
                       the check entirely (the default, for callers that are
                       not building a real email).

    Returns:
        RenderedChunk — caller is responsible for building the MIME message from it.
    """
    if not messages:
        return RenderedChunk(html_body="")

    inline_parts: list[InlinePart]     = []
    attachments:  list[AttachmentPart] = []
    omissions:    list[MediaOmission]  = []
    body_parts:   list[str]            = []

    # Day-separator pill.
    day_str = messages[0].timestamp.strftime("%d %B %Y").lstrip("0")  # "14 March 2025"
    if label:
        day_str += f"  ·  {label}"
    body_parts.append(
        f'<div style="{_SEP_ROW}">'
        f'<span style="{_SEP_PILL}">{_html.escape(day_str)}</span>'
        f'</div>'
    )

    for msg in messages:
        outgoing = msg.sender.strip().lower() == "you"
        body_parts.append(
            _render_bubble(
                msg, outgoing, extractor, inline_parts, attachments,
                max_media_bytes, omissions,
            )
        )

    html_body = (
        '<!DOCTYPE html><html><head>'
        '<meta charset="UTF-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '</head>'
        f'<body style="{_PAGE}">'
        f'<div style="{_WRAP}">'
        + "".join(body_parts)
        + '</div></body></html>'
    )

    html_bytes = len(html_body.encode("utf-8"))

    total_bytes = (
        html_bytes
        + sum(len(p.data) for p in inline_parts)
        + sum(len(a.data) for a in attachments)
    )

    # What the provider will actually be handed. Every one of these parts is
    # base64-encoded on the way out - including the HTML, because MIMEText with
    # a utf-8 charset uses base64 as its transfer encoding - so this runs about
    # 37% above total_bytes. Size decisions must use this one.
    wire_bytes = (
        encoded_part_bytes(html_bytes)
        + sum(encoded_part_bytes(len(p.data)) for p in inline_parts)
        + sum(encoded_part_bytes(len(a.data)) for a in attachments)
    )

    return RenderedChunk(
        html_body=html_body,
        inline_parts=inline_parts,
        attachments=attachments,
        total_bytes=total_bytes,
        wire_bytes=wire_bytes,
        omissions=omissions,
    )


# ---------------------------------------------------------------------------
# Bubble rendering
# ---------------------------------------------------------------------------

def _render_bubble(
    msg: ParsedMessage,
    outgoing: bool,
    extractor: Optional["MediaExtractor"],
    inline_parts: list[InlinePart],
    attachments: list[AttachmentPart],
    max_media_bytes: Optional[int] = None,
    omissions: Optional[list[MediaOmission]] = None,
) -> str:
    time_str = msg.timestamp.strftime("%H:%M")

    # ── Sender name label (incoming only) ──────────────────────────────────
    sender_html = ""
    if not outgoing:
        color = _sender_color(msg.sender)
        sender_html = (
            f'<div style="font-size:12px;font-weight:600;color:{color};margin-bottom:3px">'
            f'{_html.escape(msg.sender)}</div>'
        )

    # ── Media content ──────────────────────────────────────────────────────
    media_html = ""
    if msg.attachment_filename:
        media_html = _render_media(
            msg.attachment_filename, extractor, inline_parts, attachments,
            max_media_bytes, omissions,
        )

    # ── Text body (strip the attachment marker for display) ────────────────
    display_body = _strip_attachment_markers(msg.body) if msg.attachment_filename else msg.body
    body_html = (
        f'<div style="{_BODY}">{_html.escape(display_body)}</div>'
        if display_body.strip() else ""
    )

    return (
        f'<div style="{_row_style(outgoing)}">'
        f'<div style="{_bubble_style(outgoing)}">'
        + sender_html
        + media_html
        + body_html
        + f'<div style="{_TIME}">{time_str}</div>'
        + '</div></div>\n'
    )


def _format_size(num_bytes: int) -> str:
    mb = num_bytes / 1_000_000
    return f"{mb:.1f} MB" if mb >= 1 else f"{num_bytes / 1000:.0f} KB"


def _render_media(
    filename: str,
    extractor: Optional["MediaExtractor"],
    inline_parts: list[InlinePart],
    attachments: list[AttachmentPart],
    max_media_bytes: Optional[int] = None,
    omissions: Optional[list[MediaOmission]] = None,
) -> str:
    """Return the HTML fragment for one media item, and populate the part lists."""

    if extractor is None:
        return (
            f'<div style="{_PLACEHOLDER}">'
            f'📎 {_html.escape(filename)}'
            f'</div>'
        )

    result = extractor.resolve(filename)
    if result is None:
        return (
            f'<div style="{_PLACEHOLDER}">'
            f'[{_html.escape(filename)} — not found in export]'
            f'</div>'
        )

    data, mime_type = result

    # One file too big for any single email. Splitting cannot help here: a
    # ParsedMessage carries at most one attachment, so this file is already
    # alone in the smallest email we could ever build. The message, its text
    # and its place in the thread are all still archived - only the bytes are
    # left behind, and the placeholder plus the MediaOmission record make sure
    # that is visible both in the email and in the sync report.
    if max_media_bytes is not None and len(data) > max_media_bytes:
        if omissions is not None:
            omissions.append(
                MediaOmission(
                    filename=filename,
                    size_bytes=len(data),
                    limit_bytes=max_media_bytes,
                )
            )
        return (
            f'<div style="{_PLACEHOLDER}">'
            f'📎 {_html.escape(filename)} — {_format_size(len(data))}, '
            f'too large to email (limit {_format_size(max_media_bytes)}). '
            f'Not included; it stays in your WhatsApp export.'
            f'</div>'
        )

    if _is_inline_image(mime_type):
        cid = f"img-{uuid.uuid4().hex[:12]}"
        inline_parts.append(InlinePart(cid=cid, data=data, mime_type=mime_type))
        return (
            f'<img src="cid:{cid}" alt="{_html.escape(filename)}" style="{_IMG}">'
        )

    # Non-image: render a download card and attach the file.
    icon    = _type_icon(mime_type)
    size_kb = len(data) / 1024
    size_str = f"{size_kb:.0f} KB" if size_kb < 1024 else f"{size_kb / 1024:.1f} MB"
    attachments.append(AttachmentPart(filename=filename, data=data, mime_type=mime_type))
    return (
        f'<div style="{_CARD}">'
        f'<span style="{_CARD_ICON}">{icon}</span>'
        f'<div>'
        f'<div style="{_CARD_NAME}">{_html.escape(filename)}</div>'
        f'<div style="{_CARD_META}">{size_str} · {_html.escape(mime_type)}</div>'
        f'</div></div>'
    )
