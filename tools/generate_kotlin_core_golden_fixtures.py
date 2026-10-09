"""Golden-fixture generator for the Kotlin :core MIME byte-parity test.

Runs the *real* Python MIME/index builders (`src.mail_client._build_mime_message`,
`src.mail_index.build_index`/`index_bytes`) against a small, fixed, made-up
fixture and writes their exact output bytes to
`android/core/src/test/resources/golden/`, so the Kotlin JUnit test
(`android/core/src/test/kotlin/com/chatmailsync/core/mail/MimeGoldenParityTest.kt`)
can assert byte-for-byte parity against `MimeBuilder`/`MailIndex`.

This script is NOT run by CI and is NOT a test itself -- it is a one-off (or
re-run-on-demand) tool a human/agent runs locally whenever the Python builder
functions change, to refresh the golden fixtures that ship as Kotlin test
resources. Run it from the repo root:

    python tools/generate_kotlin_core_golden_fixtures.py

Only made-up test data is used (see FIXTURE_* below) -- no real names, chats
or credentials.
"""

from __future__ import annotations

import base64
import json
import re
import zipfile
from datetime import datetime, timedelta
from pathlib import Path

from dateutil import parser as dateutil_parser

from src import mail_index, state
from src.html_renderer import render_chunk
from src.mail_client import _build_mime_message
from src.media_extractor import MediaExtractor
from src.parser import ParsedMessage, extract_chat_info, parse_file

REPO_ROOT = Path(__file__).resolve().parent.parent
GOLDEN_DIR = REPO_ROOT / "android" / "core" / "src" / "test" / "resources" / "golden"

# Fixed, made-up fixture -- must match the constants the Kotlin test builds
# (see MimeGoldenParityTest.kt). Never a real name or chat.
FIXTURE_DISPLAY_NAME = "Meera Iyer"
FIXTURE_CHAT_ID = "test_chat"
FIXTURE_MESSAGE_ID = "<golden-fixture-anchor@local>"
FIXTURE_LABEL_ID = "WhatsApp/Meera Iyer"
FIXTURE_CHUNK = [
    ParsedMessage(
        chat_id=FIXTURE_CHAT_ID,
        timestamp=datetime(2019, 5, 3, 10, 15, 0),
        sender="Meera Iyer",
        body="hello from the past",
    ),
    ParsedMessage(
        chat_id=FIXTURE_CHAT_ID,
        timestamp=datetime(2019, 5, 3, 10, 16, 0),
        sender="Kavya Menon",
        body="line one\nline two continuation",
    ),
]

# A boundary token that looks exactly like the ones the email package
# generates ("===============<digits>==") so both sides can be normalized by
# the same regex in the JUnit test, without pinning the RNG.
_BOUNDARY_RE = re.compile(rb"={15}\d+==")


def _normalize_boundary(raw: bytes) -> bytes:
    return _BOUNDARY_RE.sub(b"===============NORMALIZED==", raw)


# ---------------------------------------------------------------------------
# Parser golden fixtures (Phase 2 "parser" port)
#
# Each fixture is a synthetic chat export exercising one TIMESTAMP_PATTERNS
# format, a date-order case, or an edge case called out by the plan document
# (2-digit year pivot, system messages, attachments, AM/PM). All names are
# made up (Meera Iyer, Rohan Mehta, Priya Nair, Kavya Menon) -- never a real
# person. parse_file() locks exactly one format per file, so each scenario is
# its own small fixture rather than one combined file.
# ---------------------------------------------------------------------------

PARSER_FIXTURES: dict[str, str] = {
    "bracketed_ampm_seconds": (
        "[3/4/25, 2:05:33 PM] - Meera Iyer: hello from bracketed ampm\n"
    ),
    "bracketed_24h_seconds": (
        "[14/03/25, 09:41:23] - Rohan Mehta: hello from bracketed 24h\n"
    ),
    "plain_ampm": (
        "3/14/25, 9:41 AM - Meera Iyer: hello from plain ampm\n"
        "3/14/25, 9:41 PM - Rohan Mehta: narrow no-break space before PM\n"
    ),
    "plain_24h": (
        "23/05/26, 16:42 - Priya Nair: hello from plain 24h\n"
    ),
    "dash_24h": (
        "14-03-2025 09:41 - Kavya Menon: hello from dash 24h\n"
    ),
    "date_order_dmy_definitive": (
        "14/03/25, 09:41 - Meera Iyer: day exceeds 12 so this locks DMY\n"
    ),
    "date_order_mdy_definitive": (
        "3/14/25, 09:41 - Meera Iyer: second field exceeds 12 so this locks MDY\n"
    ),
    "two_digit_year_pivot": (
        "01/01/49, 10:00 - Meera Iyer: year 49 becomes 2049\n"
        "01/01/50, 10:00 - Meera Iyer: year 50 becomes 1950\n"
    ),
    "system_messages_and_attachments": (
        "[14/03/25, 09:40:00] - Messages and calls are end-to-end encrypted. "
        "No one outside of this chat, not even WhatsApp, can read or listen to them.\n"
        "[14/03/25, 09:41:23] - Meera Iyer: Hey there!\n"
        "[14/03/25, 09:41:45] - Rohan Mehta: Hi Meera, how are you?\n"
        "This is a continuation line\n"
        "[14/03/25, 09:42:00] - Meera Iyer: IMG-20250314-WA0001.jpg (file attached)\n"
        "[14/03/25, 09:42:10] - Rohan Mehta: <Media omitted>\n"
        "[14/03/25, 09:42:20] - Meera Iyer: This message was deleted\n"
        "[14/03/25, 09:42:30] - Rohan Mehta: I left my charger at the office\n"
    ),
    "ios_attachment": (
        "3/14/25, 9:41 AM - Priya Nair: Hey there!\n"
        "3/14/25, 9:42 AM - Kavya Menon: <attached: 00000123-PHOTO-2025-03-14-09-41-23.jpg>\n"
    ),
    # Item 1 of the two-change follow-up (PR #92 review): an export whose
    # timestamp uses Arabic-Indic digits (U+0660-0669) throughout -- date,
    # month, year, hour and minute -- exercising the plain_24h format with a
    # non-ASCII (but still decimal, Unicode category Nd) digit script. The
    # separators ("/", ",", ":", " - ") stay ASCII, matching how phone-locale
    # digit substitution actually behaves (only the digit glyphs change).
    "arabic_indic_digits": (
        "٢٣/٠٥/٢٦, ١٦:٤٢"
        " - Priya Nair: hello from arabic-indic digits\n"
    ),
    # Extended Arabic-Indic (Persian/Urdu) digits, U+06F0-06F9, on the
    # bracketed_ampm_seconds format, which also carries an ASCII AM/PM
    # marker -- the marker letters are never digit-substituted.
    "persian_digits": (
        "[۳/۴/۲۵, ۲:۰۵:۳۳ PM]"
        " - Rohan Mehta: hello from persian digits\n"
    ),
}

PARSER_CHAT_INFO_FIXTURES: list[str] = [
    "WhatsApp Chat with Priya Nair.txt",
    "Priya Nair.txt",
    "WhatsApp Chat with Priya Nair (1).txt",
    "WhatsApp Chat with Team (2) Sales.txt",
    "WhatsApp Chat with Kavya's Café \U0001f600.txt",
]


def generate_parser_goldens() -> None:
    fixtures_out: dict[str, list[dict[str, object]]] = {}
    for name, text in PARSER_FIXTURES.items():
        fixture_path = GOLDEN_DIR / f"__tmp_parser_{name}.txt"
        fixture_path.write_text(text, encoding="utf-8", newline="\n")
        try:
            messages = list(parse_file(fixture_path, chat_id="golden_chat"))
        finally:
            fixture_path.unlink()
        fixtures_out[name] = [
            {
                "chatId": m.chat_id,
                "timestampIso": m.timestamp_iso,
                "sender": m.sender,
                "body": m.body,
                "attachmentFilename": m.attachment_filename,
            }
            for m in messages
        ]

    chat_info_out = [
        {
            "filename": filename,
            "chatId": (info := extract_chat_info(filename))[0],
            "displayName": info[1],
        }
        for filename in PARSER_CHAT_INFO_FIXTURES
    ]

    payload = {
        "fixtureTexts": PARSER_FIXTURES,
        "parsedMessages": fixtures_out,
        "chatInfo": chat_info_out,
    }
    out_path = GOLDEN_DIR / "parser_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes)")


# ---------------------------------------------------------------------------
# Time-of-day golden sweep (item 2 of the PR #92 follow-up review)
#
# `Parser.kt:parseTimeOfDay` is a hand-rolled replacement for
# `dateutil_parser.parse(time_str.strip(), default=datetime(1900, 1, 1))`,
# the only third-party dependency on `parser.py`'s hash-input path. This
# sweep runs the *real* dateutil against every input and records either its
# (hour, minute, second) or the exception class name it raised, so the
# Kotlin JUnit test can assert `parseTimeOfDay` reproduces dateutil exactly
# for every one of them, or at minimum documents precisely where it cannot.
# ---------------------------------------------------------------------------

ARABIC_INDIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
ASCII_DIGITS = "0123456789"


def _to_digits(n: int, script: str, width: int = 0) -> str:
    s = str(n)
    if width:
        s = s.zfill(width)
    return "".join(script[int(c)] for c in s)


def _dateutil_result(raw: str) -> dict[str, object]:
    try:
        r = dateutil_parser.parse(raw.strip(), default=datetime(1900, 1, 1))
        return {"input": raw, "ok": True, "hour": r.hour, "minute": r.minute, "second": r.second}
    except Exception as exc:  # noqa: BLE001 -- recording whatever dateutil raises, by design
        return {"input": raw, "ok": False, "exceptionClass": type(exc).__name__}


