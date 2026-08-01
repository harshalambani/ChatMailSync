"""Tests for ImapTransport (Road B, phase 1 — IMAP APPEND backend).

Mocks imaplib via a constructor-injected fake connection object (matching
the shape of imaplib.IMAP4: .list()/.create()/.subscribe()/.append() all
returning (typ, data) tuples), rather than monkeypatching imaplib itself —
this keeps the tests close to what a real connection returns instead of
Python's internal call machinery.
"""

import base64
import time
from datetime import datetime
from unittest.mock import MagicMock

import pytest

from src.mail_client import (
    DiscoveryTransport,
    MailTransport,
    MailTransportError,
    ImapTransport,
    RestTransport,
    _build_html_mime_message,
    _insert_with_backoff,
    get_or_create_label,
)
from src.html_renderer import render_chunk
from src.parser import ParsedMessage


class FakeImapConn:
    """Records calls and returns pre-programmed (typ, data) responses,
    standing in for imaplib.IMAP4 / IMAP4_SSL."""

    def __init__(self):
        self.calls = []
        self.list_response = ("OK", [b'(\\HasNoChildren) "/" "INBOX"'])
        self.create_response = ("OK", [b"Completed"])
        self.subscribe_response = ("OK", [b"Completed"])
        self.append_response = ("OK", [b"Completed"])
        # Set to an exception instance to make the *next* call to that method
        # raise instead of returning its canned response.
        self.raise_on = {}

    def _respond(self, name, response):
        exc = self.raise_on.pop(name, None)
        if exc is not None:
            raise exc
        return response

    def list(self, directory, pattern):
        self.calls.append(("list", directory, pattern))
        return self._respond("list", self.list_response)

    def create(self, mailbox):
        self.calls.append(("create", mailbox))
        return self._respond("create", self.create_response)

    def subscribe(self, mailbox):
        self.calls.append(("subscribe", mailbox))
        return self._respond("subscribe", self.subscribe_response)

    def append(self, mailbox, flags, date_time, message):
        self.calls.append(("append", mailbox, flags, date_time, message))
        return self._respond("append", self.append_response)

    def logout(self):
        self.calls.append(("logout",))
        return ("BYE", [b"Logging out"])


def _make_transport(conn=None, **kwargs):
    conn = conn or FakeImapConn()
    transport = ImapTransport(
        host="imap.example.com",
        port=993,
        email="me@example.com",
        password="s3cret",
        connection_factory=lambda: conn,
        **kwargs,
    )
    return transport, conn


def _sample_chunk():
    return [
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2019, 5, 3, 10, 15, 0),
            sender="Alice",
            body="hello from the past",
        )
    ]


def _real_message_body(label_id="WhatsApp/Alice", message_id="<anchor@local>"):
    """Build a real message via _build_html_mime_message / render_chunk, the
    same pipeline push_chunks() uses — so a test built on this breaks if that
    function's encoding ever changes, per the brief."""
    chunk = _sample_chunk()
    rendered = render_chunk(chunk, "Alice", None, "")
    return _build_html_mime_message(
        "Alice", chunk, "day", rendered, label_id, message_id
    )


# ---------------------------------------------------------------------------
# Conformance
# ---------------------------------------------------------------------------

def test_all_transports_satisfy_gmail_transport_protocol():
    discovery = DiscoveryTransport(MagicMock())
    rest = RestTransport("token")
    imap, _ = _make_transport()
    assert isinstance(discovery, MailTransport)
    assert isinstance(rest, MailTransport)
    assert isinstance(imap, MailTransport)


# ---------------------------------------------------------------------------
# labels_list
# ---------------------------------------------------------------------------

def test_labels_list_parses_quoted_name_with_space_and_sets_id_equal_to_name():
    transport, conn = _make_transport()
    conn.list_response = (
        "OK",
        [
            b'(\\HasNoChildren) "/" "INBOX"',
            b'(\\HasNoChildren) "/" "WhatsApp/Alice Smith"',
        ],
    )

    result = transport.labels_list()

    names = {lbl["name"] for lbl in result["labels"]}
    assert "WhatsApp/Alice Smith" in names
    for lbl in result["labels"]:
        assert lbl["id"] == lbl["name"]


def test_labels_list_detects_and_caches_non_slash_delimiter():
    transport, conn = _make_transport()
    conn.list_response = ("OK", [b'(\\HasNoChildren) "." "INBOX"'])

    transport.labels_list()

    assert transport._delimiter == "."


