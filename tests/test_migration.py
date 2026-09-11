"""Contract tests for the device-migration bundle.

Everything here builds its own throwaway "device" under tmp_path -- a root with
a data/sync_state.db inside it -- because migration.py takes the root as a
parameter precisely so it can be tested without touching config or the real
install.
"""

import json
import sqlite3
import zipfile

import pytest

from src import migration, state
from src.config import IMAP_PROVIDERS


def _device(root, chats=(("chat1", "Chat One"),), messages=2):
    """A root that looks like an install, with some history already in it."""
    db = root / "data" / "sync_state.db"
    db.parent.mkdir(parents=True, exist_ok=True)
    state.init_db(db)
    for chat_id, name in chats:
        state.upsert_chat(chat_id, name, f"{chat_id}.txt", db_path=db)
        run_id = state.start_sync_run(chat_id, db_path=db)
        rows = []
        for i in range(messages):
            ts = f"2025-03-14T09:4{i}:00"
            rows.append(
                (
                    state.compute_message_hash(chat_id, ts, "Alice", f"m{i}"),
                    chat_id,
                    ts,
                    run_id,
                )
            )
        state.insert_message_hashes(rows, db)
        state.complete_sync_run(
            run_id,
            last_synced_ts=rows[-1][2],
            last_synced_hash=rows[-1][0],
            messages_parsed=messages,
            messages_synced=messages,
            messages_skipped=0,
            db_path=db,
        )
    return db


def _counts(db):
    return migration._counts(db)


def test_round_trip_carries_the_ledger(tmp_path):
    old = tmp_path / "old"
    new = tmp_path / "new"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)

    summary = migration.export_bundle(
        old, bundle, settings={"chunk_size": 250}, app_version="1.16.0"
    )
    assert summary["ok"]
    assert bundle.exists()
    assert summary["counts"]["hashes"] == 2

    result = migration.import_bundle(new, bundle)
    assert result["ok"]
    assert result["already_imported"] is False
    assert result["chats_added"] == 1
    assert result["runs_added"] == 1
    assert result["hashes_added"] == 2
    assert result["settings"] == {"chunk_size": 250}

    # The point of the whole exercise: the new device already knows these
    # messages have been mailed, so it will not mail them again.
    new_db = migration._db_path(new)
    for i in range(2):
        h = state.compute_message_hash("chat1", f"2025-03-14T09:4{i}:00", "Alice", f"m{i}")
        assert state.hash_exists(h, new_db)


def test_import_is_a_merge_not_a_replace(tmp_path):
    old = tmp_path / "old"
    new = tmp_path / "new"
    _device(old, chats=(("chat1", "Chat One"),))
    _device(new, chats=(("chat2", "Chat Two"),))

    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={})
    result = migration.import_bundle(new, bundle)
    assert result["ok"]

    new_db = migration._db_path(new)
    ids = {row["chat_id"] for row in state.list_chats(new_db)}
    # The local chat survived the restore. An older bundle must never be able
    # to delete newer history -- here that would mean re-mailing chat2.
    assert ids == {"chat1", "chat2"}
    assert _counts(new_db)["hashes"] == 4


def test_importing_the_same_bundle_twice_is_a_no_op(tmp_path):
    old = tmp_path / "old"
    new = tmp_path / "new"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={})

    first = migration.import_bundle(new, bundle)
    assert first["runs_added"] == 1
    before = _counts(migration._db_path(new))

    second = migration.import_bundle(new, bundle)
    assert second["ok"]
    assert second["already_imported"] is True
    assert second["runs_added"] == 0
    assert _counts(migration._db_path(new)) == before


