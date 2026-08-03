"""Tests for Road B phase 2 -- mail-backend selection (gui.py / gui_worker.py).

Covers brief section 3.6:
  - mail_backend defaults to imap on a fresh install; a settings file written
    before the key existed is pinned back to gmail_oauth when a token.json is
    present (the upgrade guard) and takes the new default when it isn't; a
    saved value round-trips.
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
import src.config
from src import secret_store
from src.config import IMAP_PROVIDERS, MAIL_BACKEND_GMAIL_OAUTH, MAIL_BACKEND_IMAP


# ---------------------------------------------------------------------------
# gui.py settings round-trip + one-time backend notice
# ---------------------------------------------------------------------------

@pytest.fixture
def token_file(tmp_path, monkeypatch):
    """Control whether an OAuth token.json 'exists', for the upgrade guard.

    Points src.config.TOKEN_FILE at a path under tmp_path that is absent
    until a test creates it -- otherwise resolve_mail_backend() would see the
    developer's real auth/token.json and the result would depend on whose
    machine the suite runs on.
    """
    path = tmp_path / "auth" / "token.json"
    monkeypatch.setattr(src.config, "TOKEN_FILE", path)
    return path


@pytest.fixture
def settings_file(tmp_path, monkeypatch, token_file):
    path = tmp_path / "data" / ".settings.json"
    monkeypatch.setattr(gui, "_SETTINGS_FILE", path)
    return path


def test_mail_backend_defaults_to_imap_when_no_file(settings_file):
    settings = gui._load_settings()
    assert settings["mail_backend"] == MAIL_BACKEND_IMAP == "imap"
    assert settings["backend_notice_shown"] is False


def test_settings_file_without_key_takes_new_default_when_no_token(settings_file):
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps({"chunk_size": "hour"}))
    settings = gui._load_settings()
    assert settings["mail_backend"] == MAIL_BACKEND_IMAP
    assert settings["chunk_size"] == "hour"


def test_settings_file_without_key_stays_on_oauth_when_token_exists(
    settings_file, token_file
):
    """The upgrade guard: don't move a working OAuth user onto IMAP.

    A settings file predating the mail_backend key means the user was on
    OAuth. If they also have a token, flipping them to IMAP would ignore that
    token and demand an app password they have never created.
    """
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps({"chunk_size": "hour"}))
    token_file.parent.mkdir(parents=True, exist_ok=True)
    token_file.write_text("{}")

    settings = gui._load_settings()
    assert settings["mail_backend"] == MAIL_BACKEND_GMAIL_OAUTH


def test_explicit_saved_backend_beats_the_token_guard(settings_file, token_file):
    """A user who deliberately chose IMAP keeps IMAP even holding a token."""
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps({"mail_backend": MAIL_BACKEND_IMAP}))
    token_file.parent.mkdir(parents=True, exist_ok=True)
    token_file.write_text("{}")

    assert gui._load_settings()["mail_backend"] == MAIL_BACKEND_IMAP


def test_gui_and_worker_agree_on_backend_for_same_file(
    settings_file, token_file, monkeypatch
):
    """The whole point of the shared resolver: no drift between the two."""
    monkeypatch.setattr(gui_worker, "_SETTINGS_FILE", settings_file)
    settings_file.parent.mkdir(parents=True, exist_ok=True)
    settings_file.write_text(json.dumps({"chunk_size": "hour"}))

    for token_present in (False, True):
        if token_present:
            token_file.parent.mkdir(parents=True, exist_ok=True)
            token_file.write_text("{}")
        assert (
            gui._load_settings()["mail_backend"]
            == gui_worker._load_mail_backend_settings()["mail_backend"]
        )


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
    password = "hunter2-app-pw"
    gui_worker.connect_imap(q, "imap.gmail.com", 993, "me@example.com", password)

    event = q.get_nowait()
    assert event["type"] == "auth_ok"
    assert event["transport"] is fake_transport

    # Credentials file shape. This test machine is Windows, so
    # _save_imap_credentials will actually go through real DPAPI encryption
    # (see test_save_imap_credentials_file_shape below for the dedicated,
    # non-conditional assertion of that) -- but assert both branches here so
    # this test stays meaningful even if DPAPI is ever unavailable in CI.
    raw = worker_paths["imap_credentials"].read_bytes()
    saved = json.loads(raw)
    assert saved["host"] == "imap.gmail.com"
    assert saved["port"] == 993
    assert saved["email"] == "me@example.com"
    if secret_store.is_available():
        assert "password_dpapi" in saved
        assert "password" not in saved
        assert password.encode() not in raw
    else:
        assert saved.get("password") == password


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
    import src.mail_client as mail_client_mod

    password = "hunter2-app-pw"
    monkeypatch.setattr(mail_client_mod.imaplib, "IMAP4_SSL", _FakeLoginFailIMAP4SSL)

    q: queue.Queue = queue.Queue()
    gui_worker.connect_imap(q, "imap.gmail.com", 993, "me@example.com", password)

    event = q.get_nowait()
    assert event["type"] == "auth_error"
    assert password not in event["msg"]
    # Nothing should have been written to the credentials file on failure.
    assert not worker_paths["imap_credentials"].exists()


def test_save_imap_credentials_file_shape(worker_paths):
    """On this Windows test machine, saving goes through real DPAPI: the
    file gets password_dpapi and NO password key, and the plaintext password
    string does not appear anywhere in the raw file bytes. Written to also
    cover the plaintext-fallback shape so the assertion stays correct on a
    hypothetical non-Windows/DPAPI-unavailable CI runner."""
    password = "hunter2-app-pw"
    gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", password)
    raw = worker_paths["imap_credentials"].read_bytes()
    saved = json.loads(raw)
    assert saved["host"] == "imap.gmail.com"
    assert saved["port"] == 993
    assert saved["email"] == "me@example.com"
    if secret_store.is_available():
        assert set(saved.keys()) == {"host", "port", "email", "password_dpapi"}
        assert password.encode() not in raw
    else:
        assert set(saved.keys()) == {"host", "port", "email", "password"}
        assert saved["password"] == password


def test_save_imap_credentials_falls_back_to_plaintext_when_dpapi_unavailable(worker_paths, monkeypatch):
    """secret_store.protect() failing (DPAPI unavailable, or any runtime
    error inside it) must not refuse the save -- see _save_imap_credentials's
    docstring for why that's a deliberate call. It falls back to the same
    plaintext-protected-by-ACL storage this app always used."""
    monkeypatch.setattr(gui_worker.secret_store, "is_available", lambda: False)
    monkeypatch.setattr(gui_worker.secret_store, "protect", lambda pw: None)

    gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", "hunter2-app-pw")

    saved = json.loads(worker_paths["imap_credentials"].read_text())
    assert set(saved.keys()) == {"host", "port", "email", "password"}
    assert saved["password"] == "hunter2-app-pw"


# ---------------------------------------------------------------------------
# DPAPI-encrypted credentials: read path, transparent upgrade, hard failure
# ---------------------------------------------------------------------------

def test_build_transport_imap_decrypts_dpapi_password(worker_paths, monkeypatch):
    """A file saved with password_dpapi (the current shape) must round-trip
    back to the real plaintext password on the read path too."""
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    password = "hunter2-app-pw"
    gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", password)
    if not secret_store.is_available():
        pytest.skip("DPAPI not available in this environment")
    assert "password_dpapi" in json.loads(worker_paths["imap_credentials"].read_text())

    captured = {}

    def fake_build_imap_transport(host, port, email, pw):
        captured.update(host=host, port=port, email=email, password=pw)
        return "FAKE_TRANSPORT"

    monkeypatch.setattr(gui_worker, "build_imap_transport", fake_build_imap_transport)
    transport = gui_worker.build_transport_for_active_backend()
    assert transport == "FAKE_TRANSPORT"
    assert captured["password"] == password


def test_build_transport_imap_undecryptable_dpapi_raises_runtimeerror_without_leaking(worker_paths):
    """A password_dpapi blob that fails to decrypt (auth/ copied to another
    machine or another Windows user) must raise a specific RuntimeError --
    never silently fall through to an empty/garbage password -- and that
    error must not contain the blob or any password-shaped content."""
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    fake_blob = "AQAAANCMnd8BFdERjHoAwE_this_is_not_a_real_dpapi_blob=="
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password_dpapi": fake_blob,
    }))

    with pytest.raises(RuntimeError) as excinfo:
        gui_worker.build_transport_for_active_backend()

    msg = str(excinfo.value)
    assert fake_blob not in msg
    assert "re-enter the app password" in msg.lower() or "re-enter" in msg.lower()


def test_build_transport_imap_raises_when_file_has_neither_password_key(worker_paths):
    """A credentials file carrying neither "password" nor "password_dpapi"
    must fail loudly. Routing both readers through resolve_imap_password()
    replaced a direct data["password"] lookup that used to raise KeyError
    here; degrading to an empty password instead would reach the provider as
    an ordinary authentication rejection and point the user at their app
    password rather than at the damaged file."""
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993, "email": "me@example.com",
    }))

    with pytest.raises(RuntimeError) as excinfo:
        gui_worker.build_transport_for_active_backend()

    assert "does not contain an app password" in str(excinfo.value)


def test_build_transport_imap_upgrades_legacy_plaintext_to_dpapi_on_read(worker_paths, monkeypatch):
    """Reading a legacy plaintext-format file on a Windows box where DPAPI is
    available must opportunistically rewrite the file encrypted (transparent
    upgrade), while still returning the correct password for this read."""
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    password = "hunter2-app-pw"
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password": password,
    }))
    if not secret_store.is_available():
        pytest.skip("DPAPI not available in this environment")

    monkeypatch.setattr(gui_worker, "build_imap_transport", lambda h, p, e, pw: "FAKE_TRANSPORT")
    transport = gui_worker.build_transport_for_active_backend()
    assert transport == "FAKE_TRANSPORT"

    upgraded = json.loads(worker_paths["imap_credentials"].read_text())
    assert "password_dpapi" in upgraded
    assert "password" not in upgraded
    # And the upgraded file itself decrypts back to the same password.
    assert secret_store.unprotect(upgraded["password_dpapi"]) == password


def test_build_transport_imap_upgrade_failure_does_not_break_read(worker_paths, monkeypatch):
    """If the opportunistic re-save during a transparent upgrade blows up for
    any reason, the read that triggered it must still succeed with the
    password that was already proven to work -- an upgrade hiccup must never
    turn into a sync failure."""
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    password = "hunter2-app-pw"
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password": password,
    }))

    def boom(*a, **kw):
        raise RuntimeError("simulated upgrade failure")

    monkeypatch.setattr(gui_worker, "_save_imap_credentials", boom)
    monkeypatch.setattr(gui_worker.secret_store, "is_available", lambda: True)

    captured = {}

    def fake_build_imap_transport(host, port, email, pw):
        captured["password"] = pw
        return "FAKE_TRANSPORT"

    monkeypatch.setattr(gui_worker, "build_imap_transport", fake_build_imap_transport)
    transport = gui_worker.build_transport_for_active_backend()
    assert transport == "FAKE_TRANSPORT"
    assert captured["password"] == password


def test_check_imap_auth_status_works_for_dpapi_shape(worker_paths):
    """_check_imap_auth_status only ever reads 'email', so it must keep
    working unchanged for the new password_dpapi file shape."""
    _write_settings(worker_paths["settings"], mail_backend=MAIL_BACKEND_IMAP)
    worker_paths["imap_credentials"].write_text(json.dumps({
        "host": "imap.gmail.com", "port": 993,
        "email": "me@example.com", "password_dpapi": "irrelevant-for-this-check",
    }))
    valid, text = gui_worker.check_auth_status()
    assert valid is True
    assert text == "Connected (me@example.com)"


# ---------------------------------------------------------------------------
# At-rest protection of the credentials file (F2 sub-findings)
# ---------------------------------------------------------------------------

def test_save_imap_credentials_hardens_directory_before_file_exists(worker_paths, monkeypatch):
    """The directory ACL must be applied while the file does NOT yet exist, so
    the file inherits a restricted ACL at creation. Writing first and
    hardening afterwards left a window with a live password in a
    world-readable file."""
    monkeypatch.setattr(gui_worker.os, "name", "nt")
    seen = {}

    def fake_dir_acl(path):
        seen["file_existed_at_dir_acl"] = worker_paths["imap_credentials"].exists()
        return True

    monkeypatch.setattr(gui_worker, "_restrict_auth_dir_acl", fake_dir_acl)
    monkeypatch.setattr(gui_worker, "_restrict_file_acl", lambda p: True)

    gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", "pw")

    assert seen["file_existed_at_dir_acl"] is False


def test_save_imap_credentials_raises_and_deletes_when_windows_acl_fails(worker_paths, monkeypatch):
    """A failed ACL must fail loud, not silently leave a readable password."""
    monkeypatch.setattr(gui_worker.os, "name", "nt")
    monkeypatch.setattr(gui_worker, "_restrict_auth_dir_acl", lambda p: False)
    monkeypatch.setattr(gui_worker, "_restrict_file_acl", lambda p: False)

    password = "hunter2-app-pw"
    with pytest.raises(RuntimeError) as excinfo:
        gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", password)

    assert not worker_paths["imap_credentials"].exists()
    assert password not in str(excinfo.value)


def test_save_imap_credentials_raises_when_posix_chmod_fails(worker_paths, monkeypatch):
    monkeypatch.setattr(gui_worker.os, "name", "posix")

    def boom(*a, **kw):
        raise OSError("chmod refused")

    monkeypatch.setattr(gui_worker.os, "chmod", boom)

    with pytest.raises(RuntimeError):
        gui_worker._save_imap_credentials("imap.gmail.com", 993, "me@example.com", "pw")
    assert not worker_paths["imap_credentials"].exists()


def test_connect_imap_surfaces_acl_failure_as_auth_error(worker_paths, monkeypatch):
    """The loud failure must reach the user through the existing queue
    contract rather than escaping as an unhandled exception."""
    monkeypatch.setattr(
        gui_worker, "build_imap_transport",
        lambda h, p, e, pw: _FakeSucceedingImapTransport(),
    )
    monkeypatch.setattr(gui_worker.os, "name", "nt")
    monkeypatch.setattr(gui_worker, "_restrict_auth_dir_acl", lambda p: False)
    monkeypatch.setattr(gui_worker, "_restrict_file_acl", lambda p: False)

    password = "hunter2-app-pw"
    q: queue.Queue = queue.Queue()
    gui_worker.connect_imap(q, "imap.gmail.com", 993, "me@example.com", password)

    event = q.get_nowait()
    assert event["type"] == "auth_error"
    assert password not in event["msg"]
    assert not worker_paths["imap_credentials"].exists()


def test_current_username_falls_back_when_username_env_missing(monkeypatch):
    """%USERNAME% is absent in some service/scheduled-task contexts; that used
    to make ACL hardening a silent no-op."""
    import src.mail_client as mail_client_mod

    monkeypatch.delenv("USERNAME", raising=False)
    monkeypatch.setattr(mail_client_mod.getpass, "getuser", lambda: "fallback-user")
    assert mail_client_mod._current_username() == "fallback-user"


def test_restrict_acl_returns_false_when_username_unresolvable(tmp_path, monkeypatch):
    import src.mail_client as mail_client_mod

    monkeypatch.setattr(mail_client_mod, "_current_username", lambda: None)
    assert mail_client_mod._restrict_acl(tmp_path, "F") is False


def test_restrict_file_acl_omits_directory_inheritance_flags(tmp_path, monkeypatch):
    """(OI)(CI) are directory-only flags; icacls rejects them on a file."""
    import src.mail_client as mail_client_mod

    captured = {}

    def fake_run(cmd, **kw):
        captured["cmd"] = cmd
        class _Done:
            returncode = 0
            stdout = ""
        return _Done()

    monkeypatch.setattr(mail_client_mod, "_current_username", lambda: "someuser")
    monkeypatch.setattr(mail_client_mod.subprocess, "run", fake_run)

    assert mail_client_mod._restrict_file_acl(tmp_path / "f.json") is True
    assert "someuser:F" in captured["cmd"]
    assert not any("(OI)(CI)" in part for part in captured["cmd"])


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


# ---------------------------------------------------------------------------
# Settings window: provider dropdown -> host/port autofill
#
# These need a real Tk display, so they skip where one isn't available. They
# exist because the bug they guard against shipped: _apply_host_field_state
# disables the host entry after filling it, and a disabled Tk entry silently
# drops delete/insert -- so every provider change after the first no-oped and
# left the previous provider's host in the field, which _on_save then wrote.
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def tk_root():
    """One Tk root for the whole session. Creating and destroying a fresh
    CTk() per test eventually leaves Tcl in a state where the next one dies
    with `invalid command name "tcl_findLibrary"` -- which surfaced as a test
    that skipped at random, moving between runs. Only the Toplevel under test
    is per-test."""
    ctk = pytest.importorskip("customtkinter")
    try:
        root = ctk.CTk()
    except Exception as exc:  # no display / no Tk
        pytest.skip(f"Tk unavailable: {exc}")
    root.withdraw()
    yield root
    root.destroy()


@pytest.fixture
def settings_window(settings_file, tk_root):
    root = tk_root
    root._settings = {
        "mail_backend": MAIL_BACKEND_IMAP,
        "imap_provider": "gmail",
        "imap_host": IMAP_PROVIDERS["gmail"]["host"],
        "imap_port": 993,
        "imap_email": "you@example.com",
        "chunk_size": "day",
        "auto_refresh_label": "30 s",
    }
    win = gui._SettingsWindow(root)
    yield win
    # grab_set() in __init__ makes this window modal; release it explicitly so
    # a failed assertion can't leave the shared root grabbed for later tests.
    try:
        win.grab_release()
    except Exception:
        pass
    win.destroy()


def _select(win, provider_key):
    win._provider_var.set(gui._PROVIDER_LABELS[provider_key])
    win._on_provider_changed()


@pytest.mark.parametrize(
    "provider_key", [k for k in IMAP_PROVIDERS if k != "custom"]
)
def test_provider_change_updates_host_even_when_field_is_disabled(
    settings_window, provider_key
):
    # Start on gmail (the fixture's saved provider) so every non-gmail case
    # arrives with the host entry already disabled -- the failing path.
    _select(settings_window, "gmail")
    _select(settings_window, provider_key)
    assert settings_window._host_entry.get() == IMAP_PROVIDERS[provider_key]["host"]
    assert settings_window._port_entry.get() == str(IMAP_PROVIDERS[provider_key]["port"])


def test_custom_provider_leaves_host_editable(settings_window):
    _select(settings_window, "fastmail")
    _select(settings_window, "custom")
    assert str(settings_window._host_entry.cget("state")) == "normal"


def test_help_stays_below_the_save_row_across_a_backend_round_trip(settings_window):
    win = settings_window
    order = lambda: [str(c) for c in win.pack_slaves()]
    assert win._help_expanded is False
    assert order().index(str(win._help_container)) > order().index(str(win._btn_row))

    win._warn_oauth_is_limited = lambda: None
    win._backend_var.set(gui._BACKEND_LABELS[MAIL_BACKEND_GMAIL_OAUTH])
    win._on_backend_changed()
    assert str(win._help_container) not in order()

    win._backend_var.set(gui._BACKEND_LABELS[MAIL_BACKEND_IMAP])
    win._on_backend_changed()
    # pack_forget drops a widget from the packing order; a bare re-pack would
    # append it, leaving the IMAP form below its own Save button.
    assert order().index(str(win._imap_frame)) < order().index(str(win._btn_row))
    assert order().index(str(win._help_container)) > order().index(str(win._btn_row))


@pytest.mark.parametrize("provider_key", list(IMAP_PROVIDERS))
def test_app_password_prompt_never_contains_email_or_password(
    settings_window, provider_key
):
    win = settings_window
    win._password_entry.insert(0, "hunter2-app-password")
    _select(win, provider_key)
    win._toggle_help()

    texts = []

    def walk(parent):
        for child in parent.winfo_children():
            try:
                texts.append(str(child.cget("text")))
            except Exception:
                pass
            walk(child)

    walk(win._help_frame)
    prompt = gui._build_app_password_prompt(
        provider_key, gui._PROVIDER_LABELS[provider_key], win._host_entry.get()
    )
    blob = " ".join(texts) + " " + prompt
    assert "you@example.com" not in blob
    assert "hunter2-app-password" not in blob
