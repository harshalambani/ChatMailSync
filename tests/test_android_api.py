import shutil
from pathlib import Path

from src import android_api, config
from src.gmail_client import GmailTransport
from src.state import get_chat, upsert_chat

FIXTURES_DIR = Path(__file__).parent / "fixtures"


class _FakeTransport:
    """Minimal hand-written GmailTransport fake — no real network, no
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
    assert isinstance(transport, GmailTransport)  # structural check via duck typing
    result = android_api.sync(transport=transport, dry_run=False)

    assert result["files_synced"] == 1
    assert "WhatsApp/Test Chat" in transport.labels
    assert len(transport.inserted) >= 1


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
        "file_restored": False, "error": None,
    }
    assert get_chat("chat1", db_path)["gmail_thread_id"] is None


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
