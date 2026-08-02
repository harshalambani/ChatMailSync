"""
Gmail API wrapper: OAuth2 authentication, label management, and message insert.

Uses gmail.users.messages.insert() — not send() — so messages land directly
in the mailbox without SMTP side-effects and without consuming send quota.

Threading model (per architecture doc §4):
  - First push for a chat creates an "anchor" email whose Message-ID is stored.
  - All subsequent pushes for the same chat reference that anchor via
    In-Reply-To / References, keeping all chunks inside one Gmail thread.

Chunking:
  - Messages are grouped into chunks (default: one per calendar day) so each
    email is a readable block rather than one message per email or one giant blob.
  - Chunk size is configurable: "day", "hour", "week", or a fixed integer.
"""

import base64
import email as _email
import getpass
import imaplib
import logging
import os
import re
import socket
import ssl
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from email import encoders as _encoders
from email.mime.base import MIMEBase
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import formataddr, parsedate_to_datetime
from pathlib import Path
from typing import TYPE_CHECKING, Callable, Iterator, Optional, Protocol, Union, runtime_checkable

# google-auth-oauthlib / googleapiclient are desktop-OAuth-flow dependencies,
# used only by get_credentials()/build_service()/DiscoveryTransport below.
# They're imported lazily inside those functions (not here at module level)
# so that importing this module — and anything that imports it, like
# android_api.py — never requires those packages to be installed. Android
# never calls get_credentials()/build_service() at all (it does its own
# OAuth in Kotlin and calls set_token() instead), so it only needs
# python-dateutil (via src.parser) to import this module successfully.
if TYPE_CHECKING:
    from google.oauth2.credentials import Credentials

from src.config import (
    API_CALL_DELAY_SECONDS,
    BACKOFF_BASE_DELAY,
    BACKOFF_MAX_ATTEMPTS,
    CREDENTIALS_FILE,
    GMAIL_SCOPES,
    GMAIL_SOCKET_TIMEOUT,
    LABEL_MAX_LENGTH,
    LABEL_PARENT,
    MAX_EMAIL_SIZE_BYTES,
    TOKEN_FILE,
)
from src.html_renderer import RenderedChunk, render_chunk
from src.media_extractor import MediaExtractor
from src.parser import ParsedMessage

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Type aliases
# ---------------------------------------------------------------------------

# gmail.users.messages.insert() resource handle
_GmailService = object  # googleapiclient Resource — typed loosely to avoid import gymnastics

# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


def _current_username() -> Optional[str]:
    """Best available identity to hand to an icacls /grant, or None.

    %USERNAME% is the normal source but is simply absent in some service and
    scheduled-task contexts, which used to make ACL hardening a silent no-op.
    getpass.getuser() consults the same environment variables first, so the
    real fallback is `whoami`: it reports the process token's identity from
    the OS instead of trusting the environment.
    """
    username = os.environ.get("USERNAME")
    if username:
        return username
    try:
        username = getpass.getuser()
        if username:
            return username
    except Exception:  # getpass raises varied errors when it can't resolve
        pass
    if os.name == "nt":
        try:
            done = subprocess.run(
                ["whoami"], check=True, capture_output=True, text=True
            )
            username = done.stdout.strip()
            if username:
                return username
        except (subprocess.CalledProcessError, OSError):
            pass
    return None


def _restrict_acl(target: Path, grant: str) -> bool:
    """Strip inherited ACEs from target and grant the current user only.

    Returns True only if the ACL was actually applied. Callers that hold a
    plaintext secret must check the return value -- a False here means the
    path is still readable by other local accounts.
    """
    username = _current_username()
    if not username:
        log.warning(
            "Could not determine current username; ACL restriction on %s did "
            "NOT happen and the path may be readable by other local accounts",
            target,
        )
        return False
    try:
        subprocess.run(
            ["icacls", str(target), "/inheritance:r", "/grant:r", f"{username}:{grant}"],
            check=True,
            capture_output=True,
            text=True,
        )
        log.debug("Restricted ACL on %s to user %s", target, username)
        return True
    except (subprocess.CalledProcessError, OSError) as _acl_err:
        log.warning("Could not restrict ACL on %s: %s", target, _acl_err)
        return False


def _restrict_auth_dir_acl(auth_dir: Path) -> bool:
    """Restrict the auth directory's NTFS ACL to the current user only (Windows).

    Strips inherited permissions and grants full control solely to the
    logged-in user, so token.json / credentials.json (live client_secret)
    aren't readable by other local accounts. (OI)(CI) makes that grant the
    inherited default for files created in the directory afterwards.

    Best-effort for the OAuth callers: they ignore the return value so a
    failure never blocks authentication. Callers persisting a plaintext
    password must check it.
    """
    return _restrict_acl(auth_dir, "(OI)(CI)F")


def _restrict_file_acl(path: Path) -> bool:
    """Restrict a single file's NTFS ACL to the current user only (Windows).

    Same as _restrict_auth_dir_acl but without the (OI)(CI) inheritance flags,
    which are meaningless on a file and make icacls reject the grant.
    """
    return _restrict_acl(path, "F")


def get_credentials(
    credentials_file: Path = CREDENTIALS_FILE,
    token_file: Path = TOKEN_FILE,
) -> "Credentials":
    """Load cached OAuth2 credentials or run the browser-based auth flow.

    On first run the browser opens for the user to grant access; the token is
    then persisted to token_file for all subsequent runs.

    Windows/desktop-only — Android never calls this (see module docstring note).

    Raises:
        FileNotFoundError: if credentials_file doesn't exist.
    """
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from google_auth_oauthlib.flow import InstalledAppFlow

    if not credentials_file.exists():
        raise FileNotFoundError(
            f"credentials.json not found at {credentials_file}.\n"
            "Download it from Google Cloud Console → APIs & Services → Credentials."
        )

    creds: Optional[Credentials] = None

    if token_file.exists():
        creds = Credentials.from_authorized_user_file(str(token_file), GMAIL_SCOPES)

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            log.debug("Refreshing expired OAuth2 token")
            creds.refresh(Request())
        else:
            log.info("Opening browser for OAuth2 authorisation…")
            flow = InstalledAppFlow.from_client_secrets_file(
                str(credentials_file), GMAIL_SCOPES
            )
            creds = flow.run_local_server(port=0)

        token_file.parent.mkdir(parents=True, exist_ok=True)
        token_file.write_text(creds.to_json())
        # Restrict the auth directory to the current user only, so token.json
        # and credentials.json aren't readable by other local accounts.
        if os.name != "nt":
            try:
                os.chmod(token_file, 0o600)
            except OSError as _chmod_err:
                log.warning("Could not set permissions on token file: %s", _chmod_err)
        else:
            _restrict_auth_dir_acl(token_file.parent)
        log.debug("Token saved to %s", token_file)

    return creds


def build_service(creds: Optional["Credentials"] = None) -> _GmailService:
    """Return an authenticated Gmail API service object.

    Uses a transport-specific timeout on the Gmail API HTTP client only,
    rather than the global socket.setdefaulttimeout() which would affect all
    network I/O in the process.  Both httplib2 and google-auth-httplib2 are
    hard dependencies of google-api-python-client and always present.

    Windows/desktop-only — Android never calls this (see module docstring note).
    """
    if creds is None:
        creds = get_credentials()
    import httplib2
    from google_auth_httplib2 import AuthorizedHttp
    from googleapiclient.discovery import build as _build
    http = AuthorizedHttp(creds, http=httplib2.Http(timeout=GMAIL_SOCKET_TIMEOUT))
    return _build("gmail", "v1", http=http)


