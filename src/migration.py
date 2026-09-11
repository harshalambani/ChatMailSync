"""Carry an install across to a new device: the dedup ledger and the settings.

Why this exists at all, given the app is write-only and the mailbox already is
the archive: a new phone re-synced from scratch would still *work*, and would
mail every message a second time. What cannot be rebuilt from the mailbox is
`sync_state.db` -- the record of which message hashes have already been sent.
Without it the second device duplicates the entire history into a mailbox that
has no conflict resolution, because we deliberately have none (see
PLATFORM-PARITY.md: we are write-only, we have duplicates, not conflicts).

So this module is not "back up the app". It carries the ledger and the
settings, and nothing else.

Two rules shape everything below.

**The root is a parameter.** Nothing here reads `src.config`. Android sets its
root at runtime and Windows sets it from an environment variable, and a
function that reached for a module-level constant would work on exactly one of
them and be untestable on both.

**Settings move by allow-list, never deny-list.** `_PORTABLE_SETTINGS` is the
complete set of keys that may leave the device. A deny-list would ship any key
a future release adds and forgets to exclude -- and the key most likely to be
added near this code is a credential.

**The mail server is never taken from the bundle.** A bundle is meant to be
handed about -- mailed to yourself, copied off a dead phone -- so it is a file
an attacker can write. The password is not in it, but the address the password
gets typed into would be, and TLS does not help: a server presenting a valid
certificate for its own name passes every check this app makes. So the host is
derived here from the provider preset instead, and a bundle that names one is
ignored. See `_with_derived_host`.
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import tempfile
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Optional

from src import state
# The one thing read from config, and deliberately not a path: IMAP_PROVIDERS
# is a constant table of provider presets. The "root is a parameter" rule above
# is about config's *path* constants, which are rebound at runtime per platform;
# a lookup table is neither rebound nor a test seam.
from src.config import IMAP_PROVIDERS, retired_provider_landing

# Bumped only when the *bundle* layout changes -- the names of the members, or
# the shape of the manifest. The database inside carries its own schema and is
# brought forward by state.init_db() on the way in, so a new column in
# sync_runs is not a bundle change.
BUNDLE_SCHEMA_VERSION = 1

BUNDLE_SUFFIX = ".cmsbackup"

_MANIFEST_NAME = "manifest.json"
_DB_NAME = "sync_state.db"
_SETTINGS_NAME = "settings.json"

# Everything that may cross to another device. Read the two exclusions as
# decisions, not omissions:
#
#   - No credential of any kind. The IMAP app password is sealed by the
#     Keystore on Android and by DPAPI on Windows, both of them to *that*
#     device; a portable copy is a plaintext password in a file the user will
#     mail to themselves. The new device asks once.
#   - No watched folder. An Android SAF grant does not transfer, and a Windows
#     path is unlikely to exist on the machine being restored onto -- it would
#     come back as a permission the new device does not hold, and the app would
#     look broken rather than unconfigured.
#   - No `last_connection_ok` / `last_connection_at`. They are only a verdict
#     and a timestamp, so they are safe -- but they are a verdict about a
#     credential this bundle deliberately leaves behind. Carried across, they
#     would light the connection pill green on a device that holds no password
#     and cannot reach the mailbox at all. The new device earns that green the
#     first time it actually connects.
_PORTABLE_SETTINGS = frozenset({
    "chunk_size",
    "watch_interval_minutes",
    "synced_file_policy",
    "theme_mode",
    "dry_run_default",
    "mail_backend",
    "imap_provider",
    # No `imap_host`. It used to travel, and on the way back in it was written
    # straight into the settings store, so a crafted bundle could point the app
    # at a server of its choosing -- and the very next sentence the app shows
    # after a restore invites the user to type their mail password. Nothing
    # else in the flow names the server, so there was no moment at which that
    # was visible. The host now comes from the provider preset instead
    # (`_with_derived_host`), which loses nothing for the five presets and
    # costs a "custom" user one field they alone know the value of.
    "imap_port",
    "imap_email",
})

# A tripwire, not the mechanism. The allow-list above is what keeps secrets out
# of a bundle; this catches the case where somebody adds a key to the allow-list
# whose name says plainly what it holds. It fires at export time, loudly,
# rather than shipping the bundle.
_FORBIDDEN_SUBSTRINGS = ("password", "secret", "token", "credential")


# The manifest and the settings are both a few hundred bytes of JSON. Read
# whole into memory, so they get the ceiling the chat parser and the media
# extractor already put on their own zip members -- a kilobyte that inflates to
# a gigabyte is a cheap file to write and an expensive one to open. The
# database member is not covered by this and does not need to be: it is
# streamed to disk with copyfileobj, never read whole.
MAX_BUNDLE_MEMBER_BYTES = 4 * 1_048_576  # 4 MiB


class BundleError(RuntimeError):
    """A bundle that cannot be read, or cannot be trusted to be read."""


def _read_small_member(bundle: zipfile.ZipFile, name: str) -> bytes:
    """The bytes of [name], refused if it claims to inflate past the ceiling."""
    size = bundle.getinfo(name).file_size
    if size > MAX_BUNDLE_MEMBER_BYTES:
        raise BundleError(
            "That backup's %s would expand to %d bytes, past the "
            "%d-byte safety limit." % (name, size, MAX_BUNDLE_MEMBER_BYTES)
        )
    return bundle.read(name)


def _with_derived_host(settings: dict) -> dict:
    """Put the provider's own host and port back on [settings], in place.

    The bundle's word is not taken for either. For the five presets the answer
    is a constant this build already holds, so nothing is lost by looking it up
    rather than trusting the file. For "custom" the host is left absent
    entirely: only that user knows it, the front-ends leave a key they were not
    given alone, and the mail account screen already refuses to save a custom
    provider with an empty host -- so the failure mode is one field to fill in,
    not a silent redirection.
    """
    # Dropped first, unconditionally. The allow-list has already discarded it
    # on the way in, so this is the second lock on the same door -- and it is
    # the one that still holds if some future release puts `imap_host` back on
    # the allow-list without remembering why it came off.
    settings.pop("imap_host", None)
    # A bundle written by a release that still offered a provider this one has
    # retired restores as the key that replaced it, and is written back so the
    # rest of the app and the settings file agree from here on. Only a key we
    # actually retired is rewritten: an unrecognised one keeps falling through
    # to the no-preset path below, which drops the host rather than trusting
    # whatever came with it.
    landing = retired_provider_landing(settings.get("imap_provider"))
    if landing:
        settings["imap_provider"] = landing
    preset = IMAP_PROVIDERS.get(str(settings.get("imap_provider") or ""))
    if preset and preset.get("host"):
        settings["imap_host"] = preset["host"]
        settings["imap_port"] = preset["port"]
    return settings


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

def _db_path(root: Path) -> Path:
    """The state DB under [root], by the same layout config._apply_root uses.

    Spelled out here rather than imported so that this module keeps its promise
    of taking the root as a parameter. If that layout ever changes, it changes
    in two places -- which is the trade for being able to run this against a
    tmp_path in a test and against the real install in production, unmodified.
    """
    return root / "data" / _DB_NAME


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------

def portable_settings(settings: Mapping[str, Any]) -> dict:
    """The subset of [settings] that may leave this device."""
    out = {}
    for key in sorted(_PORTABLE_SETTINGS):
        if key not in settings:
            continue
        lowered = key.lower()
        if any(bad in lowered for bad in _FORBIDDEN_SUBSTRINGS):
            raise BundleError(
                f"Refusing to export {key!r}: nothing that names a credential "
                "leaves this device."
            )
        out[key] = settings[key]
    return out


def export_bundle(
    root: Path,
    dest: Path,
    settings: Optional[Mapping[str, Any]] = None,
    app_version: str = "",
) -> dict:
    """Write a restore bundle for the install at [root] to [dest].

    [settings] is passed in rather than read, because the two front-ends keep it
    in different places -- a JSON file on Windows, SharedPreferences on Android
    -- and neither of those belongs in here.

    Returns a summary dict; raises BundleError only for the credential tripwire,
    which is a programming error rather than something a user can cause.
    """
    root = Path(root)
    dest = Path(dest)
    db = _db_path(root)

    counts = _counts(db)
    manifest = {
        "schema_version": BUNDLE_SCHEMA_VERSION,
        # Identifies this bundle, so importing the same file twice is a no-op
        # rather than a second copy of every run. See _already_imported.
        "bundle_id": str(uuid.uuid4()),
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "app_version": app_version,
        "counts": counts,
    }
    portable = portable_settings(settings or {})

    dest.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr(_MANIFEST_NAME, json.dumps(manifest, indent=2))
        bundle.writestr(_SETTINGS_NAME, json.dumps(portable, indent=2))
        if db.exists():
            # Copied through a consistent snapshot rather than read off disk:
            # the DB runs in WAL mode, so the file alone can be missing commits
            # that live in the -wal sidecar, and a bundle short of the most
            # recent run is exactly the bundle that re-sends it.
            with tempfile.TemporaryDirectory() as tmp:
                snapshot = Path(tmp) / _DB_NAME
                _snapshot_db(db, snapshot)
                bundle.write(snapshot, _DB_NAME)

    return {
        "ok": True,
        "path": str(dest),
        "bundle_id": manifest["bundle_id"],
        "counts": counts,
        "settings_keys": sorted(portable),
    }


def _snapshot_db(source: Path, dest: Path) -> None:
    src_conn = sqlite3.connect(source)
    try:
        dst_conn = sqlite3.connect(dest)
        try:
            src_conn.backup(dst_conn)
        finally:
            dst_conn.close()
    finally:
        src_conn.close()


def _counts(db: Path) -> dict:
    if not db.exists():
        return {"chats": 0, "runs": 0, "hashes": 0, "cutoffs": 0}
    conn = sqlite3.connect(db)
    try:
        def one(table: str) -> int:
            try:
                return int(conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0])
            except sqlite3.Error:
                return 0
        return {
            "chats": one("chats"),
            "runs": one("sync_runs"),
            "hashes": one("message_hashes"),
            # Shown to the user before they restore. A per-chat cutoff is a
            # decision they made by hand and would not think to make again,
            # so a bundle that silently carried none of them should be
            # visible as such rather than discovered months later.
            "cutoffs": one("chat_cutoffs"),
        }
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Import
# ---------------------------------------------------------------------------

def read_manifest(source: Path) -> dict:
    """The manifest of [source], for showing the user what they are about to restore."""
    try:
        with zipfile.ZipFile(source) as bundle:
            return json.loads(_read_small_member(bundle, _MANIFEST_NAME))
    except (OSError, KeyError, ValueError, zipfile.BadZipFile) as exc:
        raise BundleError("That file is not a Chat Mail Sync backup.") from exc


def created_at_epoch(created_at: str) -> int:
    """Seconds since the epoch for a bundle's `created_at`, or 0 if unreadable.

    Used by both front-ends to date the cover a *restore* provides. A backup
    file dated T captures the sent-history up to T, and it goes on doing that
    whether the person on this device wrote it or was handed it: after a
    restore the honest thing to say is "there is a backup, dated T", not "no
    backup yet" over the top of the fifty messages we just declined to re-send.

    Shared rather than parsed twice: the stamp is written by this module in
    local time, and two front-ends guessing at that format is two chances to
    read it an hour out.
    """
    try:
        return int(datetime.fromisoformat(created_at).timestamp())
    except (TypeError, ValueError):
        return 0


def import_bundle(root: Path, source: Path) -> dict:
    """Merge the bundle at [source] into the install at [root].

    Merge, never replace. Replacing would let an older bundle delete newer
    history -- and history here is the record of what has already been mailed,
    so deleting it does not lose data, it re-sends it. Everything below is
    therefore additive: chats and hashes insert-or-ignore, runs append under
    fresh ids, and a local row always wins over an incoming one of the same
    name.

    That last rule is why an incoming per-chat cutoff never overwrites one
    already set here: a floor the user set on *this* device is a live
    instruction, and a bundle is a photograph of an older one.

    Returns a result dict. Never raises for anything a user can do to the file;
    a bad bundle comes back as ok=False with a sentence to show them.
    """
    root = Path(root)
    source = Path(source)

    try:
        manifest = read_manifest(source)
    except BundleError as exc:
        return {"ok": False, "error": str(exc)}

    incoming_version = manifest.get("schema_version")
    if not isinstance(incoming_version, int):
        return {"ok": False, "error": "That backup's manifest is unreadable."}
    if incoming_version > BUNDLE_SCHEMA_VERSION:
        # Refused, not guessed at. A newer bundle may carry members this build
        # does not know how to merge, and half-merging a dedup ledger is worse
        # than not merging it.
        return {
            "ok": False,
            "error": (
                "That backup was made by a newer version of Chat Mail Sync. "
                "Update this device first, then restore."
            ),
        }

    db = _db_path(root)
    state.init_db(db)
    _ensure_bundle_ledger(db)

    bundle_id = manifest.get("bundle_id") or ""
    if bundle_id and _already_imported(db, bundle_id):
        return {
            "ok": True,
            "already_imported": True,
            "chats_added": 0,
            "runs_added": 0,
            "hashes_added": 0,
            "cutoffs_added": 0,
            "settings": {},
            "manifest": manifest,
            "created_at": str(manifest.get("created_at") or ""),
        }

    try:
        with zipfile.ZipFile(source) as bundle:
            names = set(bundle.namelist())
            settings = {}
            if _SETTINGS_NAME in names:
                try:
                    raw = json.loads(_read_small_member(bundle, _SETTINGS_NAME))
                except ValueError:
                    raw = {}
                # Filtered on the way in as well as on the way out. A bundle is
                # a file on disk that anyone can edit, and the allow-list is
                # cheaper to apply twice than to reason about once.
                settings = {k: v for k, v in raw.items() if k in _PORTABLE_SETTINGS}
                # And the host is not among them -- it is looked up from the
                # provider, never read from the file.
                settings = _with_derived_host(settings)

            added = {"chats_added": 0, "runs_added": 0,
                     "hashes_added": 0, "cutoffs_added": 0}
            if _DB_NAME in names:
                with tempfile.TemporaryDirectory() as tmp:
                    incoming = Path(tmp) / _DB_NAME
                    with bundle.open(_DB_NAME) as fh, open(incoming, "wb") as out:
                        shutil.copyfileobj(fh, out)
                    # Brought forward before it is read: an older install's DB
                    # can be short a column this build selects by name.
                    state.init_db(incoming)
                    added = _merge_db(db, incoming)
    except BundleError as exc:
        return {"ok": False, "error": str(exc)}
    except (OSError, zipfile.BadZipFile) as exc:
        return {"ok": False, "error": f"That backup could not be read: {exc}"}
    except sqlite3.DatabaseError as exc:
        return {"ok": False, "error": f"That backup's history could not be read: {exc}"}

    if bundle_id:
        _record_import(db, bundle_id, str(manifest.get("app_version") or ""))

    return {
        "ok": True,
        "already_imported": False,
        "settings": settings,
        "manifest": manifest,
        "created_at": str(manifest.get("created_at") or ""),
        **added,
    }


def _ensure_bundle_ledger(db: Path) -> None:
    """Remember which bundles have been merged here.

    Kept in this module rather than state.py's DDL because it is a fact about
    restores, not about syncing, and state.py's schema is read by every other
    part of the app.
    """
    conn = sqlite3.connect(db)
    try:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS imported_bundles ("
            "  bundle_id   TEXT PRIMARY KEY,"
            "  imported_at TEXT NOT NULL,"
            "  app_version TEXT"
            ")"
        )
        conn.commit()
    finally:
        conn.close()


def _already_imported(db: Path, bundle_id: str) -> bool:
    conn = sqlite3.connect(db)
    try:
        row = conn.execute(
            "SELECT 1 FROM imported_bundles WHERE bundle_id = ?", (bundle_id,)
        ).fetchone()
        return row is not None
    finally:
        conn.close()


def _record_import(db: Path, bundle_id: str, app_version: str) -> None:
    conn = sqlite3.connect(db)
    try:
        conn.execute(
            "INSERT OR IGNORE INTO imported_bundles (bundle_id, imported_at, app_version) "
            "VALUES (?, ?, ?)",
            (bundle_id, datetime.now().isoformat(timespec="seconds"), app_version),
        )
        conn.commit()
    finally:
        conn.close()


_CHAT_COLUMNS = (
    "chat_id", "display_name", "gmail_thread_id", "gmail_label_id",
    "anchor_message_id", "source_filename", "created_at", "updated_at",
)
# One definition, in state.py, next to the table it describes -- two copies of
# a natural key drift, and a merge that disagrees with the sweep about what
# makes a run the same run would put the duplicates straight back.
_RUN_COLUMNS = state.RUN_NATURAL_KEY


def _placeholders(columns) -> str:
    return ", ".join("?" for _ in columns)


def _merge_db(target: Path, incoming: Path) -> dict:
    """Additively merge [incoming] into [target]. Returns what was added."""
    src = sqlite3.connect(incoming)
    src.row_factory = sqlite3.Row
    dst = sqlite3.connect(target)
    dst.row_factory = sqlite3.Row
    try:
        dst.execute("PRAGMA foreign_keys = ON")

        chat_cols = ", ".join(_CHAT_COLUMNS)
        chats_added = 0
        for row in src.execute("SELECT " + chat_cols + " FROM chats").fetchall():
            cur = dst.execute(
                "INSERT OR IGNORE INTO chats (" + chat_cols + ") "
                "VALUES (" + _placeholders(_CHAT_COLUMNS) + ")",
                tuple(row[c] for c in _CHAT_COLUMNS),
            )
            chats_added += max(cur.rowcount, 0)

        # Runs are appended under fresh ids, and the old id is remembered only
        # long enough to point the hashes at the new one. run_id is an
        # AUTOINCREMENT primary key, so the incoming ids almost certainly
        # collide with local ones that mean something completely different --
        # keeping them would attach the old phone's hashes to this phone's runs.
        # Restoring the same bundle twice -- or restoring onto the phone the
        # backup came from -- used to append every run a second time, and the
        # sync log showed each one twice with no way to tell which was real.
        # The row itself is the natural key: same chat, same trigger, same
        # start and finish, same counts is the same run, not a second one that
        # happens to be identical. A run already here is skipped and its
        # incoming id mapped onto the local row, so the hashes underneath it
        # still repoint correctly instead of being dropped.
        run_cols = ", ".join(_RUN_COLUMNS)
        existing_runs = {
            tuple(row[c] for c in _RUN_COLUMNS): int(row["run_id"])
            for row in dst.execute(
                "SELECT run_id, " + run_cols + " FROM sync_runs"
            ).fetchall()
        }
        run_id_map = {}
        runs_added = 0
        for row in src.execute("SELECT run_id, " + run_cols + " FROM sync_runs").fetchall():
            key = tuple(row[c] for c in _RUN_COLUMNS)
            local = existing_runs.get(key)
            if local is None:
                cur = dst.execute(
                    "INSERT INTO sync_runs (" + run_cols + ") "
                    "VALUES (" + _placeholders(_RUN_COLUMNS) + ")",
                    key,
                )
                local = int(cur.lastrowid)
                existing_runs[key] = local
                runs_added += 1
            run_id_map[int(row["run_id"])] = local

        # Per-chat cutoffs. INSERT OR IGNORE, so a chat that already has a
        # floor on this device keeps it; only chats with no opinion here
        # inherit the old device's. Reading this table at all depends on
        # state.init_db() having been run over the incoming file first, which
        # import_bundle does -- a bundle written before this table existed
        # arrives without it and is given an empty one on the way in, so an
        # older backup restores as a backup with no overrides rather than an
        # error.
        cutoffs_added = 0
        for row in src.execute(
            "SELECT chat_id, cutoff_ts, set_at FROM chat_cutoffs"
        ).fetchall():
            cur = dst.execute(
                "INSERT OR IGNORE INTO chat_cutoffs (chat_id, cutoff_ts, set_at) "
                "VALUES (?, ?, ?)",
                (row["chat_id"], row["cutoff_ts"], row["set_at"]),
            )
            cutoffs_added += max(cur.rowcount, 0)

        hashes_added = 0
        rows = src.execute(
            "SELECT hash, chat_id, message_ts, run_id FROM message_hashes"
        ).fetchall()
        for row in rows:
            new_run = run_id_map.get(int(row["run_id"]))
            if new_run is None:
                # A hash whose run did not come across cannot satisfy the
                # foreign key. Skipped rather than repointed at some other run:
                # the hash is what stops a re-send, and which run it belonged to
                # is bookkeeping, but inventing a link would corrupt the
                # bookkeeping to save a row we cannot place honestly.
                continue
            cur = dst.execute(
                "INSERT OR IGNORE INTO message_hashes (hash, chat_id, message_ts, run_id) "
                "VALUES (?, ?, ?, ?)",
                (row["hash"], row["chat_id"], row["message_ts"], new_run),
            )
            hashes_added += max(cur.rowcount, 0)

        dst.commit()
        return {
            "chats_added": chats_added,
            "runs_added": runs_added,
            "hashes_added": hashes_added,
            "cutoffs_added": cutoffs_added,
        }
    except Exception:
        dst.rollback()
        raise
    finally:
        src.close()
        dst.close()
