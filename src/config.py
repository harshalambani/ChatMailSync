"""
Constants, defaults, and configurable parameters for WA Chat Sync.
"""

import os
from pathlib import Path

# ---------------------------------------------------------------------------
# Project root and directory layout
# ---------------------------------------------------------------------------

# When running as a PyInstaller bundle, __file__ points inside the frozen
# binary and relative paths break.  The PortableApps launcher sets
# WAGMAIL_ROOT to the App/WAGmailSync/ directory before starting the exe.
_env_root = os.environ.get("WAGMAIL_ROOT")
PROJECT_ROOT = Path(_env_root) if _env_root else Path(__file__).parent.parent

AUTH_DIR = PROJECT_ROOT / "auth"
DATA_DIR = PROJECT_ROOT / "data"
INBOX_DIR = DATA_DIR / "inbox"
PROCESSED_DIR = DATA_DIR / "processed"
STATE_DB_PATH = DATA_DIR / "sync_state.db"

CREDENTIALS_FILE = AUTH_DIR / "credentials.json"
TOKEN_FILE = AUTH_DIR / "token.json"

# ---------------------------------------------------------------------------
# Gmail OAuth2 scopes
# ---------------------------------------------------------------------------

GMAIL_SCOPES = [
    "https://www.googleapis.com/auth/gmail.insert",
    "https://www.googleapis.com/auth/gmail.labels",
]

# ---------------------------------------------------------------------------
# Gmail label hierarchy
# ---------------------------------------------------------------------------

LABEL_PARENT = "WhatsApp"
LABEL_MAX_LENGTH = 225  # Gmail's hard limit for label name length

# ---------------------------------------------------------------------------
# Message chunking defaults
# ---------------------------------------------------------------------------

# Accepted values: "day", "hour", "week", or a positive integer (messages per email)
DEFAULT_CHUNK_SIZE = "day"

# ---------------------------------------------------------------------------
# Date / timestamp parsing
# ---------------------------------------------------------------------------

# Default date order when ambiguous (both day and month ≤ 12 and no heuristic resolves it)
# "DMY" = DD/MM/YY  |  "MDY" = MM/DD/YY
DATE_ORDER = "DMY"

# Number of leading file lines to inspect when detecting the timestamp format
FORMAT_DETECTION_LINES = 20

# Number of messages to scan when resolving DD/MM vs MM/DD ambiguity
DATE_ORDER_SCAN_MESSAGES = 50

# Ranked list of (format_key, regex_pattern) pairs tried during format detection.
# Patterns are tried in order; the first match locks the format for the whole file.
# Each pattern must capture exactly two groups: (date_part, time_part).
TIMESTAMP_PATTERNS = [
    # Format 6: bracketed, US-style with AM/PM — [3/4/25, 2:05:33 PM]
    ("bracketed_ampm_seconds", r"\[(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}:\d{2}\s[APap][Mm])\]"),
    # Format 1/2: bracketed, 24-hour with seconds — [14/03/25, 09:41:23] or [14/03/2025, ...]
    ("bracketed_24h_seconds",  r"\[(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}:\d{2})\]"),
    # Format 3/4: no brackets, AM/PM, no seconds — 3/14/25, 9:41 AM
    ("plain_ampm",             r"(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2}\s[APap][Mm])"),
    # Format 7: no brackets, 24-hour, no seconds — 23/05/26, 16:42  (iPhone export, common in India/EU)
    ("plain_24h",              r"(\d{1,2}/\d{1,2}/\d{2,4}),\s(\d{1,2}:\d{2})"),
    # Format 5: dash-separated date, 24-hour no seconds — 14-03-2025 09:41
    ("dash_24h",               r"(\d{1,2}-\d{1,2}-\d{4})\s(\d{1,2}:\d{2})"),
]

