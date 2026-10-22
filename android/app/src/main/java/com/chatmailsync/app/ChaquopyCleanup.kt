package com.chatmailsync.app

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * CUT-05: one-time removal of the dead `chaquopy` folder that release 2.2.0 unpacked into the
 * app's private files directory (the embedded Python runtime's own copy of itself). Nothing reads
 * it any more.
 *
 * It removes exactly `<filesDir>/chaquopy` and nothing else: no wildcard, no prefix match, so a
 * sibling such as `chaquopy-old`, `chaquopy.txt` or the `chatmailsync` data folder is never
 * touched. Links are never followed: a symbolic link inside the folder is unlinked and its target
 * is left alone.
 *
 * Failure is quiet and retried: if anything could not be removed the flag stays unset (so the
 * next start tries again) and only a count is logged, never a path.
 */
internal object ChaquopyCleanup {

    /** The one folder name this ever removes. */
    const val DIR_NAME = "chaquopy"

    /** What a run did: [done] is true when the folder is gone (or never existed). */
    data class Outcome(val done: Boolean, val failures: Int, val skipped: Boolean)

    /**
     * Runs the cleanup unless [isDone] says it already ran. Calls [markDone] only when the folder
     * is gone afterwards. Never throws.
     */
    fun run(
        filesDir: File,
        isDone: () -> Boolean,
        markDone: () -> Unit,
        log: (String) -> Unit = {},
    ): Outcome {
        if (isDone()) return Outcome(done = true, failures = 0, skipped = true)
        var failures = 0
        try {
            val target: Path = File(filesDir, DIR_NAME).toPath()
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                failures += deleteTree(target)
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && failures == 0) {
                failures = 1
            }
        } catch (e: Exception) {
            failures += 1
        }
        if (failures == 0) {
            markDone()
            return Outcome(done = true, failures = 0, skipped = false)
        }
        log("chaquopy cleanup incomplete: $failures item(s) could not be removed, will retry")
        return Outcome(done = false, failures = failures, skipped = false)
    }

    /**
     * Deletes [root] and everything under it without following links. Returns the number of
     * entries that could not be removed. A walk without FOLLOW_LINKS reports a link as a file, and
     * deleting it removes the link itself.
     */
    private fun deleteTree(root: Path): Int {
        var failures = 0
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        Files.delete(file)
                    } catch (e: IOException) {
                        failures += 1
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failures += 1
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) failures += 1
                    try {
                        Files.delete(dir)
                    } catch (e: IOException) {
                        failures += 1
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return failures
    }
}
