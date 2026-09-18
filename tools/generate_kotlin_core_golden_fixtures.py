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
import re
from datetime import datetime
from pathlib import Path

from src import mail_index
from src.mail_client import _build_mime_message
from src.parser import ParsedMessage

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


if __name__ == "__main__":
    main()
