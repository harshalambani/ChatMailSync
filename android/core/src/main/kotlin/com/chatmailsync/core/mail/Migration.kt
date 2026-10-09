package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Carry an install across to a new device: the dedup ledger and the settings. Twin of
 * `src/migration.py` (KT-08); the Kotlin side of the `.cmsbackup` bundle, byte-compatible
 * in both directions with the Python one (a bundle either side writes imports on the other
 * with the same merge result).
 *
 * Why this exists: the mailbox already is the archive, but `sync_state.db` -- the record of
 * which message hashes were already sent -- cannot be rebuilt from it. Without it a second
 * device mails the entire history again, into a mailbox that has no conflict resolution
 * because the app is write-only.
 *
 * Three rules shape everything here, carried over unchanged from the Python:
 *
 *  - **The root is a parameter.** Nothing here knows where the app keeps its files; the
 *    caller passes the install root (and the way to open a database, see [OpenDb]).
 *  - **Settings move by allow-list, never deny-list.** [PORTABLE_SETTINGS] is the complete
 *    set of keys that may leave the device, and it is applied again on the way in.
 *  - **The mail server is never taken from the bundle.** A bundle is a file anyone can
 *    edit. The host (and, for a preset, the port) is derived from the provider preset in
 *    [withDerivedHost]; a bundle that names one is ignored.
 *
 * Where the Kotlin differs from the Python is listed in the KT-08 PR body; the ones that
 * matter in use: the snapshot is taken with `VACUUM INTO` (SQLite 3.27, under the Android 11
 * floor of 3.28) instead of the Python-only `backup()` API; an export is built beside the
 * target and copied over it, so a failed export never leaves half a bundle; and database
 * driver text is never copied into a result message.
 */

/** Opens the SQLite file at the given path. The same shape [StateRepository] takes. */
typealias OpenDb = (File) -> StateDb

/** Bumped only when the *bundle* layout changes (member names, manifest shape). */
const val BUNDLE_SCHEMA_VERSION: Int = 1

const val BUNDLE_SUFFIX: String = ".cmsbackup"

internal const val MANIFEST_NAME = "manifest.json"
internal const val BUNDLE_DB_NAME = "sync_state.db"
internal const val SETTINGS_NAME = "settings.json"

/**
 * Everything that may cross to another device. No credential of any kind, no watched
 * folder (a SAF grant does not transfer), no `last_connection_*` (a verdict about a
 * credential the bundle leaves behind), and no `imap_host`: it used to travel and a
 * crafted bundle could point the app at a server of its choosing -- the host now comes from
 * the provider preset ([withDerivedHost]).
 */
val PORTABLE_SETTINGS: Set<String> = setOf(
    "chunk_size",
    "watch_interval_minutes",
    "synced_file_policy",
    "theme_mode",
    "dry_run_default",
    "mail_backend",
    "imap_provider",
    "imap_port",
    "imap_email",
)

/** A tripwire, not the mechanism: fires at export if a key whose name says what it holds gets onto the allow-list. */
val FORBIDDEN_SUBSTRINGS: List<String> = listOf("password", "secret", "token", "credential")

/**
 * The manifest and the settings are both a few hundred bytes of JSON and are read whole into
 * memory, so they get a ceiling. The database member is streamed to disk, never read whole, and
 * has its own, much larger one ([MAX_BUNDLE_DB_BYTES]).
 */
const val MAX_BUNDLE_MEMBER_BYTES: Int = 4 * 1_048_576

/** The most the database member may expand to: a hostile or corrupt zip cannot fill the disk. */
const val MAX_BUNDLE_DB_BYTES: Long = 1L shl 30

private const val HISTORY_UNREADABLE = "That backup's history could not be read."

/** An integer column value, or a refusal: a hand-edited file can hold text, NULL or a real there. */
private fun historyLong(v: Any?): Long =
    if (v is Long || v is Int || v is Short || v is Byte) (v as Number).toLong() else throw BundleError(HISTORY_UNREADABLE)