def generate_time_of_day_golden() -> None:
    import warnings

    warnings.filterwarnings("ignore")  # dateutil's UnknownTimezoneWarning on "...M" suffixes
    entries: list[dict[str, object]] = []
    seen: set[str] = set()

    def add(raw: str) -> None:
        if raw in seen:
            return
        seen.add(raw)
        entries.append(_dateutil_result(raw))

    hours = list(range(0, 25))
    minutes_sample = [0, 5, 30, 59]
    seconds_modes = [None, 0, 30, 59]
    core_markers = [None, " AM", " PM", " am", " pm"]

    # Tier A: full hour sweep x minute/second sample x core marker forms
    # (space + upper/lower AM/PM, and no marker at all -- the 24h case).
    for h in hours:
        for mi in minutes_sample:
            for se in seconds_modes:
                time_part = f"{h}:{mi:02d}" if se is None else f"{h}:{mi:02d}:{se:02d}"
                for marker in core_markers:
                    add(time_part + (marker or ""))

    # Tier B: exotic marker forms (no space, narrow no-break space U+202F,
    # no-break space U+00A0, "a.m."/"p.m." dotted forms), at a representative
    # hour sample that covers the AM/PM edge cases explicitly called out
    # (0, 12, 13, 23, 24) plus a few ordinary hours.
    hour_sample = [0, 1, 9, 11, 12, 13, 23, 24]
    exotic_markers = [
        "AM", "PM",  # no space before marker
        " AM", " PM",  # narrow no-break space (iOS)
        " AM", " PM",  # no-break space
        " a.m.", " p.m.", " A.M.", " P.M.",  # dotted forms
    ]
    for h in hour_sample:
        for mi in [0, 30]:
            for marker in exotic_markers:
                add(f"{h}:{mi:02d}" + marker)

    # Tier C: zero-padded ("HH:MM") vs unpadded ("H:MM") hour, crossed with
    # the core markers, at the same representative hour sample.
    for h in hour_sample:
        for marker in core_markers:
            add(f"{h:02d}:30" + (marker or ""))

    # Tier D: leading/trailing whitespace around an otherwise-ordinary string
    # (parseTimeOfDay strips first, same as `time_str.strip()` in parser.py).
    for wrapped in ["9:41 AM", "13:05:33", "0:00 AM", "23:59:59"]:
        add(f"  {wrapped}  ")
        add(f"\t{wrapped}\n")

    # Tier E: Unicode-digit versions (item 1) of a representative slice,
    # with and without an (ASCII-lettered) AM/PM marker -- WhatsApp never
    # digit-substitutes the AM/PM letters themselves.
    for script in (ARABIC_INDIC_DIGITS, PERSIAN_DIGITS):
        for h in hour_sample:
            for mi in [0, 30]:
                h_digits = _to_digits(h, script)
                mi_digits = _to_digits(mi, script, width=2)
                for marker in [None, " AM", " PM"]:
                    add(f"{h_digits}:{mi_digits}" + (marker or ""))

    payload = {"entries": entries}
    out_path = GOLDEN_DIR / "time_of_day_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(entries)} entries)")


# ---------------------------------------------------------------------------
# state.compute_message_hash golden sweep (Phase 2 "state" port)
#
# Runs the real state.compute_message_hash over every message already parsed
# by generate_parser_goldens() (real dates/times through every
# TIMESTAMP_PATTERNS format, Unicode-digit timestamps, multiline bodies,
# attachments, system messages) plus a handful of synthetic entries added
# here specifically for hash edge cases the parser fixtures above don't
# happen to cover on their own (emoji, U+202F, an empty body). The Kotlin
# JUnit test (StateHashGoldenParityTest.kt) asserts computeMessageHash
# reproduces every one of these byte-for-byte.
# ---------------------------------------------------------------------------

