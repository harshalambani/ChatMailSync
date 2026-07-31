"""Tests for Road B phase 2 -- mail-backend selection (gui.py / gui_worker.py).

Covers brief section 3.6:
  - mail_backend defaults to gmail_oauth; an existing settings file without
    the key loads the default; a saved value round-trips.
  - check_auth_status() under each backend, including "no credentials stored".
  - Backend selection picks the right transport builder, and the OAuth path
    is unaffected when mail_backend == "gmail_oauth".
  - Credentials file: written with the expected shape, and the app password
    never appears in any log record or exception string produced by a
    failed connect.

...plus the human-mandated one-time "IMAP backend now available" notice:
shown once, flag persists, not shown again, not shown on a fresh install.

gui.py and gui_worker.py each resolve their own module-level _SETTINGS_FILE
as Path(__file__).parent / "data" / ".settings.json" (deliberately NOT
routed through src.config.set_root() -- see gui_worker.py's docstring), so
these tests monkeypatch that constant directly on each module rather than
using the tmp_root fixture from conftest.py.
"""

import json
import queue

import pytest

import gui
import gui_worker
from src.config import IMAP_PROVIDERS, MAIL_BACKEND_GMAIL_OAUTH, MAIL_BACKEND_IMAP


# ---------------------------------------------------------------------------
# gui.py settings round-trip + one-time backend notice
# ---------------------------------------------------------------------------

@pytest.fixture
def settings_file(tmp_path, monkeypatch):
    path = tmp_path / "data" / ".settings.json"
    monkeypatch.setattr(gui, "_SETTINGS_FILE", path)
    return path


def test_mail_backend_defaults_to_gmail_oauth_when_no_file(settings_file):
    settings = gui._load_settings()
    assert settings["mail_backend"] == MAIL_BACKEND_GMAIL_OAUTH == "gmail_oauth"
    assert settings["backend_notice_shown"] is False


def test_existing_settings_file_without_key_loads_default(settings_file):
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps({"chunk_size": "hour"}))
    settings = gui._load_settings()
    assert settings["mail_backend"] == MAIL_BACKEND_GMAIL_OAUTH
    assert settings["chunk_size"] == "hour"


def test_saved_mail_backend_value_round_trips(settings_file):
    settings = gui._load_settings()
    settings["mail_backend"] = MAIL_BACKEND_IMAP
    settings["imap_email"] = "me@example.com"
    gui._save_settings(settings)

    reloaded = gui._load_settings()
    assert reloaded["mail_backend"] == MAIL_BACKEND_IMAP
    assert reloaded["imap_email"] == "me@example.com"


def test_backend_notice_shown_once_flag_persists_not_shown_again(settings_file):
    settings = gui._load_settings()

    # Existing install: has a prior settings file -> eligible for the notice.
    assert gui._should_show_backend_notice(settings, True, False) is True

    # Simulate the app marking it shown and persisting that.
    settings["backend_notice_shown"] = True
    gui._save_settings(settings)

    reloaded = gui._load_settings()
    assert reloaded["backend_notice_shown"] is True
    # Once persisted, never shown again -- regardless of prior-state inputs.
    assert gui._should_show_backend_notice(reloaded, True, False) is False
    assert gui._should_show_backend_notice(reloaded, True, True) is False


def test_backend_notice_not_shown_on_fresh_install(settings_file):
    settings = gui._load_settings()
    # Fresh install: no prior settings file and no prior token.json.
    assert gui._should_show_backend_notice(settings, False, False) is False


def test_backend_notice_shown_for_prior_token_only(settings_file):
    """A user who only ever had token.json (no settings file yet written)
    still counts as "prior state" and should see the notice."""
    settings = gui._load_settings()
    assert gui._should_show_backend_notice(settings, False, True) is True


# ---------------------------------------------------------------------------
# gui_worker.py: check_auth_status() per backend
# ---------------------------------------------------------------------------