# ---------------------------------------------------------------------------
# Transport interface
#
# Centralizes the 3 Gmail operations this app actually uses (labels.list,
# labels.create, messages.insert) behind a small structural interface, so a
# future Android build can swap in a direct-REST implementation (no
# google-api-python-client dependency) without changing get_or_create_label /
# push_chunks / _insert_with_backoff at all.
#
# threads.get and messages.list are not modeled here — nothing calls them
# today; add them if/when a caller needs them (e.g. future multi-device
# conflict detection).
# ---------------------------------------------------------------------------


@runtime_checkable
class MailTransport(Protocol):
    def labels_list(self) -> dict: ...

    def labels_create(self, body: dict) -> dict: ...

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict: ...


class MailTransportError(Exception):
    """Normalized transport error, raised by every MailTransport implementation.

    _insert_with_backoff() and other retry logic key off `.status` (HTTP
    status code, when known) rather than catching library-specific exceptions
    (HttpError vs. requests.HTTPError), so the retry policy stays shared
    across transports.
    """

    def __init__(self, message: str, status: Optional[int] = None) -> None:
        super().__init__(message)
        self.status = status


class DiscoveryTransport:
    """Wraps a googleapiclient `service` object (today's only Windows path).

    Windows/desktop-only — Android never constructs this (it uses
    RestTransport via set_token() instead), so googleapiclient is imported
    lazily here rather than at module level (see module docstring note).
    """

    def __init__(self, service: _GmailService) -> None:
        self.service = service

    def labels_list(self) -> dict:
        from googleapiclient.errors import HttpError

        try:
            return self.service.users().labels().list(userId="me").execute()
        except HttpError as exc:
            raise MailTransportError(str(exc), status=exc.resp.status) from exc

    def labels_create(self, body: dict) -> dict:
        from googleapiclient.errors import HttpError

        try:
            return self.service.users().labels().create(userId="me", body=body).execute()
        except HttpError as exc:
            raise MailTransportError(str(exc), status=exc.resp.status) from exc

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict:
        from googleapiclient.errors import HttpError

        if thread_id:
            body = {**body, "threadId": thread_id}
        try:
            return (
                self.service.users()
                .messages()
                .insert(userId="me", body=body, internalDateSource="dateHeader")
                .execute()
            )
        except HttpError as exc:
            raise MailTransportError(str(exc), status=exc.resp.status) from exc


def build_transport(creds: Optional["Credentials"] = None) -> MailTransport:
    """Build the default (googleapiclient-based) transport. Windows-only path."""
    return DiscoveryTransport(build_service(creds))


_GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"


class RestTransport:
    """Direct-REST transport over a plain bearer token.

    No get_credentials(), no token file I/O, no InstalledAppFlow — an Android
    caller does its own OAuth in Kotlin (AppAuth) and hands Python a bearer
    token via set_token(). Has no callers on Windows today; it exists here,
    fully unit-tested, so a later Android phase can wire it up with a
    one-line integration instead of a redesign.
    """

    def __init__(self, access_token: str) -> None:
        self._access_token = access_token

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self._access_token}"}

    def _request(self, method: str, url: str, **kwargs) -> dict:
        import requests

        try:
            resp = requests.request(method, url, headers=self._headers(), **kwargs)
            resp.raise_for_status()
            return resp.json()
        except requests.HTTPError as exc:
            status = exc.response.status_code if exc.response is not None else None
            raise MailTransportError(str(exc), status=status) from exc
        except requests.RequestException as exc:
            raise MailTransportError(str(exc)) from exc

    def labels_list(self) -> dict:
        return self._request("GET", f"{_GMAIL_API_BASE}/labels")

    def labels_create(self, body: dict) -> dict:
        return self._request("POST", f"{_GMAIL_API_BASE}/labels", json=body)

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict:
        if thread_id:
            body = {**body, "threadId": thread_id}
        return self._request(
            "POST",
            f"{_GMAIL_API_BASE}/messages",
            params={"internalDateSource": "dateHeader"},
            json=body,
        )


def set_token(access_token: str) -> MailTransport:
    """Build a transport from a plain OAuth2 bearer token (Android entry point)."""
    return RestTransport(access_token)


# ---------------------------------------------------------------------------
# IMAP APPEND transport (Road B, phase 1) — purely additive alongside the two
# OAuth transports above. Realizes a Gmail "label" as an IMAP folder and
# messages.insert() as IMAP APPEND, for providers/accounts where OAuth/API
# access isn't available but IMAP + an app password is (see
# 2026-07-30-road-b-imap-append-plan.md). Nothing above this point is touched
# by this backend; OAuth stays the permanent default (see MAIL_BACKEND_* in
# config.py — Phase 1 only adds the vocabulary, wiring is Phase 2).
# ---------------------------------------------------------------------------


def _strip_secret(text: str, secret: Optional[str]) -> str:
    """Defensively scrub a secret (the IMAP password) out of any text that
    might end up in a log line or exception message — e.g. if a server ever
    echoed part of the LOGIN command back in an error response."""
    if secret and secret in text:
        return text.replace(secret, "***")
    return text


def _join_imap_response(data) -> str:
    """Flatten an imaplib (typ, data) response's data list into one string
    for substring-matching against IMAP response codes like [ALREADYEXISTS]."""
    parts = []
    for item in data or []:
        if item is None:
            continue
        if isinstance(item, bytes):
            parts.append(item.decode("utf-8", errors="replace"))
        elif isinstance(item, tuple):
            parts.append(" ".join(
                x.decode("utf-8", errors="replace") if isinstance(x, bytes) else str(x)
                for x in item
            ))
        else:
            parts.append(str(item))
    return " ".join(parts)


def _is_already_exists_response(data) -> bool:
    text = _join_imap_response(data).upper()
    return "ALREADYEXISTS" in text or "ALREADY EXISTS" in text


def _status_for_imap_text(text: str) -> int:
    """Map an IMAP response-code/error string to an HTTP-style status so it
    can flow through the same MailTransportError(.status) retry policy the
    OAuth transports use (_insert_with_backoff retries 429/500/502/503/504).

    Transient (server-side, worth a retry) -> 503.
    Permanent (auth/policy/quota/bad-request, retrying won't help) -> 401/403/400.
    Unknown NO responses default to 400 (permanent) rather than 503, per the
    plan: don't blanket-retry conditions we can't positively identify as
    transient — a stuck permanent error retried 5x just delays the failure.
    """
    t = text.upper()
    if any(code in t for code in ("SERVERBUG", "UNAVAILABLE", "INUSE")):
        return 503
    if "OVERQUOTA" in t:
        return 403
    if any(code in t for code in (
        "AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED", "PERMISSIONDENIED",
    )):
        return 401
    if "TRYCREATE" in t:
        return 400
    return 400


