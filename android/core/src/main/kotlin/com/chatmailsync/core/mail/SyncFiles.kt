package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * File-system half of `src/sync_manager.py`: which inbox files a run looks at,
 * and the inbox -> processed move. No database, no mailbox. Not wired into
 * `:app`.
 */

/** Python 3.13 `PurePath.suffix`: from the last dot, only when it is neither first nor last. */
internal fun pySuffix(name: String): String {
    val i = name.lastIndexOf('.')
    return if (i > 0 && i < name.length - 1) name.substring(i) else ""
}

/** Python 3.13 `PurePath.stem`. */
internal fun pyStem(name: String): String {
    val suffix = pySuffix(name)
    return if (suffix.isEmpty()) name else name.substring(0, name.length - suffix.length)
}

/** Python compares strings by code point; Kotlin's own order is by UTF-16 unit. */
internal val CodePointOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return@Comparator ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    (a.length - i).compareTo(b.length - j)
}

/**
 * Mirrors the file listing in `SyncManager.run`: regular files whose suffix is
 * `.txt`, `.zip` or none, sorted by name in code-point order. A missing or
 * unreadable directory gives an empty list.
 */
fun listInboxFiles(inboxDir: File): List<File> {
    val entries = inboxDir.listFiles() ?: return emptyList()
    return entries
        .filter { it.isFile && pySuffix(it.name).let { s -> s == ".txt" || s == ".zip" || s == "" } }
        .sortedWith { a, b -> CodePointOrder.compare(a.name, b.name) }
}

/**
 * Mirrors `_superseded_exports`: every processed/ file that a fresh import of
 * [filename] replaces. That is the canonical name plus `_dup_<timestamp>`
 * variants older versions made. Matching is deliberately narrow (stem +
 * "_dup_" + same suffix) so an unrelated chat whose name merely starts the same
 * way is never touched.
 */
fun supersededExports(processedDir: File, filename: String): List<File> {
    val stem = pyStem(filename)
    val suffix = pySuffix(filename)
    val entries = processedDir.listFiles() ?: return emptyList()
    val out = mutableListOf<File>()
    for (p in entries) {
        if (!p.isFile) continue
        if (p.name == filename) {
            out.add(p)
        } else if (pySuffix(p.name) == suffix && pyStem(p.name).startsWith("${stem}_dup_")) {
            out.add(p)
        }
    }
    return out
}

/**
 * Mirrors `_move_to_processed`: move [file] into [processedDir], keeping one
 * export per chat. The newer export takes the canonical name and the copy it
 * supersedes goes, including `_dup_` leftovers. Pruning is housekeeping: a
 * failure to remove a stale copy never fails a sync that already delivered.
 *
 * The move itself may throw, as Python's does; the caller records that as a
 * failed run.
 */
fun moveToProcessed(file: File, processedDir: File) {
    val dest = File(processedDir, file.name)
    val superseded = supersededExports(processedDir, file.name).filter { it != file }
    if (dest.exists()) dest.delete()
    Files.move(file.toPath(), dest.toPath())
    for (stale in superseded) {
        if (stale == dest) continue
        try {
            Files.delete(stale.toPath())
        } catch (_: IOException) {
            // housekeeping only
        }
    }
}