HASH_SWEEP_EXTRA: list[dict[str, str]] = [
    {"chatId": "chat_unicode", "timestampIso": "2025-03-14T09:41:00", "sender": "Priya Nair", "body": "emoji body \U0001f600\U0001f64f"},
    {"chatId": "chat_unicode", "timestampIso": "2025-03-14T09:41:00", "sender": "Kavya Menon \U0001f600", "body": "sender has an emoji too"},
    # U+202F NARROW NO-BREAK SPACE, as WhatsApp iOS exports use before AM/PM.
    {"chatId": "chat_nnbsp", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera Iyer", "body": "narrow no-break space in the body itself"},
    {"chatId": "chat_empty", "timestampIso": "2025-03-14T09:41:00", "sender": "Rohan Mehta", "body": ""},
    {"chatId": "chat_multiline", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera Iyer", "body": "line one\nline two\nline three"},
    {"chatId": "chat_attachment", "timestampIso": "2025-03-14T09:41:00", "sender": "Priya Nair", "body": "IMG-20250314-WA0001.jpg (file attached)"},
    # A body containing the hash's own NUL separator character -- proves the
    # separator choice does not make two different messages collide.
    {"chatId": "chat1", "timestampIso": "2025-03-14T09:41:00", "sender": "Alice", "body": "Hello\x00Bob"},
    # -----------------------------------------------------------------
    # Widened sweep (held fix (a)): non-BMP/emoji beyond a single code
    # point, U+FFFD (the character `read_text(..., errors="replace")`
    # substitutes for bad bytes on the real parse path -- see the PR body
    # for why an actual lone/unpaired surrogate is NOT included here:
    # str.encode("utf-8") is strict by default and *raises*
    # UnicodeEncodeError for one, and the real ingestion path can never
    # hand compute_message_hash one in the first place, because
    # parser.py's read_text(..., errors="replace") already turns any
    # invalid byte sequence into U+FFFD before a ParsedMessage is ever
    # built. There is therefore no Python behaviour for Kotlin to match
    # byte-for-byte here.
    # -----------------------------------------------------------------
    # A ZWJ family emoji: several surrogate-pair code points joined by
    # U+200D, exercising multi-codepoint composed emoji, not just one.
    {"chatId": "chat_zwj", "timestampIso": "2025-03-14T09:41:00", "sender": "Rohan Mehta", "body": "family \U0001f468‍\U0001f469‍\U0001f467‍\U0001f466 emoji"},
    # A flag emoji: a pair of regional-indicator astral code points.
    {"chatId": "chat_flag", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera Iyer", "body": "flag \U0001f1ee\U0001f1f3 here"},
    # A non-BMP character outside the emoji ranges (e.g. a Deseret letter).
    {"chatId": "chat_nonbmp", "timestampIso": "2025-03-14T09:41:00", "sender": "Rohan", "body": "deseret \U00010400 letter"},
    # U+FFFD itself, the real replace-error substitute character.
    {"chatId": "chat_fffd", "timestampIso": "2025-03-14T09:41:00", "sender": "R. Mehta", "body": "bad byte here: � (was invalid)"},
    {"chatId": "chat_fffd_sender", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera � Iyer", "body": "sender itself has the replacement char"},
    # Empty string for each of the four fields individually, and all four
    # empty together -- compute_message_hash's params are plain required
    # `str` in Python (no Optional[str] anywhere in its signature; see
    # state.py), so "empty vs missing" collapses to "empty string" on both
    # sides -- there is no None to compare against.
    {"chatId": "", "timestampIso": "2025-03-14T09:41:00", "sender": "Rohan Mehta", "body": "empty chatId"},
    {"chatId": "chat_empty_ts", "timestampIso": "", "sender": "Rohan Mehta", "body": "empty timestampIso"},
    {"chatId": "chat_empty_sender", "timestampIso": "2025-03-14T09:41:00", "sender": "", "body": "empty sender"},
    {"chatId": "chat_empty_all", "timestampIso": "", "sender": "", "body": ""},
    # Timestamps with and without microseconds -- datetime.isoformat()
    # only emits the fractional part when microsecond != 0, so both shapes
    # occur as real timestamp_iso strings depending on the ParsedMessage's
    # source timestamp; the hash function itself just concatenates
    # whichever string it is handed.
    {"chatId": "chat_ts_no_micro", "timestampIso": "2025-03-14T09:41:00", "sender": "Rohan Mehta", "body": "no microseconds"},
    {"chatId": "chat_ts_micro", "timestampIso": "2025-03-14T09:41:00.123456", "sender": "Rohan Mehta", "body": "with microseconds"},
    {"chatId": "chat_ts_micro_short", "timestampIso": "2025-03-14T09:41:00.000001", "sender": "Rohan Mehta", "body": "microseconds near zero"},
    # CRLF vs LF inside message bodies.
    {"chatId": "chat_crlf", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera Iyer", "body": "line one\r\nline two\r\nline three"},
    {"chatId": "chat_lf", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera Iyer", "body": "line one\nline two\nline three"},
    {"chatId": "chat_mixed_eol", "timestampIso": "2025-03-14T09:41:00", "sender": "Meera Iyer", "body": "line one\r\nline two\nline three\r"},
    # A very long body (well past any small-buffer edge case).
    {"chatId": "chat_long", "timestampIso": "2025-03-14T09:41:00", "sender": "Rohan Mehta", "body": ("The quick brown fox jumps over the lazy dog. " * 2000) + "\U0001f600" * 500},
]


def generate_state_hash_golden() -> None:
    entries: list[dict[str, str]] = []
    seen: set[tuple[str, str, str, str]] = set()

    def add(chat_id: str, timestamp_iso: str, sender: str, body: str) -> None:
        key = (chat_id, timestamp_iso, sender, body)
        if key in seen:
            return
        seen.add(key)
        entries.append(
            {
                "chatId": chat_id,
                "timestampIso": timestamp_iso,
                "sender": sender,
                "body": body,
                "hash": state.compute_message_hash(chat_id, timestamp_iso, sender, body),
            }
        )

    # Every message already parsed for the parser golden sweep -- real
    # timestamps, real Unicode-digit inputs, real multiline/attachment
    # bodies, run through the real Python parser and then the real hash
    # function, so this sweep is provably built from parser output rather
    # than from hand-typed strings that might not match what parse_file()
    # actually produces.
    for name, text in PARSER_FIXTURES.items():
        fixture_path = GOLDEN_DIR / f"__tmp_hash_{name}.txt"
        fixture_path.write_text(text, encoding="utf-8", newline="\n")
        try:
            messages = list(parse_file(fixture_path, chat_id=f"golden_{name}"))
        finally:
            fixture_path.unlink()
        for m in messages:
            add(m.chat_id, m.timestamp_iso, m.sender, m.body)

    for extra in HASH_SWEEP_EXTRA:
        add(extra["chatId"], extra["timestampIso"], extra["sender"], extra["body"])

    payload = {"entries": entries}
    out_path = GOLDEN_DIR / "state_hash_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(entries)} entries)")


# ---------------------------------------------------------------------------
# state.normalise_cutoff golden sweep (held fix (b))
#
# Runs the real Python state.normalise_cutoff over a table of inputs
# covering padded/unpadded dates, whitespace, invalid calendar dates,
# two-digit years, empty string, and garbage, and records either the
# normalised result or that Python raised. normaliseCutoff (State.kt) must
# match exactly -- including every rejection, not just every acceptance --
# see NormaliseCutoffGoldenParityTest.kt.
# ---------------------------------------------------------------------------

CUTOFF_SWEEP: list[str] = [
    # Padded and unpadded dates.
    "2024-01-05",
    "2024-1-5",
    "2024-01-5",
    "2024-1-05",
    # Leading/trailing spaces.
    "  2024-01-05",
    "2024-01-05  ",
    "  2024-01-05  ",
    "\t2024-01-05\n",
    # A full ISO timestamp (only the first 10 chars are used).
    "2024-01-05T10:30:00",
    "2024-01-05T10:30:00.123456",
    "2024-01-05 extra junk after day 10",
    # Invalid calendar dates.
    "2024-13-01",
    "2024-02-30",
    "2023-02-29",  # 2023 is not a leap year
    "2024-02-29",  # 2024 IS a leap year -- must be accepted
    "2024-00-10",
    "2024-01-32",
    "2024-04-31",  # April has 30 days
    # Two-digit / non-4-digit years.
    "24-01-05",
    "024-01-05",
    "0024-01-05",
    "10000-01-05",
    # Empty / blank / None.
    "",
    "   ",
    None,
    # Garbage.
    "garbage",
    "not-a-date",
    "2024/01/05",
    "05-01-2024",
    # Width beyond 1-2 digits for month/day is rejected by Python's strptime.
    "2024-001-05",
    "2024-01-005",
]


def generate_cutoff_golden() -> None:
    entries: list[dict[str, object]] = []
    for raw in CUTOFF_SWEEP:
        try:
            result = state.normalise_cutoff(raw)
            entries.append({"input": raw, "ok": True, "result": result})
        except Exception as exc:  # noqa: BLE001 -- recording whatever it raises, by design
            entries.append({"input": raw, "ok": False, "exceptionClass": type(exc).__name__})

    payload = {"entries": entries}
    out_path = GOLDEN_DIR / "cutoff_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(entries)} entries)")


# ---------------------------------------------------------------------------
# Cross-language sync_state.db fixture (Phase 2 "state" port)
#
# Built with the *real* state.py functions -- init_db, upsert_chat,
# start_sync_run, complete_sync_run, insert_message_hashes, set_chat_cutoff,
# record_chat_senders, set_app_state -- against a throwaway path, then copied
# out as a committed binary test resource. StateDbGoldenParityTest.kt (JVM,
# xerial sqlite-jdbc) opens this exact file and asserts every row reads back
# correctly through StateRepository, and that Kotlin can also write further
# rows into it without disturbing what Python wrote -- proving Python-written
# databases are readable and writable from the Kotlin side. See this
# function's own return value note on determinism: every stored value here is
# a literal, fixed string (never `_now()`), specifically so two runs of this
# generator produce byte-identical file content module SQLite's own internal
# page/vacuum bookkeeping -- see the PR body for the exact determinism check
# performed.
# ---------------------------------------------------------------------------


def generate_state_db_golden() -> None:
    import shutil
    import sqlite3
    import tempfile
    from unittest import mock

    # state.py's own writer functions (upsert_chat, start_sync_run,
    # complete_sync_run, fail_sync_run, set_chat_cutoff, ...) all stamp
    # wall-clock state._now() into the rows they write. Left unpatched, that
    # makes this fixture's created_at/updated_at/started_at/completed_at/
    # set_at columns differ on every regeneration, which fails the "run the
    # generator twice, no diff" requirement even at the logical (iterdump)
    # level, not just byte-for-byte. Freezing state._now() to a single fixed
    # literal for the duration of this function makes every stored value a
    # deterministic function of the calls below, matching the doc comment a
    # few lines up.
    frozen_now = "2025-03-14T09:40:00"
    tmp_dir = Path(tempfile.mkdtemp(prefix="cms_state_golden_"))
    try:
        with mock.patch.object(state, "_now", return_value=frozen_now):
            _write_state_db_golden_rows(tmp_dir)
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


def _write_state_db_golden_rows(tmp_dir: Path) -> None:
    import shutil
    import sqlite3

    db_path = tmp_dir / "sync_state.db"
    state.init_db(db_path)

    state.upsert_chat("chat_meera", "Meera Iyer", "Meera Iyer.txt", db_path=db_path)
    state.upsert_chat("chat_team", "Team Sales", "WhatsApp Chat with Team Sales.txt", db_path=db_path)
    state.update_chat_gmail_ids(
        "chat_meera", gmail_thread_id="thread-1", gmail_label_id="WhatsApp/Meera Iyer",
        anchor_message_id="<golden-anchor@local>", db_path=db_path,
    )

    run1 = state.start_sync_run("chat_meera", trigger="manual", db_path=db_path)
    h1 = state.compute_message_hash("chat_meera", "2025-03-14T09:41:00", "Meera Iyer", "hello from the golden fixture")
    h2 = state.compute_message_hash("chat_meera", "2025-03-14T09:41:30", "Rohan Mehta", "line one\nline two")
    state.insert_message_hashes(
        [
            (h1, "chat_meera", "2025-03-14T09:41:00", run1),
            (h2, "chat_meera", "2025-03-14T09:41:30", run1),
        ],
        db_path=db_path,
    )
    state.complete_sync_run(
        run1,
        last_synced_ts="2025-03-14T09:41:30",
        last_synced_hash=h2,
        messages_parsed=2,
        messages_synced=2,
        messages_skipped=0,
        messages_cutoff=0,
        db_path=db_path,
    )

    run2 = state.start_sync_run("chat_team", trigger="watched_folder", db_path=db_path)
    state.fail_sync_run(run2, "golden fixture: simulated network error", db_path=db_path)

    state.set_chat_cutoff("chat_meera", "2025-01-01", db_path=db_path)
    state.record_chat_senders(
        "chat_meera",
        {"Meera Iyer": 1, "Rohan Mehta": 1, "Priya Nair": 0},
        seen_ts="2025-03-14T09:41:30",
        db_path=db_path,
    )
    state.set_app_state(state.SELF_SENDER_LEARNED, "Priya Nair", db_path=db_path)

    # Checkpoint WAL into the main file and drop the -wal/-shm sidecars so
    # exactly one file (sync_state.db) needs to be committed and read back
    # -- see the PR body for why a plain byte-diff isn't used to prove
    # determinism instead (SQLite's own free-page bookkeeping isn't
    # byte-stable run to run even with identical logical content).
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.commit()
    conn.close()

    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    out_path = GOLDEN_DIR / "state_fixture_python_written.db"
    shutil.copyfile(db_path, out_path)
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes)")


# ---------------------------------------------------------------------------
# MediaExtractor golden sweep (KT-04, Phase 2 "media_extractor" port)
#
# Runs the *real* src.media_extractor.MediaExtractor against a real ZIP
# fixture and a real plain-file-alongside-.txt fixture built on disk, and
# records exactly what MediaExtractor.resolve() returns for a sweep of
# filenames -- attachment-line-shaped names, unicode/odd names, case and
# extension variants, duplicate basenames, and path-traversal-looking names.
# MediaExtractorGoldenParityTest.kt (Kotlin) replays the same fixtures and
# queries and asserts byte-for-byte parity.
#
# Two environment-dependent landmines are worked around here, deliberately,
# so this golden reflects real Android (Chaquopy, Linux CPython) production
# behaviour rather than an artifact of the Windows machine generating it:
#
#   1. Windows-registry MIME pollution: `mimetypes.guess_type()` (the
#      module-level function `MediaExtractor.resolve()` actually calls)
#      lazily builds a global singleton that unconditionally merges Windows
#      Registry MIME associations on first use -- associations that will
#      never exist on real Android. `mimetypes.guess_type` is patched for
#      the duration of this sweep to a fresh, registry-free
#      `mimetypes.MimeTypes(filenames=()).guess_type`, whose own
#      construction never touches the registry (only the separate
#      module-level `mimetypes.init()` does that).
#
#   2. `pathlib.Path` backslash-splitting: on Windows, `pathlib.Path` splits
#      on both `/` and `\`; on real Android (POSIX), only on `/`. All
#      traversal-looking test filenames below therefore use forward slashes
#      only (real WhatsApp export filenames never contain a backslash
#      anyway), so `Path(name).name` -- the actual traversal guard both in
#      Python and in the Kotlin port's `pathBasename` -- agrees on every
#      platform this generator or the Kotlin test ever runs on.
# ---------------------------------------------------------------------------


def generate_media_extractor_golden() -> None:
    import mimetypes
    import shutil
    import tempfile
    from unittest import mock

    registry_free_guess_type = mimetypes.MimeTypes(filenames=()).guess_type

    tmp_dir = Path(tempfile.mkdtemp(prefix="cms_media_extractor_golden_"))
    try:
        with mock.patch("mimetypes.guess_type", side_effect=registry_free_guess_type):
            payload = {
                "zip": _build_media_extractor_zip_case(tmp_dir),
                "plainFile": _build_media_extractor_plain_case(tmp_dir),
            }
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

    out_path = GOLDEN_DIR / "media_extractor_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    total_cases = len(payload["zip"]["queries"]) + len(payload["plainFile"]["queries"])
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {total_cases} query cases)")


def _resolve_case(extractor: "MediaExtractor", filename: str) -> dict[str, object]:
    result = extractor.resolve(filename)
    if result is None:
        return {"filename": filename, "found": False, "bytesBase64": None, "mimeType": None}
    data, mime_type = result
    return {
        "filename": filename,
        "found": True,
        "bytesBase64": base64.b64encode(data).decode("ascii"),
        "mimeType": mime_type,
    }


def _build_media_extractor_zip_case(tmp_dir: Path) -> dict[str, object]:
    zip_path = tmp_dir / "WhatsApp Chat with Meera Iyer.zip"

    entries: list[tuple[str, bytes]] = [
        # Android attachment-line style: bare basename.
        ("IMG-20250314-WA0001.jpg", b"jpeg-bytes-android-style"),
        # Case variant of the extension/basename (queried lowercase below).
        ("Photo.PNG", b"png-bytes-case-variant"),
        # Duplicate basename in a virtual sub-directory, added *after* the
        # top-level entry above -- first occurrence must win (setdefault).
        ("originals/IMG-20250314-WA0001.jpg", b"jpeg-bytes-DUPLICATE-must-not-win"),
        # Unicode filename (accented Latin + emoji + Cyrillic).
        ("Café ☕ снимок.jpg", b"unicode-filename-bytes"),
        # iOS attachment-line style: numeric-prefixed, uppercase extension.
        ("00003-PHOTO-2023-06-01-12-34-56.HEIC", b"heic-bytes-ios-style"),
        # No extension at all -- mimetype must fall back to octet-stream.
        ("attachment_no_ext", b"no-extension-bytes"),
        # Extension present but absent from the curated/default MIME table.
        ("voice_note.m4a", b"m4a-bytes-unmapped-extension"),
        # An entirely ordinary attachment whose name happens to coincide
        # with a sensitive-looking path leaf -- used below to prove a
        # traversal-looking query only ever reaches this sandboxed entry by
        # basename, never anything outside the archive.
        ("passwd.jpg", b"ordinary-attachment-named-passwd"),
        # Two more entries colliding on the same lowercase basename from two
        # different original spellings -- pins down first-occurrence-wins
        # independent of the duplicate-via-subdirectory case above.
        ("Note.pdf", b"note-bytes-first-occurrence"),
        ("NOTE.PDF", b"note-bytes-must-not-win"),
        # An explicit stored directory entry (some zip writers emit these).
        # Path("sub_dir/").name == "" -- must not crash indexing.
        ("sub_dir/", b""),
    ]

    with zipfile.ZipFile(zip_path, "w") as zf:
        for name, data in entries:
            zf.writestr(name, data)

    queries = [
        "IMG-20250314-WA0001.jpg",
        "img-20250314-wa0001.jpg",  # case-insensitive basename match
        "photo.png",  # case-insensitive match of "Photo.PNG"
        # Queried with the duplicate's own sub-directory path, but the
        # index resolves by basename only -- must still return the
        # top-level entry's bytes, not the duplicate's.
        "originals/IMG-20250314-WA0001.jpg",
        "Café ☕ снимок.jpg",
        "CAFÉ ☕ СНИМОК.jpg",  # unicode case-insensitive
        "00003-PHOTO-2023-06-01-12-34-56.HEIC",
        "attachment_no_ext",
        "voice_note.m4a",
        # Forward-slash-only traversal-looking queries (see module docstring
        # note 2 above for why never backslash).
        "../../etc/passwd.jpg",  # basename "passwd.jpg" -- legitimately indexed, resolves
        "../../../etc/shadow",  # basename "shadow" -- not indexed, must NOT resolve
        "note.pdf",  # first-occurrence-wins: must return "Note.pdf"'s bytes
        "does_not_exist.jpg",
    ]

    with MediaExtractor(zip_path) as extractor:
        query_results = [_resolve_case(extractor, q) for q in queries]

    return {"archiveName": zip_path.name, "entries": [n for n, _ in entries], "queries": query_results}


def _build_media_extractor_plain_case(tmp_dir: Path) -> dict[str, object]:
    plain_dir = tmp_dir / "plain_export"
    plain_dir.mkdir()
    source_path = plain_dir / "_chat.txt"
    source_path.write_text("plain-file-mode source placeholder\n", encoding="utf-8", newline="\n")

    siblings: list[tuple[str, bytes]] = [
        ("IMG-20250101-WA0009.jpg", b"jpeg-bytes-plain-mode"),
        ("Vacation Video.mp4", b"mp4-bytes-with-space-in-name"),
        ("Priya Nair Voice Note.opus", b"opus-bytes-space-in-name"),
        # Real on-disk case differs from the query below; exercised via the
        # fast `.exists()` check and/or the case-insensitive `iterdir()`
        # fallback depending on the underlying filesystem's own case
        # sensitivity -- the *observable* result (found, same bytes, same
        # mimetype) is identical either way, which is what this golden pins.
        ("ROHAN-DOC.PDF", b"pdf-bytes-case-variant"),
        # An ordinary sibling file that happens to be named like a
        # traversal target, proving (together with the query below) that a
        # traversal-looking name only ever reaches a real sibling file by
        # basename, never anything outside plain_dir.
        ("passwd", b"ordinary-sibling-file-named-passwd"),
    ]
    for name, data in siblings:
        (plain_dir / name).write_bytes(data)

    queries = [
        "IMG-20250101-WA0009.jpg",
        "Vacation Video.mp4",
        "Priya Nair Voice Note.opus",
        "rohan-doc.pdf",  # case-insensitive match of "ROHAN-DOC.PDF"
        "../../etc/passwd",  # basename "passwd" -- real sibling, resolves
        "../../../etc/shadow",  # basename "shadow" -- no such sibling, must NOT resolve
        "missing_on_disk.png",
    ]

    with MediaExtractor(source_path) as extractor:
        query_results = [_resolve_case(extractor, q) for q in queries]

    return {
        "sourceName": source_path.name,
        "siblingNames": [n for n, _ in siblings],
        "queries": query_results,
    }


# ---------------------------------------------------------------------------
# HtmlRenderer golden sweep (KT-05, Phase 2 "html_renderer" port)
#
# Runs the *real* src.html_renderer.render_chunk against a sweep of chunks
# covering escaping, unicode/emoji/RTL text, day-pill/time formatting, inline
# media of every embeddable type, non-image attachments of every icon
# category, a missing media file, an oversized media file (both with and
# without a byte cap), sender-colour determinism across a group chat, the
# outgoing/incoming self-sender split, and the empty/one-message edge cases.
# HtmlRendererGoldenParityTest.kt (Kotlin) replays the same fixtures and
# asserts byte-for-byte parity.
#
# One value in the real output is genuinely non-deterministic on both sides:
# the inline-image `cid` (`f"img-{uuid.uuid4().hex[:12]}"` in Python,
# `UUID.randomUUID()`-derived in Kotlin). Both are always exactly 16
# characters ("img-" + 12 hex digits), so total_bytes/wire_bytes -- which
# only depend on that fixed length, never the actual random content -- are
# recorded directly from the real (pre-normalization) render. The cid text
# itself is normalized, in order of first appearance in html_body, to
# `img-NORMALIZEDCID<n>` placeholders before being written to the golden --
# the same normalize-then-compare approach MimeGoldenParityTest.kt already
# uses for the random MIME boundary (see `_normalize_boundary` above), and
# the Kotlin test applies the identical regex/ordering scheme to its own
# freshly-rendered output before comparing.
# ---------------------------------------------------------------------------

_CID_RE = re.compile(r"img-[0-9a-f]{12}")


def _normalize_cids(html_body: str, cids: list[str]) -> tuple[str, list[str]]:
    mapping: dict[str, str] = {}
    counter = 0

    def _sub(m: "re.Match[str]") -> str:
        nonlocal counter
        token = m.group(0)
        if token not in mapping:
            counter += 1
            mapping[token] = f"img-NORMALIZEDCID{counter}"
        return mapping[token]

    normalized_html = _CID_RE.sub(_sub, html_body)
    normalized_cids = [mapping.get(cid, cid) for cid in cids]
    return normalized_html, normalized_cids


def _hr_msg(sender: str, body: str, ts: datetime, attachment_filename: str | None = None) -> ParsedMessage:
    return ParsedMessage(
        chat_id="html_renderer_golden_chat",
        timestamp=ts,
        sender=sender,
        body=body,
        attachment_filename=attachment_filename,
    )


def _build_html_renderer_case(case: dict[str, object], extractor: "MediaExtractor") -> dict[str, object]:
    messages = case["messages"]  # type: ignore[assignment]
    use_extractor = case.get("use_extractor", False)
    rendered = render_chunk(
        messages,  # type: ignore[arg-type]
        case.get("display_name", "Meera Iyer"),  # type: ignore[arg-type]
        extractor if use_extractor else None,
        label=case.get("label", ""),  # type: ignore[arg-type]
        max_media_bytes=case.get("max_media_bytes"),  # type: ignore[arg-type]
        self_sender=case.get("self_sender"),  # type: ignore[arg-type]
    )

    normalized_html, normalized_cids = _normalize_cids(
        rendered.html_body, [p.cid for p in rendered.inline_parts]
    )

    return {
        "name": case["name"],
        "htmlBody": normalized_html,
        "inlinePartsCids": normalized_cids,
        "inlinePartsMimeTypes": [p.mime_type for p in rendered.inline_parts],
        "inlinePartsDataBase64": [
            base64.b64encode(p.data).decode("ascii") for p in rendered.inline_parts
        ],
        "attachmentsFilenames": [a.filename for a in rendered.attachments],
        "attachmentsMimeTypes": [a.mime_type for a in rendered.attachments],
        "attachmentsDataBase64": [
            base64.b64encode(a.data).decode("ascii") for a in rendered.attachments
        ],
        "totalBytes": rendered.total_bytes,
        "wireBytes": rendered.wire_bytes,
        "omissions": [
            {"filename": o.filename, "sizeBytes": o.size_bytes, "limitBytes": o.limit_bytes}
            for o in rendered.omissions
        ],
    }


def generate_html_renderer_golden() -> None:
    import mimetypes
    import shutil
    import tempfile
    from unittest import mock

    # Same Windows-registry-pollution workaround as generate_media_extractor_golden
    # above: MediaExtractor.resolve() (used here via `use_extractor=True` cases)
    # calls the module-level `mimetypes.guess_type()`, which on this Windows
    # generation machine lazily merges Windows Registry MIME associations
    # (e.g. mapping ".opus" to "audio/ogg" instead of the registry-free
    # "audio/opus") that never exist on real Android (Chaquopy, Linux
    # CPython). Patched for the duration of this sweep to a fresh,
    # registry-free `mimetypes.MimeTypes(filenames=()).guess_type` so this
    # golden reflects real Android production behaviour, matching
    # MediaExtractor.kt's own hand-curated table (verified against the same
    # registry-free instance).
    registry_free_guess_type = mimetypes.MimeTypes(filenames=()).guess_type

    tmp_dir = Path(tempfile.mkdtemp(prefix="cms_html_renderer_golden_"))
    try:
        _html_renderer_patch = mock.patch(
            "mimetypes.guess_type", side_effect=registry_free_guess_type
        )
        _html_renderer_patch.start()
        source_path = tmp_dir / "_chat.txt"
        source_path.write_text(
            "html renderer golden source placeholder\n", encoding="utf-8", newline="\n"
        )

        media_files: list[tuple[str, bytes]] = [
            ("photo.jpg", b"\xff\xd8\xff" + b"jpeg-bytes-for-html-renderer-golden"),
            ("icon.png", b"\x89PNG\r\n\x1a\n" + b"png-bytes-for-html-renderer-golden"),
            ("clip.gif", b"GIF89a" + b"gif-bytes-for-html-renderer-golden"),
            ("sticker.webp", b"RIFF" + b"webp-bytes-for-html-renderer-golden"),
            ("sketch.bmp", b"BM" + b"bmp-bytes-for-html-renderer-golden"),
            ("clip.mp4", b"video-bytes-for-html-renderer-golden"),
            ("voice.opus", b"audio-bytes-for-html-renderer-golden"),
            ("doc.pdf", b"%PDF-1.4 " + b"pdf-bytes-for-html-renderer-golden"),
            ("note.txt", b"text-bytes-for-html-renderer-golden"),
            ("unknown.xyz", b"unmapped-extension-bytes-for-html-renderer-golden"),
            ("bigdoc.pdf", b"%PDF-1.4 " + b"z" * 1_048_600),
            ("huge.jpg", b"\xff\xd8\xff" + b"z" * 200_000),
        ]
        for name, data in media_files:
            (tmp_dir / name).write_bytes(data)

        ts = lambda day, hour, minute: datetime(2025, 5, day, hour, minute, 0)  # noqa: E731

        cases: list[dict[str, object]] = [
            {
                "name": "empty_chat",
                "messages": [],
            },
            {
                "name": "one_message_chat",
                "messages": [_hr_msg("Meera Iyer", "Hello there, just checking in.", ts(3, 9, 41))],
                "display_name": "Meera Iyer",
            },
            {
                "name": "html_escaping_in_name_and_body",
                "messages": [
                    _hr_msg(
                        'Rohan "R" <Mehta>',
                        "<b>bold</b> & \"quoted\" 'single' <script>alert(1)</script>",
                        ts(3, 9, 42),
                    )
                ],
                "display_name": "Rohan Mehta",
            },
            {
                "name": "url_as_plain_text_no_autolink",
                "messages": [
                    _hr_msg(
                        "Priya Nair",
                        "Check this out: https://example.com/path?x=1&y=2 nice right?",
                        ts(3, 9, 43),
                    )
                ],
                "display_name": "Priya Nair",
            },
            {
                "name": "unicode_emoji_rtl_text",
                "messages": [
                    _hr_msg(
                        "Kavya Menon",
                        "मिलते हैं 🎉😊 مرحبا بالعالم शुक्रिया",
                        ts(3, 9, 44),
                    )
                ],
                "display_name": "Kavya Menon",
            },
            {
                "name": "newlines_and_long_message",
                "messages": [
                    _hr_msg(
                        "Meera Iyer",
                        "line one\nline two\nline three\n\n"
                        + ("This is a long message. " * 40).strip(),
                        ts(3, 9, 45),
                    )
                ],
                "display_name": "Meera Iyer",
            },
            {
                "name": "system_and_deleted_style_messages",
                "messages": [
                    _hr_msg(
                        "Rohan Mehta",
                        "Messages and calls are end-to-end encrypted. "
                        "No one outside of this chat, not even WhatsApp, can read or listen to them.",
                        ts(3, 9, 40),
                    ),
                    _hr_msg("Meera Iyer", "This message was deleted", ts(3, 9, 46)),
                ],
                "display_name": "Meera Iyer",
            },
            {
                "name": "inline_images_all_embeddable_types",
                "messages": [
                    _hr_msg("Meera Iyer", "photo.jpg (file attached)", ts(3, 10, 1), "photo.jpg"),
                    _hr_msg("Meera Iyer", "icon.png (file attached)", ts(3, 10, 2), "icon.png"),
                    _hr_msg("Meera Iyer", "clip.gif (file attached)", ts(3, 10, 3), "clip.gif"),
                    _hr_msg("Meera Iyer", "sticker.webp (file attached)", ts(3, 10, 4), "sticker.webp"),
                    _hr_msg("Meera Iyer", "sketch.bmp (file attached)", ts(3, 10, 5), "sketch.bmp"),
                ],
                "display_name": "Meera Iyer",
                "use_extractor": True,
            },
            {
                "name": "non_image_attachments_all_icon_categories",
                "messages": [
                    _hr_msg("Rohan Mehta", "clip.mp4 (file attached)", ts(3, 11, 1), "clip.mp4"),
                    _hr_msg("Rohan Mehta", "voice.opus (file attached)", ts(3, 11, 2), "voice.opus"),
                    _hr_msg("Rohan Mehta", "doc.pdf (file attached)", ts(3, 11, 3), "doc.pdf"),
                    _hr_msg("Rohan Mehta", "note.txt (file attached)", ts(3, 11, 4), "note.txt"),
                    _hr_msg("Rohan Mehta", "unknown.xyz (file attached)", ts(3, 11, 5), "unknown.xyz"),
                    _hr_msg("Rohan Mehta", "bigdoc.pdf (file attached)", ts(3, 11, 6), "bigdoc.pdf"),
                ],
                "display_name": "Rohan Mehta",
                "use_extractor": True,
            },
            {
                "name": "missing_media_file",
                "messages": [
                    _hr_msg(
                        "Priya Nair", "ghost.jpg (file attached)", ts(3, 12, 0), "ghost.jpg"
                    )
                ],
                "display_name": "Priya Nair",
                "use_extractor": True,
            },
            {
                "name": "oversized_media_omitted_with_cap",
                "messages": [
                    _hr_msg("Kavya Menon", "huge.jpg (file attached)", ts(3, 12, 30), "huge.jpg")
                ],
                "display_name": "Kavya Menon",
                "use_extractor": True,
                "max_media_bytes": 100_000,
            },
            {
                "name": "large_media_not_omitted_without_cap",
                "messages": [
                    _hr_msg("Kavya Menon", "huge.jpg (file attached)", ts(3, 12, 45), "huge.jpg")
                ],
                "display_name": "Kavya Menon",
                "use_extractor": True,
            },
            {
                "name": "group_chat_sender_colors",
                "messages": [
                    _hr_msg("Meera Iyer", "hi everyone", ts(3, 13, 1)),
                    _hr_msg("Rohan Mehta", "hey!", ts(3, 13, 2)),
                    _hr_msg("Priya Nair", "hello all", ts(3, 13, 3)),
                    _hr_msg("Kavya Menon", "good morning", ts(3, 13, 4)),
                ],
                "display_name": "Group Chat",
            },
            {
                "name": "outgoing_vs_incoming_with_self_sender",
                "messages": [
                    _hr_msg("Meera Iyer", "outgoing message text", ts(3, 14, 1)),
                    _hr_msg("Rohan Mehta", "incoming message text", ts(3, 14, 2)),
                ],
                "display_name": "Rohan Mehta",
                "self_sender": "Meera Iyer",
            },
            {
                "name": "outgoing_default_you_fallback",
                "messages": [
                    _hr_msg("You", "outgoing via literal You fallback", ts(3, 14, 30)),
                    _hr_msg("Priya Nair", "incoming reply", ts(3, 14, 31)),
                ],
                "display_name": "Priya Nair",
            },
            {
                "name": "date_pill_with_label_suffix",
                "messages": [_hr_msg("Meera Iyer", "part two of three", ts(3, 15, 0))],
                "display_name": "Meera Iyer",
                "label": "Part 2/3",
            },
            {
                "name": "attachment_marker_only_produces_no_body_div",
                "messages": [
                    _hr_msg(
                        "Meera Iyer", "(file attached)", ts(3, 15, 30), "photo.jpg"
                    )
                ],
                "display_name": "Meera Iyer",
                "use_extractor": True,
            },
            {
                "name": "attachment_with_extra_text_keeps_body_div",
                "messages": [
                    _hr_msg(
                        "Meera Iyer", "photo.jpg (file attached)", ts(3, 15, 35), "photo.jpg"
                    )
                ],
                "display_name": "Meera Iyer",
                "use_extractor": True,
            },
            {
                "name": "ios_attachment_marker_only_produces_no_body_div",
                "messages": [
                    _hr_msg(
                        "Kavya Menon",
                        "<attached: icon.png>",
                        ts(3, 15, 45),
                        "icon.png",
                    )
                ],
                "display_name": "Kavya Menon",
                "use_extractor": True,
            },
            {
                "name": "day_pill_leading_zero_stripped_various_days",
                "messages": [
                    _hr_msg("Meera Iyer", "first of the month", ts(1, 8, 5)),
                ],
                "display_name": "Meera Iyer",
            },
        ]

        with MediaExtractor(source_path) as extractor:
            payload = {"cases": [_build_html_renderer_case(c, extractor) for c in cases]}
    finally:
        _html_renderer_patch.stop()
        shutil.rmtree(tmp_dir, ignore_errors=True)

    out_path = GOLDEN_DIR / "html_renderer_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(payload['cases'])} cases)")


def _mi_msg(sender: str, body: str, ts: datetime, chat_id: str = FIXTURE_CHAT_ID) -> ParsedMessage:
    return ParsedMessage(chat_id=chat_id, timestamp=ts, sender=sender, body=body)


def _build_mail_index_case(
    name: str,
    display_name: str,
    chunk: "list[ParsedMessage]",
    chunk_size,
    message_id: str,
    app_version,
) -> dict[str, object]:
    """Build one golden case dict for a single build_index/index_bytes call.

    app_version is threaded through mail_index.set_app_version() for the
    duration of this one build only, then reset to None (never-called),
    matching tests/test_mail_index.py's autouse _reset_app_version fixture --
    one case's app_version can never leak into the next.
    """
    mail_index.set_app_version(app_version)
    try:
        index = mail_index.build_index(display_name, chunk, chunk_size, message_id)
        index_json = mail_index.index_bytes(index).decode("utf-8")
        header_chat = mail_index._header_safe(chunk[0].chat_id)
    finally:
        mail_index.set_app_version(None)
    return {
        "name": name,
        "displayName": display_name,
        "chatId": chunk[0].chat_id,
        "messageId": message_id,
        "chunkSize": chunk_size,
        "appVersionInput": app_version,
        "messages": [
            {"sender": m.sender, "body": m.body, "ts": m.timestamp_iso} for m in chunk
        ],
        "indexJson": index_json,
        "headerChat": header_chat,
    }


def generate_mail_index_golden() -> None:
    """Golden fixtures for MailIndex.kt's buildIndex/indexBytes/headerSafe,
    mirroring tests/test_mail_index.py's coverage: message ordering, every
    app_version stamping path (explicit/empty-string/never-set), every
    chunk_size shape (day/hour/week/int), the hand-assembled JSON surviving
    the same four hostile-content strings the Python suite parametrizes
    over, the single-message no-trailing-comma edge case, a chat_id
    header-injection attempt, a long-sender-name stress case, and a
    250-message sweep -- plus a separate group of estimate_index_bytes()
    upper-bound cases at the same counts (1, 10, 250) the Python suite
    parametrizes test_estimate_is_an_upper_bound_on_the_real_part over.

    mail_index does not touch mimetypes, so the registry-free-mimetypes
    workaround used by generate_media_extractor_golden/
    generate_html_renderer_golden does not apply here.
    """
    cases: list[dict[str, object]] = []

    two_msg_chunk = [
        _mi_msg("Priya Nair", "first message here", datetime(2020, 1, 2, 9, 0, 0)),
        _mi_msg("Rohan Mehta", "second message, right after", datetime(2020, 1, 2, 9, 1, 0)),
    ]
    cases.append(
        _build_mail_index_case(
            "two_messages_default_day_chunk", "Priya Nair", two_msg_chunk, "day",
            "<mi-golden-1@local>", None,
        )
    )

    def _one() -> list[ParsedMessage]:
        return [_mi_msg("Meera Iyer", "solo message", datetime(2021, 6, 15, 14, 30, 0))]

    cases.append(
        _build_mail_index_case(
            "single_message_no_trailing_comma", "Meera Iyer", _one(), "day",
            "<mi-golden-2@local>", None,
        )
    )
    cases.append(
        _build_mail_index_case(
            "app_version_explicit_stamped", "Meera Iyer", _one(), "day",
            "<mi-golden-3@local>", "2.2.0",
        )
    )
    cases.append(
        _build_mail_index_case(
            "app_version_empty_string_falls_back_to_unknown", "Meera Iyer", _one(), "day",
            "<mi-golden-4@local>", "",
        )
    )
    cases.append(
        _build_mail_index_case(
            "app_version_never_called_falls_back_to_unknown", "Meera Iyer", _one(), "day",
            "<mi-golden-5@local>", None,
        )
    )

    hour_week_chunk = [
        _mi_msg("Kavya Menon", "hour chunk message one", datetime(2022, 2, 2, 10, 0, 0)),
        _mi_msg("Kavya Menon", "hour chunk message two", datetime(2022, 2, 2, 10, 30, 0)),
    ]
    cases.append(
        _build_mail_index_case(
            "chunk_size_hour", "Kavya Menon", hour_week_chunk, "hour",
            "<mi-golden-6@local>", None,
        )
    )
    cases.append(
        _build_mail_index_case(
            "chunk_size_week", "Kavya Menon", hour_week_chunk, "week",
            "<mi-golden-7@local>", None,
        )
    )
    cases.append(
        _build_mail_index_case(
            "chunk_size_count_int_50", "Kavya Menon", hour_week_chunk, 50,
            "<mi-golden-8@local>", None,
        )
    )

    hostile_strings = [
        'quote " and backslash \\',
        "newline\nin body",
        "unicode ✅ 你好 emoji 🎉",
        "comma, brace } bracket ]",
    ]
    hostile_names = [
        "hostile_quote_and_backslash",
        "hostile_newline_in_body",
        "hostile_unicode_and_emoji",
        "hostile_comma_brace_bracket",
    ]
    for name, hostile in zip(hostile_names, hostile_strings):
        hostile_chunk = [
            ParsedMessage(
                chat_id=FIXTURE_CHAT_ID,
                timestamp=datetime(2025, 3, 14, 9, 41, 0),
                sender=hostile,
                body=hostile,
            )
        ]
        cases.append(
            _build_mail_index_case(name, hostile, hostile_chunk, "day", "<mi-golden-hostile@local>", None)
        )

    injected_chat_id = "evil\r\nX-Injected: yes"
    injection_chunk = [
        _mi_msg(
            "Kavya Menon", "normal body text", datetime(2022, 3, 3, 3, 3, 0),
            chat_id=injected_chat_id,
        )
    ]
    cases.append(
        _build_mail_index_case(
            "chat_id_header_injection_attempt", "Kavya Menon", injection_chunk, "day",
            "<mi-golden-9@local>", None,
        )
    )

    long_sender = "A" * 200
    long_sender_chunk = [_mi_msg(long_sender, "hi", datetime(2023, 4, 4, 4, 4, 0))]
    cases.append(
        _build_mail_index_case(
            "long_sender_name_stress", long_sender, long_sender_chunk, "day",
            "<mi-golden-10@local>", None,
        )
    )

    many_chunk = [
        _mi_msg(
            "Meera Iyer", f"message number {i}",
            datetime(2020, 1, 1, 0, 0, 0) + timedelta(seconds=i),
        )
        for i in range(250)
    ]
    cases.append(
        _build_mail_index_case(
            "many_messages_two_fifty", "Meera Iyer", many_chunk, "day",
            "<mi-golden-11@local>", None,
        )
    )

    estimate_cases: list[dict[str, object]] = []
    for count in (1, 10, 250):
        estimate_chunk = [
            _mi_msg(
                "Meera Iyer", f"message number {i}",
                datetime(2020, 1, 1, 0, 0, 0) + timedelta(seconds=i),
            )
            for i in range(count)
        ]
        mail_index.set_app_version(None)
        part = mail_index.build_index_part("Meera Iyer", estimate_chunk, "day", FIXTURE_MESSAGE_ID)
        part_bytes_len = len(part.as_bytes())
        index_bytes_len = len(
            mail_index.index_bytes(
                mail_index.build_index("Meera Iyer", estimate_chunk, "day", FIXTURE_MESSAGE_ID)
            )
        )
        estimate = mail_index.estimate_index_bytes(count)
        estimate_cases.append(
            {
                "messageCount": count,
                "indexBytesLen": index_bytes_len,
                "partBytesLen": part_bytes_len,
                "estimate": estimate,
            }
        )

    payload = {"cases": cases, "estimateCases": estimate_cases}
    out_path = GOLDEN_DIR / "mail_index_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(cases)} cases, {len(estimate_cases)} estimate cases)")