def _quote_imap_mailbox(wire_name: str) -> str:
    """Wrap a mailbox-name wire argument in an RFC 3501 quoted-string.

    imaplib's create()/subscribe()/append() (Lib/imaplib.py) do zero quoting
    or escaping of the mailbox argument — they hand it to _simple_command()
    verbatim, which just joins it into the command line with a space. So
    a name containing a literal space (e.g. "WhatsApp/Parity Test", the
    overwhelming common case for real contact names) becomes the wire
    command "CREATE WhatsApp/Parity Test", which the server parses as two
    arguments and rejects with BAD. RFC 3501 section 4.3 requires such
    strings to be sent as a quoted-string: wrapped in double quotes, with
    any '\\' or '"' inside backslash-escaped (section 9, quoted-specials).
    Must be applied to every CREATE/SUBSCRIBE/APPEND mailbox argument, and
    NOT to already-well-formed atoms/quoted-strings like the '""'/'*'
    arguments labels_list() passes to LIST.
    """
    escaped = wire_name.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def _encode_imap_utf7(text: str) -> str:
    """Encode a unicode string as IMAP "modified UTF-7" (RFC 3501 section
    5.1.3, "Mailbox International Naming Convention").

    Mailbox names must travel as 7-bit-safe wire text, but WhatsApp chat
    names routinely contain emoji or non-Latin scripts ("Mom <3 emoji"),
    so fixing only the quoting bug above would leave an identical BAD
    response for any such name. Modified UTF-7 differs from standard UTF-7
    (RFC 2152) in two ways that make hand-rolling this unavoidable — Python
    ships no imap4-utf-7 codec: '&' (not '+') is the shift character, and
    the modified BASE64 alphabet used inside a shift sequence replaces '/'
    with ',' and omits '=' padding.

    Printable ASCII 0x20-0x7e passes through unchanged (with '&' itself
    escaped as "&-"); any other character starts a run that is UTF-16BE
    encoded, base64'd, and wrapped as "&<base64>-".
    """
    result = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "&":
            result.append("&-")
            i += 1
            continue
        if 0x20 <= ord(ch) <= 0x7E:
            result.append(ch)
            i += 1
            continue
        # Collect the whole run of consecutive non-ASCII-printable chars
        # into a single &...- block rather than one block per char.
        j = i
        while j < n and not (text[j] == "&" or 0x20 <= ord(text[j]) <= 0x7E):
            j += 1
        utf16 = text[i:j].encode("utf-16-be")
        b64 = base64.b64encode(utf16).decode("ascii").replace("/", ",").rstrip("=")
        result.append("&" + b64 + "-")
        i = j
    return "".join(result)


def _decode_imap_utf7(text: str) -> str:
    """Inverse of _encode_imap_utf7() — see that function for the RFC 3501
    section 5.1.3 modified-UTF-7 rules being reversed here."""
    result = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch != "&":
            result.append(ch)
            i += 1
            continue
        if i + 1 < n and text[i + 1] == "-":
            result.append("&")
            i += 2
            continue
        j = i + 1
        while j < n and text[j] != "-":
            j += 1
        b64_chunk = text[i + 1 : j].replace(",", "/")
        b64_chunk += "=" * ((-len(b64_chunk)) % 4)
        try:
            raw = base64.b64decode(b64_chunk)
            result.append(raw.decode("utf-16-be"))
        except (ValueError, UnicodeDecodeError):
            # Malformed shift sequence from an unusual server — keep the
            # original text verbatim rather than raising and losing the
            # whole LIST response for one bad folder name.
            result.append(text[i : j + 1])
        i = j + 1 if j < n else j
    return "".join(result)


_LIST_RESPONSE_RE = re.compile(
    rb'^\((?P<flags>[^)]*)\)\s+(?P<delim>NIL|"(?:[^"\\]|\\.)*")\s+(?P<name>.+?)\s*$'
)


def _unquote_imap_token(token: bytes) -> Optional[str]:
    """Decode one IMAP quoted-string or atom token to str; NIL -> None."""
    if token == b"NIL":
        return None
    text = token.decode("utf-8", errors="replace")
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        inner = text[1:-1]
        return inner.replace('\\"', '"').replace("\\\\", "\\")
    return text


def _parse_list_response(raw) -> Optional[tuple]:
    """Parse one untagged IMAP LIST line into (delimiter, folder_name).

    e.g. b'(\\HasNoChildren) "/" "WhatsApp/Alice Smith"' -> ("/", "WhatsApp/Alice Smith")

    imaplib occasionally returns a (line, literal_bytes) tuple instead of a
    plain bytes line when the mailbox name arrives as an IMAP literal (used
    for names containing unusual characters); the literal bytes are the name
    verbatim in that case.

    Returns None for lines this parser doesn't recognise — logged and
    skipped, since dropping one unparseable folder from a LIST response beats
    crashing the whole sync on an unusual server.
    """
    if isinstance(raw, tuple):
        head, literal = raw
        head_bytes = head if isinstance(head, bytes) else head.encode()
        m = _LIST_RESPONSE_RE.match(head_bytes)
        if not m:
            log.warning("Could not parse LIST response head: %r", head)
            return None
        delim = _unquote_imap_token(m.group("delim"))
        name = literal.decode("utf-8", errors="replace") if isinstance(literal, bytes) else str(literal)
        return delim, name

    if not raw:
        return None
    m = _LIST_RESPONSE_RE.match(raw)
    if not m:
        log.warning("Could not parse LIST response: %r", raw)
        return None
    delim = _unquote_imap_token(m.group("delim"))
    name = _unquote_imap_token(m.group("name")) or ""
    return delim, name


_APPENDUID_RE = re.compile(r"APPENDUID\s+(\d+)\s+(\d+)")


def _extract_appenduid(data) -> Optional[str]:
    """Pull the UID out of an APPEND response's APPENDUID response code
    (RFC 4315 UIDPLUS), when the server supports it. Format: '<uidvalidity>-<uid>'.
    Returns None if the server didn't send one (no UIDPLUS extension) — caller
    falls back to the message's own Message-ID in that case.
    """
    m = _APPENDUID_RE.search(_join_imap_response(data))
    if not m:
        return None
    return f"{m.group(1)}-{m.group(2)}"


def _internaldate_from_message(msg: "_email.message.Message") -> Optional[float]:
    """Derive an APPEND internaldate (epoch seconds) from the message's own
    Date: header — never from the clock.

    This mirrors what the two OAuth transports already do: DiscoveryTransport
    passes internalDateSource="dateHeader" and RestTransport passes
    internaldatesource=dateheader as a query param (see
    RestTransport.messages_insert and tests/test_gmail_transport.py::
    test_rest_transport_messages_insert_merges_thread_id_and_query_param).
    Both force Gmail to use the message's Date: header as the mailbox
    timestamp instead of the upload time, which matters here because a
    years-old WhatsApp archive must land dated when it was actually sent.
    IMAP has no equivalent "use the Date header" flag — the caller must
    compute and pass an explicit internaldate to APPEND, or the server
    defaults it to "now". _build_html_mime_message always sets a Date:
    header (see mail_client.py), so this should only return None for
    hand-built messages in tests.
    """
    date_header = msg.get("Date")
    if not date_header:
        log.warning(
            "messages_insert: message has no Date header; IMAP server will "
            "default internaldate to the upload time, not the original send time"
        )
        return None
    try:
        dt = parsedate_to_datetime(date_header)
    except (TypeError, ValueError) as exc:
        log.warning(
            "messages_insert: could not parse Date header %r (%s); IMAP "
            "server will default internaldate to the upload time",
            date_header, exc,
        )
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp()