def test_a_second_bundle_of_the_same_history_does_not_double_the_log(tmp_path):
    """The already-imported guard only catches the *same* bundle file. Saving a
    fresh backup and restoring that -- the obvious thing to do when a first
    restore looked wrong, or when restoring onto the phone the history came
    from -- used to append every run again, and the sync log showed each one
    twice with nothing to say which was real."""
    old = tmp_path / "old"
    new_dev = tmp_path / "new"
    _device(old)

    first_bundle = tmp_path / ("backup-1" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, first_bundle, settings={})
    assert migration.import_bundle(new_dev, first_bundle)["runs_added"] == 1
    before = _counts(migration._db_path(new_dev))

    # A different file, same history inside it.
    second_bundle = tmp_path / ("backup-2" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, second_bundle, settings={})
    second = migration.import_bundle(new_dev, second_bundle)

    assert second["ok"]
    assert second["already_imported"] is False
    assert second["runs_added"] == 0
    assert second["hashes_added"] == 0
    assert _counts(migration._db_path(new_dev)) == before


def test_no_credential_reaches_the_bundle(tmp_path):
    old = tmp_path / "old"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)

    migration.export_bundle(
        old,
        bundle,
        settings={
            "chunk_size": 100,
            "imap_email": "someone@example.com",
            # Every shape a credential has taken in this app, offered at once.
            "imap_password": "hunter2",
            "imap_password_secret": "hunter2",
            "password": "hunter2",
            "token": "ya29.secret",
            "credentials": {"user": "u", "pass": "p"},
            "oauth_unlocked": True,
            "watched_folder_path": r"C:\Users\someone\Documents\WAChatBU",
        },
        app_version="1.16.0",
    )

    with zipfile.ZipFile(bundle) as z:
        settings = json.loads(z.read("settings.json"))
        blob = z.read("settings.json").decode("utf-8") + z.read("manifest.json").decode("utf-8")

    assert set(settings) <= set(migration._PORTABLE_SETTINGS)
    assert settings["chunk_size"] == 100
    assert settings["imap_email"] == "someone@example.com"
    for gone in ("imap_password", "imap_password_secret", "password",
                 "token", "credentials", "oauth_unlocked", "watched_folder_path"):
        assert gone not in settings
    # Not just absent by key -- absent by value, anywhere in the text members.
    assert "hunter2" not in blob
    assert "ya29.secret" not in blob
    assert "WAChatBU" not in blob


def test_allow_listed_key_that_names_a_credential_is_refused(tmp_path):
    """The tripwire behind the allow-list, exercised as if somebody had just
    added such a key to it."""
    old = tmp_path / "old"
    _device(old)
    patched = frozenset(migration._PORTABLE_SETTINGS | {"imap_password"})
    original = migration._PORTABLE_SETTINGS
    migration._PORTABLE_SETTINGS = patched
    try:
        with pytest.raises(migration.BundleError):
            migration.portable_settings({"imap_password": "hunter2"})
    finally:
        migration._PORTABLE_SETTINGS = original


def test_a_newer_bundle_is_refused_not_guessed_at(tmp_path):
    old = tmp_path / "old"
    new = tmp_path / "new"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={})

    # Rewrite the manifest with a schema version from the future.
    with zipfile.ZipFile(bundle) as z:
        members = {n: z.read(n) for n in z.namelist()}
    manifest = json.loads(members["manifest.json"])
    manifest["schema_version"] = migration.BUNDLE_SCHEMA_VERSION + 1
    members["manifest.json"] = json.dumps(manifest).encode("utf-8")
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in members.items():
            z.writestr(name, data)

    result = migration.import_bundle(new, bundle)
    assert result["ok"] is False
    assert "newer version" in result["error"]
    # Nothing was half-merged on the way to refusing.
    assert _counts(migration._db_path(new))["hashes"] == 0


def test_a_file_that_is_not_a_bundle_comes_back_as_a_sentence(tmp_path):
    new = tmp_path / "new"
    junk = tmp_path / "holiday.jpg"
    junk.write_bytes(b"not a zip, not even close")

    result = migration.import_bundle(new, junk)
    assert result["ok"] is False
    assert result["error"] == "That file is not a Chat Mail Sync backup."

    missing = migration.import_bundle(new, tmp_path / "nope.cmsbackup")
    assert missing["ok"] is False
    assert missing["error"]


