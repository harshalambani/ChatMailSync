"""
CLI entry point for WA Chat Sync.

Usage examples:
  python cli.py sync
  python cli.py sync --dry-run --verbose
  python cli.py sync --chunk-size hour --chat "John Doe"
  python cli.py status
  python cli.py reset john_doe
"""

import argparse
import logging
import sys
from pathlib import Path

from src.config import (
    DEFAULT_CHUNK_SIZE,
    INBOX_DIR,
    PROCESSED_DIR,
    STATE_DB_PATH,
)
from src.state import (
    get_chat,
    get_sync_summary,
    init_db,
    list_chats,
    reset_chat,
)


# ---------------------------------------------------------------------------
# Logging setup
# ---------------------------------------------------------------------------


def _configure_logging(verbose: bool) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        format="%(asctime)s  %(levelname)-8s  %(message)s",
        datefmt="%H:%M:%S",
        level=level,
        stream=sys.stderr,
    )
    # Silence noisy third-party loggers unless verbose.
    if not verbose:
        logging.getLogger("googleapiclient").setLevel(logging.WARNING)
        logging.getLogger("google_auth_oauthlib").setLevel(logging.WARNING)
        logging.getLogger("urllib3").setLevel(logging.WARNING)


# ---------------------------------------------------------------------------
# Chunk-size validation
# ---------------------------------------------------------------------------


def _parse_chunk_size(value: str):
    """Accept 'day', 'hour', 'week', or a positive integer string."""
    if value in ("day", "hour", "week"):
        return value
    try:
        n = int(value)
        if n < 1:
            raise ValueError
        return n
    except ValueError:
        raise argparse.ArgumentTypeError(
            f"Invalid chunk size {value!r}. "
            "Use 'day', 'hour', 'week', or a positive integer."
        )


# ---------------------------------------------------------------------------
# Command: sync
# ---------------------------------------------------------------------------


def cmd_sync(args: argparse.Namespace) -> int:
    # Warn about timezone limitation on first run.
    _timezone_notice()

    if args.dry_run:
        print("DRY RUN — no Gmail API calls will be made, no state will be written.\n")

    # Authenticate (skipped in dry-run only if no credentials exist yet, to
    # allow offline testing; the push path would fail anyway).
    service = None
    if not args.dry_run:
        from src.gmail_client import build_service
        try:
            service = build_service()
        except FileNotFoundError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

    from src.sync_manager import SyncManager
    mgr = SyncManager(
        service=service,
        chunk_size=args.chunk_size,
        dry_run=args.dry_run,
        db_path=STATE_DB_PATH,
        inbox_dir=INBOX_DIR,
        processed_dir=PROCESSED_DIR,
    )

    stats = mgr.run(chat_filter=args.chat)
    print()
    print(str(stats))
    return 0 if stats.files_failed == 0 else 1


# ---------------------------------------------------------------------------
# Command: status
# ---------------------------------------------------------------------------


def cmd_status(args: argparse.Namespace) -> int:
    init_db(STATE_DB_PATH)
    rows = get_sync_summary(STATE_DB_PATH)

    if not rows:
        print("No chats tracked yet. Run `python cli.py sync` to get started.")
        return 0

    # Column widths.
    w_name   = max(len(r["display_name"]) for r in rows)
    w_status = 8

    header = (
        f"{'Chat':<{w_name}}  {'Status':<{w_status}}  "
        f"{'Last synced':<19}  {'Msgs synced':>11}  Thread"
    )
    print(header)
    print("-" * len(header))

    for r in rows:
        status   = r["last_run_status"] or "—"
        last_ts  = (r["last_synced_ts"] or "never")[:19]
        synced   = str(r["messages_synced"] or 0)
        thread   = "yes" if r["has_thread"] else "no"
        print(
            f"{r['display_name']:<{w_name}}  {status:<{w_status}}  "
            f"{last_ts:<19}  {synced:>11}  {thread}"
        )
    return 0


# ---------------------------------------------------------------------------
# Command: reset
# ---------------------------------------------------------------------------


def cmd_reset(args: argparse.Namespace) -> int:
    init_db(STATE_DB_PATH)

    # Accept either chat_id or display_name.
    target = args.chat_id

    # Try direct chat_id lookup first.
    chat = get_chat(target, STATE_DB_PATH)

    # If not found, try matching display_name (case-insensitive).
    if chat is None:
        for row in list_chats(STATE_DB_PATH):
            if row["display_name"].lower() == target.lower():
                chat = row
                break

    if chat is None:
        print(f"No chat found matching {target!r}.", file=sys.stderr)
        print("Run `python cli.py status` to see tracked chats.", file=sys.stderr)
        return 1

    chat_id      = chat["chat_id"]
    display_name = chat["display_name"]

    if not args.yes:
        answer = input(
            f"Reset all sync state for '{display_name}' (chat_id={chat_id!r})? "
            "This cannot be undone. [y/N] "
        ).strip().lower()
        if answer != "y":
            print("Aborted.")
            return 0

    reset_chat(chat_id, STATE_DB_PATH)
    print(
        f"Reset complete for '{display_name}'. "
        "The next sync will start from scratch and create a new Gmail thread."
    )
    source_file = PROCESSED_DIR / chat["source_filename"]
    if source_file.exists():
        inbox_path = source_file.parent.parent / "inbox" / chat["source_filename"]
        print(
            f"To re-sync, move the file back to inbox:\n"
            f"  Move-Item '{source_file}' '{inbox_path}'"
        )
    return 0


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python cli.py",
        description="Sync WhatsApp chat exports to Gmail.",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="Enable debug-level logging.",
    )

    sub = parser.add_subparsers(dest="command", metavar="COMMAND")
    sub.required = True

    # -- sync --
    p_sync = sub.add_parser("sync", help="Sync all files in inbox/.")
    p_sync.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse and report without touching Gmail or writing state.",
    )
    p_sync.add_argument(
        "--chunk-size",
        metavar="SIZE",
        type=_parse_chunk_size,
        default=DEFAULT_CHUNK_SIZE,
        help="Messages per email: day (default), hour, week, or an integer.",
    )
    p_sync.add_argument(
        "--chat",
        metavar="NAME",
        default=None,
        help="Sync only this chat (display name or chat_id).",
    )
    p_sync.set_defaults(func=cmd_sync)

    # -- status --
    p_status = sub.add_parser("status", help="Show sync state for all tracked chats.")
    p_status.set_defaults(func=cmd_status)

    # -- reset --
    p_reset = sub.add_parser(
        "reset", help="Reset sync state for a specific chat (re-sync from scratch)."
    )
    p_reset.add_argument(
        "chat_id",
        help="chat_id or display name of the chat to reset.",
    )
    p_reset.add_argument(
        "-y", "--yes",
        action="store_true",
        help="Skip confirmation prompt.",
    )
    p_reset.set_defaults(func=cmd_reset)

    return parser


# ---------------------------------------------------------------------------
# Timezone notice
# ---------------------------------------------------------------------------


def _timezone_notice() -> None:
    """Print a one-time notice about naive timestamps (arch doc §2)."""
    notice_file = STATE_DB_PATH.parent / ".tz_notice_shown"
    if notice_file.exists():
        return
    print(
        "NOTE: WhatsApp exports carry no timezone information. All timestamps\n"
        "      are stored as naive local times. If your phone's timezone changed\n"
        "      between exports, some timestamps may appear shifted in Gmail.\n"
        "      See the README for details.\n"
    )
    try:
        notice_file.touch()
    except OSError:
        pass


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    _configure_logging(args.verbose)
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
