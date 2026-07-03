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
import logging
import os
import re
import socket
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta
from email import encoders as _encoders
from email.mime.base import MIMEBase
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import formataddr
from pathlib import Path
from typing import Iterator, Optional, Union

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

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


def _restrict_auth_dir_acl(auth_dir: Path) -> None:
    """Restrict the auth directory's NTFS ACL to the current user only (Windows).

    Strips inherited permissions and grants full control solely to the
    logged-in user, so token.json / credentials.json (live client_secret)
    aren't readable by other local accounts. Best-effort: failures are
    logged, never raised, since this must not block authentication.
    """
    username = os.environ.get("USERNAME")
    if not username:
        log.warning(
            "Could not determine current username; skipping ACL restriction on %s",
            auth_dir,
        )
        return
    try:
        subprocess.run(
            ["icacls", str(auth_dir), "/inheritance:r", "/grant:r", f"{username}:(OI)(CI)F"],
            check=True,
            capture_output=True,
            text=True,
        )
        log.debug("Restricted ACL on %s to user %s", auth_dir, username)
    except (subprocess.CalledProcessError, OSError) as _acl_err:
        log.warning("Could not restrict ACL on %s: %s", auth_dir, _acl_err)


def get_credentials(
    credentials_file: Path = CREDENTIALS_FILE,
    token_file: Path = TOKEN_FILE,
) -> Credentials:
    """Load cached OAuth2 credentials or run the browser-based auth flow.

    On first run the browser opens for the user to grant access; the token is
    then persisted to token_file for all subsequent runs.

    Raises:
        FileNotFoundError: if credentials_file doesn't exist.
    """
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


def build_service(creds: Optional[Credentials] = None) -> _GmailService:
    """Return an authenticated Gmail API service object.

    Uses a transport-specific timeout on the Gmail API HTTP client only,
    rather than the global socket.setdefaulttimeout() which would affect all
    network I/O in the process.  Both httplib2 and google-auth-httplib2 are
    hard dependencies of google-api-python-client and always present.
    """
    if creds is None:
        creds = get_credentials()
    import httplib2
    from google_auth_httplib2 import AuthorizedHttp
    http = AuthorizedHttp(creds, http=httplib2.Http(timeout=GMAIL_SOCKET_TIMEOUT))
    return build("gmail", "v1", http=http)


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


def get_or_create_label(service: _GmailService, display_name: str) -> str:
    """Return the Gmail label ID for 'WhatsApp/<display_name>', creating if absent.

    Also ensures the parent 'WhatsApp' label exists.

    Returns:
        The label ID string (e.g. 'Label_123456789').
    """
    target = _full_label_name(display_name)

    # Fetch all existing labels in one call.
    result = service.users().labels().list(userId="me").execute()
    existing: dict[str, str] = {
        lbl["name"]: lbl["id"] for lbl in result.get("labels", [])
    }

    # Ensure parent label exists.
    if LABEL_PARENT not in existing:
        log.info("Creating parent label '%s'", LABEL_PARENT)
        resp = service.users().labels().create(
            userId="me", body={"name": LABEL_PARENT, "labelListVisibility": "labelShow",
                               "messageListVisibility": "show"}
        ).execute()
        existing[LABEL_PARENT] = resp["id"]

    # Return or create child label.
    if target in existing:
        return existing[target]

    log.info("Creating label '%s'", target)
    resp = service.users().labels().create(
        userId="me",
        body={
            "name": target,
            "labelListVisibility": "labelShow",
            "messageListVisibility": "show",
        },
    ).execute()
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
    service: _GmailService,
    body: dict,
    thread_id: Optional[str] = None,
) -> dict:
    """Call users.messages.insert() with exponential backoff on 429 / 5xx.

    Args:
        body:      The message body dict (raw + labelIds).
        thread_id: If set, added to the request body so Gmail places the message
                   in the existing thread.

    Returns:
        The API response dict (contains 'id' and 'threadId').

    Raises:
        HttpError: after BACKOFF_MAX_ATTEMPTS failures.
    """
    if thread_id:
        body = {**body, "threadId": thread_id}

    # Exceptions that warrant a retry (network/timeout errors in addition to
    # HTTP 429 / 5xx).  socket.timeout is an alias for TimeoutError on Py3.3+.
    _RETRYABLE_NETWORK = (socket.timeout, TimeoutError, ConnectionError, OSError)

    delay = BACKOFF_BASE_DELAY
    for attempt in range(1, BACKOFF_MAX_ATTEMPTS + 1):
        try:
            response = (
                service.users()
                .messages()
                .insert(userId="me", body=body, internalDateSource="dateHeader")
                .execute()
            )
            time.sleep(API_CALL_DELAY_SECONDS)
            return response
        except HttpError as exc:
            status = exc.resp.status
            if status in (429, 500, 502, 503, 504) and attempt < BACKOFF_MAX_ATTEMPTS:
                log.warning(
                    "Gmail API %d on attempt %d/%d - retrying in %.1fs",
                    status, attempt, BACKOFF_MAX_ATTEMPTS, delay,
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
    service: _GmailService,
    display_name: str,
    chunks: list[list[ParsedMessage]],
    label_id: str,
    chunk_size: ChunkSize = "day",
    anchor_message_id: Optional[str] = None,
    gmail_thread_id: Optional[str] = None,
    dry_run: bool = False,
    source_path: Optional[Path] = None,
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

        response     = _insert_with_backoff(service, body, thread_id=current_thread_id)
        thread_id_r: str = response["threadId"]
        gmail_msg_id: str = response["id"]

        msgs_done += len(sub_chunk)
        results.append(PushResult(new_mid, gmail_msg_id, thread_id_r))
        log.debug(
            "Pushed email %d/%d (%d msgs, %d bytes) → gmail_id=%s thread=%s",
            i + 1, total_emails, len(sub_chunk), rendered.total_bytes,
            gmail_msg_id, thread_id_r,
        )

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
    service: _GmailService,
    display_name: str,
    messages: list[ParsedMessage],
    chunk_size: ChunkSize = "day",
    label_id: Optional[str] = None,
    anchor_message_id: Optional[str] = None,
    gmail_thread_id: Optional[str] = None,
    dry_run: bool = False,
    source_path: Optional[Path] = None,
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
        label_id = get_or_create_label(service, display_name)

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
        service=service,
        display_name=display_name,
        chunks=chunks,
        label_id=label_id,
        chunk_size=chunk_size,
        anchor_message_id=anchor_message_id,
        gmail_thread_id=gmail_thread_id,
        dry_run=dry_run,
        source_path=source_path,
    )

    final_thread_id = results[-1].thread_id if results else (gmail_thread_id or "")
    return results, label_id, final_thread_id
