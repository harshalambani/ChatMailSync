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
#     mail to themselves. The new device asks once. Same for token.json.
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
    "imap_host",
    "imap_port",
    "imap_email",
})

# A tripwire, not the mechanism. The allow-list above is what keeps secrets out
# of a bundle; this catches the case where somebody adds a key to the allow-list
# whose name says plainly what it holds. It fires at export time, loudly,
# rather than shipping the bundle.
_FORBIDDEN_SUBSTRINGS = ("password", "secret", "token", "credential")


class BundleError(RuntimeError):
    """A bundle that cannot be read, or cannot be trusted to be read."""


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
        return {"chats": 0, "runs": 0, "hashes": 0}
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
            return json.loads(bundle.read(_MANIFEST_NAME))
    except (OSError, KeyError, ValueError, zipfile.BadZipFile) as exc:
        raise BundleError("That file is not a Chat Mail Sync backup.") from exc


def import_bundle(root: Path, source: Path) -> dict:
    """Merge the bundle at [source] into the install at [root].

    Merge, never replace. Replacing would let an older bundle delete newer
    history -- and history here is the record of what has already been mailed,
    so deleting it does not lose data, it re-sends it. Everything below is
    therefore additive: chats and hashes insert-or-ignore, runs append under
    fresh ids, and a local row always wins over an incoming one of the same
    name.

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
            "settings": {},
            "manifest": manifest,
        }

    try:
        with zipfile.ZipFile(source) as bundle:
            names = set(bundle.namelist())
            settings = {}
            if _SETTINGS_NAME in names:
                try:
                    raw = json.loads(bundle.read(_SETTINGS_NAME))
                except ValueError:
                    raw = {}
                # Filtered on the way in as well as on the way out. A bundle is
                # a file on disk that anyone can edit, and the allow-list is
                # cheaper to apply twice than to reason about once.
                settings = {k: v for k, v in raw.items() if k in _PORTABLE_SETTINGS}

            added = {"chats_added": 0, "runs_added": 0, "hashes_added": 0}
            if _DB_NAME in names:
                with tempfile.TemporaryDirectory() as tmp:
                    incoming = Path(tmp) / _DB_NAME
                    with bundle.open(_DB_NAME) as fh, open(incoming, "wb") as out:
                        shutil.copyfileobj(fh, out)
                    # Brought forward before it is read: an older install's DB
                    # can be short a column this build selects by name.
                    state.init_db(incoming)
                    added = _merge_db(db, incoming)
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
        }
    except Exception:
        dst.rollback()
        raise
    finally:
        src.close()
        dst.close()
