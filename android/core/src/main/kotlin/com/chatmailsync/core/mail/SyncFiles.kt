package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * File-system half of `src/sync_manager.py`: which inbox files a run looks at,
 * and the delete-after-sync cleanup. No database, no mailbox.
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
 * PRIV-03: a completed sync no longer keeps a copy of the export. Deletes [file] (the text
 * or zip, with any media inside it) once its mail is delivered. It refuses, returning false,
 * for anything that is not a regular, non-link file directly inside [inboxDir], so a
 * `source_filename` such as `../x` can never reach a file outside the inbox. [deleter] is
 * injectable for tests; it may throw, and the caller treats that as "left in inbox/".
 */
internal fun deleteCompletedExport(
    file: File,
    inboxDir: File,
    deleter: (File) -> Unit = { Files.delete(it.toPath()) },
): Boolean {
    val path = file.toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
    val parent = file.absoluteFile.parentFile ?: return false
    if (parent.canonicalFile != inboxDir.canonicalFile) return false
    deleter(file)
    return true
}

/**
 * PRIV-03: clears the copies older versions kept in processed/. Removes only entries
 * directly inside [processedDir]: regular files, and symbolic links as links (the target is
 * never touched). Sub-directories and anything else are left alone and not entered. Does
 * nothing when processed/ is itself a link or is the same folder as [inboxDir], and never
 * touches inbox/. One entry failing does not stop the rest. Returns how many were removed.
 */
internal fun sweepKeptCopies(processedDir: File, inboxDir: File): Int {
    try {
        val root = processedDir.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
        if (processedDir.canonicalFile == inboxDir.canonicalFile) return 0
    } catch (_: IOException) {
        return 0
    }
    val entries = processedDir.listFiles() ?: return 0
    var removed = 0
    for (entry in entries) {
        try {
            val p = entry.toPath()
            val isLink = Files.isSymbolicLink(p)
            if (!isLink && !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue
            if (!isLink && entry.canonicalFile.parentFile != processedDir.canonicalFile) continue
            Files.delete(p)
            removed += 1
        } catch (_: IOException) {
            // housekeeping only
        } catch (_: SecurityException) {
        }
    }
    return removed
}