class ImapTransport:
    """IMAP APPEND transport (Road B phase 1): app-password IMAP instead of
    Gmail OAuth. Implements the same 3-method MailTransport Protocol as
    DiscoveryTransport / RestTransport, so get_or_create_label(),
    _insert_with_backoff(), and push_chunks() all work against it unmodified.

    A Gmail "label" maps to an IMAP folder; the folder's path *is* its id
    (get_or_create_label uses the returned id verbatim as the next call's
    body["labelIds"][0], so id and name must always be equal — see plan §9.5).

    Folder names are kept in canonical '/'-delimited form (e.g.
    "WhatsApp/Alice") at this Protocol boundary, matching what
    get_or_create_label always builds via _full_label_name(). The real
    server-side hierarchy delimiter (learned from LIST, cached per
    connection) is only substituted in at the two points that actually talk
    to the wire: the CREATE/SUBSCRIBE mailbox name in labels_create(), and
    the APPEND mailbox name in messages_insert(). LIST responses are
    translated back from the wire delimiter to '/' when parsed. This keeps
    the id==name contract intact regardless of what delimiter a given
    provider uses.
    """

    def __init__(
        self,
        host: str,
        port: int,
        email: str,
        password: str,
        connection_factory: Optional[Callable[[], "imaplib.IMAP4"]] = None,
        set_seen: bool = True,
    ) -> None:
        self._host = host
        self._port = port
        self._email = email
        self._password = password
        self._set_seen = set_seen
        # Constructor-injected fake connection factory for tests; production
        # callers (build_imap_transport) leave this None and get a real
        # imaplib.IMAP4_SSL login via _default_connection_factory().
        self._connection_factory = connection_factory
        self._conn: Optional["imaplib.IMAP4"] = None
        self._delimiter: Optional[str] = None

    # -- connection lifetime --------------------------------------------

    def _default_connection_factory(self) -> "imaplib.IMAP4":
        # ssl_context is REQUIRED, not optional hardening: imaplib falls back
        # to ssl._create_stdlib_context(), which sets check_hostname=False and
        # verify_mode=CERT_NONE, so the handshake would succeed against any
        # certificate at all — handing the app password (sent in the clear
        # inside LOGIN) and every archived chat to an active MITM.
        # create_default_context() gives check_hostname=True + CERT_REQUIRED.
        #
        # timeout is keyword-only and is applied to the underlying socket, so
        # it bounds every later read too, not just the connect. Without it a
        # server that accepts the TCP connection and then never replies hangs
        # the worker thread forever. Same value the OAuth path already uses.
        try:
            conn = imaplib.IMAP4_SSL(
                self._host,
                self._port,
                ssl_context=ssl.create_default_context(),
                timeout=GMAIL_SOCKET_TIMEOUT,
            )
        except ssl.SSLError as exc:
            raise MailTransportError(
                f"TLS verification failed for {self._host}:{self._port}: "
                f"{_strip_secret(str(exc), self._password)} — the server's "
                "certificate could not be verified. Refusing to send "
                "credentials over this connection.",
                status=503,
            ) from exc
        except OSError as exc:
            # socket.timeout is an OSError subclass, so a connect that hangs
            # past GMAIL_SOCKET_TIMEOUT lands here rather than blocking.
            raise MailTransportError(
                f"Could not connect to {self._host}:{self._port}: "
                f"{_strip_secret(str(exc), self._password)}",
                status=503,
            ) from exc
        try:
            conn.login(self._email, self._password)
        except imaplib.IMAP4.error as exc:
            # login() is the one imaplib convenience method that self-raises
            # on any non-OK response, so it's a clean hook for the one thing
            # Q1 requires: an account whose provider/admin has disabled
            # app-password IMAP looks identical from here to a wrong
            # password, and both must fail permanently (never retried) with
            # a message that tells the user what's likely going on, instead
            # of the raw imaplib string being retried 5x by
            # _insert_with_backoff before finally surfacing.
            raise MailTransportError(
                "IMAP login failed for %s @ %s:%s — this can mean a wrong "
                "password, but for many providers (especially Workspace/"
                "Microsoft 365 accounts) it means the provider or an admin "
                "has disabled app-password / basic-auth IMAP access "
                "entirely. Server said: %s"
                % (self._email, self._host, self._port, _strip_secret(str(exc), self._password)),
                status=401,
            ) from exc
        except OSError as exc:
            raise MailTransportError(
                f"Could not connect to {self._host}:{self._port}: "
                f"{_strip_secret(str(exc), self._password)}",
                status=503,
            ) from exc
        return conn

    def _get_conn(self) -> "imaplib.IMAP4":
        if self._conn is None:
            factory = self._connection_factory or self._default_connection_factory
            self._conn = factory()
        return self._conn

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.logout()
            except Exception:
                pass
            finally:
                self._conn = None
                self._delimiter = None

    def _call(self, method: str, *args):
        """Invoke an imaplib.IMAP4 method on the live connection, reconnecting
        once transparently if the connection has been dropped/aborted (IMAP
        servers commonly close idle connections). A fresh IMAP4 connection is
        a brand new session with no memory of the old one, but none of our
        calls (LIST/CREATE/SUBSCRIBE/APPEND) require a prior SELECT, so there
        is no server-side session state to re-establish after a reconnect —
        the only thing we cache locally is the hierarchy delimiter, which is
        conservatively dropped here and re-learned on the next labels_list().
        """
        conn = self._get_conn()
        try:
            return getattr(conn, method)(*args)
        except imaplib.IMAP4.abort as exc:
            log.warning("IMAP connection aborted during %s; reconnecting once: %s", method, exc)
            self._conn = None
            self._delimiter = None
            conn = self._get_conn()
            return getattr(conn, method)(*args)

    # -- delimiter translation -------------------------------------------

    def _to_wire(self, name: str) -> str:
        delim = self._delimiter
        if not delim or delim == "/":
            return name
        return name.replace("/", delim)

    def _from_wire(self, name: str) -> str:
        delim = self._delimiter
        if not delim or delim == "/":
            return name
        return name.replace(delim, "/")

    # -- wire encoding (RFC 3501 quoting + modified UTF-7) -----------------
    #
    # mUTF-7 encode/decode is deliberately kept OUTSIDE _to_wire()/_from_wire()
    # above, which the class docstring documents as handling the hierarchy
    # delimiter only. Reasons:
    #   - _to_wire()/_from_wire() are pure str->str delimiter substitution and
    #     several existing tests assert on their behaviour directly; folding
    #     mUTF-7 in would change their contract.
    #   - Quoting is a *transport-argument* concern (only CREATE/SUBSCRIBE/
    #     APPEND's mailbox argument needs it — labels_list()'s '""'/'*' LIST
    #     arguments must NOT be touched), so it belongs next to the call
    #     sites, not inside a name-translation helper reused by parsing.
    #   - The two translations must compose in opposite orders on encode vs.
    #     decode: encode does delimiter-substitution first (while the name is
    #     still plain text, so "/" reliably maps to the server's delimiter),
    #     then mUTF-7-encodes the result; decode reverses that — mUTF-7-decode
    #     first, then delimiter-substitution back to "/". This is safe because
    #     delimiter characters are always printable ASCII, so mUTF-7 always
    #     passes them through literally in both directions.
    # Quoting is layered on top only for the outbound (to-wire) direction:
    # _unquote_imap_token() (used by _parse_list_response()) already strips
    # the RFC 3501 quoted-string wrapper on the way in, so the inbound side
    # only ever needs the mUTF-7 decode here.
    def _mailbox_to_wire(self, name: str) -> str:
        """Canonical '/'-delimited label name -> a fully wire-ready mailbox
        argument for CREATE/SUBSCRIBE/APPEND: delimiter substitution, then
        RFC 3501 SS5.1.3 modified UTF-7 encoding, then RFC 3501 SS4.3
        quoted-string quoting."""
        wire_name = self._to_wire(name)
        return _quote_imap_mailbox(_encode_imap_utf7(wire_name))

    def _mailbox_from_wire(self, wire_name: str) -> str:
        """Inverse of _mailbox_to_wire() for a name already unquoted by
        _unquote_imap_token(): mUTF-7 decode, then delimiter substitution
        back to the canonical '/' form."""
        return self._from_wire(_decode_imap_utf7(wire_name))

    # -- error mapping -----------------------------------------------------

    def _map_exception(self, exc: Exception, context: str) -> MailTransportError:
        if isinstance(exc, MailTransportError):
            return exc  # already normalized (e.g. by _default_connection_factory)
        text = _strip_secret(str(exc), self._password)
        if isinstance(exc, imaplib.IMAP4.abort):
            return MailTransportError(f"{context}: IMAP connection aborted: {text}", status=503)
        if isinstance(exc, (socket.timeout, TimeoutError, ConnectionError, OSError)):
            return MailTransportError(f"{context}: network error: {text}", status=503)
        if isinstance(exc, imaplib.IMAP4.error):
            # BAD/other self-raised imaplib errors. Deliberately NOT treated
            # as retryable by default (plan §9.2: don't blanket-retry bare
            # imaplib.IMAP4.error) — only abort/OSError above are.
            return MailTransportError(f"{context}: {text}", status=_status_for_imap_text(text))
        return MailTransportError(f"{context}: {text}", status=400)

    def _map_response(self, typ: str, data, context: str) -> MailTransportError:
        text = _join_imap_response(data)
        return MailTransportError(
            f"{context} failed ({typ}): {text}", status=_status_for_imap_text(text)
        )

    # -- MailTransport protocol -------------------------------------------

    def labels_list(self) -> dict:
        try:
            typ, data = self._call("list", '""', "*")
        except Exception as exc:
            raise self._map_exception(exc, "LIST") from exc
        if typ != "OK":
            raise self._map_response(typ, data, "LIST")

        labels = []
        for raw in data:
            parsed = _parse_list_response(raw)
            if parsed is None:
                continue
            delim, wire_name = parsed
            if delim is not None:
                self._delimiter = delim
            canonical_name = self._mailbox_from_wire(wire_name)
            labels.append({"name": canonical_name, "id": canonical_name})
        return {"labels": labels}

    def labels_create(self, body: dict) -> dict:
        name = body["name"]
        wire_arg = self._mailbox_to_wire(name)
        try:
            typ, data = self._call("create", wire_arg)
        except Exception as exc:
            raise self._map_exception(exc, "CREATE") from exc
        # "Already exists" is success, not an error (get_or_create_label may
        # race with a previous run, or the folder may pre-exist from before
        # this app managed it).
        if typ != "OK" and not _is_already_exists_response(data):
            raise self._map_response(typ, data, "CREATE")

        try:
            self._call("subscribe", wire_arg)
        except Exception as exc:
            # Subscription only affects whether other IMAP clients show the
            # folder by default; the folder itself exists and APPEND/
            # labels_list both work without it, so this is best-effort.
            log.warning(
                "Could not SUBSCRIBE to %r: %s", wire_arg,
                _strip_secret(str(exc), self._password),
            )
        return {"id": name}

    def messages_insert(self, body: dict, thread_id: Optional[str] = None) -> dict:
        raw_bytes = base64.urlsafe_b64decode(body["raw"])
        # RFC 3501 literals are CRLF-terminated; the message as built by
        # _build_html_mime_message() uses Python's default (LF-only) email
        # policy. Normalize explicitly rather than relying solely on
        # imaplib.IMAP4.append()'s own internal MapCRLF remap, so the bytes
        # we hand off are correct regardless of which imaplib internals a
        # given Python version happens to apply them at.
        raw_bytes = re.sub(rb"\r?\n", b"\r\n", raw_bytes)

        folder = body["labelIds"][0]
        wire_folder = self._mailbox_to_wire(folder)

        msg = _email.message_from_bytes(raw_bytes)
        internaldate = _internaldate_from_message(msg)
        message_id = msg.get("Message-ID") or _new_message_id()

        flags = "(\\Seen)" if self._set_seen else None

        try:
            typ, data = self._call("append", wire_folder, flags, internaldate, raw_bytes)
        except Exception as exc:
            raise self._map_exception(exc, "APPEND") from exc
        if typ != "OK":
            raise self._map_response(typ, data, "APPEND")

        uid = _extract_appenduid(data)
        response_thread_id = thread_id or message_id
        return {"id": uid or message_id, "threadId": response_thread_id}