def test_labels_list_translates_wire_delimiter_back_to_slash():
    transport, conn = _make_transport()
    conn.list_response = (
        "OK",
        [
            b'(\\HasNoChildren) "." "INBOX"',
            b'(\\HasNoChildren) "." "WhatsApp.Alice"',
        ],
    )

    result = transport.labels_list()

    names = {lbl["name"] for lbl in result["labels"]}
    assert "WhatsApp/Alice" in names
    assert "WhatsApp.Alice" not in names


# ---------------------------------------------------------------------------
# labels_create
# ---------------------------------------------------------------------------

def test_labels_create_success_returns_id_equal_to_name():
    transport, conn = _make_transport()

    result = transport.labels_create({"name": "WhatsApp/Alice"})

    # The label id/name contract stays on the canonical, unquoted string --
    # quoting/mUTF-7 is purely a wire-argument concern (see
    # test_labels_create_quotes_name_with_space_on_the_wire below for what
    # actually goes out over the wire).
    assert result == {"id": "WhatsApp/Alice"}
    assert ("create", '"WhatsApp/Alice"') in conn.calls
    assert ("subscribe", '"WhatsApp/Alice"') in conn.calls


def test_labels_create_already_exists_is_success_not_error():
    transport, conn = _make_transport()
    conn.create_response = ("NO", [b"[ALREADYEXISTS] Mailbox already exists."])

    result = transport.labels_create({"name": "WhatsApp/Alice"})

    assert result == {"id": "WhatsApp/Alice"}


def test_labels_create_translates_name_to_wire_delimiter():
    transport, conn = _make_transport()
    conn.list_response = ("OK", [b'(\\HasNoChildren) "." "INBOX"'])
    transport.labels_list()  # learns "." delimiter

    transport.labels_create({"name": "WhatsApp/Alice"})

    assert ("create", '"WhatsApp.Alice"') in conn.calls


def test_labels_create_real_no_error_is_permanent_not_retried():
    transport, conn = _make_transport()
    conn.create_response = ("NO", [b"[TRYCREATE] invalid mailbox"])

    with pytest.raises(MailTransportError) as exc_info:
        transport.labels_create({"name": "Bad/Name"})
    assert exc_info.value.status not in (429, 500, 502, 503, 504)


# ---------------------------------------------------------------------------
# Wire syntax: quoting + modified UTF-7 (RFC 3501 SS4.3 / SS5.1.3)
#
# The tests above only ever exercised names with no spaces or special
# characters, so they never would have caught the real production bug: a
# mailbox name containing a space (e.g. a WhatsApp contact called "Parity
# Test") produces the wire command "CREATE WhatsApp/Parity Test", which the
# server parses as two arguments and rejects with BAD. These tests assert on
# the *exact string* handed to the fake imaplib methods, so they fail loudly
# if quoting/encoding is ever lost again.
# ---------------------------------------------------------------------------

def test_labels_create_quotes_name_with_space_on_the_wire():
    transport, conn = _make_transport()

    transport.labels_create({"name": "WhatsApp/Parity Test"})

    assert ("create", '"WhatsApp/Parity Test"') in conn.calls
    assert ("subscribe", '"WhatsApp/Parity Test"') in conn.calls


def test_labels_create_escapes_backslash_and_quote_on_the_wire():
    transport, conn = _make_transport()

    transport.labels_create({"name": 'WhatsApp/Weird\\"Name'})

    assert ("create", '"WhatsApp/Weird\\\\\\"Name"') in conn.calls


def test_labels_create_encodes_non_ascii_name_as_modified_utf7():
    transport, conn = _make_transport()

    # An emoji chat name -- routine for real WhatsApp contacts. Expected
    # value computed independently via the RFC 3501 SS5.1.3 algorithm:
    # U+1F600 ("\U0001F600") is a surrogate pair in UTF-16BE
    # (0xD83D 0xDE00) -> base64 "2D3eAA==" -> "/" swapped for "," and "="
    # padding stripped -> "2D3eAA".
    transport.labels_create({"name": "WhatsApp/\U0001F600"})

    assert ("create", '"WhatsApp/&2D3eAA-"') in conn.calls


def test_labels_create_plain_ascii_name_still_works_unquoted_content():
    transport, conn = _make_transport()

    result = transport.labels_create({"name": "WhatsApp/Alice"})

    assert result == {"id": "WhatsApp/Alice"}
    assert ("create", '"WhatsApp/Alice"') in conn.calls