def test_a_zip_without_a_manifest_is_refused(tmp_path):
    new = tmp_path / "new"
    bundle = tmp_path / "photos.zip"
    with zipfile.ZipFile(bundle, "w") as z:
        z.writestr("cat.jpg", b"meow")

    result = migration.import_bundle(new, bundle)
    assert result["ok"] is False
    assert "not a Chat Mail Sync backup" in result["error"]


def test_exporting_a_device_that_has_never_synced(tmp_path):
    """A fresh install has no data/ at all. That is a bundle with nothing in
    it, not an error -- the user may simply have backed up on day one."""
    old = tmp_path / "old"
    old.mkdir()
    new = tmp_path / "new"
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)

    summary = migration.export_bundle(old, bundle, settings={"chunk_size": 50})
    assert summary["counts"] == {
        "chats": 0, "runs": 0, "hashes": 0, "cutoffs": 0,
    }

    result = migration.import_bundle(new, bundle)
    assert result["ok"]
    assert result["hashes_added"] == 0
    assert result["settings"] == {"chunk_size": 50}


def test_read_manifest_describes_what_will_be_restored(tmp_path):
    old = tmp_path / "old"
    _device(old, chats=(("chat1", "Chat One"), ("chat2", "Chat Two")))
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={}, app_version="1.16.0")

    manifest = migration.read_manifest(bundle)
    assert manifest["app_version"] == "1.16.0"
    assert manifest["counts"] == {
        "chats": 2, "runs": 2, "hashes": 4, "cutoffs": 0,
    }
    assert manifest["schema_version"] == migration.BUNDLE_SCHEMA_VERSION
    assert manifest["bundle_id"]
    assert manifest["created_at"]


def test_a_restore_carries_the_date_of_the_bundle_it_came_from(tmp_path):
    """The fact both front-ends need to stop saying "No backup yet".

    A phone rebuilt from a backup is protected by that backup. Reporting it as
    unprotected put a red "a reset makes the app mail every chat again" line
    directly above the messages the restore had just stopped it re-mailing.
    """
    old = tmp_path / "old"
    new = tmp_path / "new"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={}, app_version="2.0.2")

    made = migration.read_manifest(bundle)["created_at"]

    first = migration.import_bundle(new, bundle)
    assert first["created_at"] == made
    assert migration.created_at_epoch(first["created_at"]) > 0

    # Restoring the same bundle a second time changes nothing else, but it
    # must still report the cover: that attempt is exactly when someone is
    # checking whether they are protected.
    again = migration.import_bundle(new, bundle)
    assert again["already_imported"] is True
    assert again["created_at"] == made


def test_an_unreadable_creation_stamp_is_no_cover_rather_than_a_crash():
    assert migration.created_at_epoch("") == 0
    assert migration.created_at_epoch("not a date") == 0
    assert migration.created_at_epoch(None) == 0
    assert migration.created_at_epoch("2026-09-01T11:07:33") > 0


# ---------------------------------------------------------------------------
# Per-chat cutoff dates
#
# A cutoff is the one thing in this database the user typed by hand. History
# they can always re-derive by re-importing an export; a floor they set on a
# particular chat months ago they would never think to set again, and losing
# it silently means that chat quietly starts mailing everything below it.
# ---------------------------------------------------------------------------


def test_a_per_chat_cutoff_moves_with_the_bundle(tmp_path):
    """The whole point of carrying them: the new device withholds exactly what
    the old one withheld, without the user re-entering anything."""
    old = tmp_path / "old"
    new = tmp_path / "new"
    db = _device(old)
    state.set_chat_cutoff("chat1", "2025-01-31", db)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)

    summary = migration.export_bundle(old, bundle, settings={})
    assert summary["counts"]["cutoffs"] == 1

    result = migration.import_bundle(new, bundle)

    assert result["cutoffs_added"] == 1
    assert state.get_chat_cutoff(
        "chat1", new / "data" / "sync_state.db"
    ) == "2025-01-31T00:00:00"