# ---------------------------------------------------------------------------
# MIME message goldens (PAR-05 / PAR-07): header folding and RFC 2047 encoding
#
# Each case is one full message built by the REAL Python
# `_build_mime_message` (Python 3.13 email package, compat32 policy) and the
# exact bytes are recorded, boundary-normalized. The expected bytes are never
# written by hand. MimeCasesGoldenParityTest.kt rebuilds every case with
# MimeBuilder and compares.
#
# Names are made up (Meera Iyer, Rohan Mehta) or synthetic. The Devanagari and
# emoji strings are invented group names. The sweeps walk every name length
# across the 75-character encoded-word limit and the 78-character fold limit,
# so an off-by-one in either shows up as a failing case.
# ---------------------------------------------------------------------------

_MIME_CASE_TS = datetime(2019, 5, 3, 10, 15, 0)

_LONG_ASCII_BASE = "Meera Iyer and Rohan Mehta Weekend Trip Planning Committee Group 2026 Extra"
_HINDI_BASE = (
    "मीरा अय्यर "
    "और रोहन मेहता "
    "का परिवार समूह"
)
_EMOJI_BASE = "Rohan Mehta 🎉🎂🥳 Birthday Crew 🎈🎊🎁"
_ACCENT_BASE = "Café Crème Société of Meera Iyer and Röhan Mehta"