def build_imap_transport(host: str, port: int, email: str, password: str) -> MailTransport:
    """Build the IMAP APPEND transport (Road B, phase 1 backend).

    Purely additive alongside build_transport() (OAuth/Discovery, Windows)
    and set_token() (OAuth/REST, Android) — none of those are touched. See
    IMAP_PROVIDERS / MAIL_BACKEND_* in src/config.py for the (not-yet-wired;
    phase 2) preset/selection vocabulary this pairs with.
    """
    return ImapTransport(host=host, port=port, email=email, password=password)


# ---------------------------------------------------------------------------
# Label management
# ---------------------------------------------------------------------------


def _sanitise_label_name(name: str) -> str:
    """Remove characters Gmail doesn't allow inside a label name segment."""
    # Gmail forbids leading/trailing whitespace and '/' within the name part.
    name = name.strip()
    name = name.replace("/", "-")  # swap slashes (reserved as hierarchy delimiter)
    # Enforce Gmail's 225-char limit on the full label name.
    # We budget: len(LABEL_PARENT) + 1 (/) + len(name) ≤ LABEL_MAX_LENGTH
    max_child = LABEL_MAX_LENGTH - len(LABEL_PARENT) - 1
    if len(name) > max_child:
        log.warning(
            "Label name truncated from %d to %d chars: %r", len(name), max_child, name
        )
        name = name[:max_child].rstrip()
    return name


def _full_label_name(display_name: str) -> str:
    return f"{LABEL_PARENT}/{_sanitise_label_name(display_name)}"