/** A bundle that cannot be read, or cannot be trusted to be read. */
class BundleError(message: String) : RuntimeException(message)

/** The five row counts a manifest records and the import result reports. */
data class BundleCounts(val chats: Int, val runs: Int, val hashes: Int, val cutoffs: Int, val senders: Int) {
    internal fun toJson(): Map<String, Any?> = linkedMapOf(
        "chats" to chats, "runs" to runs, "hashes" to hashes, "cutoffs" to cutoffs, "senders" to senders,
    )
}

data class ExportResult(
    val path: String,
    val bundleId: String,
    val counts: BundleCounts,
    val settingsKeys: List<String>,
)

/**
 * What [importBundle] reports. `ok = false` carries a sentence to show the person in [error]
 * and nothing else; the `*Added` counts are only meaningful when `ok`.
 */
data class ImportResult(
    val ok: Boolean,
    val error: String? = null,
    val alreadyImported: Boolean = false,
    val chatsAdded: Int = 0,
    val runsAdded: Int = 0,
    val hashesAdded: Int = 0,
    val cutoffsAdded: Int = 0,
    val sendersAdded: Int = 0,
    val settings: Map<String, Any?> = emptyMap(),
    val manifest: Map<String, Any?> = emptyMap(),
    val createdAt: String = "",
)

// ---------------------------------------------------------------------------
// Zip member reading
// ---------------------------------------------------------------------------

/** The member named [name], the LAST one if the name is repeated (what Python's `getinfo` returns). */
private fun findMember(zf: ZipFile, name: String): ZipEntry? {
    var found: ZipEntry? = null
    val all = zf.entries()
    while (all.hasMoreElements()) {
        val e = all.nextElement()
        if (e.name == name) found = e
    }
    return found
}

/**
 * Copies [entry] to [out], checking it against its own header: a member whose real size or
 * CRC disagrees with what the header claims is refused (Python's `BadZipFile`), so a lying
 * header cannot make a small claim and deliver a large file. With [limit] set, more than
 * that many bytes is refused as soon as it is seen -- nothing past the limit is held.
 */
private fun copyChecked(zf: ZipFile, entry: ZipEntry, out: OutputStream, limit: Long) {
    val crc = CRC32()
    var total = 0L
    zf.getInputStream(entry).use { input: InputStream ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw BundleError(
                    "That backup's ${entry.name} would expand past the $limit-byte safety limit.",
                )
            }
            crc.update(buf, 0, n)
            out.write(buf, 0, n)
        }
    }
    if ((entry.size >= 0 && total != entry.size) || (entry.crc >= 0 && crc.value != entry.crc)) {
        throw ZipException("member does not match its header")
    }
}

/** The bytes of [entry], refused if it claims -- or turns out -- to inflate past the ceiling. */
private fun readSmallMember(zf: ZipFile, entry: ZipEntry): ByteArray {
    val size = entry.size
    if (size > MAX_BUNDLE_MEMBER_BYTES) {
        throw BundleError(
            "That backup's ${entry.name} would expand to $size bytes, past the " +
                "$MAX_BUNDLE_MEMBER_BYTES-byte safety limit.",
        )
    }
    val out = java.io.ByteArrayOutputStream()
    copyChecked(zf, entry, out, MAX_BUNDLE_MEMBER_BYTES.toLong())
    return out.toByteArray()
}

private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    null
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/**
 * Puts the provider's own host and port back on [settings], in place. The bundle's word is
 * not taken for either. For a preset the answer is a constant this build already holds; for
 * "custom" the host is left absent (only that user knows it). A retired provider key is
 * rewritten to the key that replaced it ([retiredProviderLanding]); an unrecognised one is
 * left alone and falls through to the no-preset path, which drops the host.
 */
