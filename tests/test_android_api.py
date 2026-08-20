import shutil
from pathlib import Path

from src import android_api, config
from src.mail_client import MailTransport
from src.state import (
    complete_sync_run,
    compute_message_hash,
    get_chat,
    insert_message_hashes,
    start_sync_run,
    upsert_chat,
)

FIXTURES_DIR = Path(__file__).parent / "fixtures"


class _FakeTransport:
    """Minimal hand-written MailTransport fake — no real network, no
    googleapiclient/requests involvement at all."""

    def __init__(self):
        self.labels = {}
        self.inserted = []

    def labels_list(self) -> dict:
        return {"labels": [{"name": n, "id": i} for n, i in self.labels.items()]}

    def labels_create(self, body: dict) -> dict:
        label_id = f"L{len(self.labels) + 1}"
        self.labels[body["name"]] = label_id
        return {"id": label_id, "name": body["name"]}

    def messages_insert(self, body: dict, thread_id=None) -> dict:
        msg_id = f"m{len(self.inserted) + 1}"
        self.inserted.append(body)
        return {"id": msg_id, "threadId": thread_id or f"t{msg_id}"}


def test_ping_returns_a_string_with_python_version():
    result = android_api.ping()
    assert isinstance(result, str)
    assert "Python" in result


def test_list_inbox_empty_when_dir_missing(tmp_root):
    assert android_api.list_inbox() == []


def test_list_inbox_lists_files_sorted_by_name(tmp_root):
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    (config.INBOX_DIR / "b.txt").write_text("b")
    (config.INBOX_DIR / "a.txt").write_text("aa")

    rows = android_api.list_inbox()

    assert [r["name"] for r in rows] == ["a.txt", "b.txt"]
    assert rows[0]["size_bytes"] == 2


def test_preview_returns_summary_for_valid_export(tmp_root):
    fixture = FIXTURES_DIR / "android_export.txt"
    result = android_api.preview(str(fixture))

    assert result["ok"] is True
    assert result["error"] is None
    assert result["display_name"] == "android_export"
    assert result["message_count"] > 0
    assert result["participant_count"] == 2
    assert result["media_count"] == 1
    assert result["first_message_ts"] <= result["last_message_ts"]


def test_preview_handles_missing_file_gracefully(tmp_root):
    result = android_api.preview(str(config.INBOX_DIR / "does_not_exist.txt"))

    assert result["ok"] is False
    assert result["error"] is not None


def test_sync_dry_run_returns_stats_dict(tmp_root):
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    result = android_api.sync(dry_run=True)
    assert result == {
        "files_found": 0,
        "files_synced": 0,
        "files_skipped": 0,
        "files_failed": 0,
        "messages_parsed": 0,
        "messages_synced": 0,
        "messages_skipped": 0,
        "chats_recovered": 0,
        "errors": [],
        # Files the provider's message-size limit made unsendable. Part of the
        # Android contract, not just the desktop summary -- both front-ends have
        # to be able to tell the user which media will never sync.
        "media_omitted": [],
        "stopped": False,
    }


def test_sync_dry_run_with_fixture_file_and_progress_callback(tmp_root):
    fixture = FIXTURES_DIR / "android_export.txt"
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(fixture, config.INBOX_DIR / "WhatsApp Chat with Test Chat.txt")

    events = []
    result = android_api.sync(dry_run=True, on_progress=events.append)

    assert result["files_found"] == 1
    assert result["files_synced"] == 1
    assert any(e["type"] == "files_total" for e in events)
    assert any(e["type"] == "syncing" and e["name"] == "Test Chat" for e in events)
    assert any(e["type"] == "file_done" for e in events)


