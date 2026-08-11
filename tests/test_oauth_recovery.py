"""Recovering from a dead Google sign-in.

The app's own help tells users the Google sign-in expires roughly every 7 days,
because the OAuth client is unverified and Google keeps it in "Testing". That
makes recovery from a revoked grant a routine path, not an edge case -- and it
was broken: a stored token satisfies "expired, and has a refresh token" forever,
so every Connect tried to refresh, got invalid_grant, and raised, without ever
reaching the browser flow. Observed live on 2026-08-10.
"""

import json
from pathlib import Path

import pytest
from google.auth.exceptions import RefreshError

from src import mail_client


class _FakeCreds:
    """Stand-in for google.oauth2.credentials.Credentials."""

    def __init__(self, *, valid=False, expired=True, refresh_token="rt"):
        self.valid = valid
        self.expired = expired
        self.refresh_token = refresh_token
        self.refreshed = False

    def refresh(self, _request):
        self.refreshed = True

    def to_json(self):
        return json.dumps({"token": "fresh"})


class _RevokedCreds(_FakeCreds):
    def refresh(self, _request):
        self.refreshed = True
        raise RefreshError("invalid_grant: Bad Request", {"error": "invalid_grant"})


@pytest.fixture()
def auth_files(tmp_path: Path) -> tuple[Path, Path]:
    credentials = tmp_path / "credentials.json"
    credentials.write_text(json.dumps({"installed": {"client_id": "x"}}), encoding="utf-8")
    return credentials, tmp_path / "token.json"


def _stub_google(monkeypatch, stored, flow_result):
    """Patch get_credentials' three lazily-imported google symbols.

    `flow_result` is returned from run_local_server(); an exception instance
    is raised from it instead, for the abandoned-sign-in case.
    """
    import google.oauth2.credentials as creds_mod
    import google_auth_oauthlib.flow as flow_mod

    monkeypatch.setattr(
        creds_mod.Credentials, "from_authorized_user_file",
        classmethod(lambda _cls, *_a, **_k: stored),
    )

    opened = {"count": 0, "kwargs": None}

    class _FakeFlow:
        @classmethod
        def from_client_secrets_file(cls, *_a, **_k):
            return cls()

        def run_local_server(self, **kwargs):
            opened["count"] += 1
            opened["kwargs"] = kwargs
            if isinstance(flow_result, BaseException):
                raise flow_result
            return flow_result

    monkeypatch.setattr(flow_mod, "InstalledAppFlow", _FakeFlow)
    return opened


def test_a_revoked_grant_falls_back_to_the_browser_flow(auth_files, monkeypatch):
    credentials_file, token_file = auth_files
    token_file.write_text(json.dumps({"refresh_token": "dead"}), encoding="utf-8")
    fresh = _FakeCreds(valid=True, expired=False)
    opened = _stub_google(monkeypatch, _RevokedCreds(), fresh)

    result = mail_client.get_credentials(credentials_file, token_file)

    assert opened["count"] == 1, "invalid_grant must re-authorise, not raise"
    assert result is fresh
    # The dead token is replaced, so the next start is connected rather than
    # walking into the same wall.
    assert json.loads(token_file.read_text())["token"] == "fresh"


def test_a_merely_expired_token_still_refreshes_silently(auth_files, monkeypatch):
    """The fallback must not turn every routine refresh into a browser prompt."""
    credentials_file, token_file = auth_files
    token_file.write_text(json.dumps({"refresh_token": "live"}), encoding="utf-8")
    stored = _FakeCreds()
    opened = _stub_google(monkeypatch, stored, _FakeCreds(valid=True))

    result = mail_client.get_credentials(credentials_file, token_file)

    assert stored.refreshed
    assert opened["count"] == 0
    assert result is stored


def test_no_token_at_all_opens_the_browser(auth_files, monkeypatch):
    credentials_file, token_file = auth_files
    fresh = _FakeCreds(valid=True)
    opened = _stub_google(monkeypatch, None, fresh)

    assert mail_client.get_credentials(credentials_file, token_file) is fresh
    assert opened["count"] == 1


def test_an_abandoned_browser_sign_in_gives_up_instead_of_waiting_forever(
    auth_files, monkeypatch
):
    """Reported live: the browser was opened and closed again seconds later,
    and the app sat on "Connecting…" indefinitely. run_local_server() defaults
    to timeout_seconds=None -- an unbounded wait on a redirect that closing the
    browser guarantees will never arrive. Bounded, the caller gets a TimeoutError
    it can show, and the Connect button comes back."""
    from google_auth_oauthlib.flow import WSGITimeoutError

    credentials_file, token_file = auth_files
    opened = _stub_google(monkeypatch, None, WSGITimeoutError("no response"))

    with pytest.raises(TimeoutError) as err:
        mail_client.get_credentials(credentials_file, token_file)

    assert "Connect" in str(err.value)      # says how to get out of it
    assert opened["kwargs"]["timeout_seconds"] == (
        mail_client.OAUTH_BROWSER_TIMEOUT_SECONDS
    )
    # Nothing half-written: no token file from a sign-in that never happened.
    assert not token_file.exists()


def test_missing_credentials_json_is_still_a_hard_error(tmp_path, monkeypatch):
    """Re-authorising cannot help when the client secrets are absent, so this
    must stay a clear failure rather than opening a browser to nothing."""
    with pytest.raises(FileNotFoundError):
        mail_client.get_credentials(tmp_path / "nope.json", tmp_path / "token.json")