@pytest.fixture
def worker_paths(tmp_path, monkeypatch):
    auth_dir = tmp_path / "auth"
    data_dir = tmp_path / "data"
    auth_dir.mkdir()
    data_dir.mkdir()
    credentials_file = auth_dir / "credentials.json"
    token_file = auth_dir / "token.json"
    imap_credentials_file = auth_dir / "imap_credentials.json"
    settings_file = data_dir / ".settings.json"

    monkeypatch.setattr(gui_worker, "CREDENTIALS_FILE", credentials_file)
    monkeypatch.setattr(gui_worker, "TOKEN_FILE", token_file)
    monkeypatch.setattr(gui_worker, "IMAP_CREDENTIALS_FILE", imap_credentials_file)
    monkeypatch.setattr(gui_worker, "_SETTINGS_FILE", settings_file)

    return {
        "credentials": credentials_file,
        "token": token_file,
        "imap_credentials": imap_credentials_file,
        "settings": settings_file,
    }


def _write_settings(settings_file, **overrides):
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps(overrides))


def test_check_auth_status_gmail_no_credentials(worker_paths):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_GMAIL_OAUTH)
    valid, text = gui_worker.check_auth_status()
    assert valid is False
    assert text == "No credentials.json"


def test_check_auth_status_gmail_no_token(worker_paths):
    worker_paths["credentials"].write_text("{}")
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_GMAIL_OAUTH)
    valid, text = gui_worker.check_auth_status()
    assert valid is False
    assert text == "Not connected"


def test_check_auth_status_imap_no_credentials_stored(worker_paths):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    valid, text = gui_worker.check_auth_status()
    assert valid is False
    assert text == "Not connected"


def test_check_auth_status_imap_with_saved_credentials(worker_paths):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password": "hunter2-app-pw",
    }))
    valid, text = gui_worker.check_auth_status()
    assert valid is True
    assert text == "Connected (me@example.com)"
    assert "hunter2-app-pw" not in text


def test_check_auth_status_imap_corrupt_credentials_file_no_password_leak(worker_paths):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    # Deliberately corrupt/undecodable JSON so the except branch fires.
    worker_paths["imap_credentials"].write_text("not json {password: hunter2-app-pw")
    valid, text = gui_worker.check_auth_status()
    assert valid is False
    assert "hunter2-app-pw" not in text


# ---------------------------------------------------------------------------
# gui_worker.py: build_transport_for_active_backend()
# ---------------------------------------------------------------------------

def test_build_transport_gmail_oauth_calls_build_service(worker_paths, monkeypatch):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_GMAIL_OAUTH)
    sentinel_service = object()
    called = {}

    def fake_build_service():
        called["yes"] = True
        return sentinel_service

    monkeypatch.setattr(gui_worker, "build_service", fake_build_service)
    transport = gui_worker.build_transport_for_active_backend()
    assert called.get("yes") is True
    assert isinstance(transport, gui_worker.DiscoveryTransport)


def test_build_transport_imap_missing_credentials_raises_runtimeerror(worker_paths):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    with pytest.raises(RuntimeError, match="no saved app password"):
        gui_worker.build_transport_for_active_backend()


def test_build_transport_imap_uses_saved_credentials(worker_paths, monkeypatch):
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password": "hunter2-app-pw",
    }))

    captured = {}

    def fake_build_imap_transport(host, port, email, password):
        captured.update(host=host, port=port, email=email, password=password)
        return "FAKE_TRANSPORT"

    monkeypatch.setattr(gui_worker, "build_imap_transport", fake_build_imap_transport)
    transport = gui_worker.build_transport_for_active_backend()
    assert transport == "FAKE_TRANSPORT"
    assert captured == {
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password": "hunter2-app-pw",
    }


# ---------------------------------------------------------------------------
# gui_worker.py: connect_gmail() / connect_imap() event contract
# ---------------------------------------------------------------------------

def test_connect_gmail_posts_auth_ok_with_transport(worker_paths, monkeypatch):
    sentinel_service = object()
    monkeypatch.setattr(gui_worker, "build_service", lambda: sentinel_service)
    q: queue.Queue = queue.Queue()
    gui_worker.connect_gmail(q)
    event = q.get_nowait()
    assert event["type"] == "auth_ok"
    assert isinstance(event["transport"], gui_worker.DiscoveryTransport)