# ---------------------------------------------------------------------------
# System message filtering
#
# Two separate lists are used to avoid false positives:
#
#   SYSTEM_BODY_PHRASES  — checked against the *body* portion of lines that
#       have a "Sender: body" structure.  These phrases only appear in body
#       position, never as ordinary chat content.  Safe to substring-match.
#
#   SYSTEM_BARE_PHRASES  — checked against the full content of lines that
#       have NO "Sender: body" colon (bare system notifications).  The parser
#       actually skips all no-colon lines automatically, so this list is only
#       needed if you want to explicitly document / extend bare phrases.
#
# Users can append entries to either list.
# ---------------------------------------------------------------------------

# Phrases that appear as the body in "Sender: <body>" lines.
SYSTEM_BODY_PHRASES = [
    # Media placeholders — WhatsApp exports the specific media type.
    # Two forms exist: "image omitted" (plain) and "<Media omitted>" (angle-bracket).
    # The U+200E character sometimes prepended by WhatsApp is stripped by
    # _clean_text() before this check runs.
    "media omitted",
    "<media omitted>",
    "image omitted",
    "video omitted",
    "audio omitted",
    "sticker omitted",
    "document omitted",
    "gif omitted",
    # Deleted messages
    "this message was deleted",
    "you deleted this message",
    # Calls (can appear in either bare or body form depending on WA version)
    "missed voice call",
    "missed video call",
]

# Phrases that appear as bare system lines (no "Sender: " prefix).
# The parser already skips all colon-less lines, but this list is kept for
# documentation and future use (e.g. a --verbose dry-run that names the reason
# a line was dropped).
SYSTEM_BARE_PHRASES = [
    "messages and calls are end-to-end encrypted",
    "joined using this group's invite link",
    " left",
    " was added",
    "changed the subject to",
    "changed the group description",
    "changed this group's icon",
    "security code changed",
]

# Combined list kept for backwards compatibility and user extension.
SYSTEM_MESSAGE_PHRASES = SYSTEM_BODY_PHRASES + SYSTEM_BARE_PHRASES

# ---------------------------------------------------------------------------
# Attachment recognition (Phase 2.5)
#
# Raw regex strings matched against the body of a parsed message line.
# Group 1 must capture the bare filename (no surrounding markers).
# These are compiled once at import time in parser.py.
# ---------------------------------------------------------------------------

ATTACHMENT_PATTERNS = [
    # Android: "IMG-20250314-WA0001.jpg (file attached)"
    r"^(.+?)\s+\(file attached\)$",
    # iOS:     "<attached: 00000123-PHOTO-2025-03-14-09-41-23.jpg>"
    r"^<attached:\s*(.+?)>\s*$",
]

# Maximum total MIME payload per email (bytes).
# Gmail hard-limits at 25 MB; we stay at 20 MB to leave headroom for
# MIME headers and base64 encoding overhead (~33% expansion).
MAX_EMAIL_SIZE_BYTES = 20 * 1_048_576  # 20 MiB

# Maximum total decompressed bytes allowed from a single ZIP archive.
# Guards against zip-bomb attacks where a small .zip expands to enormous content.
MAX_ZIP_DECOMPRESSED_BYTES = 500 * 1_048_576  # 500 MiB

# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------

# Socket timeout for Gmail API calls (seconds).
# Large HTML emails with embedded images can take many seconds to upload;
# the httplib2 default (~60 s) is too tight for those cases.
GMAIL_SOCKET_TIMEOUT = 180  # 3 minutes

# Pause between consecutive Gmail API insert calls (seconds)
API_CALL_DELAY_SECONDS = 0.1

# Exponential backoff: base delay and maximum attempts on 429 / 5xx
BACKOFF_BASE_DELAY = 1.0   # seconds
BACKOFF_MAX_ATTEMPTS = 5

# Gmail quota: 250 quota units/second; insert costs 25 units → max ~10 inserts/second
# The default 100 ms delay stays comfortably under that limit.