def _mime_msgs(chat_id: str = FIXTURE_CHAT_ID, body: str = "hello from the past") -> "list[ParsedMessage]":
    return [
        ParsedMessage(chat_id=chat_id, timestamp=_MIME_CASE_TS, sender="Meera Iyer", body=body),
        ParsedMessage(
            chat_id=chat_id,
            timestamp=_MIME_CASE_TS + timedelta(minutes=1),
            sender="Rohan Mehta",
            body="line one\nline two continuation",
        ),
    ]


def _mime_case(
    name: str,
    display_name: str,
    messages: "list[ParsedMessage] | None" = None,
    chunk_size="day",
    message_id: str = FIXTURE_MESSAGE_ID,
    in_reply_to: "str | None" = None,
    references: "str | None" = None,
) -> dict[str, object]:
    chunk = messages if messages is not None else _mime_msgs()
    result = _build_mime_message(
        display_name=display_name,
        chunk=chunk,
        chunk_size=chunk_size,
        label_id=f"WhatsApp/{display_name}",
        message_id=message_id,
        in_reply_to=in_reply_to,
        references=references,
    )
    raw_bytes = base64.urlsafe_b64decode(result["raw"])
    eml = _normalize_boundary(raw_bytes).decode("ascii")
    return {
        "name": name,
        "displayName": display_name,
        "chatId": chunk[0].chat_id,
        "messageId": message_id,
        "chunkSize": chunk_size,
        "inReplyTo": in_reply_to,
        "references": references,
        "messages": [
            {"sender": m.sender, "body": m.body, "ts": m.timestamp_iso} for m in chunk
        ],
        "eml": eml,
    }