def get_or_create_label(transport: MailTransport, display_name: str) -> str:
    """Return the Gmail label ID for 'WhatsApp/<display_name>', creating if absent.

    Also ensures the parent 'WhatsApp' label exists.

    Returns:
        The label ID string (e.g. 'Label_123456789').
    """
    target = _full_label_name(display_name)

    # Fetch all existing labels in one call.
    result = transport.labels_list()
    existing: dict[str, str] = {
        lbl["name"]: lbl["id"] for lbl in result.get("labels", [])
    }

    # Ensure parent label exists.
    if LABEL_PARENT not in existing:
        log.info("Creating parent label '%s'", LABEL_PARENT)
        resp = transport.labels_create(
            {"name": LABEL_PARENT, "labelListVisibility": "labelShow",
             "messageListVisibility": "show"}
        )
        existing[LABEL_PARENT] = resp["id"]

    # Return or create child label.
    if target in existing:
        return existing[target]

    log.info("Creating label '%s'", target)
    resp = transport.labels_create(
        {
            "name": target,
            "labelListVisibility": "labelShow",
            "messageListVisibility": "show",
        },
    )
    return resp["id"]


# ---------------------------------------------------------------------------
# Message chunking
# ---------------------------------------------------------------------------


ChunkSize = Union[str, int]  # "day" | "hour" | "week" | positive int


def chunk_messages(
    messages: list[ParsedMessage],
    chunk_size: ChunkSize = "day",
) -> list[list[ParsedMessage]]:
    """Group messages into chunks for batching into emails.

    Args:
        messages:   Sorted (ascending) list of ParsedMessage.
        chunk_size: "day", "hour", "week", or a positive integer (message count).

    Returns:
        List of non-empty sublists; order preserved.
    """
    if not messages:
        return []

    if isinstance(chunk_size, int):
        return [messages[i : i + chunk_size] for i in range(0, len(messages), chunk_size)]

    if chunk_size not in ("day", "hour", "week"):
        raise ValueError(f"chunk_size must be 'day', 'hour', 'week', or int; got {chunk_size!r}")

    def _bucket(ts: datetime) -> tuple:
        if chunk_size == "hour":
            return (ts.year, ts.month, ts.day, ts.hour)
        if chunk_size == "week":
            iso = ts.isocalendar()
            return (iso[0], iso[1])  # (ISO year, ISO week number)
        # "day"
        return (ts.year, ts.month, ts.day)

    chunks: list[list[ParsedMessage]] = []
    current_bucket = None
    current_chunk: list[ParsedMessage] = []

    for msg in messages:
        b = _bucket(msg.timestamp)
        if b != current_bucket:
            if current_chunk:
                chunks.append(current_chunk)
            current_chunk = [msg]
            current_bucket = b
        else:
            current_chunk.append(msg)

    if current_chunk:
        chunks.append(current_chunk)

    return chunks


# ---------------------------------------------------------------------------
# Email construction
# ---------------------------------------------------------------------------


def _format_chunk_body(chunk: list[ParsedMessage]) -> str:
    """Render a chunk of messages as a readable plain-text email body."""
    lines: list[str] = []
    for msg in chunk:
        time_str = msg.timestamp.strftime("%H:%M")
        header = f"[{time_str}] {msg.sender}:"
        body_lines = msg.body.splitlines()
        if body_lines:
            lines.append(f"{header} {body_lines[0]}")
            indent = " " * (len(header) + 1)
            for continuation in body_lines[1:]:
                lines.append(f"{indent}{continuation}")
        else:
            lines.append(header)
    return "\n".join(lines)


def _chunk_subject(
    display_name: str,
    chunk: list[ParsedMessage],
    chunk_size: ChunkSize,
    suffix: str = "",
) -> str:
    """Generate the email subject for a chunk, with an optional Part k/N suffix."""
    first_ts = chunk[0].timestamp
    if chunk_size == "hour":
        label = first_ts.strftime("%Y-%m-%d %H:00")
    elif chunk_size == "week":
        iso = first_ts.isocalendar()
        label = f"Week {iso[1]:02d}, {iso[0]}"
    elif isinstance(chunk_size, int):
        label = first_ts.strftime("%Y-%m-%d") + f" (+{len(chunk)} msgs)"
    else:
        label = first_ts.strftime("%Y-%m-%d")
    base = f"WhatsApp: {display_name} — {label}"
    return f"{base}  ({suffix})" if suffix else base


def _format_sender(display_name: str) -> str:
    """Build a From address that Gmail can match against contacts.

    - Regular names ("Jane Roe") → "Jane Roe <whatsapp-sync@local>"
      Gmail matches the display name against Google Contacts, so the contact
      card appears automatically if the name is saved.

    - Phone numbers ("+91 98765 43210", "919876543210") are normalised to
      international format with a leading '+', which is how they're stored in
      Google Contacts → "+919876543210 <whatsapp-sync@local>"
    """
    # Strip formatting characters to test if it's a phone number.
    stripped = re.sub(r"[\s\-\(\)]", "", display_name)
    if re.match(r"^\+?\d{7,15}$", stripped):
        normalized = stripped if stripped.startswith("+") else f"+{stripped}"
        return formataddr((normalized, "whatsapp-sync@local"))
    return formataddr((display_name, "whatsapp-sync@local"))


def _build_mime_message(
    display_name: str,
    chunk: list[ParsedMessage],
    chunk_size: ChunkSize,
    label_id: str,
    message_id: str,
    in_reply_to: Optional[str] = None,
    references: Optional[str] = None,
) -> dict:
    """Build the RFC 2822 message and return the base64url-encoded dict for insert().

    Plain-text only — no HTML part needed for archived chat messages.
    """
    body_text = _format_chunk_body(chunk)
    msg = MIMEText(body_text, "plain", "utf-8")
    msg["Subject"] = _chunk_subject(display_name, chunk, chunk_size)
    msg["From"] = _format_sender(display_name)
    msg["To"] = "me"
    msg["Message-ID"] = message_id
    msg["Date"] = chunk[0].timestamp.strftime("%a, %d %b %Y %H:%M:%S +0000")

    if in_reply_to:
        msg["In-Reply-To"] = in_reply_to
        msg["References"] = references or in_reply_to

    raw = base64.urlsafe_b64encode(msg.as_bytes()).decode()
    return {"raw": raw, "labelIds": [label_id]}


def _new_message_id() -> str:
    return f"<wa-sync-{uuid.uuid4().hex}@local>"


# ---------------------------------------------------------------------------
# HTML MIME message builder (Phase 2.5)
# ---------------------------------------------------------------------------

