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
# WAMAILSYNC_ROOT to the App/WAMailSync/ directory before starting the exe.
#
# An Android/Chaquopy caller has no env var to set and no __file__-relative
# layout to fall back to (app-private storage lives under
# Context.getFilesDir()), so it calls set_root() explicitly instead.
_explicit_root: Path | None = None


def _compute_root() -> Path:
    if _explicit_root is not None:
        return _explicit_root
    # WAMAILSYNC_ROOT is the only accepted name. A WAGMAIL_ROOT fallback lived
    # here until 2026-08-08, for portable installs built before the
    # WAGmailSync -> WAMailSync rename whose launcher still exported the old
    # variable. Dropped deliberately: a PortableApps upgrade replaces the
    # launcher .ini along with App\, so the old name only survived a hand-copy
    # of App\ over a pre-1.0.0 install, and those were prereleases.
    env_root = os.environ.get("WAMAILSYNC_ROOT")
    return Path(env_root) if env_root else Path(__file__).parent.parent


def _apply_root(root: Path) -> None:
    """Rewrite every root-derived path constant on this module in place.

    Needed because `from src.config import DATA_DIR` binds a value at import
    time; reassigning module attributes after the fact is only visible to
    callers that read them via attribute access (`config.DATA_DIR`) on or
    after that point, not to names already bound by an earlier `from`-import.
    set_root() must therefore run before any other src.* module imports
    config's derived constants.
    """
    g = globals()
    g["PROJECT_ROOT"] = root
    g["AUTH_DIR"] = root / "auth"
    g["DATA_DIR"] = root / "data"
    g["INBOX_DIR"] = g["DATA_DIR"] / "inbox"
    g["PROCESSED_DIR"] = g["DATA_DIR"] / "processed"
    g["STATE_DB_PATH"] = g["DATA_DIR"] / "sync_state.db"
    g["CREDENTIALS_FILE"] = g["AUTH_DIR"] / "credentials.json"
    g["TOKEN_FILE"] = g["AUTH_DIR"] / "token.json"
    # IMAP backend (Road B, phase 1): the confirmed storage decision is an
    # ACL-locked file (Windows NTFS ACL hardening on top of a scheme that
    # also has to work on Android, which has neither NTFS ACLs nor DPAPI —
    # so the *file layout/format* is what's shared cross-platform, and
    # per-OS hardening is layered on separately). Phase 1 writes nothing
    # here; this constant only reserves the path (routed through
    # _apply_root like CREDENTIALS_FILE/TOKEN_FILE above) as the seam
    # Phase 2 will use.
    g["IMAP_CREDENTIALS_FILE"] = g["AUTH_DIR"] / "imap_credentials.json"


def set_root(path: str | Path) -> None:
    """Explicitly set the storage root (for embedding contexts like Android).

    Must be called before any other src.* module imports config's derived
    path constants (AUTH_DIR, DATA_DIR, INBOX_DIR, ...) — see _apply_root()'s
    docstring. Windows never calls this; it keeps using the WAMAILSYNC_ROOT
    env var / __file__-relative fallback below.
    """
    global _explicit_root
    _explicit_root = Path(path)
    _apply_root(_compute_root())


_apply_root(_compute_root())

# ---------------------------------------------------------------------------
# Gmail OAuth2 scopes
# ---------------------------------------------------------------------------

GMAIL_SCOPES = [
    "https://www.googleapis.com/auth/gmail.insert",
    "https://www.googleapis.com/auth/gmail.labels",
]

# ---------------------------------------------------------------------------
# Mail backend selection
#
# Both backends are fully supported and user-selectable in Settings. IMAP is
# the default for NEW installs: the OAuth client stays in Google's "Testing"
# publishing status (verification stalled on an annual paid CASA assessment
# that isn't worth it for a personal tool), and Testing status caps the app at
# 100 explicitly-listed test users AND expires every consent 7 days after it
# is granted. IMAP + an app password has neither limit.
#
# This is a default, not a deprecation -- gmail_oauth remains selectable and
# its code path is unchanged. See resolve_mail_backend() for why flipping the
# default does not disturb anyone already signed in with Google.
# ---------------------------------------------------------------------------

MAIL_BACKEND_GMAIL_OAUTH = "gmail_oauth"
MAIL_BACKEND_IMAP = "imap"
DEFAULT_MAIL_BACKEND = MAIL_BACKEND_IMAP


def resolve_mail_backend(saved: dict) -> str:
    """Pick the backend for a settings dict that may predate the setting.

    Shared by gui.py and gui_worker.py precisely so the two cannot drift into
    disagreeing about which backend a given settings file means -- they read
    the same file and must reach the same answer or a sync would run against
    a different transport than the UI is showing.

    An explicit saved value always wins. The interesting case is its absence,
    which means a settings file written before the setting existed. Those
    users were, by definition, on OAuth and may have a working token; falling
    through to DEFAULT_MAIL_BACKEND would silently move them to IMAP on
    upgrade, ignore that token, and demand an app password they have never
    created. An existing token.json is the evidence that this is such a user,
    so it pins them to gmail_oauth and only genuinely new installs get the
    new default.

    TOKEN_FILE is read from the module globals at call time rather than
    captured at import, because _apply_root() rebinds it when WAMAILSYNC_ROOT
    moves the storage root out from under us.
    """
    backend = saved.get("mail_backend")
    if backend:
        return backend
    if globals()["TOKEN_FILE"].exists():
        return MAIL_BACKEND_GMAIL_OAUTH
    return DEFAULT_MAIL_BACKEND