def _mime_cases() -> "list[dict[str, object]]":
    cases: list[dict[str, object]] = []

    # Named cases: the three names the PAR-05 review measured, plus the quoting
    # and encoding-choice edges.
    cases.append(_mime_case("long_ascii_name_64", _LONG_ASCII_BASE[:64]))
    cases.append(_mime_case("hindi_group_name", _HINDI_BASE))
    cases.append(_mime_case("emoji_name", _EMOJI_BASE))
    cases.append(_mime_case("accent_name_long", _ACCENT_BASE))
    cases.append(_mime_case("quoted_ascii_name", 'Meera "Mee" Iyer, Jr. (Family)'))
    cases.append(_mime_case("backslash_name", "Rohan \\ Mehta"))
    cases.append(
        _mime_case(
            "long_reply_headers",
            "Meera Iyer",
            in_reply_to="<" + "a" * 60 + "@local>",
            references="<" + "b" * 50 + "@local> <" + "c" * 50 + "@local> <" + "d" * 50 + "@local>",
        )
    )

    # Sweeps across the two limits. Subject is always encoded (the em dash),
    # From is ASCII and folded, or one encoded word for the non-ASCII names.
    for n in range(55, 101):
        cases.append(_mime_case(f"sweep_ascii_{n}", _LONG_ASCII_BASE[:n].rstrip() or "x"))
    for n in range(1, 41):
        cases.append(_mime_case(f"sweep_hindi_{n}", _HINDI_BASE[:n].rstrip()))
    for n in range(1, 31):
        cases.append(_mime_case(f"sweep_emoji_{n}", _EMOJI_BASE[:n].rstrip()))
    for n in range(1, 41):
        cases.append(_mime_case(f"sweep_accent_{n}", _ACCENT_BASE[:n].rstrip()))

    # PAR-07: phone-number names (PAR-06 item 5), a Week chunk, and bodies
    # ending in a newline (PAR-06 item 4). Expected bytes come from Python.
    cases.append(_mime_case("phone_spaced", "+91 98765 43210"))
    cases.append(_mime_case("phone_bare_digits", "919876543210"))
    cases.append(_mime_case("phone_punctuated", "(+91) 98765-43210"))
    cases.append(_mime_case("phone_six_digits_is_a_name", "123456"))
    cases.append(_mime_case("phone_sixteen_digits_is_a_name", "1234567890123456"))
    cases.append(_mime_case("phone_nbsp", "+91\u00a098765\u00a043210"))
    cases.append(_mime_case("phone_ideographic_space", "+91\u300098765\u300043210"))
    cases.append(_mime_case("phone_arabic_indic_digits", "\u0669\u0661\u0669\u0668\u0667\u0666\u0665\u0664\u0663\u0662\u0661\u0660"))
    cases.append(_mime_case("phone_devanagari_digits", "\u096f\u0967 \u096f\u096e\u096d\u096c\u096b\u096a\u0969\u0968\u0967\u0966"))
    cases.append(_mime_case("week_chunk", "Meera Iyer", chunk_size="week"))
    cases.append(_mime_case("count_chunk", "Meera Iyer", chunk_size=2))
    cases.append(_mime_case("hour_chunk", "Meera Iyer", chunk_size="hour"))
    for name, body in [
        ("body_ends_lf", "hello\n"),
        ("body_ends_blank_line", "hello\n\n"),
        ("body_ends_crlf", "hello\r\n"),
        ("body_ends_cr", "hello\r"),
        ("body_only_newline", "\n"),
        ("body_empty", ""),
        ("body_leading_blank", "\nhello"),
        ("body_middle_blank", "a\n\nb"),
        ("body_ends_ls", "hello\u2028"),
        ("body_ends_vt_ff", "a\x0bb\x0c"),
        ("body_ends_nel", "a\x85"),
    ]:
        cases.append(_mime_case(name, "Meera Iyer", messages=_mime_msgs(body=body)))

    return cases


# ---------------------------------------------------------------------------
# PAR-06 edge cases: UTF-7 decode of malformed input, APPENDLIMIT, empty ids
# ---------------------------------------------------------------------------


def _utf7_inputs() -> "list[str]":
    import random

    fixed = [
        "", "&", "&-", "&&-", "&AGE", "&AGE-", "&AG-", "&A-", "&AGEA-", "&AGEAA-", "&!!-", "&AGE&AGE-",
        "a&b&c-", "&AGE=-", "&AGE==-", "&A=-", "&AGEA=-", "&,,,-", "&+/A-", "&AGE/-", "&==-", "&=AGE-",
        "&2D3eAA-", "&2D0-", "&3gA-", "&AGFh-", "&AA-", "&AGE-x&", "x&AGE", "x&AGE-y", "INBOX&AGE",
        "&ZeVnLIqe-", "WhatsApp/&ZeVnLIqe-", "Caf&AOk-", "&AOk", "&AOk=", "&AO", "&AOkA", "&A Gk-",
        "&AGE-&", "&AGE-&-", "&2D3eA-", "&2D3eAAA-", "&AGEAYQ-", "&AGEAYQA-", "&AGEAYQ==-",
    ]
    rng = random.Random(20260930)
    alphabet = "&-AGEk,+/=! 0"
    fuzz = ["".join(rng.choice(alphabet) for _ in range(rng.randint(0, 11))) for _ in range(500)]
    seen: "list[str]" = []
    for item in fixed + fuzz:
        if item not in seen:
            seen.append(item)
    return seen


class _FakeImapConn:
    def __init__(self, capabilities):
        self.capabilities = tuple(capabilities)

    def append(self, folder, flags, internaldate, raw):
        return "OK", [b"Append completed"]


def _transport(host: str, capabilities):
    from src.mail_client import ImapTransport

    conn = _FakeImapConn(capabilities)
    t = ImapTransport(host, 993, "meera.iyer@example.com", "pw-FAKE", connection_factory=lambda: conn)
    t._get_conn()
    return t


