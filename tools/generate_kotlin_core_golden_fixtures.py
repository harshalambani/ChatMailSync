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
from datetime import datetime
from pathlib import Path

from dateutil import parser as dateutil_parser

from src import mail_index, state
from src.mail_client import _build_mime_message
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
        fixture_path.write_text(text, encoding="utf-8")
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
        fixture_path.write_text(text, encoding="utf-8")
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
    generate_state_db_golden()


if __name__ == "__main__":
    main()
