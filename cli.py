"""
CLI entry point for Chat Mail Sync.

Usage examples:
  python cli.py sync
  python cli.py sync --dry-run --verbose
  python cli.py sync --chunk-size hour --chat "John Doe"
  python cli.py status
  python cli.py log --days 30
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
    is_gmail_mailbox,
    mailbox_clear_steps,
)
from src.state import (
    MailboxNotClearedError,
    count_archived_messages,
    get_recent_runs,
    get_sync_summary,
    init_db,
    reset_chat,
    resolve_chat,
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
        print("DRY RUN — no mail API calls will be made, no state will be written.\n")

    # Authenticate (skipped in dry-run only if no credentials exist yet, to
    # allow offline testing; the push path would fail anyway). Backend
    # (Gmail OAuth vs IMAP) is whatever the desktop app's Settings panel has
    # saved to .settings.json; gui_worker has no tkinter import so this stays
    # a plain, GUI-free dependency.
    transport = None
    if not args.dry_run:
        from gui_worker import build_transport_for_active_backend
        try:
            transport = build_transport_for_active_backend()
        except (FileNotFoundError, RuntimeError) as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

    from src.sync_manager import SyncManager
    mgr = SyncManager(
        transport=transport,
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
# Command: log
# ---------------------------------------------------------------------------


def cmd_log(args: argparse.Namespace) -> int:
    """Mirrors the Android app's Sync log screen — same underlying
    state.get_recent_runs() query (per-run history: trigger, status, message
    counts, error), just rendered as a table instead of a Compose list."""
    init_db(STATE_DB_PATH)
    rows = get_recent_runs(days=args.days, db_path=STATE_DB_PATH)

    if not rows:
        print(f"No sync runs in the last {args.days} days.")
        return 0

    w_name = max(len(r["display_name"]) for r in rows)

    header = (
        f"{'Chat':<{w_name}}  {'Status':<8}  {'Trigger':<15}  "
        f"{'Started':<19}  {'Synced':>7}  {'Skipped':>7}"
    )
    print(header)
    print("-" * len(header))

    for r in rows:
        print(
            f"{r['display_name']:<{w_name}}  {r['status']:<8}  {r['trigger']:<15}  "
            f"{(r['started_at'] or '')[:19]:<19}  "
            f"{r['messages_synced']:>7}  {r['messages_skipped']:>7}"
        )
        if r["status"] == "failed" and r["error_message"]:
            print(f"{'':<{w_name}}    error: {r['error_message']}")
    return 0


# ---------------------------------------------------------------------------
# Command: reset
# ---------------------------------------------------------------------------


def cmd_reset(args: argparse.Namespace) -> int:
    init_db(STATE_DB_PATH)

    # Accept either chat_id or display_name.
    target = args.chat_id
    chat = resolve_chat(target, STATE_DB_PATH)

    if chat is None:
        print(f"No chat found matching {target!r}.", file=sys.stderr)
        print("Run `python cli.py status` to see tracked chats.", file=sys.stderr)
        return 1

    chat_id      = chat["chat_id"]
    display_name = chat["display_name"]

    archived = count_archived_messages(chat_id, STATE_DB_PATH)
    noun = "message" if archived == 1 else "messages"

    if archived > 0:
        # Imported here rather than at module scope: cli.py otherwise never
        # touches mail_client, and this keeps the transport imports off the
        # startup path of every other subcommand.
        from src.mail_client import mailbox_folder_for
        # Same reader gui_worker uses, so the CLI reaches the same conclusion
        # about the mailbox as the GUI would for the same settings file.
        from gui_worker import _load_mail_backend_settings

        folder = mailbox_folder_for(display_name)
        steps = mailbox_clear_steps(
            folder, is_gmail_mailbox(_load_mail_backend_settings())
        )
        numbered = "\n".join(f"  {i}. {s}" for i, s in enumerate(steps, 1))
        print(
            f"WARNING: '{display_name}' has {archived} {noun} already archived "
            f"in your mailbox, in:\n"
            f"    {folder}\n\n"
            f"Resetting makes the app forget it sent them, so the next sync files\n"
            f"a second copy. This app can never delete mail - only you can.\n\n"
            f"Before resetting:\n"
            f"{numbered}\n"
        )

    if not args.yes:
        answer = input(
            f"Reset all sync state for '{display_name}' (chat_id={chat_id!r})? "
            "This cannot be undone. [y/N] "
        ).strip().lower()
        if answer != "y":
            print("Aborted.")
            return 0
        if archived > 0:
            # Note what this does NOT claim: reset sends no mail, it only
            # clears local state. The duplicate risk is conditional on the
            # old mail still being there, which is exactly what is being
            # asked here - asserting it outright would contradict the
            # answer the user is about to give.
            answer = input(
                f"Have you already deleted those {archived} {noun} from your "
                "mailbox? No mail is sent now - the next sync re-archives all "
                f"{archived} into a fresh thread, and if the old mail is still "
                "there you get a second copy only you can clean up. [y/N] "
            ).strip().lower()
            if answer != "y":
                print("Aborted. Delete the mail first, then run this again.")
                return 0
    elif archived > 0:
        # --yes is a scripted, non-interactive path. It cannot ask, so it must
        # not assume: refuse rather than silently duplicating the user's mail.
        print(
            "Refusing to reset with --yes while mail is archived. Run without "
            "--yes so the mailbox-cleared confirmation can be answered.",
            file=sys.stderr,
        )
        return 1

    try:
        reset_chat(chat_id, STATE_DB_PATH, confirmed_mailbox_cleared=True)
    except MailboxNotClearedError as exc:
        print(f"Reset refused: {exc}", file=sys.stderr)
        return 1
    print(
        f"Reset complete for '{display_name}'. "
        "The next sync will start from scratch and create a new mail thread."
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
        description="Sync WhatsApp chat exports to your mailbox.",
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
        help="Parse and report without touching your mailbox or writing state.",
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

    # -- log --
    p_log = sub.add_parser(
        "log", help="Show recent sync run history (default: last 90 days)."
    )
    p_log.add_argument(
        "--days",
        type=int,
        default=90,
        help="How many days of history to show (default: 90).",
    )
    p_log.set_defaults(func=cmd_log)

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
        "      between exports, some timestamps may appear shifted in your mailbox.\n"
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