def test_a_floor_set_on_this_device_beats_the_one_in_the_bundle(tmp_path):
    """Same rule the rest of the merge follows, and the safe way round. A
    floor set here is a live instruction; a bundle is a photograph of an older
    device. Overwriting the live one could only ever lower the floor and mail
    the difference."""
    old = tmp_path / "old"
    new = tmp_path / "new"
    old_db = _device(old)
    state.set_chat_cutoff("chat1", "2024-01-01", old_db)
    new_db = _device(new)
    state.set_chat_cutoff("chat1", "2025-06-01", new_db)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={})

    result = migration.import_bundle(new, bundle)

    assert result["cutoffs_added"] == 0
    assert state.get_chat_cutoff("chat1", new_db) == "2025-06-01T00:00:00"


def test_a_bundle_from_before_cutoffs_existed_still_restores(tmp_path):
    """Backward compatibility, which is not theoretical: every backup taken
    before this release has no chat_cutoffs table at all, and the merge reads
    that table by name. It works because import_bundle brings the incoming
    database forward with init_db before reading it -- so an old bundle
    restores as a backup carrying no overrides, rather than an error."""
    old = tmp_path / "old"
    new = tmp_path / "new"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={"chunk_size": 100})

    # Re-write the bundle with the table removed, which is byte for byte what
    # an older build would have produced.
    staged = tmp_path / "staged"
    staged.mkdir()
    with zipfile.ZipFile(bundle) as z:
        names = z.namelist()
        z.extractall(staged)
    conn = sqlite3.connect(staged / "sync_state.db")
    conn.execute("DROP TABLE chat_cutoffs")
    conn.commit()
    conn.close()
    assert not _has_table(staged / "sync_state.db", "chat_cutoffs")
    old_bundle = tmp_path / ("old" + migration.BUNDLE_SUFFIX)
    with zipfile.ZipFile(old_bundle, "w") as z:
        for name in names:
            z.write(staged / name, name)

    result = migration.import_bundle(new, old_bundle)

    assert result["ok"] is True
    assert result["cutoffs_added"] == 0
    assert result["hashes_added"] == 2
    assert result["settings"] == {"chunk_size": 100}


def _tamper(bundle, member, payload):
    """Rewrite one member of [bundle] with [payload], as an attacker would.

    A .cmsbackup is a zip a person is invited to mail to themselves, so the
    threat model is not a corrupt file -- it is a deliberate one, handed over
    by someone who wants it opened.
    """
    with zipfile.ZipFile(bundle) as z:
        members = [(n, z.read(n)) for n in z.namelist()]
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in members:
            z.writestr(name, payload if name == member else data)


def _bundle_with_settings(tmp_path, settings):
    """A real exported bundle whose settings.json has been replaced wholesale."""
    old = tmp_path / "old"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)
    migration.export_bundle(old, bundle, settings={"chunk_size": 100},
                            app_version="2.1.0")
    _tamper(bundle, "settings.json", json.dumps(settings))
    return bundle


def test_the_host_never_leaves_the_device(tmp_path):
    """Export side: a host in the settings store is not written to the file."""
    old = tmp_path / "old"
    _device(old)
    bundle = tmp_path / ("backup" + migration.BUNDLE_SUFFIX)

    migration.export_bundle(
        old,
        bundle,
        settings={
            "imap_provider": "gmail",
            "imap_host": "imap.gmail.com",
            "imap_email": "someone@example.com",
        },
        app_version="2.1.0",
    )

    with zipfile.ZipFile(bundle) as z:
        written = json.loads(z.read("settings.json"))
    assert "imap_host" not in written
    assert written["imap_provider"] == "gmail"


