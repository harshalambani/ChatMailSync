import zipfile
from pathlib import Path

import pytest

from src.parser import (
    _build_line_re,
    _detect_format,
    _is_system_body,
    _resolve_date_order,
    extract_chat_info,
    parse_file,
)

FIXTURES_DIR = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# Format auto-detection — one case per TIMESTAMP_PATTERNS entry
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "line, expected_format, expected_date, expected_time",
    [
        ("[3/4/25, 2:05:33 PM] - Alice: hi", "bracketed_ampm_seconds", "3/4/25", "2:05:33 PM"),
        ("[14/03/25, 09:41:23] - Alice: hi", "bracketed_24h_seconds", "14/03/25", "09:41:23"),
        ("3/14/25, 9:41 AM - Alice: hi", "plain_ampm", "3/14/25", "9:41 AM"),
        ("23/05/26, 16:42 - Alice: hi", "plain_24h", "23/05/26", "16:42"),
        ("14-03-2025 09:41 - Alice: hi", "dash_24h", "14-03-2025", "09:41"),
    ],
)
def test_format_detection(line, expected_format, expected_date, expected_time):
    detected = _detect_format([line])
    assert detected is not None
    format_key, line_re = detected
    assert format_key == expected_format
    m = line_re.match(line)
    assert m is not None
    assert m.group(1) == expected_date
    assert m.group(2) == expected_time


def test_format_detection_no_match_returns_none():
    assert _detect_format(["not a timestamp line at all"]) is None


# ---------------------------------------------------------------------------
# DD/MM vs MM/DD ambiguity resolution
# ---------------------------------------------------------------------------

def test_date_order_definitive_dmy_when_first_field_over_12():
    line_re = _build_line_re(r"(\d{1,2}/\d{1,2}/\d{2,4})\s(\d{1,2}:\d{2})")
    lines = ["14/03/25 09:41 - Alice: hi"]
    assert _resolve_date_order(lines, line_re, "plain_24h") == "DMY"


def test_date_order_definitive_mdy_when_second_field_over_12():
    line_re = _build_line_re(r"(\d{1,2}/\d{1,2}/\d{2,4})\s(\d{1,2}:\d{2})")
    lines = ["3/14/25 09:41 - Alice: hi"]
    assert _resolve_date_order(lines, line_re, "plain_24h") == "MDY"


def test_date_order_falls_back_to_configured_default_when_fully_ambiguous():
    line_re = _build_line_re(r"(\d{1,2}/\d{1,2}/\d{2,4})\s(\d{1,2}:\d{2})")
    # All day/month values <= 12 - no definitive or strong heuristic signal.
    lines = ["01/02/25 10:00 - Alice: hi", "02/03/25 11:00 - Bob: hi"]
    from src.config import DATE_ORDER
    assert _resolve_date_order(lines, line_re, "plain_24h") == DATE_ORDER


# ---------------------------------------------------------------------------
# Multi-line continuation
# ---------------------------------------------------------------------------

def test_multiline_continuation_joins_into_one_message(tmp_path):
    filepath = FIXTURES_DIR / "android_export.txt"
    messages = list(parse_file(filepath, chat_id="test_chat"))
    bob_msg = next(m for m in messages if m.sender == "Bob" and "how are you" in m.body)
    assert bob_msg.body == "Hi Alice, how are you?\nThis is a continuation line\nand another one"


# ---------------------------------------------------------------------------
# System-message filtering
# ---------------------------------------------------------------------------

def test_system_body_phrases_are_dropped():
    filepath = FIXTURES_DIR / "android_export.txt"
    messages = list(parse_file(filepath, chat_id="test_chat"))
    bodies = [m.body for m in messages]
    assert not any("media omitted" in b.lower() for b in bodies)
    assert not any("this message was deleted" in b.lower() for b in bodies)


def test_bare_system_line_with_no_colon_is_dropped():
    filepath = FIXTURES_DIR / "android_export.txt"
    messages = list(parse_file(filepath, chat_id="test_chat"))
    assert not any("end-to-end encrypted" in m.body.lower() for m in messages)


def test_body_position_left_is_not_falsely_dropped():
    """' left' is a SYSTEM_BARE_PHRASES entry (bare 'X left' group-exit lines),
    but must NOT cause false positives when 'left' appears naturally inside a
    real message body — _is_system_body only checks SYSTEM_BODY_PHRASES."""
    filepath = FIXTURES_DIR / "android_export.txt"
    messages = list(parse_file(filepath, chat_id="test_chat"))
    assert any("I left my charger" in m.body for m in messages)
    assert not _is_system_body("I left my charger at the office, can you grab it?")


# ---------------------------------------------------------------------------
# Attachment recognition
# ---------------------------------------------------------------------------

def test_android_style_attachment_recognized():
    filepath = FIXTURES_DIR / "android_export.txt"
    messages = list(parse_file(filepath, chat_id="test_chat"))
    att = next(m for m in messages if m.attachment_filename is not None)
    assert att.attachment_filename == "IMG-20250314-WA0001.jpg"


def test_ios_style_attachment_recognized():
    filepath = FIXTURES_DIR / "ios_export.txt"
    messages = list(parse_file(filepath, chat_id="test_chat"))
    att = next(m for m in messages if m.attachment_filename is not None)
    assert att.attachment_filename == "00000123-PHOTO-2025-03-14-09-41-23.jpg"


# ---------------------------------------------------------------------------
# extract_chat_info
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "filename, expected_chat_id, expected_display_name",
    [
        ("WhatsApp Chat with Jane Doe.txt", "jane_doe", "Jane Doe"),
        ("Jane Doe.txt", "jane_doe", "Jane Doe"),
    ],
)
def test_extract_chat_info(filename, expected_chat_id, expected_display_name):
    chat_id, display_name = extract_chat_info(filename)
    assert chat_id == expected_chat_id
    assert display_name == expected_display_name


def test_extract_chat_info_normalizes_punctuation_to_ascii():
    chat_id, display_name = extract_chat_info("WhatsApp Chat with Jane's Café 😀.txt")
    assert chat_id.isascii()
    assert " " not in chat_id
    assert display_name == "Jane's Café 😀"


# ---------------------------------------------------------------------------
# ZIP input (iOS export mode)
# ---------------------------------------------------------------------------

def test_parse_file_reads_chat_txt_from_zip(tmp_path):
    zip_path = tmp_path / "export.zip"
    with zipfile.ZipFile(zip_path, "w") as zf:
        zf.writestr("_chat.txt", "3/14/25, 9:41 AM - Alice: Hey from a zip!")

    messages = list(parse_file(zip_path, chat_id="test_chat"))
    assert len(messages) == 1
    assert messages[0].body == "Hey from a zip!"


def test_parse_file_zip_bomb_guard_raises(tmp_path, monkeypatch):
    zip_path = tmp_path / "export.zip"
    with zipfile.ZipFile(zip_path, "w") as zf:
        zf.writestr("_chat.txt", "irrelevant")

    import src.parser as parser_module
    monkeypatch.setattr(parser_module, "MAX_ZIP_DECOMPRESSED_BYTES", 1)

    with pytest.raises(ValueError, match="safety limit"):
        list(parse_file(zip_path, chat_id="test_chat"))
