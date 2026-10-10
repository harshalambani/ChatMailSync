package com.chatmailsync.core.mail

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern

/**
 * Kotlin port of the bookkeeping half of `src/sync_manager.py`: `SyncStats`
 * (with its `__str__`), `_collect_omissions` and `_scrub_paths`.
 *
 * Not wired into `:app`. Nothing here touches the database, the mailbox or
 * the file system.
 */

/**
 * What one sync pass did. Mirrors `SyncStats`; the counters are plain vars
 * because the run loop adds to them as it goes, exactly like the dataclass.
 */
class SyncStats {
    var filesFound: Int = 0
    var filesSynced: Int = 0
    /** Nothing new to push. */
    var filesSkipped: Int = 0
    var filesFailed: Int = 0
    var messagesParsed: Int = 0
    var messagesSynced: Int = 0
    /** Deduped or filtered: the app had already sent these. */
    var messagesSkipped: Int = 0
    /**
     * Messages the user asked not to have because they predate the cutoff
     * date. Its own number on purpose: folding it into [messagesSkipped] would
     * make a run that withheld thousands of messages on a mistyped date look
     * identical to a run that simply found nothing new.
     */
    var messagesCutoff: Int = 0
    var chatsRecovered: Int = 0
    val errors: MutableList<String> = mutableListOf()
    /**
     * Media that no email could carry, or that a file name made unsafe to
     * send. Kept apart from [errors] because nothing failed: the message and
     * its place in the thread were archived and only the file stayed behind.
     */
    val mediaOmitted: MutableList<String> = mutableListOf()

    /** Mirrors `SyncStats.__str__`. */
    override fun toString(): String {
        val lines = mutableListOf(
            "Files   : found=$filesFound  synced=$filesSynced  skipped=$filesSkipped  failed=$filesFailed",
            "Messages: parsed=$messagesParsed  synced=$messagesSynced  skipped=$messagesSkipped",
        )
        if (messagesCutoff != 0) {
            lines.add("Held back $messagesCutoff message(s) from before your cutoff date")
        }
        if (chatsRecovered != 0) {
            lines.add("Recovered $chatsRecovered interrupted run(s)")
        }
        if (mediaOmitted.isNotEmpty()) {
            lines.add("Media too large to email (archived without the file - it stays in your WhatsApp export):")
            for (m in mediaOmitted) lines.add("  - $m")
        }
        if (errors.isNotEmpty()) {
            lines.add("Errors:")
            for (e in errors) lines.add("  - $e")
        }
        return lines.joinToString("\n")
    }

    /**
     * The same keys `dataclasses.asdict(SyncStats)` gives the Python caller
     * (`android_api.sync` returns `{**asdict(stats), "stopped": ...}`), so a
     * later cut-over can hand this map to the code that reads that dict.
     */
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "files_found" to filesFound,
        "files_synced" to filesSynced,
        "files_skipped" to filesSkipped,
        "files_failed" to filesFailed,
        "messages_parsed" to messagesParsed,
        "messages_synced" to messagesSynced,
        "messages_skipped" to messagesSkipped,
        "messages_cutoff" to messagesCutoff,
        "chats_recovered" to chatsRecovered,
        "errors" to errors.toList(),
        "media_omitted" to mediaOmitted.toList(),
    )
}

/** Python's `f"{x:.{digits}f}"`: round-half-even on the exact binary value of [x]. */
private fun pyFixed(x: Double, digits: Int): String =
    BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()

/**
 * Replace every control character in [name] with a question mark. A file name
 * can hold a line break, and these lines are shown in the app and in logs.
 */
internal fun printableName(name: String): String =
    buildString {
        for (c in name) append(if (c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F) '?' else c)
    }

/**
 * Mirrors `_collect_omissions`: fold any media a push left out into the run's
 * summary. Deduplicated on the whole line, so a chat re-synced after new
 * messages arrive (which re-renders the same day) announces a given file once.
 *
 * Deliberate difference from Python (D35): an omission with a [reason] is a
 * file this port chose not to send because its name could not be written into
 * a mail header. It is announced with that reason rather than as a size.
 */
fun collectOmissions(stats: SyncStats, displayName: String, results: List<PushResult>) {
    val seen = stats.mediaOmitted.toMutableSet()
    for (result in results) {
        for (om in result.omissions) {
            val line = if (om.reason != null) {
                "$displayName: ${printableName(om.filename)} skipped - ${om.reason}"
            } else {
                val mb = om.sizeBytes / 1_000_000.0
                val limitMb = om.limitBytes / 1_000_000.0
                "$displayName: ${om.filename} (${pyFixed(mb, 1)} MB) exceeds the ${pyFixed(limitMb, 0)} MB per-email limit"
            }
            if (seen.add(line)) stats.mediaOmitted.add(line)
        }
    }
}

// ---------------------------------------------------------------------------
// _scrub_paths
// ---------------------------------------------------------------------------

// Python's \s and \S are Unicode-aware; the extra range is the four ASCII
// separators str.isspace() also counts.
private const val PY_SPACE = "\\s\\u001C-\\u001F"
private val URL_RE: Pattern = Pattern.compile("https?://[^$PY_SPACE]+", Pattern.UNICODE_CHARACTER_CLASS)
private val FS_PATH_RE: Pattern = Pattern.compile(
    "(?:[A-Za-z]:\\\\|\\\\\\\\)[^$PY_SPACE,'\")\\]]+|(?:/[^/$PY_SPACE,'\")\\]]+){2,}",
    Pattern.UNICODE_CHARACTER_CLASS,
)

/**
 * `PurePosixPath(text).name` for a matched path (Python on Android is POSIX):
 * the last segment, with empty and "." segments dropped the way pathlib does.
 */
private fun pathBaseName(path: String): String =
    path.split('/').filter { it.isNotEmpty() && it != "." }.lastOrNull() ?: ""

private fun scrubSegment(text: String): String {
    val m = FS_PATH_RE.matcher(text)
    val sb = StringBuilder()
    var last = 0
    while (m.find()) {
        sb.append(text, last, m.start())
        sb.append(pathBaseName(m.group()))
        last = m.end()
    }
    sb.append(text, last, text.length)
    return sb.toString()
}

/**
 * Mirrors `_scrub_paths`: replace absolute file system paths in error text
 * with their last segment, and leave URLs alone (otherwise the path part of a
 * URL would be collapsed to its final segment).
 *
 * This hides install paths. It does NOT hide secrets; text that may carry the
 * app password goes through [stripSecret] as well (see [SyncManager]).
 *
 * Platform note: Python's `Path.name` follows the platform it runs on. This
 * port follows POSIX, which is what the Android build of the Python code did,
 * so a Windows drive path is matched but kept whole, as it was there.
 */
fun scrubPaths(text: String): String {
    val out = StringBuilder()
    var last = 0
    val urls = URL_RE.matcher(text)
    while (urls.find()) {
        out.append(scrubSegment(text.substring(last, urls.start())))
        out.append(urls.group())
        last = urls.end()
    }
    out.append(scrubSegment(text.substring(last)))
    return out.toString()
}
