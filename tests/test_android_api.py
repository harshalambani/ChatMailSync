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

    assert result == {"ok": True, "chat_id": "chat1", "display_name": "Chat One", "error": None}
    assert get_chat("chat1", db_path)["gmail_thread_id"] is None


def test_reset_unknown_chat_returns_error(tmp_root, db_path):
    result = android_api.reset("nonexistent")
    assert result["ok"] is False
    assert result["error"] is not None
