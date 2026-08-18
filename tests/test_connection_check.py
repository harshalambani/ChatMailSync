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
# TLS
# ---------------------------------------------------------------------------


def test_tls_context_verifies_and_refuses_tls_below_1_2():
    # The app password travels in the clear inside LOGIN, so an unverified
    # or downgraded handshake hands it to whoever answered. Asserted rather
    # than assumed because both of the interpreters this ships inside move
    # their own defaults independently.
    ctx = mail_client.imap_tls_context()
    assert ctx.check_hostname is True
    assert ctx.verify_mode == ssl.CERT_REQUIRED
    assert ctx.minimum_version >= ssl.TLSVersion.TLSv1_2


def test_the_probe_and_the_real_transport_share_one_tls_context(monkeypatch):
    # A probe laxer than the real connection would pass and then leave the
    # user with a sync that fails -- and a probe stricter than it would
    # block a connection that actually works.
    calls = []
    sock = _FakeSocket()

    class _FakeContext:
        def wrap_socket(self, s, server_hostname=None):
            calls.append(server_hostname)
            return sock

    monkeypatch.setattr(mail_client, "imap_tls_context", _FakeContext)
    mail_client._probe_tls(sock, HOST)
    assert calls == [HOST], "_probe_tls must use the shared context, not its own"


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


@pytest.mark.parametrize(
    "host, email",
    [
        ("imap.notgmail.com.example.net", "someone@acme.co"),
        ("imap.acme.co", "someone@gmail.com.phish.example"),
        ("gmail.com.example.net", "someone@acme.co"),
    ],
)
def test_lookalike_domains_do_not_get_the_gmail_hint(
    monkeypatch, all_probes_pass, host, email
):
    # The hint is matched on the domain, not on "gmail.com" appearing
    # anywhere: sending someone to generate a Google app password for a
    # provider that does not issue them is worse than saying nothing.
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    result = check_connection(host, PORT, email, PASSWORD)
    assert "16-character app password" not in result["message"]