def test_sync_with_fake_transport_creates_label_and_inserts(tmp_root):
    fixture = FIXTURES_DIR / "android_export.txt"
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(fixture, config.INBOX_DIR / "WhatsApp Chat with Test Chat.txt")

    transport = _FakeTransport()
    assert isinstance(transport, MailTransport)  # structural check via duck typing
    result = android_api.sync(transport=transport, dry_run=False)

    assert result["files_synced"] == 1
    assert "WhatsApp/Test Chat" in transport.labels
    assert len(transport.inserted) >= 1


def test_sync_records_trigger_on_sync_runs(tmp_root, db_path):
    fixture = FIXTURES_DIR / "android_export.txt"
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(fixture, config.INBOX_DIR / "WhatsApp Chat with Test Chat.txt")

    android_api.sync(transport=_FakeTransport(), dry_run=False, trigger="watched_folder")

    runs = android_api.sync_log()
    assert len(runs) == 1
    assert runs[0]["trigger"] == "watched_folder"
    assert runs[0]["display_name"] == "Test Chat"


def test_sync_log_defaults_trigger_to_manual(tmp_root, db_path):
    fixture = FIXTURES_DIR / "android_export.txt"
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(fixture, config.INBOX_DIR / "WhatsApp Chat with Test Chat.txt")

    android_api.sync(transport=_FakeTransport(), dry_run=False)

    runs = android_api.sync_log()
    assert runs[0]["trigger"] == "manual"


def test_sync_log_stamps_uneventful_flag(tmp_root, db_path):
    """Both sync logs fold away runs that finished and changed nothing, and
    both must fold the same ones -- so the rule lives in the shared core and
    Kotlin only reads the flag rather than restating it.

    Rows are written directly here: a re-scan that finds nothing new is what
    produces an uneventful run in the field (sync_manager completes the run
    with messages_synced=0 when there is nothing to push), and driving that
    through a real sync would test the importer rather than the flag."""
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    moved = start_sync_run("chat1", db_path=db_path)
    complete_sync_run(moved, None, None, 5, 5, 0, db_path=db_path)
    quiet = start_sync_run("chat1", db_path=db_path)
    complete_sync_run(quiet, None, None, 5, 0, 5, db_path=db_path)

    runs = {r["run_id"]: r for r in android_api.sync_log()}
    assert len(runs) == 2
    assert all("uneventful" in r for r in runs.values())
    assert runs[quiet]["uneventful"] is True
    assert runs[moved]["uneventful"] is False


