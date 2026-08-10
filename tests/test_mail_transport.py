import base64
from datetime import datetime
from typing import Optional
from unittest.mock import MagicMock

import pytest
from googleapiclient.errors import HttpError

from src.mail_client import (
    DiscoveryTransport,
    MailTransportError,
    MessageTooLargeError,
    RestTransport,
    _status_for_imap_text,
    chunk_messages,
    is_too_large,
    push_chunks,
    set_token,
)
from src.parser import ParsedMessage


def _http_error(status: int) -> HttpError:
    resp = MagicMock()
    resp.status = status
    return HttpError(resp, b"boom")


# ---------------------------------------------------------------------------
# DiscoveryTransport
# ---------------------------------------------------------------------------

def _make_service_mock():
    service = MagicMock()
    return service


def test_discovery_transport_labels_list():
    service = _make_service_mock()
    service.users().labels().list().execute.return_value = {"labels": [{"name": "WhatsApp", "id": "L1"}]}

    transport = DiscoveryTransport(service)
    result = transport.labels_list()

    assert result == {"labels": [{"name": "WhatsApp", "id": "L1"}]}
    service.users().labels().list.assert_called_with(userId="me")


def test_discovery_transport_labels_create():
    service = _make_service_mock()
    service.users().labels().create().execute.return_value = {"id": "L2", "name": "WhatsApp/Alice"}

    transport = DiscoveryTransport(service)
    result = transport.labels_create({"name": "WhatsApp/Alice"})

    assert result["id"] == "L2"
    service.users().labels().create.assert_called_with(userId="me", body={"name": "WhatsApp/Alice"})


def test_discovery_transport_messages_insert_merges_thread_id():
    service = _make_service_mock()
    service.users().messages().insert().execute.return_value = {"id": "m1", "threadId": "t1"}

    transport = DiscoveryTransport(service)
    result = transport.messages_insert({"raw": "xyz"}, thread_id="t1")

    assert result == {"id": "m1", "threadId": "t1"}
    _, kwargs = service.users().messages().insert.call_args
    assert kwargs["body"] == {"raw": "xyz", "threadId": "t1"}


def test_discovery_transport_wraps_http_error():
    service = _make_service_mock()
    service.users().labels().list().execute.side_effect = _http_error(429)

    transport = DiscoveryTransport(service)
    with pytest.raises(MailTransportError) as exc_info:
        transport.labels_list()
    assert exc_info.value.status == 429


# ---------------------------------------------------------------------------
# RestTransport
# ---------------------------------------------------------------------------

def test_rest_transport_labels_list(requests_mock):
    requests_mock.get(
        "https://gmail.googleapis.com/gmail/v1/users/me/labels",
        json={"labels": [{"name": "WhatsApp", "id": "L1"}]},
    )
    transport = set_token("fake-token")
    assert isinstance(transport, RestTransport)

    result = transport.labels_list()

    assert result == {"labels": [{"name": "WhatsApp", "id": "L1"}]}
    assert requests_mock.last_request.headers["Authorization"] == "Bearer fake-token"


def test_rest_transport_labels_create(requests_mock):
    requests_mock.post(
        "https://gmail.googleapis.com/gmail/v1/users/me/labels",
        json={"id": "L2", "name": "WhatsApp/Alice"},
    )
    transport = RestTransport("fake-token")

    result = transport.labels_create({"name": "WhatsApp/Alice"})

    assert result["id"] == "L2"
    assert requests_mock.last_request.json() == {"name": "WhatsApp/Alice"}


def test_rest_transport_messages_insert_merges_thread_id_and_query_param(requests_mock):
    requests_mock.post(
        "https://gmail.googleapis.com/gmail/v1/users/me/messages",
        json={"id": "m1", "threadId": "t1"},
    )
    transport = RestTransport("fake-token")

    result = transport.messages_insert({"raw": "xyz"}, thread_id="t1")

    assert result == {"id": "m1", "threadId": "t1"}
    assert requests_mock.last_request.json() == {"raw": "xyz", "threadId": "t1"}
    assert requests_mock.last_request.qs.get("internaldatesource") == ["dateheader"]


def test_rest_transport_raises_gmail_transport_error_on_http_error(requests_mock):
    requests_mock.get(
        "https://gmail.googleapis.com/gmail/v1/users/me/labels",
        status_code=429,
        json={"error": "rate limited"},
    )
    transport = RestTransport("fake-token")

    with pytest.raises(MailTransportError) as exc_info:
        transport.labels_list()
    assert exc_info.value.status == 429


# ---------------------------------------------------------------------------
# push_chunks: on_chunk contract
#
# This is the exact guarantee sync_manager relies on to record message
# hashes incrementally (see the bug this was written to catch): on_chunk
# must fire once per email, strictly *after* that email's insert/APPEND has
# succeeded, and must hand back precisely the ParsedMessage list that just
# landed in the mailbox — not the full message set, not a count.
# ---------------------------------------------------------------------------


