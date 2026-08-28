"""
WhatsApp .txt file parsing engine.

Parses exported chat files into ParsedMessage instances with automatic
timestamp format detection and DD/MM vs MM/DD ambiguity resolution.

Timezone note: WhatsApp timestamps carry no timezone indicator. All datetimes
here are naive local times. No UTC conversion is attempted (see arch doc §2).
"""

import logging
import re
import zipfile
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterator, Optional

from dateutil import parser as dateutil_parser

from src.config import (
    ATTACHMENT_PATTERNS,
    DATE_ORDER,
    DATE_ORDER_SCAN_MESSAGES,
    FORMAT_DETECTION_LINES,
    MAX_ZIP_DECOMPRESSED_BYTES,
    SYSTEM_BODY_PHRASES,
    TIMESTAMP_PATTERNS,
)

# Compiled once at import time — used in _build_message to detect attachment lines.
_ATTACHMENT_RES = [re.compile(p, re.IGNORECASE) for p in ATTACHMENT_PATTERNS]

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass
class ParsedMessage:
    chat_id: str
    timestamp: datetime        # naive local time
    sender: str
    body: str                  # raw body text including any attachment marker
    attachment_filename: Optional[str] = None  # set when body is an attachment line
    timestamp_iso: str = field(init=False)

    def __post_init__(self) -> None:
        self.timestamp_iso = self.timestamp.isoformat(timespec="seconds")


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def parse_file(
    filepath: Path,
    chat_id: str,
    date_order: Optional[str] = None,
) -> Iterator[ParsedMessage]:
    """Yield ParsedMessage objects from a WhatsApp .txt export file.

    Auto-detects the timestamp format from the first lines of the file,
    then locks that pattern for all subsequent lines (fast path).

    Args:
        filepath:   Path to the .txt export file.
        chat_id:    Normalized chat identifier (from extract_chat_info).
        date_order: "DMY" or "MDY" to override auto-detection.
    """
    text = _read_chat_text(filepath)
    lines = text.splitlines()

    # --- Format detection ---
    detection_lines = [l for l in lines if l.strip()][:FORMAT_DETECTION_LINES]
    detected = _detect_format(detection_lines)
    if detected is None:
        log.warning("No timestamp pattern matched in %s — skipping file", filepath.name)
        return
    format_key, line_re = detected
    log.debug("Format '%s' locked for %s", format_key, filepath.name)

    # --- Date order resolution ---
    effective_order = date_order or _resolve_date_order(lines, line_re, format_key)
    log.debug("Date order for %s: %s", filepath.name, effective_order)

    # --- Line-by-line parsing ---
    # current accumulates fields until the next timestamp line triggers a yield.
    current: Optional[dict] = None

    for raw_line in lines:
        line = _clean_text(raw_line)
        m = line_re.match(line)

        if m:
            # New timestamp line — emit whatever was accumulated, then start fresh.
            if current is not None:
                msg = _build_message(current, chat_id, format_key, effective_order)
                if msg is not None:
                    yield msg
                current = None

            date_str = m.group(1)
            time_str = m.group(2)
            content = _clean_text(m.group(3))

            # Bare system messages have no "Sender: body" structure.
            if ": " not in content:
                continue

            sender, _, body = content.partition(": ")

            # Filter body-level system messages (e.g. "‎image omitted",
            # "This message was deleted").
            # Note: SYSTEM_MESSAGE_PHRASES are checked against the body only,
            # not the full content, to minimise false positives.
            if _is_system_body(body):
                continue

            current = {
                "date_str": date_str,
                "time_str": time_str,
                "sender": sender.strip(),
                "body_lines": [body],
            }

        else:
            # Continuation line — append to the previous message body,
            # preserving original whitespace/blank lines.
            if current is not None:
                current["body_lines"].append(raw_line)

    # Emit the final accumulated message.
    if current is not None:
        msg = _build_message(current, chat_id, format_key, effective_order)
        if msg is not None:
            yield msg