def test_a_bundle_cannot_redirect_the_mail_server(tmp_path):
    """The finding itself. A crafted bundle names a host; the host it gets is
    the provider's own, because the next thing the app says after a restore is
    "enter your mail password once to finish"."""
    bundle = _bundle_with_settings(tmp_path, {
        "imap_provider": "gmail",
        "imap_host": "mail.attacker.example",
        "imap_port": 1993,
        "imap_email": "victim@gmail.com",
    })
    new = tmp_path / "new"

    result = migration.import_bundle(new, bundle)

    assert result["ok"] is True
    assert result["settings"]["imap_host"] == "imap.gmail.com"
    assert result["settings"]["imap_port"] == 993


def test_every_preset_provider_resolves_to_its_own_host(tmp_path):
    """Not just Gmail -- the lookup is the mechanism, so it is tested as one."""
    for key, preset in IMAP_PROVIDERS.items():
        if not preset["host"]:
            continue
        settings = {"imap_provider": key, "imap_host": "mail.attacker.example"}
        assert migration._with_derived_host(settings)["imap_host"] == preset["host"]


def test_a_custom_provider_carries_no_host_at_all(tmp_path):
    """The one case with no preset to fall back on. The host is dropped rather
    than trusted: a custom user knows their own server, and the account screen
    will not save a custom provider without one, so the worst case is a field
    to fill in."""
    bundle = _bundle_with_settings(tmp_path, {
        "imap_provider": "custom",
        "imap_host": "mail.attacker.example",
        "imap_email": "victim@example.com",
    })
    new = tmp_path / "new"

    result = migration.import_bundle(new, bundle)

    assert result["ok"] is True
    assert "imap_host" not in result["settings"]
    assert result["settings"]["imap_email"] == "victim@example.com"


def test_an_unknown_provider_name_carries_no_host_either(tmp_path):
    """A provider key this build has never heard of is not a licence to keep
    whatever host came with it."""
    settings = {"imap_provider": "definitely-not-a-provider",
                "imap_host": "mail.attacker.example"}
    assert "imap_host" not in migration._with_derived_host(dict(settings))


def test_an_oversized_manifest_is_refused(tmp_path):
    """L1. A member that compresses to nothing and inflates to everything is
    refused on its declared size, before a byte of it is read."""
    bundle = _bundle_with_settings(tmp_path, {"chunk_size": 100})
    fat = json.dumps({"schema_version": 1, "pad": " " * (5 * 1_048_576)})
    _tamper(bundle, "manifest.json", fat)

    with pytest.raises(migration.BundleError):
        migration.read_manifest(bundle)

    result = migration.import_bundle(tmp_path / "new", bundle)
    assert result["ok"] is False
    assert "safety limit" in result["error"]


def test_an_oversized_settings_member_is_refused(tmp_path):
    bundle = _bundle_with_settings(tmp_path, {"chunk_size": 100})
    _tamper(bundle, "settings.json",
            json.dumps({"chunk_size": 100, "pad": " " * (5 * 1_048_576)}))

    result = migration.import_bundle(tmp_path / "new", bundle)

    assert result["ok"] is False
    assert "safety limit" in result["error"]


def _has_table(db, name):
    conn = sqlite3.connect(db)
    try:
        return conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            (name,),
        ).fetchone() is not None
    finally:
        conn.close()



def test_a_retired_provider_key_is_rewritten_on_restore():
    """A bundle written by a release that still offered Outlook.com restores
    as the key this build reads it as, and is written back so the settings
    file and the rest of the app agree from here on. The host is not
    replaced with some other provider's -- "custom" has no preset, so
    whatever the user had stays theirs to see and change."""
    settings = {"imap_provider": "outlook", "imap_host": "outlook.office365.com"}
    result = migration._with_derived_host(settings)
    assert result["imap_provider"] == "custom"
    assert "imap_host" not in result