internal fun withDerivedHost(settings: MutableMap<String, Any?>): MutableMap<String, Any?> {
    // Dropped first, unconditionally: the second lock on the same door, the one that still
    // holds if a future release puts imap_host back on the allow-list.
    settings.remove("imap_host")
    val landing = retiredProviderLanding(settings["imap_provider"] as? String)
    if (landing.isNotEmpty()) settings["imap_provider"] = landing
    val preset = IMAP_PROVIDERS[(settings["imap_provider"] as? String) ?: ""]
    if (preset != null && preset.host != null) {
        settings["imap_host"] = preset.host
        settings["imap_port"] = preset.port.toLong()
    }
    return settings
}

/** The subset of [settings] that may leave this device, in sorted key order. */
fun portableSettings(settings: Map<String, Any?>): Map<String, Any?> =
    portableSettings(settings, PORTABLE_SETTINGS)

internal fun portableSettings(settings: Map<String, Any?>, allowed: Set<String>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (key in allowed.sorted()) {
        if (!settings.containsKey(key)) continue
        val lowered = key.lowercase()
        if (FORBIDDEN_SUBSTRINGS.any { lowered.contains(it) }) {
            throw BundleError("Refusing to export '$key': nothing that names a credential leaves this device.")
        }
        out[key] = settings[key]
    }
    return out
}

// ---------------------------------------------------------------------------
// Paths and small database helpers
// ---------------------------------------------------------------------------

/** The state DB under [root], by the same layout `config._apply_root` uses. */
internal fun bundleDbPath(root: File): File = File(File(root, "data"), BUNDLE_DB_NAME)

private fun tempDirIn(parent: File?, prefix: String): File =
    (if (parent != null) Files.createTempDirectory(parent.toPath(), prefix) else Files.createTempDirectory(prefix)).toFile()

private fun removeOwnTemp(dir: File) {
    // Only ever the scratch directory this module created itself, a flat folder of the
    // snapshot / extracted member / built zip.
    dir.listFiles()?.forEach { it.delete() }
    dir.delete()
}

internal fun countBundleRows(db: File, openDb: OpenDb): BundleCounts {
    if (!db.exists()) return BundleCounts(0, 0, 0, 0, 0)
    val conn = try {
        openDb(db)
    } catch (_: StateDbException) {
        return BundleCounts(0, 0, 0, 0, 0)
    }
    return conn.use { c ->
        fun one(table: String): Int = try {
            (c.query("SELECT COUNT(*) AS n FROM $table").first()["n"] as Number).toInt()
        } catch (_: StateDbException) {
            0
        }
        BundleCounts(one("chats"), one("sync_runs"), one("message_hashes"), one("chat_cutoffs"), one("chat_senders"))
    }
}

/**
 * A consistent copy of [source] at [dest] (which must not exist yet). The DB runs in WAL mode
 * so the file alone can be missing commits that live in the -wal sidecar; `VACUUM INTO` reads
 * one consistent snapshot through the connection, as Python's `backup()` does.
 */
internal fun snapshotBundleDb(source: File, dest: File, openDb: OpenDb) {
    openDb(source).use { it.exec("VACUUM INTO ?", listOf(dest.path)) }
}

// ---------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------

/**
 * Writes a restore bundle for the install at [root] to [dest]. [settings] is passed in
 * rather than read, because the caller keeps it elsewhere (SharedPreferences). [bundleId],
 * [createdAt] and [zipTimeMillis] default to a fresh UUID, now, and now; they are parameters
 * so a test can pin them.
 *
 * Throws [BundleError] only for the credential tripwire (a programming error, not something
 * a person can cause).
 */