def extract_chat_info(filename: str) -> tuple[str, str]:
    """Parse a WhatsApp export filename into (chat_id, display_name).

    Handles Android ('WhatsApp Chat with Name.txt') and iOS ('Name.txt').
    """
    stem = Path(filename).stem

    prefix = "whatsapp chat with "
    display_name = stem[len(prefix):] if stem.lower().startswith(prefix) else stem

    # A chat's identity is derived from this name, so a copy that Downloads
    # renamed -- "WhatsApp Chat with Bijal (1).txt", which is what you get when
    # the same export arrives a second time -- used to become a second chat
    # with its own thread, and every message mailed again on top of the first
    # copy. The same export reaching the watched folder and the Share sheet is
    # exactly how that happens. Nobody names a chat "Bijal (1)".
    stripped = re.sub(r"\s*\(\d+\)$", "", display_name).strip()
    if stripped:
        display_name = stripped

    # Normalize: ASCII alphanumeric only, spaces collapsed to underscores.
    chat_id = re.sub(r"[^a-z0-9\s]", "", display_name.lower())
    chat_id = re.sub(r"\s+", "_", chat_id.strip()).strip("_")
    chat_id = chat_id or "unknown_chat"

    return chat_id, display_name


# ---------------------------------------------------------------------------
# File reading (plain text or ZIP)
# ---------------------------------------------------------------------------


def _read_chat_text(filepath: Path) -> str:
    """Return the chat text from a file, handling both plain .txt and ZIP archives.

    WhatsApp on iPhone exports a ZIP containing a '_chat.txt' (or a file named
    after the contact). The ZIP is detected by magic bytes, not file extension.
    """
    if zipfile.is_zipfile(filepath):
        with zipfile.ZipFile(filepath, "r") as zf:
            # Zip-bomb guard: check total uncompressed size before reading anything.
            total_uncompressed = sum(zi.file_size for zi in zf.infolist())
            if total_uncompressed > MAX_ZIP_DECOMPRESSED_BYTES:
                raise ValueError(
                    f"ZIP archive {filepath.name!r} would decompress to "
                    f"{total_uncompressed:,} bytes, which exceeds the "
                    f"{MAX_ZIP_DECOMPRESSED_BYTES:,}-byte safety limit."
                )
            # Prefer '_chat.txt'; fall back to the first .txt entry found.
            txt_names = [n for n in zf.namelist() if n.lower().endswith(".txt")]
            if not txt_names:
                raise ValueError(
                    f"ZIP archive {filepath.name!r} contains no .txt file."
                )
            target = next((n for n in txt_names if "_chat" in n.lower()), txt_names[0])
            raw = zf.read(target)
            # Decode: try utf-8-sig first (handles BOM), fall back to latin-1.
            for enc in ("utf-8-sig", "utf-8", "latin-1"):
                try:
                    return raw.decode(enc)
                except UnicodeDecodeError:
                    continue
            return raw.decode("latin-1")  # last-resort, never raises
    else:
        return filepath.read_text(encoding="utf-8-sig", errors="replace")


# ---------------------------------------------------------------------------
# Format detection
# ---------------------------------------------------------------------------


def _build_line_re(ts_pattern: str) -> re.Pattern:
    """Wrap a raw timestamp pattern into a full message-line regex.

    The resulting pattern captures three groups:
      (1) date_part   (2) time_part   (3) remainder (sender: body or system text)
    """
    return re.compile(r"^" + ts_pattern + r" - (.+)$")


def _detect_format(lines: list[str]) -> Optional[tuple[str, re.Pattern]]:
    """Return (format_key, line_regex) for the first pattern that matches any line.

    Patterns are tried in the priority order defined in config.TIMESTAMP_PATTERNS.
    """
    for format_key, ts_pattern in TIMESTAMP_PATTERNS:
        line_re = _build_line_re(ts_pattern)
        for line in lines:
            if line_re.match(_clean_text(line)):
                return format_key, line_re
    return None


# ---------------------------------------------------------------------------
# Date order resolution
# ---------------------------------------------------------------------------