def test_connect_gmail_posts_auth_error_on_failure(worker_paths, monkeypatch):
    def boom():
        raise FileNotFoundError("credentials.json missing")

    monkeypatch.setattr(gui_worker, "build_service", boom)
    q: queue.Queue = queue.Queue()
    gui_worker.connect_gmail(q)
    event = q.get_nowait()
    assert event["type"] == "auth_error"
    assert "credentials.json" in event["msg"]


class _FakeSucceedingImapTransport:
    def labels_list(self):
        return []


def test_connect_imap_success_persists_credentials_and_posts_transport(worker_paths, monkeypatch):
    fake_transport = _FakeSucceedingImapTransport()
    monkeypatch.setattr(gui_worker, "build_imap_transport", lambda h, p, e, pw: fake_transport)

    q: queue.Queue = queue.Queue()
    gui_worker.connect_imap(q, "imap.gmail.com", 993, "me@example.com", "hunter2-app-pw")

    event = q.get_nowait()
    assert event["type"] == "auth_ok"
    assert event["transport"] is fake_transport

    # Credentials file shape.
    saved = json.loads(worker_paths["imap_credentials"].read_text())
    assert saved == {
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password": "hunter2-app-pw",
    }


class _FakeLoginFailIMAP4SSL:
    """Stands in for imaplib.IMAP4_SSL: constructs fine, but .login() raises
    imaplib.IMAP4.error with the password embedded in the server-response
    text -- the same shape a real "wrong password" reply takes. Used to
    drive ImapTransport._default_connection_factory()'s real code path (the
    one that actually calls _strip_secret), rather than a hand-rolled fake
    that would only prove the test's own assumption."""

    def __init__(self, host, port):
        pass

    def login(self, email, password):
        import imaplib as _imaplib
        raise _imaplib.IMAP4.error(
            f"[AUTHENTICATIONFAILED] Invalid credentials for {email}, "
            f"password sent was {password}"
        )


def test_connect_imap_failure_never_leaks_password_and_does_not_persist(worker_paths, monkeypatch):
    """End-to-end through the real production path: connect_imap() ->
    build_imap_transport() (not mocked) -> ImapTransport with
    connection_factory=None -> _default_connection_factory() -> a real
    imaplib.IMAP4.error whose original text contains the password. Only
    imaplib.IMAP4_SSL is faked (to avoid a real network call); the
    scrubbing itself is exercised for real, proving _strip_secret actually
    runs on this path rather than trusting it does."""
    import src.gmail_client as gmail_client_mod

    password = "hunter2-app-pw"
    monkeypatch.setattr(gmail_client_mod.imaplib, "IMAP4_SSL", _FakeLoginFailIMAP4SSL)

    q: queue.Queue = queue.Queue()
    gui_worker.connect_imap(q, "imap.gmail.com", 993, "me@example.com", password)

    event = q.get_nowait()
    assert event["type"] == "auth_error"
    assert password not in event["msg"]
    # Nothing should have been written to the credentials file on failure.
    assert not worker_paths["imap_credentials"].exists()


def test_save_imap_credentials_file_shape(worker_paths):
    gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", "hunter2-app-pw")
    saved = json.loads(worker_paths["imap_credentials"].read_text())
    assert set(saved.keys()) == {"host", "port", "email", "password"}
    assert saved["host"] == "imap.gmail.com"
    assert saved["port"] == 993
    assert saved["email"] == "me@example.com"
    assert saved["password"] == "hunter2-app-pw"


# ---------------------------------------------------------------------------
# IMAP_PROVIDERS sanity (used by the Settings-window provider dropdown)
# ---------------------------------------------------------------------------

def test_imap_providers_all_have_label_host_port():
    assert "gmail" in IMAP_PROVIDERS
    assert "custom" in IMAP_PROVIDERS
    for key, info in IMAP_PROVIDERS.items():
        assert "label" in info and info["label"]
        assert "port" in info and info["port"] == 993
        if key == "custom":
            assert info["host"] is None
        else:
            assert info["host"]
