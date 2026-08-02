from unittest.mock import MagicMock

import pytest
from googleapiclient.errors import HttpError

from src.mail_client import (
    DiscoveryTransport,
    MailTransportError,
    RestTransport,
    set_token,
)


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