def test_labels_list_round_trips_encoded_non_ascii_name_back_to_canonical():
    transport, conn = _make_transport()
    conn.list_response = (
        "OK",
        [b'(\\HasNoChildren) "/" "WhatsApp/&2D3eAA-"'],
    )

    result = transport.labels_list()

    names = {lbl["name"] for lbl in result["labels"]}
    assert "WhatsApp/\U0001F600" in names
    for lbl in result["labels"]:
        assert lbl["id"] == lbl["name"]


def test_non_slash_delimiter_combines_correctly_with_utf7_encoding():
    transport, conn = _make_transport()
    conn.list_response = ("OK", [b'(\\HasNoChildren) "." "INBOX"'])
    transport.labels_list()  # learns "." delimiter

    transport.labels_create({"name": "WhatsApp/\U0001F600 Alice"})

    # Delimiter substitution ("/" -> ".") happens before mUTF-7 encoding, so
    # the literal "." from the hierarchy separator passes through the
    # printable-ASCII path untouched while the emoji is encoded, and the
    # plain-ASCII " Alice" suffix stays outside the &...- block.
    assert ("create", '"WhatsApp.&2D3eAA- Alice"') in conn.calls


# ---------------------------------------------------------------------------
# messages_insert — CRLF normalization
# ---------------------------------------------------------------------------

def test_messages_insert_produces_crlf_terminated_bytes_from_real_message():
    transport, conn = _make_transport()
    body = _real_message_body()

    transport.messages_insert(body)

    appended_bytes = conn.calls[-1][4]
    assert b"\r\n" in appended_bytes
    # No lone (non-CRLF-preceded) newline should survive normalization.
    assert b"\n" not in appended_bytes.replace(b"\r\n", b"")


# ---------------------------------------------------------------------------
# messages_insert — threadId contract
# ---------------------------------------------------------------------------

def test_messages_insert_returns_stable_thread_id_on_first_insert():
    transport, conn = _make_transport()
    body = _real_message_body(message_id="<anchor-123@local>")

    result = transport.messages_insert(body, thread_id=None)

    assert result["threadId"] is not None
    assert result["threadId"] == "<anchor-123@local>"


def test_messages_insert_echoes_thread_id_on_subsequent_insert():
    transport, conn = _make_transport()
    body = _real_message_body(message_id="<chunk-2@local>")

    result = transport.messages_insert(body, thread_id="<anchor-123@local>")

    assert result["threadId"] == "<anchor-123@local>"


def test_messages_insert_prefers_appenduid_when_available():
    transport, conn = _make_transport()
    conn.append_response = ("OK", [b"[APPENDUID 38505 3955] APPEND completed"])
    body = _real_message_body()

    result = transport.messages_insert(body)

    assert result["id"] == "38505-3955"


def test_messages_insert_falls_back_to_message_id_without_appenduid():
    transport, conn = _make_transport()
    conn.append_response = ("OK", [b"Completed"])
    body = _real_message_body(message_id="<anchor-fallback@local>")

    result = transport.messages_insert(body)

    assert result["id"] == "<anchor-fallback@local>"


# ---------------------------------------------------------------------------
# messages_insert — internaldate fidelity (human-mandated: must be derived
# from the Date: header, never from the clock/upload time)
# ---------------------------------------------------------------------------

def test_messages_insert_internaldate_comes_from_date_header_not_clock():
    transport, conn = _make_transport()
    body = _real_message_body()  # Date header is 2019-05-03 (see _sample_chunk)

    before = time.time()
    transport.messages_insert(body)

    appended = conn.calls[-1]
    date_time_arg = appended[3]

    assert date_time_arg is not None
    # 2019-05-03 must be nowhere near "now" -- proves the value wasn't
    # defaulted to upload time.
    assert date_time_arg < before - 1000
    expected_epoch = datetime(2019, 5, 3, 10, 15, 0).timestamp()
    # Allow for the timezone normalization the Date header (+0000 / UTC)
    # goes through -- just assert it's the historical date, not "now".
    assert abs(date_time_arg - expected_epoch) < 24 * 3600 * 2


def test_messages_insert_sets_seen_flag_by_default():
    transport, conn = _make_transport()
    body = _real_message_body()

    transport.messages_insert(body)

    appended = conn.calls[-1]
    flags_arg = appended[2]
    assert "\\Seen" in flags_arg


# ---------------------------------------------------------------------------
# Error mapping
# ---------------------------------------------------------------------------

