import json
import zipfile
import shutil
from pathlib import Path

from src import android_api, config
from src.mail_client import MailTransport
from src.parser import extract_chat_info
from src.state import (
    complete_sync_run,
    compute_message_hash,
    get_chat,
    get_chat_cutoff,
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
        # Withheld by the user's cutoff date -- its own number, never folded
        # into messages_skipped. Android reads this dict straight through, so
        # the counter reaching the Sync log is this key being here.
        "messages_cutoff": 0,
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


# ---------------------------------------------------------------------------
# Device migration façade (P3)
# ---------------------------------------------------------------------------


def test_export_backup_round_trips_through_the_bridge(tmp_root, db_path):
    """The bridge deals in paths and JSON strings, because SAF gives Kotlin a
    content:// URI Python cannot open and a heterogeneous settings map is not
    worth marshalling."""
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    dest = tmp_root / "backup.cmsbackup"

    result = android_api.export_backup(
        str(dest),
        json.dumps({"chunk_size": 250, "imap_password": "hunter2"}),
        "1.16.0",
    )
    assert result["ok"]
    assert dest.exists()

    described = android_api.describe_backup(str(dest))
    assert described["ok"]
    assert described["app_version"] == "1.16.0"
    assert described["chats"] == 1

    # The credential never left, even though the caller handed one over.
    assert "hunter2" not in dest.read_bytes().decode("latin-1")


def test_export_backup_survives_junk_settings_json(tmp_root, db_path):
    dest = tmp_root / "backup.cmsbackup"
    assert android_api.export_backup(str(dest), "not json at all")["ok"]
    assert android_api.export_backup(str(dest), "[1, 2, 3]")["ok"]


def test_describe_backup_counts_the_cutoffs_a_bundle_carries(tmp_root, db_path):
    """Shown before the restore, because a per-chat floor is the one thing in
    a bundle the user set by hand and would not think to set again."""
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    android_api.set_cutoff("chat1", "2025-01-31")
    dest = tmp_root / "backup.cmsbackup"
    android_api.export_backup(str(dest), "{}", "2.0.5")

    assert android_api.describe_backup(str(dest))["cutoffs"] == 1


def test_a_bundle_written_before_cutoffs_existed_reports_none(tmp_root, db_path):
    """Its manifest has no such key at all. Honestly reporting "no overrides"
    is the whole backward-compatibility story on this side; raising a
    KeyError over a field that did not exist last release is not."""
    upsert_chat("chat1", "Chat One", "chat1.txt", db_path=db_path)
    dest = tmp_root / "backup.cmsbackup"
    android_api.export_backup(str(dest), "{}", "2.0.4")
    _strip_manifest_key(dest, tmp_root / "old.cmsbackup", "cutoffs")

    described = android_api.describe_backup(str(tmp_root / "old.cmsbackup"))

    assert described["ok"] is True
    assert described["cutoffs"] == 0
    assert described["chats"] == 1


def _strip_manifest_key(src, dest, key):
    """Rewrite a bundle with one count removed, as an older build wrote it."""
    with zipfile.ZipFile(src) as z:
        items = [(n, z.read(n)) for n in z.namelist()]
    with zipfile.ZipFile(dest, "w") as z:
        for name, blob in items:
            if name == "manifest.json":
                manifest = json.loads(blob)
                manifest["counts"].pop(key, None)
                blob = json.dumps(manifest, indent=2).encode("utf-8")
            z.writestr(name, blob)


def test_describe_backup_on_a_file_that_is_not_one(tmp_root):
    junk = tmp_root / "holiday.jpg"
    junk.write_bytes(b"nope")
    result = android_api.describe_backup(str(junk))
    assert result["ok"] is False
    assert result["error"]


def test_import_backup_returns_settings_in_both_shapes(tmp_root, db_path):
    dest = tmp_root / "backup.cmsbackup"
    android_api.export_backup(str(dest), json.dumps({"chunk_size": 250}), "1.16.0")

    result = android_api.import_backup(str(dest))
    assert result["ok"]
    # Same bundle, same install: already-imported, and nothing duplicated.
    assert json.loads(result["settings_json"]) == result["settings"]


# ---------------------------------------------------------------------------
# The cutoff date
#
# Kotlin reaches every part of this feature through these four entry points
# and nothing else, so a break here is a break on the phone with nothing in
# the Python suite to catch it. The app-wide floor is passed in on every
# sync() call rather than read here, because it lives in AppPrefs and Python
# has no view of it; the per-chat overrides live in the database and these
# three accessors are the only door to them.
# ---------------------------------------------------------------------------


def _copy_fixture_chat():
    """The shipped export, whose messages all fall on 14 March 2025."""
    config.INBOX_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(
        FIXTURES_DIR / "android_export.txt",
        config.INBOX_DIR / "WhatsApp Chat with Test Chat.txt",
    )
    chat_id, _ = extract_chat_info("WhatsApp Chat with Test Chat.txt")
    return chat_id


def test_the_cutoff_reaches_the_sync_and_the_count_comes_back(tmp_root):
    """The whole Android contract in one call: a floor goes in as a plain
    date, and what it withheld comes back as its own number for the Sync
    log. Everything in the fixture predates this one."""
    _copy_fixture_chat()
    transport = _FakeTransport()

    result = android_api.sync(transport=transport, cutoff_date="2025-03-15")

    assert result["messages_synced"] == 0
    assert result["messages_cutoff"] == result["messages_parsed"] > 0
    # Withheld, not merely uncounted.
    assert transport.inserted == []


def test_a_cutoff_on_the_export_s_own_day_keeps_everything(tmp_root):
    """The boundary, through the facade. The comparison is `<`, so a message
    on the cutoff day itself is on a day the user asked for."""
    _copy_fixture_chat()

    result = android_api.sync(transport=_FakeTransport(), cutoff_date="2025-03-14")

    assert result["messages_cutoff"] == 0
    assert result["messages_synced"] > 0


def test_an_absent_cutoff_withholds_nothing(tmp_root):
    """The regression guard: the feature has to be invisible when it is off,
    and Kotlin passes None for "no floor set"."""
    _copy_fixture_chat()

    result = android_api.sync(transport=_FakeTransport(), cutoff_date=None)

    assert result["messages_cutoff"] == 0
    assert result["messages_synced"] > 0


def test_a_chat_with_no_override_says_so(tmp_root, db_path):
    """None means "inherit the app-wide floor", which is what the detail
    screen shows as "Using the app-wide cutoff"."""
    chat_id = _copy_fixture_chat()

    assert android_api.get_cutoff(chat_id)["cutoff_date"] is None


def test_an_override_round_trips_as_a_plain_day(tmp_root, db_path):
    """Stored as a midnight instant, handed back as a date, because what
    asks for it is a date picker and what compares it is a timestamp."""
    chat_id = _copy_fixture_chat()

    assert android_api.set_cutoff(chat_id, "2025-03-15")["ok"] is True
    assert android_api.get_cutoff(chat_id)["cutoff_date"] == "2025-03-15"


def test_clearing_the_field_removes_the_override(tmp_root, db_path):
    """One call sets and clears so the Kotlin side has no branch to get
    wrong -- an emptied date field means "use the app-wide floor again", not
    "a floor of nothing"."""
    chat_id = _copy_fixture_chat()
    android_api.set_cutoff(chat_id, "2025-03-15")

    result = android_api.set_cutoff(chat_id, "")

    assert result["ok"] is True
    assert result["cutoff_date"] is None
    assert android_api.get_cutoff(chat_id)["cutoff_date"] is None


def test_a_date_that_cannot_be_compared_is_refused_and_nothing_is_stored(
    tmp_root, db_path
):
    """A floor nothing can evaluate would withhold unpredictably, so it is
    refused with a message rather than stored. The previous value has to
    survive the refusal: a rejected edit that silently wiped the old floor
    would deliver everything below it on the next sync."""
    chat_id = _copy_fixture_chat()
    android_api.set_cutoff(chat_id, "2025-03-15")

    result = android_api.set_cutoff(chat_id, "15/03/2025")

    assert result["ok"] is False
    assert result["error"]
    assert android_api.get_cutoff(chat_id)["cutoff_date"] == "2025-03-15"


def test_an_override_can_be_set_before_the_chat_has_ever_synced(tmp_root, db_path):
    """Set from the import preview, which is where the user first sees how
    far back an export goes and the one moment the override is most useful.
    There is no row in `chats` yet, and refusing here would break exactly
    that case -- which is why chat_cutoffs has no foreign key to it.

    Checked against the database rather than by reading back through
    get_cutoff: both doors resolve the id the same way, so a resolver that
    dropped the id entirely would still round-trip through them and this test
    would pass while every override piled up under one blank key.
    """
    result = android_api.set_cutoff("chat-never-seen", "2025-03-15")

    assert result["ok"] is True
    assert result["chat_id"] == "chat-never-seen"
    assert get_chat_cutoff("chat-never-seen", config.STATE_DB_PATH) == (
        "2025-03-15T00:00:00"
    )


def test_an_override_can_be_set_by_display_name(tmp_root, db_path):
    """Every other chat-addressed call in this facade takes an id or a name;
    this one has to as well, or the caller has to know which door it is at."""
    chat_id = _copy_fixture_chat()
    android_api.sync(transport=_FakeTransport())

    result = android_api.set_cutoff("Test Chat", "2025-03-15")

    assert result["chat_id"] == chat_id
    assert android_api.get_cutoff(chat_id)["cutoff_date"] == "2025-03-15"


def test_list_cutoffs_reports_every_override_in_one_call(tmp_root, db_path):
    """For a chat list that marks which chats depart from the app-wide
    floor. One query, not one per row."""
    android_api.set_cutoff("chat-b", "2025-03-15")
    android_api.set_cutoff("chat-a", "2024-01-31")

    assert android_api.list_cutoffs() == [
        {"chat_id": "chat-a", "cutoff_date": "2024-01-31"},
        {"chat_id": "chat-b", "cutoff_date": "2025-03-15"},
    ]


def test_an_override_beats_the_app_wide_cutoff_end_to_end(tmp_root, db_path):
    """The two halves of the feature meeting, over the same wire Kotlin
    uses. The override is *earlier* than the app-wide floor, which is the
    case a max() of the two would silently refuse -- and the only reason to
    offer an override at all."""
    chat_id = _copy_fixture_chat()
    android_api.set_cutoff(chat_id, "2025-01-01")

    result = android_api.sync(transport=_FakeTransport(), cutoff_date="2025-06-01")

    assert result["messages_cutoff"] == 0
    assert result["messages_synced"] > 0
