"""Tests for the staged connection test (check_connection / _text).

The point of the feature is that the five stages fail *separately* and say
so, so the tests break each stage on its own and assert on which stage was
named -- not just that something failed. The network is never touched: each
probe helper and the transport's connection factory are monkeypatched.
"""

import socket
import ssl

import pytest

from src import mail_client
from src.config import LABEL_PARENT
from src.mail_client import (
    MailTransportError,
    check_connection,
    check_connection_text,
    format_connection_result,
)

HOST = "imap.example.com"
PORT = 993
EMAIL = "someone@example.com"
PASSWORD = "abcd efgh ijkl mnop"


class _FakeSocket:
    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True


@pytest.fixture
def all_probes_pass(monkeypatch):
    """DNS/TCP/TLS all succeed; stages 4-5 are left to each test."""
    sock = _FakeSocket()
    monkeypatch.setattr(mail_client, "_probe_dns", lambda host, port: None)
    monkeypatch.setattr(mail_client, "_probe_tcp", lambda host, port: sock)
    monkeypatch.setattr(mail_client, "_probe_tls", lambda s, host: None)
    return sock


def _patch_transport(monkeypatch, *, login_exc=None, create_exc=None):
    """Replace ImapTransport with a stub whose login/create outcomes are set
    per test, so stages 4 and 5 can fail independently of each other."""
    closed = {"value": False}

    class StubTransport:
        def __init__(self, host, port, email, password):
            self.host, self.port = host, port
            self.email, self.password = email, password

        def _get_conn(self):
            if login_exc is not None:
                raise login_exc
            return object()

        def labels_create(self, body):
            if create_exc is not None:
                raise create_exc
            return {"id": body["name"]}

        def close(self):
            closed["value"] = True

    monkeypatch.setattr(mail_client, "ImapTransport", StubTransport)
    return closed


# ---------------------------------------------------------------------------
# Missing input
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "host,port,email,password",
    [
        ("", PORT, EMAIL, PASSWORD),
        (HOST, 0, EMAIL, PASSWORD),
        (HOST, PORT, "", PASSWORD),
        (HOST, PORT, EMAIL, ""),
        (HOST, "not-a-port", EMAIL, PASSWORD),
    ],
)
def test_missing_details_never_touch_the_network(monkeypatch, host, port, email, password):
    def boom(*_a, **_k):
        raise AssertionError("probe attempted with incomplete details")

    monkeypatch.setattr(mail_client, "_probe_dns", boom)
    result = check_connection(host, port, email, password)
    assert result["ok"] is False
    assert "Fill in" in result["message"]


# ---------------------------------------------------------------------------
# One stage at a time
# ---------------------------------------------------------------------------


def test_dns_failure_names_dns(monkeypatch):
    def fail(host, port):
        raise socket.gaierror("Name or service not known")

    monkeypatch.setattr(mail_client, "_probe_dns", fail)
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert result["failed_stage"] == "DNS"
    assert HOST in result["message"]
    assert [s["name"] for s in result["stages"]] == ["DNS"]


def test_tcp_failure_names_tcp_and_the_port(monkeypatch):
    monkeypatch.setattr(mail_client, "_probe_dns", lambda host, port: None)

    def fail(host, port):
        raise OSError("connection refused")

    monkeypatch.setattr(mail_client, "_probe_tcp", fail)
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert result["failed_stage"] == "TCP"
    assert str(PORT) in result["message"]
    assert [s["ok"] for s in result["stages"]] == [True, False]


def test_tls_failure_names_tls_and_says_nothing_was_sent(monkeypatch):
    sock = _FakeSocket()
    monkeypatch.setattr(mail_client, "_probe_dns", lambda host, port: None)
    monkeypatch.setattr(mail_client, "_probe_tcp", lambda host, port: sock)

    def fail(s, host):
        raise ssl.SSLError("certificate verify failed")

    monkeypatch.setattr(mail_client, "_probe_tls", fail)
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert result["failed_stage"] == "TLS"
    assert "no password was sent" in result["message"]
    # The probe socket must not be left open when TLS is what failed.
    assert sock.closed is True


def test_login_failure_names_login(monkeypatch, all_probes_pass):
    _patch_transport(
        monkeypatch, login_exc=MailTransportError("IMAP login failed", status=401)
    )
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert result["failed_stage"] == "LOGIN"
    assert [s["name"] for s in result["stages"]] == ["DNS", "TCP", "TLS", "LOGIN"]


def test_folder_failure_names_folder_and_the_parent(monkeypatch, all_probes_pass):
    _patch_transport(
        monkeypatch, create_exc=MailTransportError("CREATE failed", status=550)
    )
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert result["failed_stage"] == "FOLDER"
    assert LABEL_PARENT in result["message"]


def test_success_reports_all_five_stages(monkeypatch, all_probes_pass):
    closed = _patch_transport(monkeypatch)
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert result["ok"] is True
    assert result["failed_stage"] is None
    assert [s["name"] for s in result["stages"]] == list(mail_client.CONNECTION_STAGES)
    assert all(s["ok"] for s in result["stages"])
    assert closed["value"] is True, "the test connection must be logged out"


def test_transport_is_closed_even_when_login_fails(monkeypatch, all_probes_pass):
    closed = _patch_transport(
        monkeypatch, login_exc=MailTransportError("nope", status=401)
    )
    check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert closed["value"] is True