class _FakeChunkTransport:
    """Minimal MailTransport that succeeds every messages_insert() call up
    to (but not including) `fail_at` (1-based email index), then raises a
    plain RuntimeError — standing in for a hard process interruption, not a
    retryable transport error (retryable errors are covered separately by
    _insert_with_backoff's own tests; here we want an immediate, clean stop
    so the test isn't at the mercy of backoff delays)."""

    def __init__(self, fail_at: Optional[int] = None):
        self.fail_at = fail_at
        self.calls = 0

    def labels_list(self) -> dict:
        return {"labels": []}

    def labels_create(self, body: dict) -> dict:
        return {"id": "L1", "name": body.get("name", "")}

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict:
        self.calls += 1
        if self.fail_at is not None and self.calls == self.fail_at:
            raise RuntimeError("simulated interruption")
        return {"id": f"m{self.calls}", "threadId": thread_id or f"t{self.calls}"}


def _make_message(day: int, chat_id: str = "test_chat") -> ParsedMessage:
    return ParsedMessage(
        chat_id=chat_id,
        timestamp=datetime(2025, 3, day, 9, 0, 0),
        sender="Alice",
        body=f"message on day {day}",
    )


def test_push_chunks_on_chunk_fires_only_after_successful_write():
    # Five messages on five distinct days -> five single-message "day" chunks,
    # so on_chunk should fire five times, each with exactly one message.
    messages = [_make_message(d) for d in range(10, 15)]
    chunks = chunk_messages(messages, "day")
    transport = _FakeChunkTransport()

    seen: list[tuple[int, int, int, int, list[ParsedMessage]]] = []

    def on_chunk(i, total, done, total_msgs, chunk_msgs):
        seen.append((i, total, done, total_msgs, list(chunk_msgs)))

    push_chunks(
        transport=transport,
        display_name="Alice",
        chunks=chunks,
        label_id="L1",
        chunk_size="day",
        on_chunk=on_chunk,
    )

    assert len(seen) == 5
    # Each callback's chunk_messages must be exactly the one message that
    # was actually delivered in that email, not the whole message list.
    for idx, (i, total, done, total_msgs, chunk_msgs) in enumerate(seen):
        assert i == idx + 1
        assert total == 5
        assert total_msgs == 5
        assert done == idx + 1
        assert chunk_msgs == [messages[idx]]


def test_push_chunks_stops_delivering_on_chunk_after_a_failed_write():
    # Fail on the 3rd email (of 5): on_chunk must have fired exactly twice
    # (for the two emails that truly landed), and the exception from the
    # failed 3rd write must propagate rather than being swallowed.
    messages = [_make_message(d) for d in range(10, 15)]
    chunks = chunk_messages(messages, "day")
    transport = _FakeChunkTransport(fail_at=3)

    seen: list[list[ParsedMessage]] = []

    def on_chunk(i, total, done, total_msgs, chunk_msgs):
        seen.append(list(chunk_msgs))

    with pytest.raises(RuntimeError):
        push_chunks(
            transport=transport,
            display_name="Alice",
            chunks=chunks,
            label_id="L1",
            chunk_size="day",
            on_chunk=on_chunk,
        )

    assert seen == [[messages[0]], [messages[1]]]


# ---------------------------------------------------------------------------
# Message size: recognising a refusal, and recovering from one
#
# Written against the live failure of 2026-08-10, where Gmail answered a
# 1,505-message chat with "[TOOBIG] Message too large" and the whole file was
# recorded as failed (synced=0). Two separate things have to hold: we must
# recognise that refusal for what it is, and we must be able to finish the push
# anyway by sending smaller emails.
# ---------------------------------------------------------------------------


def test_toobig_maps_to_its_own_status_not_a_generic_failure():
    # 413 rather than the 400s, because it is permanent for THIS message and
    # says nothing at all about the next one.
    assert _status_for_imap_text(
        "APPEND command error: BAD [b'[TOOBIG] Message too large.']"
    ) == 413
    assert _status_for_imap_text("Message size exceeds fixed maximum") == 413


def test_is_too_large_recognises_size_refusals_only():
    assert is_too_large(MessageTooLargeError("nope"))
    assert is_too_large(MailTransportError("[TOOBIG] Message too large", status=413))
    assert is_too_large(MailTransportError("maximum message size is 25MB", status=None))
    # Everything else must stay a hard failure. A false positive here re-sends
    # a message the server may already have taken, which duplicates the user's
    # archive -- strictly worse than one failed chat.
    assert not is_too_large(MailTransportError("Invalid credentials", status=401))
    assert not is_too_large(MailTransportError("server busy", status=503))
    assert not is_too_large(RuntimeError("[TOOBIG] Message too large"))