def test_googlemail_address_still_gets_the_gmail_hint(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    result = check_connection("imap.acme.co", PORT, "someone@googlemail.com", PASSWORD)
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


def test_the_stage_plan_matches_what_actually_runs(monkeypatch, all_probes_pass):
    # The plan is drawn before the check starts, so a plan that disagreed with
    # the run would show the user a list that never fills in.
    _patch_transport(monkeypatch)
    plan = mail_client.connection_stage_plan()
    result = check_connection(HOST, PORT, EMAIL, PASSWORD)
    assert [p["name"] for p in plan] == [s["name"] for s in result["stages"]]
    assert [p["label"] for p in plan] == [s["label"] for s in result["stages"]]


def test_every_stage_has_a_label():
    # A stage without a label would render as a bare "Connecting failed."
    for stage in mail_client.CONNECTION_STAGES:
        assert mail_client._STAGE_LABELS[stage]


# ---------------------------------------------------------------------------
# Live stage reporting (on_stage)
# ---------------------------------------------------------------------------


def test_on_stage_receives_every_stage_in_order(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch)
    seen = []
    result = check_connection(
        HOST, PORT, EMAIL, PASSWORD, on_stage=lambda n, l, ok: seen.append((n, l, ok))
    )
    assert [n for n, _, _ in seen] == list(mail_client.CONNECTION_STAGES)
    assert all(ok for _, _, ok in seen)
    # The label is handed over too, so a caller never has to reach into
    # _STAGE_LABELS to render the line it was just told about.
    assert [l for _, l, _ in seen] == [
        mail_client._STAGE_LABELS[n] for n in mail_client.CONNECTION_STAGES
    ]
    # Same stages, same order, as the dict that comes back at the end.
    assert [n for n, _, _ in seen] == [s["name"] for s in result["stages"]]


def test_on_stage_stops_where_the_check_stops(monkeypatch, all_probes_pass):
    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    seen = []
    check_connection(
        HOST, PORT, EMAIL, PASSWORD, on_stage=lambda n, l, ok: seen.append((n, ok))
    )
    assert seen == [("DNS", True), ("TCP", True), ("TLS", True), ("LOGIN", False)]


def test_a_listener_object_is_called_through_its_onStage_method(
    monkeypatch, all_probes_pass
):
    # Android cannot hand a Python callable across the Chaquopy bridge, so it
    # passes a Kotlin StageListener instead: not callable, but it has the
    # method. Modelled here by an object that would raise if it were called.
    _patch_transport(monkeypatch)

    class _Listener:
        def __init__(self):
            self.seen = []

        def onStage(self, name, label, ok):  # noqa: N802 -- Kotlin's name.
            self.seen.append(name)

    listener = _Listener()
    result = check_connection(HOST, PORT, EMAIL, PASSWORD, on_stage=listener)
    assert listener.seen == list(mail_client.CONNECTION_STAGES)
    assert result["ok"] is True


def test_a_broken_listener_cannot_fail_a_good_connection(monkeypatch, all_probes_pass):
    # A progress indicator is never worth the connection. A UI thread that has
    # gone away mid-check must not turn a working account into a failed one.
    _patch_transport(monkeypatch)

    def boom(*_a):
        raise RuntimeError("the UI went away")

    result = check_connection(HOST, PORT, EMAIL, PASSWORD, on_stage=boom)
    assert result["ok"] is True
    assert [s["name"] for s in result["stages"]] == list(mail_client.CONNECTION_STAGES)


def test_without_a_listener_nothing_changes(monkeypatch, all_probes_pass):
    # on_stage is strictly additional: the returned dict is byte-for-byte what
    # every existing caller already gets.
    _patch_transport(monkeypatch)
    plain = check_connection(HOST, PORT, EMAIL, PASSWORD)
    _patch_transport(monkeypatch)
    watched = check_connection(HOST, PORT, EMAIL, PASSWORD, on_stage=lambda *_a: None)
    assert plain == watched


def test_incomplete_details_report_no_stages_at_all(monkeypatch):
    # The early return never records a stage, so it must never emit one
    # either -- a wizard would otherwise tick "Finding the server" green for
    # a check that never left the building.
    seen = []
    result = check_connection("", PORT, EMAIL, PASSWORD, on_stage=lambda *a: seen.append(a))
    assert seen == []
    assert result["stages"] == []


# ---------------------------------------------------------------------------
# The Windows worker entry points
# ---------------------------------------------------------------------------


def test_worker_test_connection_posts_the_shared_text(monkeypatch):
    import queue

    import gui_worker

    monkeypatch.setattr(
        gui_worker,
        "check_connection",
        lambda h, p, e, pw, on_stage=None: {
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


def test_worker_posts_each_stage_before_the_result(monkeypatch, all_probes_pass):
    """The wizard's live tick-list, end to end through the real check.

    Ordering is the point, not just presence: a stage event arriving after
    the result would tick a line green on a panel the user has already been
    moved off.
    """
    import queue

    import gui_worker

    _patch_transport(monkeypatch, login_exc=MailTransportError("bad", status=401))
    q: "queue.Queue" = queue.Queue()
    gui_worker.test_imap_connection(q, HOST, PORT, EMAIL, PASSWORD)

    events = []
    while True:
        try:
            events.append(q.get_nowait())
        except queue.Empty:
            break

    assert [e["type"] for e in events] == [
        "test_stage", "test_stage", "test_stage", "test_stage", "test_result",
    ]
    assert [e["name"] for e in events[:-1]] == ["DNS", "TCP", "TLS", "LOGIN"]
    assert [e["ok"] for e in events[:-1]] == [True, True, True, False]
    assert events[0]["label"] == "Finding the server"
    assert events[-1]["ok"] is False
    # And the password never rides along on any of them.
    assert all(PASSWORD not in str(e) for e in events)


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
