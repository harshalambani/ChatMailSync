package com.chatmailsync.core.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-13 F-11 guard: the backup export must stream the database snapshot into the zip and must never
 * read a whole database file into memory. A source scan of the export block in Migration.kt.
 */
class ExportStreamsGuardTest {

    private val wholeFileReads = Regex("""\.readBytes\(|\.readText\(|\.readLines\(|readAllBytes\(|readAllLines\(|readString\(""")

    /** Problems found in the export block of [source]; empty when it streams. */
    private fun problems(source: String): List<String> {
        val start = source.indexOf("ZipOutputStream(built.outputStream()")
        val end = source.indexOf("Files.copy(built.toPath()", start)
        if (start < 0 || end < 0) {
            return listOf("Cannot find the export block (ZipOutputStream(built.outputStream() ... Files.copy(built.toPath()). " +
                "If the export was restructured, update ExportStreamsGuardTest so it still finds it.")
        }
        val block = source.substring(start, end)
        val out = mutableListOf<String>()
        if (!block.contains("streamFileTo(zip, snapshot)")) {
            out += "The export no longer calls streamFileTo(zip, snapshot). Stream the database snapshot into the zip entry."
        }
        for (line in block.lines()) {
            if (wholeFileReads.containsMatchIn(line)) {
                out += "Whole-file read in the export block: '${line.trim()}'. Do not read a database file into memory " +
                    "(F-11); use streamFileTo(zip, snapshot)."
            }
        }
        return out
    }

    @Test
    fun theRealExportStreamsTheSnapshot() {
        val source = migrationSource().readText()
        assertEquals(emptyList<String>(), problems(source))
    }

    // NEGATIVE: the two ways the old code could come back are caught.
    @Test
    fun aWholeFileReadOfTheSnapshotIsCaught() {
        val real = migrationSource().readText()
        val streamed = "streamFileTo(zip, snapshot)"
        val putBack = real.replace(streamed, "put(BUNDLE_DB_NAME, snapshot.readBytes())")
        assertTrue(problems(putBack).isNotEmpty())
        val variant = real.replace(streamed, "zip.write(java.nio.file.Files.readAllBytes(snapshot.toPath()))")
        assertTrue(problems(variant).isNotEmpty())
        assertTrue(problems(real.replace(streamed, "val b = db.readBytes()\n$streamed")).isNotEmpty())
    }

    private fun migrationSource(): File {
        val marker = "src/main/kotlin/com/chatmailsync/core/mail/Migration.kt"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (candidate in listOf(dir, File(dir, "core"), File(dir, "android/core"))) {
                val f = File(candidate, marker)
                if (f.isFile) return f
            }
            dir = dir.parentFile
            depth++
        }
        throw IllegalStateException("Could not find Migration.kt from user.dir=${System.getProperty("user.dir")}")
    }
}