class _SizeLimitedTransport:
    """A transport that refuses anything over `wire_ceiling` bytes, the way a
    real provider does: at submission, with nothing stored."""

    def __init__(self, wire_ceiling: int, advertised: Optional[int] = None):
        self.wire_ceiling = wire_ceiling
        self.max_message_bytes = advertised if advertised is not None else 25_000_000
        self.calls = 0
        self.rejections = 0
        self.accepted_sizes: list[int] = []

    def labels_list(self) -> dict:
        return {"labels": []}

    def labels_create(self, body: dict) -> dict:
        return {"id": "L1", "name": body.get("name", "")}

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict:
        self.calls += 1
        # What the *provider* sees, which is the decoded MIME message -- the
        # "raw" field is base64 of it for the Gmail API's sake and is a third
        # larger. ImapTransport decodes it before APPEND for the same reason.
        size = len(base64.urlsafe_b64decode(body["raw"]))
        if size > self.wire_ceiling:
            self.rejections += 1
            raise MessageTooLargeError(
                "APPEND: APPEND command error: BAD [b'[TOOBIG] Message too large.']"
            )
        self.accepted_sizes.append(size)
        return {"id": f"m{self.calls}", "threadId": thread_id or "t1"}


def _same_day_messages(count: int) -> list[ParsedMessage]:
    """`count` messages on one day, so they all land in a single day-chunk."""
    return [
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2025, 3, 14, 9, 0, 0),
            sender="Alice",
            body=f"message number {i} " + ("padding " * 40),
        )
        for i in range(count)
    ]


def test_a_declared_limit_splits_the_chunk_before_anything_is_sent():
    messages = _same_day_messages(60)
    chunks = chunk_messages(messages, "day")
    assert len(chunks) == 1  # one day, one chunk -- the split must come from size

    transport = _SizeLimitedTransport(wire_ceiling=10_000_000, advertised=20_000)

    results = push_chunks(
        transport=transport,
        display_name="Alice",
        chunks=chunks,
        label_id="L1",
        chunk_size="day",
    )

    assert len(results) > 1
    assert transport.rejections == 0  # split up-front, never refused
    assert max(transport.accepted_sizes) <= 20_000


def test_a_refusal_in_flight_is_recovered_by_re_splitting():
    # The server's real ceiling is far below what it advertises -- exactly the
    # 2026-08-10 case, where Gmail advertises no APPENDLIMIT at all and our
    # projection was the only thing standing between us and a refusal.
    messages = _same_day_messages(40)
    chunks = chunk_messages(messages, "day")
    transport = _SizeLimitedTransport(wire_ceiling=6_000, advertised=25_000_000)

    delivered: list[ParsedMessage] = []

    def on_chunk(i, total, done, total_msgs, chunk_msgs):
        delivered.extend(chunk_msgs)

    results = push_chunks(
        transport=transport,
        display_name="Alice",
        chunks=chunks,
        label_id="L1",
        chunk_size="day",
        on_chunk=on_chunk,
    )

    # It refused at least once and still finished...
    assert transport.rejections >= 1
    assert len(results) > 1
    # ...with every message delivered exactly once, in order. Nothing was
    # stored by a refused append, so a re-send cannot duplicate.
    assert delivered == messages
    assert max(transport.accepted_sizes) <= 6_000


def test_one_refusal_teaches_the_rest_of_the_run():
    # The ceiling ratchets down after a rejection, so the run does not
    # rediscover the same limit on every oversized chunk. With 8 separate days
    # of the same size, only the first should ever be refused.
    messages = [
        ParsedMessage(
            chat_id="test_chat",
            timestamp=datetime(2025, 3, day, 9, 0, 0),
            sender="Alice",
            body=f"day {day} " + ("padding " * 60),
        )
        for day in range(10, 18)
        for _ in range(6)
    ]
    chunks = chunk_messages(messages, "day")
    assert len(chunks) == 8

    transport = _SizeLimitedTransport(wire_ceiling=5_000, advertised=25_000_000)

    push_chunks(
        transport=transport,
        display_name="Alice",
        chunks=chunks,
        label_id="L1",
        chunk_size="day",
    )

    # One day pays for the discovery; the other seven are pre-split.
    assert transport.rejections <= 2


def test_a_single_message_the_server_will_not_take_still_fails():
    # The recovery must not become an infinite loop when there is nothing left
    # to split and no media to drop.
    messages = _same_day_messages(1)
    chunks = chunk_messages(messages, "day")
    transport = _SizeLimitedTransport(wire_ceiling=10, advertised=25_000_000)

    with pytest.raises(MailTransportError):
        push_chunks(
            transport=transport,
            display_name="Alice",
            chunks=chunks,
            label_id="L1",
            chunk_size="day",
        )