fun exportBundle(
    root: File,
    dest: File,
    openDb: OpenDb,
    settings: Map<String, Any?> = emptyMap(),
    appVersion: String = "",
    bundleId: String = UUID.randomUUID().toString(),
    createdAt: String = StateRepository.now(),
    zipTimeMillis: Long = System.currentTimeMillis(),
    tempDir: File? = null,
): ExportResult {
    val db = bundleDbPath(root)
    val counts = countBundleRows(db, openDb)
    val manifest = linkedMapOf<String, Any?>(
        "schema_version" to BUNDLE_SCHEMA_VERSION,
        "bundle_id" to bundleId,
        "created_at" to createdAt,
        "app_version" to appVersion,
        "counts" to counts.toJson(),
    )
    val portable = portableSettings(settings)

    val scratch = tempDirIn(tempDir, "cms_export_")
    try {
        val built = File(scratch, "bundle.zip")
        ZipOutputStream(built.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                val e = ZipEntry(name)
                e.time = zipTimeMillis
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST_NAME, dumpBundleJson(manifest).toByteArray(Charsets.UTF_8))
            put(SETTINGS_NAME, dumpBundleJson(portable).toByteArray(Charsets.UTF_8))
            if (db.exists()) {
                val snapshot = File(scratch, BUNDLE_DB_NAME)
                snapshotBundleDb(db, snapshot, openDb)
                put(BUNDLE_DB_NAME, snapshot.readBytes())
            }
        }
        dest.absoluteFile.parentFile?.mkdirs()
        Files.copy(built.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        removeOwnTemp(scratch)
    }
    return ExportResult(dest.path, bundleId, counts, portable.keys.sorted())
}

// ---------------------------------------------------------------------------
// Import
// ---------------------------------------------------------------------------

/** The manifest of [source], for showing the person what they are about to restore. */
fun readManifest(source: File): Map<String, Any?> {
    val notABackup = "That file is not a Chat Mail Sync backup."
    try {
        ZipFile(source).use { zf ->
            val entry = findMember(zf, MANIFEST_NAME) ?: throw BundleError(notABackup)
            val text = decodeStrictUtf8(readSmallMember(zf, entry)) ?: throw BundleError(notABackup)
            @Suppress("UNCHECKED_CAST")
            return (parseBundleJson(text) as? Map<String, Any?>) ?: throw BundleError(notABackup)
        }
    } catch (e: BundleError) {
        throw e
    } catch (_: IOException) {
        throw BundleError(notABackup)
    } catch (_: IllegalArgumentException) {
        throw BundleError(notABackup)
    } catch (_: BundleJsonException) {
        throw BundleError(notABackup)
    }
}

/**
 * Seconds since the epoch for a bundle's `created_at` (written in local time), or 0 if it is
 * unreadable. Twin of `created_at_epoch`; accepts the ISO shapes Python's `fromisoformat`
 * does for a stamp this module writes, with or without an offset.
 */
fun createdAtEpoch(createdAt: String?, zone: ZoneId = ZoneId.systemDefault()): Long {
    val s = createdAt?.trim() ?: return 0
    if (s.isEmpty()) return 0
    val iso = s.replace(' ', 'T')
    return try {
        if (iso.length == 10) {
            LocalDate.parse(iso).atStartOfDay(zone).toEpochSecond()
        } else {
            try {
                OffsetDateTime.parse(iso).toEpochSecond()
            } catch (_: java.time.format.DateTimeParseException) {
                LocalDateTime.parse(iso).atZone(zone).toEpochSecond()
            }
        }
    } catch (_: java.time.DateTimeException) {
        0
    }
}

private fun pyStr(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Boolean -> if (v) "True" else ""
    is Long -> if (v == 0L) "" else v.toString()
    is Double -> if (v == 0.0) "" else v.toString()
    is Collection<*> -> if (v.isEmpty()) "" else v.toString()
    is Map<*, *> -> if (v.isEmpty()) "" else v.toString()
    else -> v.toString()
}

private fun failure(message: String) = ImportResult(ok = false, error = message)

/**
 * Merges the bundle at [source] into the install at [root]. Merge, never replace: replacing
 * would let an older bundle delete newer history, and history here is the record of what has
 * already been mailed, so deleting it does not lose data, it re-sends it. Everything is
 * additive -- chats, hashes, cutoffs, senders and app state insert-or-ignore, runs matched on
 * [StateRepository.RUN_NATURAL_KEY] and appended under fresh ids otherwise -- and a local row
 * always wins over an incoming one of the same name.
 *
 * Never throws for anything a person can do to the file; a bad bundle comes back as
 * `ok = false` with a sentence to show them, and none of those sentences carries a settings
 * value, a name, an address or driver text.
 */
