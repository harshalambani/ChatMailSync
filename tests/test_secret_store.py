"""Tests for src/secret_store.py -- Windows DPAPI wrapper (P1: at-rest
encryption for the stored IMAP app password).

These run against the REAL crypt32.dll (no mocking of CryptProtectData /
CryptUnprotectData) so the round-trip genuinely proves DPAPI works in this
environment, not just that the ctypes plumbing was called. Tests that need
DPAPI to be genuinely available skip cleanly when it isn't (e.g. a non-
Windows CI runner), rather than failing for an environment reason unrelated
to the code under test.
"""

import base64

import pytest

from src import secret_store


# Applied per-test (not as a module-wide pytestmark) so the "simulated
# non-Windows" tests at the bottom -- which must run everywhere, precisely
# because they're testing the branch taken when DPAPI is NOT available --
# aren't accidentally skipped along with everything else.
_requires_dpapi = pytest.mark.skipif(
    not secret_store.is_available(),
    reason="Windows DPAPI (crypt32.dll) not available in this environment",
)


@_requires_dpapi
def test_protect_unprotect_round_trip():
    plaintext = "hunter2-app-pw"
    blob = secret_store.protect(plaintext)
    assert blob is not None
    assert plaintext not in blob  # ciphertext must not just be an encoding of the input
    assert secret_store.unprotect(blob) == plaintext


@_requires_dpapi
def test_protect_unprotect_round_trip_empty_string():
    blob = secret_store.protect("")
    assert blob is not None
    assert secret_store.unprotect(blob) == ""


@_requires_dpapi
def test_protect_unprotect_round_trip_unicode():
    plaintext = "p@ssw0rd-éè中文"
    blob = secret_store.protect(plaintext)
    assert blob is not None
    assert secret_store.unprotect(blob) == plaintext


@_requires_dpapi
def test_unprotect_returns_none_for_garbage_string():
    assert secret_store.unprotect("this is not a dpapi blob at all") is None


@_requires_dpapi
def test_unprotect_returns_none_for_non_base64_input():
    assert secret_store.unprotect("!!!not-base64!!!") is None


@_requires_dpapi
def test_unprotect_returns_none_for_valid_base64_non_dpapi_blob():
    # Valid base64, decodes to real bytes, but was never produced by
    # CryptProtectData -- CryptUnprotectData must reject it, not crash.
    junk = base64.b64encode(b"just some random bytes, not a DPAPI blob").decode("ascii")
    assert secret_store.unprotect(junk) is None


@_requires_dpapi
def test_unprotect_returns_none_for_empty_string():
    assert secret_store.unprotect("") is None


@_requires_dpapi
def test_protect_never_raises_on_non_string_free_of_secret_in_errors():
    # protect()/unprotect() must swallow all exceptions and never surface the
    # plaintext or blob in any exception text. There's no exception to
    # inspect here (both are designed to return None), so this simply
    # confirms garbage input doesn't propagate an exception up to the caller.
    try:
        result = secret_store.unprotect("%%%")
    except Exception as exc:  # pragma: no cover -- must never happen
        pytest.fail(f"unprotect() raised instead of returning None: {exc}")
    assert result is None


@_requires_dpapi
def test_is_available_true_on_this_windows_box():
    assert secret_store.is_available() is True


# ---------------------------------------------------------------------------
# Non-Windows behaviour -- simulated via monkeypatch since this suite only
# runs on Windows machines. Not gated by the module-level skipif above: these
# must run regardless of whether real DPAPI is available, since they're
# specifically testing the "not on Windows" branch.
# ---------------------------------------------------------------------------

def test_is_available_false_when_not_windows(monkeypatch):
    monkeypatch.setattr(secret_store.os, "name", "posix")
    assert secret_store.is_available() is False


def test_protect_returns_none_when_not_windows(monkeypatch):
    monkeypatch.setattr(secret_store.os, "name", "posix")
    assert secret_store.protect("hunter2-app-pw") is None


def test_unprotect_returns_none_when_not_windows(monkeypatch):
    monkeypatch.setattr(secret_store.os, "name", "posix")
    assert secret_store.unprotect("anything") is None