# ---------------------------------------------------------------------------
# IMAP provider presets (Road B, phase 1)
#
# Host/port presets for the "pick your provider" step of IMAP setup (Phase 2
# UI). All use implicit TLS on port 993. "custom" is the escape hatch for any
# other IMAP host/port the user needs to type in by hand; host is None there
# deliberately, so a caller can't accidentally use it without supplying one.
# ---------------------------------------------------------------------------

IMAP_PROVIDERS = {
    "gmail":    {"label": "Gmail",          "host": "imap.gmail.com",        "port": 993},
    "outlook":  {"label": "Outlook / 365",  "host": "outlook.office365.com", "port": 993},
    "yahoo":    {"label": "Yahoo",          "host": "imap.mail.yahoo.com",   "port": 993},
    "icloud":   {"label": "iCloud",         "host": "imap.mail.me.com",      "port": 993},
    "fastmail": {"label": "Fastmail",       "host": "imap.fastmail.com",     "port": 993},
    "custom":   {"label": "Custom",         "host": None,                    "port": 993},
}

def is_gmail_mailbox(saved: dict) -> bool:
    """Whether the destination mailbox is Gmail, however we authenticate to it.

    Wider than `resolve_mail_backend(...) == MAIL_BACKEND_GMAIL_OAUTH`: IMAP is
    the default backend now, and most IMAP users here point it at
    imap.gmail.com with an app password. Both land on the same mailbox with
    the same label semantics, so anything that depends on Gmail's behaviour
    rather than on our auth method has to ask this question instead.
    """
    if resolve_mail_backend(saved) == MAIL_BACKEND_GMAIL_OAUTH:
        return True
    host = (saved.get("imap_host") or "").lower()
    return "gmail" in host or "googlemail" in host


def mailbox_clear_steps(folder: str, gmail: bool) -> list[str]:
    """The steps a user must actually follow to empty `folder` before a reset.

    Gmail needs different instructions, and getting this wrong is worse than
    saying nothing: Gmail has no folders, only labels, so "delete the folder"
    unlabels every message and leaves it sitting in All Mail. Someone follows
    that, truthfully answers "yes, I deleted it", and the next sync duplicates
    the lot. Moving the messages themselves to the Bin is the only action that
    strips every label and removes them from All Mail.

    Shared by gui.py and cli.py so the two front-ends cannot give contradictory
    instructions for the same destructive action; the Android copy of this
    wording lives in ChatDetailScreen.kt and must be kept in step (see
    PLATFORM-PARITY.md).
    """
    if gmail:
        return [
            f"In Gmail, open the label '{folder}' and select every conversation.",
            "Delete them. Deleting the label itself is not enough - the mail "
            "stays in All Mail.",
            "Empty the Bin.",
        ]
    return [
        f"In your mail client, delete the folder '{folder}' (or all mail inside it).",
        "Empty the trash, if your provider keeps one.",
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

# ---------------------------------------------------------------------------
# Message size limits
# ---------------------------------------------------------------------------
#
# Every number below is a WIRE size: the length of the finished RFC 2822
# message as the provider receives it, base64 and all. That distinction is the
# whole point of this block.
#
# It replaces MAX_EMAIL_SIZE_BYTES = 20 MiB, which was compared against the sum
# of *raw* media bytes while carrying the comment "we stay at 20 MB to leave
# headroom for ... base64 encoding overhead (~33% expansion)". The arithmetic
# ran the wrong way: base64 is a multiplier applied to the payload AFTER the
# check, not an allowance you subtract before it. 20 MiB of raw media leaves as
# ~28.7 MB on the wire, so any chunk between roughly 17.4 MiB and 20 MiB passed
# the budget and was then rejected by Gmail with
# "[TOOBIG] Message too large" - observed live on 2026-08-10, where one
# oversized day took a whole 1,505-message chat down with it.
#
# So: measure encoded bytes (html_renderer.encoded_part_bytes), compare against
# these, and the constant means what it says.

# Fallback ceiling for a server that neither advertises RFC 7889 APPENDLIMIT
# nor appears in the table below. Deliberately the tightest common limit rather
# than an average - guessing high produces the exact failure described above,
# guessing low only costs an extra email.
DEFAULT_MAX_MESSAGE_BYTES = 25_000_000

# Per-provider wire limits, keyed to match IMAP_PROVIDERS above. Only consulted
# when the server does not advertise APPENDLIMIT (Gmail, for one, does not).
# These are the providers' documented attachment/message ceilings; where a
# provider states a limit for "attachments" the true message limit is usually a
# little higher, which is headroom in our favour, not against us.
PROVIDER_MAX_MESSAGE_BYTES = {
    "gmail":    25_000_000,
    "outlook":  25_000_000,
    "yahoo":    25_000_000,
    "icloud":   20_000_000,
    "fastmail": 70_000_000,
}

# Fraction of the provider limit we will actually fill. The remainder absorbs
# what the projection does not model exactly: MIME boundary strings, per-part
# headers, the Subject/From/References block, and the traceability index. 0.90
# is generous, and generosity here is cheap - the cost of being 5% under is one
# more email, the cost of being 1% over is a hard failure that blocks the chat.
MESSAGE_SIZE_SAFETY_FACTOR = 0.90

# Kept as an alias so nothing that imported the old name breaks silently at
# import time. It is NOT the same quantity - do not use it for new code.
MAX_EMAIL_SIZE_BYTES = DEFAULT_MAX_MESSAGE_BYTES

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