fun importBundle(
    root: File,
    source: File,
    openDb: OpenDb,
    tempDir: File? = null,
    importedAt: String = StateRepository.now(),
    maxDbBytes: Long = MAX_BUNDLE_DB_BYTES,
): ImportResult {
    val manifest = try {
        readManifest(source)
    } catch (e: BundleError) {
        return failure(e.message ?: "That file is not a Chat Mail Sync backup.")
    }

    val incomingVersion = manifest["schema_version"]
    if (incomingVersion !is Long) return failure("That backup's manifest is unreadable.")
    if (incomingVersion > BUNDLE_SCHEMA_VERSION) {
        // Refused, not guessed at: half-merging a dedup ledger is worse than not merging it.
        return failure(
            "That backup was made by a newer version of Chat Mail Sync. " +
                "Update this device first, then restore.",
        )
    }

    val db = bundleDbPath(root)
    db.parentFile?.mkdirs()
    StateRepository { openDb(db) }.initDb()
    ensureBundleLedger(db, openDb)

    val createdAt = pyStr(manifest["created_at"])
    val bundleId = (manifest["bundle_id"] as? String) ?: ""
    if (bundleId.isNotEmpty() && alreadyImported(db, bundleId, openDb)) {
        return ImportResult(ok = true, alreadyImported = true, manifest = manifest, createdAt = createdAt)
    }

    var settings: Map<String, Any?> = emptyMap()
    var added = MergeCounts()
    val scratch = tempDirIn(tempDir, "cms_import_")
    try {
        ZipFile(source).use { zf ->
            val settingsEntry = findMember(zf, SETTINGS_NAME)
            if (settingsEntry != null) {
                // Unparseable settings are treated as empty (as Python does). Settings that
                // parse but are not an object make Python crash uncaught; here they are refused.
                var parsedOk = false
                val raw = decodeStrictUtf8(readSmallMember(zf, settingsEntry))
                    ?.let { try { parseBundleJson(it).also { parsedOk = true } } catch (_: BundleJsonException) { null } }
                if (parsedOk && raw !is Map<*, *>) throw BundleError("That backup's settings are unreadable.")
                val kept = LinkedHashMap<String, Any?>()
                if (raw is Map<*, *>) {
                    // Filtered on the way in as well as on the way out: a bundle is a file
                    // anyone can edit, and the allow-list is cheaper to apply twice.
                    for ((k, v) in raw) if (k in PORTABLE_SETTINGS) kept[k as String] = v
                }
                // And the host is not among them -- it is looked up from the provider.
                settings = withDerivedHost(kept)
            }

            val dbEntry = findMember(zf, BUNDLE_DB_NAME)
            if (dbEntry != null) {
                val incoming = File(scratch, BUNDLE_DB_NAME)
                if (dbEntry.size > maxDbBytes) {
                    throw BundleError(
                        "That backup's ${dbEntry.name} would expand to ${dbEntry.size} bytes, past the " +
                            "$maxDbBytes-byte safety limit.",
                    )
                }
                incoming.outputStream().buffered().use { copyChecked(zf, dbEntry, it, maxDbBytes) }
                // Brought forward before it is read: an older install's DB can be short a column.
                StateRepository { openDb(incoming) }.initDb()
                added = mergeDb(db, incoming, openDb)
            }
        }
    } catch (e: BundleError) {
        return failure(e.message ?: "That backup could not be read.")
    } catch (_: IOException) {
        return failure("That backup could not be read.")
    } catch (_: IllegalArgumentException) {
        return failure("That backup could not be read.")
    } catch (_: StateDbException) {
        return failure("That backup's history could not be read.")
    } finally {
        removeOwnTemp(scratch)
    }

    if (bundleId.isNotEmpty()) recordImport(db, bundleId, pyStr(manifest["app_version"]), importedAt, openDb)

    return ImportResult(
        ok = true,
        alreadyImported = false,
        chatsAdded = added.chats,
        runsAdded = added.runs,
        hashesAdded = added.hashes,
        cutoffsAdded = added.cutoffs,
        sendersAdded = added.senders,
        settings = settings,
        manifest = manifest,
        createdAt = createdAt,
    )
}