def _build_html_mime_message(
    display_name: str,
    chunk: list[ParsedMessage],
    chunk_size: ChunkSize,
    rendered: RenderedChunk,
    label_id: str,
    message_id: str,
    suffix: str = "",
    in_reply_to: Optional[str] = None,
    references: Optional[str] = None,
) -> dict:
    """Build a multipart HTML MIME message for gmail.users.messages.insert().

    MIME structure:
      multipart/mixed          ← only present when non-inline attachments exist
      └── multipart/related
          ├── text/html        ← the rendered bubble HTML
          └── image/*  × N    ← CID-referenced inline images
      (+ application/* attachments at mixed level)

    Returns the base64url-encoded dict expected by the Gmail API.
    """
    # ── multipart/related: HTML + inline images ───────────────────────────
    related = MIMEMultipart("related")

    html_part = MIMEText(rendered.html_body, "html", "utf-8")
    related.attach(html_part)

    for part in rendered.inline_parts:
        main_t, sub_t = part.mime_type.split("/", 1)
        img = MIMEBase(main_t, sub_t)
        img.set_payload(part.data)
        _encoders.encode_base64(img)
        img["Content-ID"]          = f"<{part.cid}>"
        img["Content-Disposition"] = "inline"
        related.attach(img)

    # ── Wrap in multipart/mixed if there are file attachments ─────────────
    if rendered.attachments:
        root = MIMEMultipart("mixed")
        root.attach(related)
        for att in rendered.attachments:
            main_t, sub_t = att.mime_type.split("/", 1)
            file_part = MIMEBase(main_t, sub_t)
            file_part.set_payload(att.data)
            _encoders.encode_base64(file_part)
            # Use add_header() so Python's email library applies RFC 2231 encoding,
            # preventing header injection via embedded quotes, CR, LF, or non-ASCII.
            file_part.add_header("Content-Disposition", "attachment", filename=att.filename)
            root.attach(file_part)
    else:
        root = related

    # ── RFC 2822 headers ──────────────────────────────────────────────────
    subject = _chunk_subject(display_name, chunk, chunk_size, suffix)
    root["Subject"]    = subject
    root["From"]       = _format_sender(display_name)
    root["To"]         = "me"
    root["Message-ID"] = message_id
    root["Date"]       = chunk[0].timestamp.strftime("%a, %d %b %Y %H:%M:%S +0000")

    if in_reply_to:
        root["In-Reply-To"] = in_reply_to
        root["References"]  = references or in_reply_to

    raw = base64.urlsafe_b64encode(root.as_bytes()).decode()
    return {"raw": raw, "labelIds": [label_id]}


# ---------------------------------------------------------------------------
# Size-aware chunk splitting (Phase 2.5)
# ---------------------------------------------------------------------------

def _size_split_cached(
    messages: list[ParsedMessage],
    display_name: str,
    extractor: Optional[MediaExtractor],
    depth: int = 0,
) -> list[tuple[list[ParsedMessage], RenderedChunk]]:
    """Recursively split messages so each piece renders under MAX_EMAIL_SIZE_BYTES.

    Renders once per call and caches the result to avoid redundant work.
    Returns a list of (sub_messages, rendered_with_no_label_suffix).
    """
    if not messages:
        return []

    # Render with no suffix first; suffix is applied later when N is known.
    rendered = render_chunk(messages, display_name, extractor, "")

    if rendered.total_bytes <= MAX_EMAIL_SIZE_BYTES or len(messages) <= 1 or depth >= 5:
        return [(messages, rendered)]

    log.debug(
        "_size_split_cached: chunk of %d msgs is %d bytes — splitting (depth=%d)",
        len(messages), rendered.total_bytes, depth,
    )
    mid = len(messages) // 2
    return (
        _size_split_cached(messages[:mid], display_name, extractor, depth + 1)
        + _size_split_cached(messages[mid:], display_name, extractor, depth + 1)
    )


def _prepare_emails(
    chunks: list[list[ParsedMessage]],
    display_name: str,
    extractor: Optional[MediaExtractor],
    chunk_size: ChunkSize,
) -> list[tuple[list[ParsedMessage], RenderedChunk]]:
    """Flatten all chunks into a final list of (sub_chunk, RenderedChunk).

    Oversized chunks are split by _size_split_cached, then each piece is
    re-rendered with the correct "Part k/N" label once the total count is known.
    """
    result: list[tuple[list[ParsedMessage], RenderedChunk]] = []
    for chunk in chunks:
        sub_pairs = _size_split_cached(chunk, display_name, extractor)
        n = len(sub_pairs)
        for k, (msgs, cached_render) in enumerate(sub_pairs):
            if n > 1:
                # This specific day/period chunk was too large and got split —
                # label only these parts (e.g. "Part 1/3").
                suffix   = f"Part {k + 1}/{n}"
                rendered = render_chunk(msgs, display_name, extractor, suffix)
            else:
                # Normal single email for this period — no suffix.
                rendered = cached_render
            result.append((msgs, rendered))
    return result


def _print_progress(
    display_name: str, chunk: int, total_chunks: int, msgs_done: int, total_msgs: int
) -> None:
    """Overwrite the current terminal line with a compact progress indicator.

    No-ops silently when sys.stderr is None (PyInstaller GUI bundle, console=False).
    """
    if sys.stderr is None:
        return
    pct = int(100 * msgs_done / total_msgs) if total_msgs else 0
    bar_filled = pct // 5          # 20-char bar
    bar = "#" * bar_filled + "-" * (20 - bar_filled)
    line = (
        f"\r  {display_name}: [{bar}] {pct:3d}%"
        f"  chunk {chunk}/{total_chunks}"
        f"  ({msgs_done}/{total_msgs} messages)"
    )
    sys.stderr.write(line[:79])    # cap at terminal width
    sys.stderr.flush()


# ---------------------------------------------------------------------------
# Insert with retry / backoff
# ---------------------------------------------------------------------------


def _insert_with_backoff(
    transport: MailTransport,
    body: dict,
    thread_id: Optional[str] = None,
) -> dict:
    """Call messages.insert() with exponential backoff on 429 / 5xx.

    Args:
        body:      The message body dict (raw + labelIds).
        thread_id: If set, added to the request body so Gmail places the message
                   in the existing thread.

    Returns:
        The API response dict (contains 'id' and 'threadId').

    Raises:
        MailTransportError: after BACKOFF_MAX_ATTEMPTS failures.
    """
    # Exceptions that warrant a retry (network/timeout errors in addition to
    # HTTP 429 / 5xx).  socket.timeout is an alias for TimeoutError on Py3.3+.
    _RETRYABLE_NETWORK = (socket.timeout, TimeoutError, ConnectionError, OSError)

    delay = BACKOFF_BASE_DELAY
    for attempt in range(1, BACKOFF_MAX_ATTEMPTS + 1):
        try:
            response = transport.messages_insert(body, thread_id=thread_id)
            time.sleep(API_CALL_DELAY_SECONDS)
            return response
        except MailTransportError as exc:
            if exc.status in (429, 500, 502, 503, 504) and attempt < BACKOFF_MAX_ATTEMPTS:
                log.warning(
                    "Gmail API %s on attempt %d/%d - retrying in %.1fs",
                    exc.status, attempt, BACKOFF_MAX_ATTEMPTS, delay,
                )
                time.sleep(delay)
                delay *= 2
            else:
                raise
        except _RETRYABLE_NETWORK as exc:
            if attempt < BACKOFF_MAX_ATTEMPTS:
                log.warning(
                    "Network error on attempt %d/%d (%s) - retrying in %.1fs",
                    attempt, BACKOFF_MAX_ATTEMPTS, exc, delay,
                )
                time.sleep(delay)
                delay *= 2
            else:
                raise


# ---------------------------------------------------------------------------
# High-level push API
# ---------------------------------------------------------------------------


