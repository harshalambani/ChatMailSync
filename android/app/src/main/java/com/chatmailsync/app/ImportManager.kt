package com.chatmailsync.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Copies a WhatsApp export received via share-sheet (ACTION_SEND) or the SAF
 * file picker into the shared core's data/inbox/ directory. The app owns this
 * copy because content:// Uris are an Android-only concept — the core only
 * ever sees plain files already sitting in inbox/.
 */
/** Result of an import attempt: the file now sitting in the inbox, and
 * whether it was already there before this call (vs. freshly copied in). */
data class ImportOutcome(val file: File, val alreadyQueued: Boolean)

/** What one import produced. [outcome] is set when the file is in the queue; otherwise
 * [message] says in plain words why not. */
data class ImportResult(val outcome: ImportOutcome?, val message: String?)

/** What the screen shows once a share or a pick has finished. [id] is new for every report. */
data class ImportReport(val id: Long, val text: String, val fromShare: Boolean)

/** The largest export a share may copy in. The sync reads at most
 * MAX_ZIP_DECOMPRESSED_BYTES (500 MiB) out of a zip, so a bigger file could not be processed
 * anyway; 600 MiB leaves room for the zip's own overhead. */
internal const val MAX_SHARED_EXPORT_BYTES = 600L * 1_048_576L

/** A copy that has seen no new bytes for this long is a leftover and is cleaned at the next import. */
internal const val STALE_PART_MS = 10L * 60L * 1000L

private const val PART_SUFFIX = ".part"

/** Where a copy in progress lives: beside the queue, never inside it, so the queue listing, the
 * queue count and the "already queued" check cannot see it. Same volume as [inboxDir], so the
 * final rename is atomic. */
internal fun incomingDirFor(inboxDir: File): File = File(inboxDir.absoluteFile.parentFile, "incoming")

/** Only content links are copied; they are what another app hands over on purpose. */
internal fun isShareableScheme(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

/** The leaf of a name from another app: no folders, no separators, no dot names. Null if nothing is left. */
internal fun safeLeafName(raw: String?): String? {
    if (raw == null) return null
    val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    if (leaf.isEmpty() || leaf == "." || leaf == ".." || leaf.contains('\u0000')) return null
    return leaf
}

internal sealed interface CopyResult {
    data class Copied(val file: File) : CopyResult
    data class AlreadyQueued(val file: File) : CopyResult
    object TooBig : CopyResult
    object Failed : CopyResult
}

private class TooBigException : IOException()

private val copyLock = Any()

/** Removes copies left behind by a crash: [PART_SUFFIX] files in [incomingDir] untouched for [STALE_PART_MS]. */
internal fun sweepStaleParts(incomingDir: File, nowMs: Long) {
    val parts = incomingDir.listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) } ?: return
    for (p in parts) {
        if (nowMs - p.lastModified() >= STALE_PART_MS) runCatching { p.delete() }
    }
}

/**
 * Copies [open]'s bytes into [inboxDir] as [displayName]. The bytes are written to a temporary file in
 * [incomingDir] and renamed into the queue only when complete; a copy that fails, is cut off or goes
 * over [maxBytes] removes its own temporary file and queues nothing.
 */
internal fun copyIntoInbox(
    open: () -> InputStream?,
    displayName: String,
    inboxDir: File,
    incomingDir: File,
    maxBytes: Long = MAX_SHARED_EXPORT_BYTES,
    nowMs: Long = System.currentTimeMillis(),
): CopyResult {
    val dest = File(inboxDir, displayName)
    if (dest.exists()) return CopyResult.AlreadyQueued(dest)
    incomingDir.mkdirs()
    sweepStaleParts(incomingDir, nowMs)
    val tmp = File(incomingDir, "copy-${System.nanoTime()}$PART_SUFFIX")
    try {
        val input = open() ?: return CopyResult.Failed
        input.use { src ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw TooBigException()
                    out.write(buf, 0, n)
                }
            }
        }
        synchronized(copyLock) {
            if (dest.exists()) return CopyResult.AlreadyQueued(dest)
            inboxDir.mkdirs()
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath())
            }
        }
        return CopyResult.Copied(dest)
    } catch (_: TooBigException) {
        return CopyResult.TooBig
    } catch (_: Exception) {
        return CopyResult.Failed
    } finally {
        // A copy that did not finish (error, cut off, over the cap) leaves nothing behind.
        if (tmp.exists()) runCatching { tmp.delete() }
    }
}