# ---------------------------------------------------------------------------
# Microcopy
# ---------------------------------------------------------------------------


def test_gmail_rejection_names_the_app_password(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    result = check_connection("imap.gmail.com", PORT, "someone@gmail.com", PASSWORD)
    assert "16-character app password" in result["message"]
    assert "will always be rejected" in result["message"]


def test_workspace_domain_on_gmail_host_still_gets_the_gmail_hint(
    monkeypatch, all_probes_pass
):
    # A Workspace address has no "gmail" in it; the host is the giveaway.
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    result = check_connection("imap.gmail.com", PORT, "someone@acme.co", PASSWORD)
    assert "16-character app password" in result["message"]


def test_outlook_rejection_mentions_the_administrator(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    result = check_connection(
        "outlook.office365.com", PORT, "someone@acme.co", PASSWORD
    )
    assert "administrator" in result["message"]


def test_unknown_provider_gets_the_generic_hint(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert "IMAP switched on" in result["message"]


def test_non_401_login_failure_is_not_dressed_up_as_a_password_problem(
    monkeypatch, all_probes_pass
):
    _patch_transport(
        monkeypatch, login_exc=MailTransportError("server closed connection", status=503)
    )
    result = check_connection("imap.gmail.com", PORT, "someone@gmail.com", PASSWORD)
    assert "16-character app password" not in result["message"]
    assert "signing in failed" in result["message"]


# ---------------------------------------------------------------------------
# The password must never come back out
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "stage_kwargs",
    [
        {"login_exc": MailTransportError(f"login failed: {PASSWORD}", status=401)},
        {"login_exc": MailTransportError(f"boom {PASSWORD}", status=500)},
        {"create_exc": MailTransportError(f"CREATE denied for {PASSWORD}", status=550)},
    ],
)
def test_password_is_never_echoed_back(monkeypatch, all_probes_pass, stage_kwargs):
    _patch_transport(monkeypatch, **stage_kwargs)
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert PASSWORD not in result["message"]
    assert PASSWORD not in format_connection_result(result)


def test_password_is_never_echoed_back_from_a_probe_failure(monkeypatch):
    monkeypatch.setattr(mail_client, "_probe_dns", lambda host, port: None)

    def fail(host, port):
        raise OSError(f"refused while sending {PASSWORD}")

    monkeypatch.setattr(mail_client, "_probe_tcp", fail)
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert PASSWORD not in result["message"]


# ---------------------------------------------------------------------------
# The flattened string both front-ends actually show
# ---------------------------------------------------------------------------


def test_text_prefixes_the_failed_stage_label(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    text = check_connection_text(HOST, PORT, EMAIL, PASSWORD)
    assert text.startswith("Signing in failed.")


def test_text_on_success_has_no_stage_prefix(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch)
    text = check_connection_text(HOST, PORT, EMAIL, PASSWORD)
    assert text.startswith("All good")
    assert "failed" not in text


def test_every_stage_has_a_label():
    # A stage without a label would render as a bare "Connecting failed."
    for stage in mail_client.CONNECTION_STAGES:
        assert mail_client._STAGE_LABELS[stage]


# ---------------------------------------------------------------------------
# The Windows worker entry points
# ---------------------------------------------------------------------------


def test_worker_test_connection_posts_the_shared_text(monkeypatch):
    import queue

    import gui_worker

    monkeypatch.setattr(
        gui_worker,
        "check_connection",
        lambda h, p, e, pw: {
            "ok": False,
            "stage": "LOGIN",
            "failed_stage": "LOGIN",
            "message": "nope",
            "stages": [],
        },
    )
    q: "queue.Queue" = queue.Queue()
    gui_worker.test_imap_connection(q, HOST, PORT, EMAIL, PASSWORD)
    event = q.get_nowait()
    assert event["type"] == "test_result"
    assert event["ok"] is False
    assert event["msg"] == "Signing in failed. nope"


def test_worker_test_connection_never_raises(monkeypatch):
    import queue

    import gui_worker

    def boom(*_a, **_k):
        raise RuntimeError("unexpected")

    monkeypatch.setattr(gui_worker, "check_connection", boom)
    q: "queue.Queue" = queue.Queue()
    gui_worker.test_imap_connection(q, HOST, PORT, EMAIL, PASSWORD)
    event = q.get_nowait()
    assert event["ok"] is False
    assert "unexpected" in event["msg"]


def test_save_path_rejection_gets_the_gmail_hint(monkeypatch, tmp_path):
    """A failed Save says the same thing [Test connection] would.

    Before this, a Gmail user who pasted their account password got the raw
    imaplib rejection back and no hint that the *kind* of password was
    wrong.
    """
    import queue

    import gui_worker

    def boom(h, p, e, pw):
        raise MailTransportError("IMAP login failed", status=401)

    monkeypatch.setattr(gui_worker, "build_imap_transport", boom)
    q: "queue.Queue" = queue.Queue()
    gui_worker.connect_imap(q, "imap.gmail.com", 993, "me@gmail.com", PASSWORD)
    event = q.get_nowait()
    assert event["type"] == "auth_error"
    assert "16-character app password" in event["msg"]
    assert PASSWORD not in event["msg"]