class PushResult:
    """Outcome of pushing one chunk to Gmail."""
    __slots__ = ("message_id", "gmail_message_id", "thread_id")

    def __init__(self, message_id: str, gmail_message_id: str, thread_id: str) -> None:
        self.message_id = message_id          # RFC 2822 Message-ID we generated
        self.gmail_message_id = gmail_message_id  # Gmail's internal message ID
        self.thread_id = thread_id            # Gmail thread ID


def push_chunks(
    transport: MailTransport,
    display_name: str,
    chunks: list[list[ParsedMessage]],
    label_id: str,
    chunk_size: ChunkSize = "day",
    anchor_message_id: Optional[str] = None,
    gmail_thread_id: Optional[str] = None,
    dry_run: bool = False,
    source_path: Optional[Path] = None,
    on_chunk: Optional[Callable[[int, int, int, int, list[ParsedMessage]], None]] = None,
) -> list[PushResult]:
    """Push a list of message chunks to Gmail as individual HTML emails in one thread.

    Each chunk may be sub-split if its rendered size would exceed MAX_EMAIL_SIZE_BYTES.
    HTML rendering and media embedding are handled automatically when source_path
    points to the original export file (ZIP or .txt).

    Args:
        display_name:      Human-readable chat name (used in subject line).
        chunks:            Output of chunk_messages(); each sublist → one email (before splitting).
        label_id:          Gmail label ID to apply to every email.
        chunk_size:        Chunk size (for subject generation).
        anchor_message_id: RFC 2822 Message-ID of the thread's first email.
        gmail_thread_id:   Gmail thread ID from the first insert.
        dry_run:           If True, log what would be pushed without calling Gmail.
        source_path:       Path to the original export file; used to build a
                           MediaExtractor for embedding images/attachments.
        on_chunk:          Optional callback(email_index_1based, total_emails,
                           msgs_done, total_msgs, chunk_messages), invoked once
                           per email — and only *after* that email's
                           messages.insert()/APPEND has returned successfully
                           (see the call site below) — lets callers (e.g. the
                           Android sync-progress screen) show live within-file
                           progress for large chats, instead of only ever
                           learning done/total *files* (a chat with hundreds
                           of chunks can take minutes to push, during which
                           file-level progress alone looks frozen even though
                           work is progressing). `chunk_messages` is exactly
                           the sub-list of ParsedMessage that just landed in
                           the mailbox in that one email — callers that need
                           to durably record "this much has been delivered"
                           (e.g. sync_manager's message-hash bookkeeping) key
                           off this rather than the whole `messages` argument,
                           so an interruption partway through a push leaves
                           the record matching reality instead of all-or-nothing.

    Returns:
        List of PushResult, one per email sent.
    """
    results: list[PushResult] = []
    current_anchor_mid = anchor_message_id
    current_thread_id  = gmail_thread_id

    # ── Build and render all emails upfront (includes size-based splitting) ──
    extractor: Optional[MediaExtractor] = (
        MediaExtractor(source_path) if source_path else None
    )
    try:
        email_list = _prepare_emails(chunks, display_name, extractor, chunk_size)
    finally:
        if extractor:
            extractor.close()

    total_emails = len(email_list)
    total_msgs   = sum(len(sc) for sc, _ in email_list)
    msgs_done    = 0

    for i, (sub_chunk, rendered) in enumerate(email_list):
        new_mid     = _new_message_id()
        in_reply_to = current_anchor_mid

        if dry_run:
            subject = _chunk_subject(display_name, sub_chunk, chunk_size)
            log.info(
                "[dry-run] Would push email %d/%d: %d messages → subject=%r thread=%s",
                i + 1, total_emails, len(sub_chunk), subject, current_thread_id or "(new)",
            )
            results.append(
                PushResult(new_mid, f"dry-run-{i}", current_thread_id or "dry-run-thread")
            )
            if current_anchor_mid is None:
                current_anchor_mid = new_mid
                current_thread_id  = "dry-run-thread"
            continue

        _print_progress(display_name, i + 1, total_emails, msgs_done, total_msgs)

        body = _build_html_mime_message(
            display_name   = display_name,
            chunk          = sub_chunk,
            chunk_size     = chunk_size,
            rendered       = rendered,
            label_id       = label_id,
            message_id     = new_mid,
            in_reply_to    = in_reply_to,
            references     = current_anchor_mid,
        )

        response     = _insert_with_backoff(transport, body, thread_id=current_thread_id)
        thread_id_r: str = response["threadId"]
        gmail_msg_id: str = response["id"]

        msgs_done += len(sub_chunk)
        results.append(PushResult(new_mid, gmail_msg_id, thread_id_r))
        log.debug(
            "Pushed email %d/%d (%d msgs, %d bytes) → gmail_id=%s thread=%s",
            i + 1, total_emails, len(sub_chunk), rendered.total_bytes,
            gmail_msg_id, thread_id_r,
        )
        if on_chunk is not None:
            # sub_chunk is the exact set of messages this email just carried,
            # and this call happens strictly after _insert_with_backoff()
            # returned successfully above — so by the time a caller sees this,
            # the messages are durably in the mailbox, not merely attempted.
            on_chunk(i + 1, total_emails, msgs_done, total_msgs, sub_chunk)

        if current_anchor_mid is None:
            current_anchor_mid = new_mid
        if current_thread_id is None:
            current_thread_id = thread_id_r

    if not dry_run and email_list and sys.stderr is not None:
        sys.stderr.write("\r" + " " * 72 + "\r")
        sys.stderr.flush()

    return results


# ---------------------------------------------------------------------------
# Convenience: full push for a single chat
# ---------------------------------------------------------------------------


def push_chat(
    transport: MailTransport,
    display_name: str,
    messages: list[ParsedMessage],
    chunk_size: ChunkSize = "day",
    label_id: Optional[str] = None,
    anchor_message_id: Optional[str] = None,
    gmail_thread_id: Optional[str] = None,
    dry_run: bool = False,
    source_path: Optional[Path] = None,
    on_chunk: Optional[Callable[[int, int, int, int, list[ParsedMessage]], None]] = None,
) -> tuple[list[PushResult], str, str]:
    """High-level helper: chunk messages, ensure label, and push to Gmail.

    Resolves (or creates) the label automatically when label_id is None.

    Returns:
        (results, label_id, thread_id) — all three values that the caller
        should persist back to the chats / sync_runs tables.
    """
    if not messages:
        return [], label_id or "", gmail_thread_id or ""

    if label_id is None and not dry_run:
        label_id = get_or_create_label(transport, display_name)

    label_id = label_id or "dry-run-label"

    chunks = chunk_messages(messages, chunk_size)
    log.info(
        "%s%s: %d messages → %d chunk(s)",
        "[dry-run] " if dry_run else "",
        display_name,
        len(messages),
        len(chunks),
    )

    results = push_chunks(
        transport=transport,
        display_name=display_name,
        chunks=chunks,
        label_id=label_id,
        chunk_size=chunk_size,
        anchor_message_id=anchor_message_id,
        gmail_thread_id=gmail_thread_id,
        dry_run=dry_run,
        source_path=source_path,
        on_chunk=on_chunk,
    )

    final_thread_id = results[-1].thread_id if results else (gmail_thread_id or "")
    return results, label_id, final_thread_id
