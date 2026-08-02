from datetime import datetime
from typing import Optional
from unittest.mock import MagicMock

import pytest
from googleapiclient.errors import HttpError

from src.mail_client import (
    DiscoveryTransport,
    MailTransportError,
    RestTransport,
    chunk_messages,
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