def generate_par06_edge_golden() -> None:
    from src.mail_client import _decode_imap_utf7

    utf7 = [{"input": t, "output": _decode_imap_utf7(t)} for t in _utf7_inputs()]

    caps_cases = []
    for host, caps in [
        ("imap.example.com", []),
        ("imap.example.com", ["IMAP4REV1", "APPENDLIMIT=35651584"]),
        ("imap.example.com", ["APPENDLIMIT=0"]),
        ("imap.example.com", ["APPENDLIMIT"]),
        ("imap.example.com", ["APPENDLIMIT="]),
        ("imap.example.com", ["APPENDLIMIT=abc"]),
        ("imap.example.com", ["APPENDLIMIT=-5"]),
        ("imap.example.com", ["appendlimit=1000"]),
        ("imap.example.com", ["APPENDLIMIT=0", "APPENDLIMIT=2000"]),
        ("imap.example.com", ["APPENDLIMIT=99999999999999999999"]),
        ("imap.example.com", ["APPENDLIMIT=9223372036854775807"]),
        ("imap.example.com", ["APPENDLIMIT=9223372036854775808"]),
        ("imap.gmail.com", []),
        ("imap.gmail.com", ["APPENDLIMIT=0"]),
        ("imap.gmail.com", ["APPENDLIMIT=1234"]),
        ("imap.mail.yahoo.com", ["APPENDLIMIT=0"]),
    ]:
        value = _transport(host, caps).max_message_bytes
        caps_cases.append({"host": host, "capabilities": caps, "maxMessageBytes": str(value)})

    insert_cases = []
    for msg_id, thread_id in [
        ("<m1@local>", None), ("<m1@local>", ""), ("<m1@local>", "t1"),
        ("", None), ("", ""), ("", "t1"), (None, None), (None, "t1"),
    ]:
        header = "" if msg_id is None else f"Message-ID: {msg_id}\n"
        raw = (
            "From: a@b.c\nDate: Thu, 01 Jan 2026 10:00:00 +0000\n" + header + "Subject: x\n\nbody\n"
        ).encode("ascii")
        t = _transport("imap.example.com", [])
        out = t.messages_insert(
            {"raw": base64.urlsafe_b64encode(raw).decode("ascii"), "labelIds": ["WhatsApp/x"]},
            thread_id,
        )

        def shape(v: str) -> str:
            return v if v == msg_id or v == thread_id else "GENERATED"

        insert_cases.append({
            "messageId": msg_id,
            "threadId": thread_id,
            "expectedId": shape(out["id"]),
            "expectedThreadId": shape(out["threadId"]),
            "generatedLooksLikeMessageId": out["id"].startswith("<") and out["id"].endswith(">"),
        })

    payload = {"utf7": utf7, "appendLimit": caps_cases, "insert": insert_cases}
    out_path = GOLDEN_DIR / "par06_edge_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes)")


def generate_mime_cases_golden() -> None:
    cases = _mime_cases()
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "duplicate MIME golden case name"
    payload = {"cases": cases}
    out_path = GOLDEN_DIR / "mime_cases_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(cases)} cases)")



# ---------------------------------------------------------------------------
# KT-08: .cmsbackup (src/migration.py) parity goldens
# ---------------------------------------------------------------------------

_MIG_TABLES = (
    "chats", "sync_runs", "message_hashes", "chat_cutoffs", "chat_senders",
    "app_state", "imported_bundles",
)
_MIG_NOW = "2026-10-01T10:00:00"
_MIG_BUNDLE_ID = "00000000-0000-4000-8000-00000000c0de"
# Settings handed to export_bundle: allowed keys, a hostile host, a credential-looking key
# and an unknown one. Only the allowed ones may reach settings.json.
_MIG_EXPORT_SETTINGS = {
    "chunk_size": "week",
    "theme_mode": "dark",
    "imap_provider": "yahoo",
    "imap_port": 993,
    "imap_email": "test@example.com",
    "imap_host": "evil.example.com",
    "app_password": "not-a-real-password",
    "unknown_key": "x",
}


def _mig_dump(db_path: Path) -> dict:
    import sqlite3

    con = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro&immutable=1", uri=True)
    con.row_factory = sqlite3.Row
    try:
        present = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        out = {}
        for table in _MIG_TABLES:
            if table in present:
                out[table] = [dict(r) for r in con.execute(f"SELECT * FROM {table}")]
        return out
    finally:
        con.close()


def _mig_seed_source(root: Path) -> None:
    db = root / "data" / "sync_state.db"
    db.parent.mkdir(parents=True, exist_ok=True)
    state.init_db(db)
    state.upsert_chat("chat-meera", "Meera Iyer", "Meera Iyer.txt", db_path=db)
    state.upsert_chat("chat-rohan", "Rohan Mehta", "Rohan Mehta.txt", db_path=db)
    r1 = state.start_sync_run("chat-meera", db_path=db)
    state.complete_sync_run(r1, "2026-09-30T09:02:00", "m2", 3, 2, 1, db_path=db)
    r2 = state.start_sync_run("chat-meera", "scheduled", db_path=db)
    state.fail_sync_run(r2, "placeholder failure", db_path=db)
    r3 = state.start_sync_run("chat-rohan", db_path=db)
    state.complete_sync_run(r3, "2026-09-30T09:01:00", "r1", 1, 1, 0, db_path=db)
    state.insert_message_hashes(
        [
            ("m1", "chat-meera", "2026-09-30T09:01:00", r1),
            ("m2", "chat-meera", "2026-09-30T09:02:00", r1),
            ("r1", "chat-rohan", "2026-09-30T09:01:00", r3),
        ],
        db_path=db,
    )
    state.set_chat_cutoff("chat-meera", "2026-01-01", db_path=db)
    state.record_chat_senders("chat-meera", {"Meera Iyer": 2, "Rohan Mehta": 1}, "2026-09-30T09:02:00", db_path=db)
    state.set_app_state("owner_override", "Meera Iyer", db_path=db)


def _mig_seed_overlap(root: Path) -> None:
    """A destination that already holds some of the same history, and some of its own."""
    db = root / "data" / "sync_state.db"
    db.parent.mkdir(parents=True, exist_ok=True)
    state.init_db(db)
    state.upsert_chat("chat-meera", "Meera I. (local)", "local.txt", db_path=db)
    state.upsert_chat("chat-asha", "Asha Nair", "Asha Nair.txt", db_path=db)
    # Identical to the bundle's first run (same natural key), so it must not be duplicated.
    r1 = state.start_sync_run("chat-meera", db_path=db)
    state.complete_sync_run(r1, "2026-09-30T09:02:00", "m2", 3, 2, 1, db_path=db)
    r2 = state.start_sync_run("chat-asha", db_path=db)
    state.complete_sync_run(r2, "2026-09-29T08:00:00", "a1", 1, 1, 0, db_path=db)
    state.insert_message_hashes(
        [
            ("m1", "chat-meera", "2026-09-30T09:01:00", r1),
            ("local-only", "chat-meera", "2026-09-30T09:09:00", r1),
            ("a1", "chat-asha", "2026-09-29T08:00:00", r2),
        ],
        db_path=db,
    )
    state.set_chat_cutoff("chat-meera", "2026-06-01", db_path=db)  # the local floor must win


def _mig_checkpointed(db: Path) -> None:
    import sqlite3

    con = sqlite3.connect(db)
    try:
        con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        con.execute("PRAGMA journal_mode = DELETE")
    finally:
        con.close()


def _mig_hostile_bundle(path: Path, settings: dict, bundle_id: str) -> None:
    manifest = {"schema_version": 1, "bundle_id": bundle_id, "created_at": _MIG_NOW,
                "app_version": "placeholder", "counts": {}}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("settings.json", json.dumps(settings, indent=2))


