"""
Windows DPAPI (Data Protection API) wrapper for the one secret this desktop
app stores at rest: the IMAP app password in auth/imap_credentials.json.

Why this exists on top of the NTFS ACL that file already gets
(mail_client._restrict_file_acl / _restrict_auth_dir_acl):

  - The ACL stops *other local Windows accounts* on the same machine from
    opening the file through the filesystem while Windows is running and
    enforcing permissions.
  - It does nothing once NTFS permission checks are out of the picture --
    the disk (or a backup of it, or a VM snapshot, or a forensic image) read
    offline by booting another OS, pulling the drive into another machine, or
    restoring a backup elsewhere. An ACL is metadata the *filesystem driver*
    enforces; it is not a property of the bytes on disk.
  - DPAPI closes exactly that gap: CryptProtectData encrypts the bytes with a
    key derived from the logged-in user's Windows credentials (via the OS's
    per-user master key, itself protected by the user's login secret). The
    ciphertext is only meaningful to CryptUnprotectData running as that same
    user on that same machine (this can also break intentionally -- see
    protect()'s docstring). Reading the raw file bytes anywhere else, with
    any tool, yields nothing usable.

What DPAPI does NOT protect against, so nobody mistakes this for more than
it is: malware or any other code already running *as this Windows user* can
call CryptUnprotectData itself just as easily as this app does -- DPAPI binds
to an account, not to a specific process or binary. Nothing available to an
unsigned portable desktop app (no code signing, no hardware-backed enclave
API) can defend against that. This is a defence-in-depth layer against
offline/at-rest disk access, not a defence against a compromised session.

Everything here is optional-path plumbing: every caller must be prepared for
protect()/unprotect() to return None (wrong OS, DLL unavailable, or any
runtime failure) and fall back accordingly -- see gui_worker._save_imap_credentials
and gui_worker's credential-read helper for the two fallback policies (save
still succeeds in plaintext; a read of an undecryptable blob is a loud,
specific RuntimeError instead of a silent retry-forever).
"""

import base64
import ctypes
import ctypes.wintypes as wintypes
import os
from typing import Optional

# Optional entropy mixed into every CryptProtectData/CryptUnprotectData call.
# This is NOT a secret -- it ships in the source tree and anyone with the
# binary can read it. Its only purpose is a small hardening measure: it stops
# a DPAPI blob produced by some *other* application under the same Windows
# account (which also can call CryptProtectData with no entropy, or with its
# own) from being silently accepted here as if it were one of ours, and vice
# versa. Do not treat this as a key -- DPAPI's real protection comes entirely
# from the per-user master key, not from this constant.
_APP_ENTROPY = b"WA Mail Sync IMAP app password v1"

# CRYPTPROTECT_UI_FORBIDDEN: never let CryptProtectData/CryptUnprotectData
# show a UI prompt. This app can run headless (e.g. a scheduled/background
# sync); a blocking OS prompt in that context would hang the process instead
# of failing fast, which is worse than falling back to "no password".
_CRYPTPROTECT_UI_FORBIDDEN = 0x1


class _DATA_BLOB(ctypes.Structure):
    _fields_ = [
        ("cbData", wintypes.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_char)),
    ]


def _load_dpapi():
    """Return (crypt32, kernel32) with the needed prototypes bound, or None.

    Isolated into its own function so is_available() and every real call site
    share one place that decides whether DPAPI can be used at all, rather
    than duplicating the "are we even on Windows" check everywhere.
    """
    if os.name != "nt":
        return None
    try:
        crypt32 = ctypes.WinDLL("crypt32.dll")
        kernel32 = ctypes.WinDLL("kernel32.dll")
        crypt32.CryptProtectData.argtypes = [
            ctypes.POINTER(_DATA_BLOB),
            wintypes.LPCWSTR,
            ctypes.POINTER(_DATA_BLOB),
            wintypes.LPVOID,
            wintypes.LPVOID,
            wintypes.DWORD,
            ctypes.POINTER(_DATA_BLOB),
        ]
        crypt32.CryptProtectData.restype = wintypes.BOOL
        crypt32.CryptUnprotectData.argtypes = [
            ctypes.POINTER(_DATA_BLOB),
            ctypes.POINTER(wintypes.LPWSTR),
            ctypes.POINTER(_DATA_BLOB),
            wintypes.LPVOID,
            wintypes.LPVOID,
            wintypes.DWORD,
            ctypes.POINTER(_DATA_BLOB),
        ]
        crypt32.CryptUnprotectData.restype = wintypes.BOOL
        kernel32.LocalFree.argtypes = [wintypes.HLOCAL]
        kernel32.LocalFree.restype = wintypes.HLOCAL
        return crypt32, kernel32
    except Exception:
        # Any failure here (DLL missing, prototype binding error, running
        # under a Windows flavour without crypt32) means DPAPI simply is not
        # usable on this machine -- treat it the same as "not on Windows".
        return None