/**
 * Remembers which bundles have been merged here. Kept in this module rather than the state
 * schema because it is a fact about restores, not about syncing, and the state schema is
 * read by every other part of the app.
 */
internal fun ensureBundleLedger(db: File, openDb: OpenDb) {
    openDb(db).use {
        it.exec(
            "CREATE TABLE IF NOT EXISTS imported_bundles (" +
                "  bundle_id   TEXT PRIMARY KEY," +
                "  imported_at TEXT NOT NULL," +
                "  app_version TEXT" +
                ")",
        )
    }
}

internal fun alreadyImported(db: File, bundleId: String, openDb: OpenDb): Boolean =
    openDb(db).use {
        it.query("SELECT 1 AS found FROM imported_bundles WHERE bundle_id = ?", listOf(bundleId)).isNotEmpty()
    }

internal fun recordImport(db: File, bundleId: String, appVersion: String, importedAt: String, openDb: OpenDb) {
    openDb(db).use {
        it.exec(
            "INSERT OR IGNORE INTO imported_bundles (bundle_id, imported_at, app_version) VALUES (?, ?, ?)",
            listOf(bundleId, importedAt, appVersion),
        )
    }
}

private val CHAT_COLUMNS = listOf(
    "chat_id", "display_name", "gmail_thread_id", "gmail_label_id",
    "anchor_message_id", "source_filename", "created_at", "updated_at",
)

/** One definition, in [StateRepository], next to the table it describes. */
private val RUN_COLUMNS: List<String> = StateRepository.RUN_NATURAL_KEY

/** Hashes are read from the incoming file this many at a time, so a large ledger is never one list. */
private const val HASH_PAGE = 5000

internal data class MergeCounts(
    val chats: Int = 0,
    val runs: Int = 0,
    val hashes: Int = 0,
    val cutoffs: Int = 0,
    val senders: Int = 0,
)

private fun placeholders(n: Int) = List(n) { "?" }.joinToString(", ")

private fun StateDb.changes(): Int = (query("SELECT changes() AS n").first()["n"] as Number).toInt()

/** Additively merges [incoming] into [target], in one transaction. Returns what was added. */
internal fun mergeDb(target: File, incoming: File, openDb: OpenDb): MergeCounts {
    val src = openDb(incoming)
    try {
        val dst = openDb(target)
        try {
            // Outside the transaction (SQLite ignores it inside one): a hash whose chat is
            // missing is then refused, and the whole merge rolls back.
            dst.exec("PRAGMA foreign_keys = ON")
            dst.beginTransaction()
            try {
                val counts = mergeRows(src, dst)
                dst.commit()
                return counts
            } catch (t: Throwable) {
                try { dst.rollback() } catch (r: Throwable) { t.addSuppressed(r) }
                throw t
            }
        } finally {
            dst.close()
        }
    } finally {
        src.close()
    }
}