def test_status_returns_list_of_dicts(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    rows = android_api.status()
    assert isinstance(rows, list)
    assert rows[0]["display_name"] == "Chat One"


def test_reset_by_display_name(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", gmail_thread_id="t1", db_path=db_path)
    result = android_api.reset("Chat One")

    assert result == {
        "ok": True, "chat_id": "chat1", "display_name": "Chat One",
        "file_restored": False, "archived_count": 0, "needs_confirmation": False,
        "error": None,
    }
    assert get_chat("chat1", db_path)["gmail_thread_id"] is None


def _archive_one_message(chat_id: str, db_path) -> str:
    """Record a message as sent, the way a real sync would."""
    run_id = start_sync_run(chat_id, db_path=db_path)
    h = compute_message_hash(chat_id, "2025-03-14T09:41:00", "Alice", "Hello")
    insert_message_hashes([(h, chat_id, "2025-03-14T09:41:00", run_id)], db_path)
    return h


def test_reset_without_confirmation_refuses_when_mail_archived(tmp_root, db_path):
    """Unconfirmed reset must be refused, and must change nothing.

    The UI is expected to catch this via reset_preview, but the gate lives in
    the core so a caller that skips the preview cannot duplicate the user's mail.
    """
    upsert_chat("chat1", "Chat One", "chat1.txt", gmail_thread_id="t1", db_path=db_path)
    _archive_one_message("chat1", db_path)

    result = android_api.reset("chat1")

    assert result["ok"] is False
    assert result["needs_confirmation"] is True
    assert result["archived_count"] == 1
    assert "WhatsApp/Chat One" in result["error"]
    assert get_chat("chat1", db_path)["gmail_thread_id"] == "t1"


def test_reset_with_confirmation_proceeds(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", gmail_thread_id="t1", db_path=db_path)
    _archive_one_message("chat1", db_path)

    result = android_api.reset("chat1", True)

    assert result["ok"] is True
    assert get_chat("chat1", db_path)["gmail_thread_id"] is None


def test_reset_preview_reports_count_and_folder(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", gmail_thread_id="t1", db_path=db_path)
    _archive_one_message("chat1", db_path)

    preview = android_api.reset_preview("chat1")

    assert preview["ok"] is True
    assert preview["archived_count"] == 1
    assert preview["requires_confirmation"] is True
    # The exact string the user is told to go and delete must be the same one
    # the write path creates, sanitising included.
    assert preview["mailbox_folder"] == "WhatsApp/Chat One"


def test_reset_preview_on_untouched_chat_needs_no_confirmation(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)

    preview = android_api.reset_preview("chat1")

    assert preview["archived_count"] == 0
    assert preview["requires_confirmation"] is False


def test_reset_preview_unknown_chat_returns_error(tmp_root, db_path):
    preview = android_api.reset_preview("nonexistent")
    assert preview["ok"] is False
    assert preview["error"] is not None


def test_reset_unknown_chat_returns_error(tmp_root, db_path):
    result = android_api.reset("nonexistent")
    assert result["ok"] is False
    assert result["error"] is not None


def test_reset_restores_file_from_processed_to_inbox(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", gmail_thread_id="t1", db_path=db_path)
    config.PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    (config.PROCESSED_DIR / "chat1.txt").write_text("export contents")

    result = android_api.reset("chat1")

    assert result["file_restored"] is True
    assert (config.INBOX_DIR / "chat1.txt").exists()
    assert not (config.PROCESSED_DIR / "chat1.txt").exists()


def test_delete_chat_removes_entry(tmp_root, db_path):
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    result = android_api.delete_chat("chat1")
    assert result == {"ok": True, "chat_id": "chat1", "display_name": "Chat One", "error": None}
    assert get_chat("chat1", db_path) is None


def test_delete_chat_unknown_returns_error(tmp_root, db_path):
    result = android_api.delete_chat("nonexistent")
    assert result["ok"] is False
    assert result["error"] is not None


# --- format_preview ---------------------------------------------------------
# Both front-ends render this string verbatim, so its shape is a contract:
# Windows' inline strip and Android's queue card would otherwise drift apart
# the first time either one "tidied" its own copy of the wording.


def test_format_preview_failed_read_uses_the_error():
    text = android_api.format_preview({"ok": False, "error": "Not a chat export."})
    assert text == "Not a chat export."


def test_format_preview_failed_read_without_an_error_still_says_something():
    assert android_api.format_preview({"ok": False}) == "This file could not be read."


def test_format_preview_lists_counts_and_a_date_range():
    text = android_api.format_preview({
        "ok": True,
        "display_name": "Neha",
        "message_count": 2,
        "participant_count": 2,
        "media_count": 3,
        "first_message_ts": "2024-01-02T10:11:12",
        "last_message_ts": "2025-03-04T05:06:07",
        "error": None,
    })
    assert text == "Neha\n2 messages, 2 participants, 3 media\n2024-01-02 to 2025-03-04"


def test_format_preview_singular_and_no_media():
    text = android_api.format_preview({
        "ok": True,
        "display_name": "Solo",
        "message_count": 1,
        "participant_count": 1,
        "media_count": 0,
        "first_message_ts": None,
        "last_message_ts": None,
        "error": None,
    })
    assert text == "Solo\n1 message, 1 participant"


def test_format_preview_parsed_but_empty_keeps_the_name_and_the_reason():
    text = android_api.format_preview({
        "ok": True,
        "display_name": "Empty",
        "error": "No messages found.",
    })
    assert text == "Empty\nNo messages found."