def is_available() -> bool:
    """True only when os.name == 'nt' AND crypt32.dll actually loads."""
    return _load_dpapi() is not None


def _make_blob(data: bytes) -> _DATA_BLOB:
    buf = ctypes.create_string_buffer(data, len(data))
    return _DATA_BLOB(cbData=len(data), pbData=ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))


def protect(plaintext: str) -> Optional[str]:
    """Encrypt plaintext with per-user DPAPI; return a base64 blob, or None.

    None is returned -- never an exception -- for absolutely any failure:
    wrong OS, DPAPI unavailable, or any runtime error from the OS call. This
    is deliberate: a save-path caller must always have a plaintext fallback
    ready (see gui_worker._save_imap_credentials), and letting a DPAPI hiccup
    turn into an unhandled exception would take down the whole save instead
    of degrading to the ACL-only protection that already existed before this
    module.

    Callers must not log the return value -- it is still sensitive-adjacent
    (a DPAPI blob decrypts to the password on the originating machine/account)
    even though it is not itself the plaintext.
    """
    dll = _load_dpapi()
    if dll is None:
        return None
    crypt32, kernel32 = dll

    out_blob = _DATA_BLOB()
    try:
        data_bytes = plaintext.encode("utf-8")
        in_blob = _make_blob(data_bytes)
        entropy_blob = _make_blob(_APP_ENTROPY)
        ok = crypt32.CryptProtectData(
            ctypes.byref(in_blob),
            None,  # szDataDescr -- no human-readable description needed
            ctypes.byref(entropy_blob),
            None,  # pvReserved -- must be NULL
            None,  # pPromptStruct -- no UI, see dwFlags below
            _CRYPTPROTECT_UI_FORBIDDEN,
            ctypes.byref(out_blob),
        )
        if not ok:
            return None
        encrypted = ctypes.string_at(out_blob.pbData, out_blob.cbData)
        return base64.b64encode(encrypted).decode("ascii")
    except Exception:
        return None
    finally:
        # DPAPI allocates pbData for us on success; it is our responsibility
        # to free it with LocalFree regardless of what happened above, or
        # every successful protect() call leaks that buffer.
        if out_blob.pbData:
            try:
                kernel32.LocalFree(ctypes.cast(out_blob.pbData, wintypes.HLOCAL))
            except Exception:
                pass


def unprotect(blob: str) -> Optional[str]:
    """Decrypt a base64 blob produced by protect(); return plaintext, or None.

    Returns None -- never raises -- for any failure: wrong OS, DPAPI
    unavailable, malformed base64, a base64 string that isn't a DPAPI blob at
    all, or (the expected real-world case) a blob produced on a different
    machine or under a different Windows user account, which DPAPI refuses
    to decrypt by design. Callers must treat None as "cannot be decrypted
    here" and surface a clear message to the user rather than retrying or
    treating it as an empty password -- see gui_worker's credential-read
    helper for the RuntimeError it raises in that case.

    Never includes the blob or any decrypted content in a log line or
    exception -- this function has no logging calls and swallows every
    exception without re-raising or formatting it into a message.
    """
    dll = _load_dpapi()
    if dll is None:
        return None
    crypt32, kernel32 = dll

    out_blob = _DATA_BLOB()
    try:
        encrypted = base64.b64decode(blob, validate=True)
    except Exception:
        return None

    try:
        in_blob = _make_blob(encrypted)
        entropy_blob = _make_blob(_APP_ENTROPY)
        ok = crypt32.CryptUnprotectData(
            ctypes.byref(in_blob),
            None,  # ppszDataDescr -- discard the optional description output
            ctypes.byref(entropy_blob),
            None,  # pvReserved -- must be NULL
            None,  # pPromptStruct -- no UI, see dwFlags below
            _CRYPTPROTECT_UI_FORBIDDEN,
            ctypes.byref(out_blob),
        )
        if not ok:
            return None
        decrypted = ctypes.string_at(out_blob.pbData, out_blob.cbData)
        return decrypted.decode("utf-8")
    except Exception:
        return None
    finally:
        if out_blob.pbData:
            try:
                kernel32.LocalFree(ctypes.cast(out_blob.pbData, wintypes.HLOCAL))
            except Exception:
                pass