def generate_migration_golden() -> None:
    import datetime as _dt
    import shutil
    import tempfile
    from unittest import mock

    from src import migration

    class _FrozenDatetime(_dt.datetime):
        @classmethod
        def now(cls, tz=None):
            return _dt.datetime.fromisoformat(_MIG_NOW)

    tmp = Path(tempfile.mkdtemp(prefix="cms_migration_golden_"))
    try:
        with mock.patch.object(state, "_now", return_value=_MIG_NOW), \
                mock.patch.object(migration, "datetime", _FrozenDatetime), \
                mock.patch.object(migration.uuid, "uuid4", return_value=_MIG_BUNDLE_ID):
            src_root = tmp / "src"
            _mig_seed_source(src_root)
            _mig_checkpointed(src_root / "data" / "sync_state.db")
            shutil.copyfile(src_root / "data" / "sync_state.db", GOLDEN_DIR / "migration_source.db")

            overlap_root = tmp / "overlap_seed"
            _mig_seed_overlap(overlap_root)
            _mig_checkpointed(overlap_root / "data" / "sync_state.db")
            shutil.copyfile(overlap_root / "data" / "sync_state.db", GOLDEN_DIR / "migration_overlap_seed.db")

            bundle = GOLDEN_DIR / "migration_python_written.cmsbackup"
            export = migration.export_bundle(src_root, bundle, _MIG_EXPORT_SETTINGS, app_version="placeholder")
            _mig_hostile_bundle(
                GOLDEN_DIR / "migration_hostile_gmail.cmsbackup",
                {"imap_provider": "gmail", "imap_host": "evil.example.com", "imap_port": 1,
                 "chunk_size": "day", "app_password": "x"},
                "hostile-gmail",
            )
            _mig_hostile_bundle(
                GOLDEN_DIR / "migration_hostile_outlook.cmsbackup",
                {"imap_provider": "outlook", "imap_host": "evil.example.com", "imap_port": 1},
                "hostile-outlook",
            )
            _mig_hostile_bundle(
                GOLDEN_DIR / "migration_hostile_custom.cmsbackup",
                {"imap_provider": "custom", "imap_host": "evil.example.com", "imap_port": 2993},
                "hostile-custom",
            )

            with zipfile.ZipFile(bundle) as z:
                manifest_text = z.read("manifest.json").decode("utf-8")
                settings_text = z.read("settings.json").decode("utf-8")
                z.extract("sync_state.db", tmp / "bundle_db")
            bundle_dump = _mig_dump(tmp / "bundle_db" / "sync_state.db")

            cases = []
            for name, bundle_name, seed in (
                ("python_written_into_empty", "migration_python_written.cmsbackup", None),
                ("python_written_into_overlap", "migration_python_written.cmsbackup", "overlap"),
                ("hostile_gmail", "migration_hostile_gmail.cmsbackup", None),
                ("hostile_outlook", "migration_hostile_outlook.cmsbackup", None),
                ("hostile_custom", "migration_hostile_custom.cmsbackup", None),
            ):
                root = tmp / ("case_" + name)
                if seed:
                    _mig_seed_overlap(root)
                first = migration.import_bundle(root, GOLDEN_DIR / bundle_name)
                after_first = _mig_dump(root / "data" / "sync_state.db")
                second = migration.import_bundle(root, GOLDEN_DIR / bundle_name)
                after_second = _mig_dump(root / "data" / "sync_state.db")
                cases.append({
                    "name": name,
                    "bundle": bundle_name,
                    "seed": "migration_overlap_seed.db" if seed else None,
                    "first": first,
                    "dump_after_first": after_first,
                    "second": second,
                    "dump_unchanged_by_second": after_first == after_second,
                })
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    golden = {
        "bundle_id": _MIG_BUNDLE_ID,
        "created_at": _MIG_NOW,
        "app_version": "placeholder",
        "export_settings_input": _MIG_EXPORT_SETTINGS,
        "export_counts": export["counts"],
        "export_settings_keys": export["settings_keys"],
        "manifest_text": manifest_text,
        "settings_text": settings_text,
        "bundle_db_dump": bundle_dump,
        "import_cases": cases,
    }
    (GOLDEN_DIR / "migration_golden.json").write_text(
        json.dumps(golden, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n"
    )
    print(f"Wrote {GOLDEN_DIR / 'migration_golden.json'} and the migration_*.cmsbackup / .db fixtures")


# ---------------------------------------------------------------------------
# KT-07: _build_html_mime_message goldens
#
# The builder takes an already-rendered chunk, so the golden pins the RENDERED
# input (html body, inline parts, attachments) as explicit data and the Kotlin
# test builds from exactly that. That keeps the random cids out of the picture
# and makes this a pure builder-parity check; the renderer has its own golden.
# Boundaries are random in Python, so both are normalised IN ORDER OF FIRST
# APPEARANCE (the mixed boundary is in the top header, the related one next),
# which keeps regeneration byte-stable.
# ---------------------------------------------------------------------------

_BOUNDARY_ANY_RE = re.compile(rb"={15}\d+==")
_PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def _normalize_boundaries_ordered(raw: bytes) -> bytes:
    seen: dict[bytes, bytes] = {}

    def _sub(m: "re.Match[bytes]") -> bytes:
        tok = m.group(0)
        if tok not in seen:
            seen[tok] = b"===============NORMALIZED%d==" % (len(seen) + 1)
        return seen[tok]

    return _BOUNDARY_ANY_RE.sub(_sub, raw)


def _html_case(
    name: str,
    display_name: str,
    html_body: str = "<html><body><p>hello</p></body></html>",
    inline: "list[tuple[str, bytes, str]] | None" = None,
    attachments: "list[tuple[str, bytes, str]] | None" = None,
    suffix: str = "",
    in_reply_to: "str | None" = None,
    references: "str | None" = None,
    chunk_size="day",
    messages: "list[ParsedMessage] | None" = None,
) -> dict[str, object]:
    from src.html_renderer import AttachmentPart, InlinePart, RenderedChunk
    from src.mail_client import _build_html_mime_message

    chunk = messages if messages is not None else _mime_msgs()
    inline = inline or []
    attachments = attachments or []
    rendered = RenderedChunk(
        html_body=html_body,
        inline_parts=[InlinePart(cid=c, data=d, mime_type=m) for c, d, m in inline],
        attachments=[AttachmentPart(filename=f, data=d, mime_type=m) for f, d, m in attachments],
    )
    result = _build_html_mime_message(
        display_name=display_name,
        chunk=chunk,
        chunk_size=chunk_size,
        rendered=rendered,
        label_id=f"WhatsApp/{display_name}",
        message_id=FIXTURE_MESSAGE_ID,
        suffix=suffix,
        in_reply_to=in_reply_to,
        references=references,
    )
    raw_bytes = base64.urlsafe_b64decode(result["raw"])
    return {
        "name": name,
        "displayName": display_name,
        "chatId": chunk[0].chat_id,
        "messageId": FIXTURE_MESSAGE_ID,
        "chunkSize": chunk_size,
        "suffix": suffix,
        "inReplyTo": in_reply_to,
        "references": references,
        "messages": [{"sender": m.sender, "body": m.body, "ts": m.timestamp_iso} for m in chunk],
        "htmlBody": html_body,
        "inline": [
            {"cid": c, "mimeType": m, "dataBase64": base64.b64encode(d).decode("ascii")} for c, d, m in inline
        ],
        "attachments": [
            {"filename": f, "mimeType": m, "dataBase64": base64.b64encode(d).decode("ascii")}
            for f, d, m in attachments
        ],
        "eml": _normalize_boundaries_ordered(raw_bytes).decode("ascii"),
    }


def _html_cases() -> "list[dict[str, object]]":
    pdf = b"%PDF-1.4 fake pdf bytes for the golden\n" * 3
    cases: list[dict[str, object]] = []
    cases.append(_html_case("html_plain", "Meera Iyer"))
    cases.append(_html_case("html_long_ascii_name_64", _LONG_ASCII_BASE[:64]))
    cases.append(_html_case("html_hindi_group_name", _HINDI_BASE))
    cases.append(_html_case("html_emoji_name", _EMOJI_BASE))
    cases.append(_html_case("html_phone_name", "+91 98765 43210"))
    cases.append(_html_case("html_quoted_name", 'Meera "Mee" Iyer, Jr. (Family)'))
    cases.append(
        _html_case(
            "html_inline_image",
            "Meera Iyer",
            html_body='<html><body><img src="cid:img-000000000001"></body></html>',
            inline=[("img-000000000001", _PNG_1X1, "image/png")],
        )
    )
    cases.append(
        _html_case(
            "html_two_inline_images_and_pdf",
            "Rohan Mehta",
            html_body='<html><body><img src="cid:img-000000000001"><img src="cid:img-000000000002"></body></html>',
            inline=[
                ("img-000000000001", _PNG_1X1, "image/png"),
                ("img-000000000002", _PNG_1X1 * 40, "image/jpeg"),
            ],
            attachments=[("notes.pdf", pdf, "application/pdf")],
        )
    )
    cases.append(_html_case("html_pdf_attachment", "Meera Iyer", attachments=[("report.pdf", pdf, "application/pdf")]))
    cases.append(
        _html_case(
            "html_audio_attachment",
            "Meera Iyer",
            attachments=[("voice note.opus", b"OggS" + bytes(range(200)), "audio/ogg")],
        )
    )
    cases.append(_html_case("html_empty_attachment", "Meera Iyer", attachments=[("empty.bin", b"", "application/octet-stream")]))
    cases.append(
        _html_case(
            "html_big_attachment_multiline",
            "Meera Iyer",
            attachments=[("big.bin", bytes(range(256)) * 20, "application/octet-stream")],
        )
    )
    for fname_case, fname in [
        ("quote_backslash", 'we"ird\\name.txt'),
        ("semicolon_percent", "a;b%20c.txt"),
        ("long_ascii_100", "x" * 96 + ".pdf"),
        ("hindi", "मीरा रिपोर्ट.pdf"),
        ("hindi_with_newline", "मीरा\nरिपोर्ट.pdf"),
        ("accent", "café menu.pdf"),
        ("emoji", "party \U0001f389.jpg"),
        ("nul_ascii", "a\x00b.txt"),
        ("del_us_ascii", "a\x1fb\x7f.txt"),
        ("hindi_long", "म" * 60 + ".pdf"),
    ]:
        cases.append(
            _html_case(
                f"html_filename_{fname_case}",
                "Meera Iyer",
                attachments=[(fname, pdf, "application/pdf")],
            )
        )
    cases.append(
        _html_case(
            "html_reply_set",
            "Meera Iyer",
            in_reply_to="<anchor-1@local>",
            references="<anchor-0@local> <anchor-1@local>",
        )
    )
    cases.append(_html_case("html_reply_no_references", "Meera Iyer", in_reply_to="<anchor-1@local>"))
    cases.append(_html_case("html_reply_unset", "Meera Iyer", in_reply_to=None, references="<ignored@local>"))
    cases.append(_html_case("html_part_2_of_3", "Meera Iyer", suffix="Part 2/3"))
    cases.append(_html_case("html_part_10_of_12_hindi", _HINDI_BASE, suffix="Part 10/12"))
    cases.append(_html_case("html_week_chunk", "Meera Iyer", chunk_size="week"))
    cases.append(_html_case("html_hour_chunk", "Meera Iyer", chunk_size="hour"))
    cases.append(_html_case("html_count_chunk", "Meera Iyer", chunk_size=2))
    return cases


def _html_refused_filenames() -> "list[str]":
    """ASCII filenames Python 3.13 cannot write; asserts that it really raises."""
    from src.html_renderer import AttachmentPart, RenderedChunk
    from src.mail_client import _build_html_mime_message

    names = [
        "a\nb.txt",
        "a\rb.txt",
        "a\r\nBcc: x@y.z\r\nb.txt",
        "a\x0bb.txt",
        "a\x0cb.txt",
        "a\x1cb.txt",
        "a\x1db.txt",
        "a\x1eb.txt",
    ]
    for fname in names:
        rendered = RenderedChunk(
            html_body="<p>x</p>",
            attachments=[AttachmentPart(filename=fname, data=b"x", mime_type="application/pdf")],
        )
        try:
            _build_html_mime_message("Meera Iyer", _mime_msgs(), "day", rendered, "L", FIXTURE_MESSAGE_ID)
        except Exception:  # noqa: BLE001 - the point is that ANY raise happens
            continue
        raise AssertionError(f"Python 3.13 wrote filename {fname!r} without raising; update the Kotlin refusal list")
    return names


def generate_html_mime_golden() -> None:
    cases = _html_cases()
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "duplicate HTML MIME golden case name"
    payload = {"cases": cases, "refusedFilenames": _html_refused_filenames()}
    out_path = GOLDEN_DIR / "html_mime_golden.json"
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes, {len(cases)} cases)")


def main() -> None:
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)

    # app_version is deliberately left at its default (UNKNOWN_VERSION) --
    # set_app_version() is an Android-only call this script never makes,
    # matching a source checkout that never wired it up, which is also what
    # the Kotlin fixture's default appVersion ("unknown") represents.
    result = _build_mime_message(
        display_name=FIXTURE_DISPLAY_NAME,
        chunk=FIXTURE_CHUNK,
        chunk_size="day",
        label_id=FIXTURE_LABEL_ID,
        message_id=FIXTURE_MESSAGE_ID,
    )
    raw_bytes = base64.urlsafe_b64decode(result["raw"])
    normalized = _normalize_boundary(raw_bytes)
    (GOLDEN_DIR / "mime_message_golden.eml").write_bytes(normalized)

    index = mail_index.build_index(
        FIXTURE_DISPLAY_NAME, FIXTURE_CHUNK, "day", FIXTURE_MESSAGE_ID
    )
    index_raw = mail_index.index_bytes(index)
    (GOLDEN_DIR / "index_golden.json").write_bytes(index_raw)

    print(f"Wrote {GOLDEN_DIR / 'mime_message_golden.eml'} ({len(normalized)} bytes)")
    print(f"Wrote {GOLDEN_DIR / 'index_golden.json'} ({len(index_raw)} bytes)")

    generate_parser_goldens()
    generate_time_of_day_golden()
    generate_state_hash_golden()
    generate_cutoff_golden()
    generate_state_db_golden()
    generate_media_extractor_golden()
    generate_html_renderer_golden()
    generate_mail_index_golden()
    generate_mime_cases_golden()
    generate_html_mime_golden()
    generate_par06_edge_golden()
    generate_migration_golden()


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Regenerate the Kotlin core golden fixtures.")
    parser.add_argument(
        "--out",
        metavar="DIR",
        help="write every golden into DIR instead of the checked-in golden directory "
        "(CI regenerates into a temp dir and diffs against the committed files)",
    )
    args = parser.parse_args()
    if args.out:
        GOLDEN_DIR = Path(args.out).resolve()
    main()