/** The whole decision for one shared item, without Android types so it can be tested directly. */
internal fun importShared(
    scheme: String?,
    rawName: String?,
    open: () -> InputStream?,
    inboxDir: File,
    incomingDir: File,
    maxBytes: Long = MAX_SHARED_EXPORT_BYTES,
): ImportResult {
    if (!isShareableScheme(scheme)) {
        return ImportResult(
            null,
            "That item is not a shared file, so it was not imported. Nothing was copied.",
        )
    }
    val name = safeLeafName(rawName)
        ?: return ImportResult(null, "Import failed: could not read the shared/selected file.")
    return when (val r = copyIntoInbox(open, name, inboxDir, incomingDir, maxBytes)) {
        is CopyResult.Copied -> ImportResult(ImportOutcome(r.file, alreadyQueued = false), null)
        is CopyResult.AlreadyQueued -> ImportResult(ImportOutcome(r.file, alreadyQueued = true), null)
        CopyResult.TooBig -> ImportResult(
            null,
            "$name is larger than ${maxBytes / 1_048_576L} MB, which is more than a sync can read. Nothing was copied.",
        )
        CopyResult.Failed -> ImportResult(null, "Import failed: could not read the shared/selected file.")
    }
}

/** The line for a batch of results. */
internal fun importSummary(results: List<ImportResult>): String {
    var imported = 0
    var alreadyQueued = 0
    var failed = 0
    results.forEach {
        val outcome = it.outcome
        when {
            outcome == null -> failed++
            outcome.alreadyQueued -> alreadyQueued++
            else -> imported++
        }
    }
    return listOfNotNull(
        "Imported ${plural(imported, "file")}",
        "$alreadyQueued already queued".takeIf { alreadyQueued > 0 },
        "${plural(failed, "file")} could not be read".takeIf { failed > 0 },
    ).joinToString(" · ")
}

object ImportManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Items being copied right now, so a second request for the same item cannot start a second copy. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _report = MutableStateFlow<ImportReport?>(null)

    /** The latest finished batch. It lives here, not in a screen, so it survives the screen being rebuilt. */
    val report: StateFlow<ImportReport?> = _report

    private var nextId = 0L

    /** The screen has shown [id]; clear it so it is shown once. */
    fun consume(id: Long) {
        val current = _report.value
        if (current != null && current.id == id) _report.compareAndSet(current, null)
    }

    /**
     * Copies [uris] in the background and publishes a report when done. Returns at once, so the
     * main thread never waits on a file copy. Rotating the screen does not stop the copy and does
     * not start another: the work belongs to the app, and an item already being copied is skipped.
     */
    fun submit(context: Context, uris: List<Uri>, fromShare: Boolean) {
        val app = context.applicationContext
        val fresh = uris.filter { inFlight.add(it.toString()) }
        if (fresh.isEmpty()) return
        scope.launch {
            val results = try {
                fresh.map { importUriResult(app, it) }
            } finally {
                fresh.forEach { inFlight.remove(it.toString()) }
            }
            val text = if (results.size == 1) singleText(app, results.first()) else importSummary(results)
            synchronized(this@ImportManager) {
                _report.value = ImportReport(++nextId, text, fromShare)
            }
        }
    }

    private fun singleText(context: Context, result: ImportResult): String {
        val outcome = result.outcome
            ?: return result.message ?: "Import failed: could not read the shared/selected file."
        if (outcome.alreadyQueued) {
            return "${outcome.file.name} is already queued — remove it first (X) to re-import."
        }
        // preview_text() is the same call with format_preview() applied, and is what every other call
        // site already uses.
        val preview = ChatMailApplication.core(context)
            .previewText(outcome.file.absolutePath, AppPrefs.getCutoffDate(context))
        return "Imported ${outcome.file.name}\n\n$preview"
    }

    /** Returns the queued File on success, or null if the Uri couldn't be
     * read or was not a content link. Blocking: call it off the main thread. */
    fun importUri(context: Context, uri: Uri): ImportOutcome? = importUriResult(context, uri).outcome

    /** Blocking: call it off the main thread. */
    fun importUriResult(context: Context, uri: Uri): ImportResult {
        val inbox = ChatMailApplication.inboxDir(context)
        return importShared(
            scheme = uri.scheme,
            rawName = if (isShareableScheme(uri.scheme)) queryDisplayName(context, uri) else null,
            open = { context.contentResolver.openInputStream(uri) },
            inboxDir = inbox,
            incomingDir = incomingDirFor(inbox),
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        // content:// Uris carry their filename as metadata, not in the Uri
        // itself — must query the ContentResolver.
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
            // fall through to the last path segment
        }
        return uri.lastPathSegment
    }
}