def test_login_failure_maps_to_permanent_non_retryable_status():
    import imaplib

    def factory():
        raise MailTransportError(
            "IMAP login failed for me@example.com @ imap.example.com:993 — "
            "provider blocked app-password IMAP. Server said: boom",
            status=401,
        )

    transport = ImapTransport(
        host="imap.example.com", port=993, email="me@example.com",
        password="s3cret", connection_factory=factory,
    )

    with pytest.raises(MailTransportError) as exc_info:
        transport.labels_list()
    assert exc_info.value.status not in (429, 500, 502, 503, 504)
    assert "s3cret" not in str(exc_info.value)


def test_transient_server_error_maps_to_503():
    transport, conn = _make_transport()
    conn.append_response = ("NO", [b"[UNAVAILABLE] Server temporarily unavailable"])
    body = _real_message_body()

    with pytest.raises(MailTransportError) as exc_info:
        transport.messages_insert(body)
    assert exc_info.value.status == 503


def test_password_never_appears_in_any_exception_message():
    import imaplib

    transport, conn = _make_transport()
    conn.raise_on["append"] = imaplib.IMAP4.error("auth failed with s3cret in it")
    body = _real_message_body()

    with pytest.raises(MailTransportError) as exc_info:
        transport.messages_insert(body)
    assert "s3cret" not in str(exc_info.value)


def test_imap4_error_is_not_blanket_retried():
    import imaplib

    transport, conn = _make_transport()
    conn.raise_on["append"] = imaplib.IMAP4.error("BAD command syntax")
    body = _real_message_body()

    with pytest.raises(MailTransportError) as exc_info:
        transport.messages_insert(body)
    assert exc_info.value.status not in (429, 500, 502, 503, 504)


def test_abort_reconnects_once_transparently():
    import imaplib

    conn = FakeImapConn()
    factories = []

    def factory():
        c = FakeImapConn() if factories else conn
        factories.append(c)
        return c

    transport = ImapTransport(
        host="imap.example.com", port=993, email="me@example.com",
        password="s3cret", connection_factory=factory,
    )
    conn.raise_on["list"] = imaplib.IMAP4.abort("connection dropped")

    result = transport.labels_list()

    assert result == {"labels": [{"name": "INBOX", "id": "INBOX"}]}
    assert len(factories) == 2  # reconnected exactly once


# ---------------------------------------------------------------------------
# Retry round-trip through _insert_with_backoff
# ---------------------------------------------------------------------------

def test_insert_with_backoff_retries_transient_imap_error_then_succeeds(monkeypatch):
    monkeypatch.setattr("src.mail_client.time.sleep", lambda *_: None)
    monkeypatch.setattr("src.mail_client.API_CALL_DELAY_SECONDS", 0)

    transport, conn = _make_transport()
    body = _real_message_body()

    # Monkeypatch the transport's own _call so the first "append" attempt
    # returns a transient NO response and the second succeeds, exercising
    # _insert_with_backoff's retry loop end-to-end.
    call_count = {"n": 0}
    real_call = transport._call

    def flaky_call(method, *args):
        if method == "append":
            call_count["n"] += 1
            if call_count["n"] == 1:
                return ("NO", [b"[UNAVAILABLE] try later"])
        return real_call(method, *args)

    transport._call = flaky_call

    result = _insert_with_backoff(transport, body)

    assert result["id"] is not None
    assert call_count["n"] >= 1


# ---------------------------------------------------------------------------
# End-to-end-ish: get_or_create_label against the mocked ImapTransport
# ---------------------------------------------------------------------------

def test_get_or_create_label_end_to_end_with_imap_transport():
    transport, conn = _make_transport()
    conn.list_response = ("OK", [b'(\\HasNoChildren) "/" "INBOX"'])

    label_id = get_or_create_label(transport, "Alice Smith")

    assert label_id == "WhatsApp/Alice Smith"
    created = [c for c in conn.calls if c[0] == "create"]
    assert ("create", '"WhatsApp"') in created or ("create", '"WhatsApp/Alice Smith"') in created


def test_get_or_create_label_returns_existing_without_recreating():
    transport, conn = _make_transport()
    conn.list_response = (
        "OK",
        [
            b'(\\HasNoChildren) "/" "WhatsApp"',
            b'(\\HasNoChildren) "/" "WhatsApp/Alice Smith"',
        ],
    )

    label_id = get_or_create_label(transport, "Alice Smith")

    assert label_id == "WhatsApp/Alice Smith"
    assert not any(c[0] == "create" for c in conn.calls)