private fun mergeRows(src: StateDb, dst: StateDb): MergeCounts {
    val chatCols = CHAT_COLUMNS.joinToString(", ")
    var chatsAdded = 0
    for (row in src.query("SELECT $chatCols FROM chats")) {
        dst.exec(
            "INSERT OR IGNORE INTO chats ($chatCols) VALUES (${placeholders(CHAT_COLUMNS.size)})",
            CHAT_COLUMNS.map { row[it] },
        )
        chatsAdded += dst.changes()
    }

    // Runs are appended under fresh ids and the old id is remembered only long enough to
    // point the hashes at the new one (run_id is AUTOINCREMENT, so the incoming ids collide
    // with local ones that mean something else). The row itself is the natural key: a run
    // already here is skipped and its incoming id mapped onto the local row, so the hashes
    // underneath it still repoint correctly.
    val runCols = RUN_COLUMNS.joinToString(", ")
    val existingRuns = HashMap<List<Any?>, Long>()
    for (row in dst.query("SELECT run_id, $runCols FROM sync_runs")) {
        existingRuns[RUN_COLUMNS.map { row[it] }] = historyLong(row["run_id"])
    }
    val runIdMap = HashMap<Long, Long>()
    var runsAdded = 0
    for (row in src.query("SELECT run_id, $runCols, messages_cutoff FROM sync_runs")) {
        val oldId = historyLong(row["run_id"])
        val key = RUN_COLUMNS.map { row[it] }
        var local = existingRuns[key]
        if (local == null) {
            // BUG-09: messages_cutoff is carried across (Python drops it) but is not part of the
            // natural key, so a run already here keeps its own value and is never rewritten.
            val cutoff = historyLong(row["messages_cutoff"])
            dst.exec(
                "INSERT INTO sync_runs ($runCols, messages_cutoff) VALUES (${placeholders(RUN_COLUMNS.size + 1)})",
                key + cutoff,
            )
            local = dst.lastInsertRowId()
            existingRuns[key] = local
            runsAdded++
        }
        runIdMap[oldId] = local
    }

    // INSERT OR IGNORE: a chat that already has a floor on this device keeps it.
    var cutoffsAdded = 0
    for (row in src.query("SELECT chat_id, cutoff_ts, set_at FROM chat_cutoffs")) {
        dst.exec(
            "INSERT OR IGNORE INTO chat_cutoffs (chat_id, cutoff_ts, set_at) VALUES (?, ?, ?)",
            listOf(row["chat_id"], row["cutoff_ts"], row["set_at"]),
        )
        cutoffsAdded += dst.changes()
    }

    // A (chat_id, sender) pair this device has already started counting keeps its own total.
    var sendersAdded = 0
    val senderRows = try {
        src.query("SELECT chat_id, sender, first_seen, last_seen, msg_count FROM chat_senders")
    } catch (_: StateDbException) {
        emptyList()
    }
    for (row in senderRows) {
        dst.exec(
            "INSERT OR IGNORE INTO chat_senders (chat_id, sender, first_seen, last_seen, msg_count) " +
                "VALUES (?, ?, ?, ?, ?)",
            listOf(row["chat_id"], row["sender"], row["first_seen"], row["last_seen"], row["msg_count"]),
        )
        sendersAdded += dst.changes()
    }

    // This device's own answer stands; only keys it has no opinion on are inherited.
    val stateRows = try {
        src.query("SELECT key, value FROM app_state")
    } catch (_: StateDbException) {
        emptyList()
    }
    for (row in stateRows) {
        dst.exec("INSERT OR IGNORE INTO app_state (key, value) VALUES (?, ?)", listOf(row["key"], row["value"]))
    }

    var hashesAdded = 0
    var after = Long.MIN_VALUE
    while (true) {
        val page = src.query(
            "SELECT rowid AS rid, hash, chat_id, message_ts, run_id FROM message_hashes " +
                "WHERE rowid > ? ORDER BY rowid LIMIT $HASH_PAGE",
            listOf(after),
        )
        if (page.isEmpty()) break
        for (row in page) {
            after = historyLong(row["rid"])
            // A hash whose run did not come across is skipped, not repointed at some other
            // run: which run it belonged to is bookkeeping, and inventing a link would
            // corrupt the bookkeeping to save a row we cannot place honestly.
            val newRun = runIdMap[historyLong(row["run_id"])] ?: continue
            dst.exec(
                "INSERT OR IGNORE INTO message_hashes (hash, chat_id, message_ts, run_id) VALUES (?, ?, ?, ?)",
                listOf(row["hash"], row["chat_id"], row["message_ts"], newRun),
            )
            hashesAdded += dst.changes()
        }
    }
    return MergeCounts(chatsAdded, runsAdded, hashesAdded, cutoffsAdded, sendersAdded)
}
