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

from src import mail_index
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


if __name__ == "__main__":
    main()