def _resolve_date_order(lines: list[str], line_re: re.Pattern, format_key: str) -> str:
    """Determine whether date fields are DD/MM (DMY) or MM/DD (MDY).

    Three-step approach (architecture doc §2):
      1. Definitive: any first-field value > 12  → DMY
                     any second-field value > 12 → MDY
      2. Heuristic: first-field max > second-field max → DMY (weak signal)
      3. Default:   config.DATE_ORDER
    """
    sep = "-" if format_key == "dash_24h" else "/"
    first_vals: list[int] = []
    second_vals: list[int] = []

    for line in lines:
        if len(first_vals) >= DATE_ORDER_SCAN_MESSAGES:
            break
        m = line_re.match(_clean_text(line))
        if not m:
            continue
        parts = m.group(1).split(sep)
        if len(parts) < 2:
            continue
        try:
            first_vals.append(int(parts[0]))
            second_vals.append(int(parts[1]))
        except ValueError:
            continue

    if not first_vals:
        log.warning("No date samples found; defaulting to %s", DATE_ORDER)
        return DATE_ORDER

    # Step 1: definitive
    if any(v > 12 for v in first_vals):
        log.debug("Date order DMY confirmed (first field > 12)")
        return "DMY"
    if any(v > 12 for v in second_vals):
        log.debug("Date order MDY confirmed (second field > 12)")
        return "MDY"

    # Step 2: heuristic — days reach higher values than months
    if max(first_vals) > max(second_vals):
        log.debug(
            "Date order DMY (heuristic: first-max %d > second-max %d)",
            max(first_vals), max(second_vals),
        )
        return "DMY"

    # Step 3: configured default
    log.warning(
        "Date order ambiguous (all sampled values ≤ 12); defaulting to %s. "
        "Override with --date-order if needed.",
        DATE_ORDER,
    )
    return DATE_ORDER


# ---------------------------------------------------------------------------
# Timestamp construction
# ---------------------------------------------------------------------------


def _parse_timestamp(
    date_str: str, time_str: str, format_key: str, date_order: str
) -> datetime:
    """Build a naive datetime from the two captured regex groups.

    Handles 2-digit years (< 50 → 2000s, ≥ 50 → 1900s) and delegates
    time parsing to dateutil to cover both 12h and 24h variants cleanly.
    """
    sep = "-" if format_key == "dash_24h" else "/"
    parts = date_str.split(sep)
    if len(parts) < 3:
        raise ValueError(f"Unexpected date string: {date_str!r}")

    a, b = int(parts[0]), int(parts[1])
    year_raw = int(parts[2])
    year = year_raw if year_raw >= 100 else (2000 + year_raw if year_raw < 50 else 1900 + year_raw)

    day, month = (a, b) if date_order == "DMY" else (b, a)

    # dateutil handles "9:41 AM", "09:41:23", "14:05:33 PM", etc.
    t = dateutil_parser.parse(time_str.strip(), default=datetime(1900, 1, 1))
    return datetime(year, month, day, t.hour, t.minute, t.second)


# ---------------------------------------------------------------------------
# System message filtering
# ---------------------------------------------------------------------------


def _is_system_body(body: str) -> bool:
    """Return True if the body of a 'sender: body' line is a system notification.

    Uses SYSTEM_BODY_PHRASES (not the combined SYSTEM_MESSAGE_PHRASES) so that
    group-event phrases like ' left' and ' was added' — which only ever appear
    on bare system lines (no colon) — cannot cause false positives on real
    messages like 'I left the party early'.
    Bare system messages (no ': ') are already skipped upstream.
    """
    body_lower = _clean_text(body).lower()
    return any(phrase in body_lower for phrase in SYSTEM_BODY_PHRASES)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

# Unicode artifacts injected by WhatsApp into exported text.
_UNICODE_ARTIFACTS = str.maketrans("", "", "‎‏﻿​")


def _clean_text(text: str) -> str:
    return text.translate(_UNICODE_ARTIFACTS)


def _build_message(
    current: dict,
    chat_id: str,
    format_key: str,
    date_order: str,
) -> Optional[ParsedMessage]:
    """Construct a ParsedMessage from an accumulated line dict.

    Returns None and logs a warning if the timestamp cannot be parsed.
    """
    try:
        ts = _parse_timestamp(
            current["date_str"], current["time_str"], format_key, date_order
        )
    except (ValueError, OverflowError) as exc:
        log.warning(
            "Skipping message with unparseable timestamp '%s %s': %s",
            current["date_str"], current["time_str"], exc,
        )
        return None

    body = "\n".join(current["body_lines"])

    # Detect attachment lines (Phase 2.5).
    # The body is kept verbatim for hashing stability; attachment_filename
    # is set as a separate field for the HTML renderer to use.
    attachment_filename: Optional[str] = None
    for att_re in _ATTACHMENT_RES:
        m = att_re.match(body.strip())
        if m:
            attachment_filename = m.group(1).strip()
            break

    return ParsedMessage(
        chat_id=chat_id,
        timestamp=ts,
        sender=current["sender"],
        body=body,
        attachment_filename=attachment_filename,
    )
