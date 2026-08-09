"""Per-email traceability index: which parsed messages went into which email.

Archived emails are *nested* - one email carries a whole chunk of messages,
anywhere from one to a few thousand depending on the chunk size. Nothing in the
email said which messages those were, and nothing locally said it either: the
`message_hashes` table records (hash, chat_id, message_ts, run_id) and has no
column pointing at the email a message landed in. So "where did this message
go?" was not slow or awkward to answer, it was unanswerable.

This module produces a small JSON document that is attached to every archived
email and lists exactly what that email contains, one entry per message. Two
things then become possible that were not before:

  1. A human can open the attachment and read it. That is why it is an
     attachment and not a header - headers are invisible in every normal mail
     client, and the point is to be reachable without special tooling.
  2. The mailbox carries enough information to rebuild local sync state, so
     losing the state DB (phone reset, uninstall, "clear storage") stops
     meaning "every chat gets re-archived a second time".

Nothing in this codebase *reads* these indexes yet, and nothing here opens a
mailbox to fetch one - that is deliberate, and the recovery side is a separate
piece of work. What matters is that the write side cannot be added
retroactively: the hash is taken over the *parsed* fields, while the email body
is a human-readable rendering of them, so an already-sent email cannot have its
index reconstructed reliably. Every email sent without one is permanently
outside the scheme, which is why this ships before the thing that consumes it.

The document is a pure function of its inputs - deliberately no "generated_at"
or other clock/environment value - so the same chunk always produces identical
bytes, and a diff between two builds means a real difference.
"""

from __future__ import annotations

import json
from email import encoders as _encoders
from email.mime.base import MIMEBase
from typing import TYPE_CHECKING, Any, Union

from src.app_version import app_version
from src.state import compute_message_hash

if TYPE_CHECKING:  # pragma: no cover - import cycle only matters to type checkers
    from src.parser import ParsedMessage


# Bumped only on a breaking change to the document's shape. A reader that does
# not recognise the value should refuse the file rather than guess at it.
INDEX_SCHEMA = 1

INDEX_FILENAME = "wamailsync-index.json"
INDEX_MIME_TYPE = ("application", "json")

# Small, fixed-size facts also go on headers, so a future reader can identify
# and filter our messages with a HEADER.FIELDS fetch instead of pulling whole
# bodies. The per-message hashes deliberately do NOT go here: Gmail caps a
# custom X- header value at 32 KB, which is about 500 hashes, and a week's
# chunk of a busy group chat can exceed that. A header index would therefore
# silently truncate on exactly the largest chats - and a truncated index that
# looks complete is worse than none, because a rebuild would conclude those
# messages were never archived and send them a second time.
HEADER_VERSION = "X-WAMailSync-Version"
HEADER_CHAT = "X-WAMailSync-Chat"
HEADER_COUNT = "X-WAMailSync-Count"
HEADER_INDEX = "X-WAMailSync-Index"

# Upper bound on one serialised entry after base64 transfer encoding. Used only
# to keep size-based chunk splitting honest - see estimate_index_bytes(). A
# real entry runs ~150 bytes plus a third for base64; 256 leaves headroom for
# long sender names without needing to render the index twice.
_ESTIMATED_BYTES_PER_ENTRY = 256


def estimate_index_bytes(message_count: int) -> int:
    """Conservative upper bound on the attached index size for N messages.

    Callers deciding whether a chunk fits under a size ceiling need this
    *before* the index exists, since the index is built from the final chunk.
    Overestimating is safe (an extra split); underestimating is not.
    """
    return 512 + _ESTIMATED_BYTES_PER_ENTRY * message_count


def build_index(
    display_name: str,
    chunk: "list[ParsedMessage]",
    chunk_size: Union[str, int],
    message_id: str,
) -> dict[str, Any]:
    """Build the index document for one email's worth of messages.

    `message_id` is the parent email's own Message-ID. Storing it inside that
    same email looks redundant, and is not: once a few hundred of these are
    downloaded into a folder, it is the only thing tying each index back to the
    email it describes - and the email's Message-ID is the thread anchor that
    cannot be recomputed from anything held locally.
    """
    return {
        "schema": INDEX_SCHEMA,
        "chat_id": chunk[0].chat_id,
        "display_name": display_name,
        "message_id": message_id,
        "chunk": chunk_size,
        "count": len(chunk),
        "first_ts": chunk[0].timestamp_iso,
        "last_ts": chunk[-1].timestamp_iso,
        "app_version": app_version(),
        "messages": [
            {
                "n": n,
                "ts": msg.timestamp_iso,
                "sender": msg.sender,
                "hash": compute_message_hash(
                    msg.chat_id, msg.timestamp_iso, msg.sender, msg.body
                ),
            }
            for n, msg in enumerate(chunk, start=1)
        ],
    }


def index_bytes(index: dict[str, Any]) -> bytes:
    """Serialise the index: metadata one key per line, then one message per line.

    The layout is assembled rather than handed to json.dumps(indent=...)
    because the two obvious settings are both wrong for this file. Compact
    output puts a few thousand messages on one enormous line, and indented
    output spends five lines and roughly triple the bytes on every message. One
    message per line is what makes the attachment answer "is my message in
    here?" by eye or with a plain text search, which is the entire reason it is
    a readable attachment.

    Every key and value still goes through json.dumps individually, so quoting
    and escaping are the library's job, not this function's - only the
    surrounding structure is assembled here. test_mail_index round-trips the
    output through json.loads to keep that honest.
    """
    meta = {key: value for key, value in index.items() if key != "messages"}

    lines = ["{"]
    for key, value in meta.items():
        lines.append(f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)},")
    lines.append('  "messages": [')

    entries = [
        json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
        for entry in index["messages"]
    ]
    for i, entry in enumerate(entries):
        trailing = "," if i < len(entries) - 1 else ""
        lines.append(f"    {entry}{trailing}")

    lines.append("  ]")
    lines.append("}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def build_index_part(
    display_name: str,
    chunk: "list[ParsedMessage]",
    chunk_size: Union[str, int],
    message_id: str,
) -> MIMEBase:
    """The index as a ready-to-attach MIME part."""
    payload = index_bytes(build_index(display_name, chunk, chunk_size, message_id))

    main_type, sub_type = INDEX_MIME_TYPE
    part = MIMEBase(main_type, sub_type)
    part.set_payload(payload)
    _encoders.encode_base64(part)
    # add_header() rather than direct assignment so the filename goes through
    # RFC 2231 encoding, matching how ordinary attachments are built.
    part.add_header("Content-Disposition", "attachment", filename=INDEX_FILENAME)
    return part


def _header_safe(value: str) -> str:
    """Collapse anything that could break out of a header value into spaces.

    chat_id comes from an export filename, so it is user-influenced. The
    authoritative copy is in the JSON body, where quoting is handled properly;
    the header is a convenience, and a convenience is never worth a header
    injection.
    """
    return " ".join(value.split())


def apply_index_headers(
    root: Any,
    chunk: "list[ParsedMessage]",
) -> None:
    """Stamp the small fixed-size identity headers onto a built message."""
    root[HEADER_VERSION] = str(INDEX_SCHEMA)
    root[HEADER_CHAT] = _header_safe(chunk[0].chat_id)
    root[HEADER_COUNT] = str(len(chunk))
    root[HEADER_INDEX] = INDEX_FILENAME
