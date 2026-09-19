package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Cross-language parity test for [MediaExtractor] against the *real* Python
 * `src.media_extractor.MediaExtractor`.
 *
 * `tools/generate_kotlin_core_golden_fixtures.py`'s
 * `generate_media_extractor_golden` builds a real ZIP archive and a real
 * plain-file-alongside-`.txt` directory on disk, runs the real Python
 * `MediaExtractor.resolve()` over a sweep of filenames, and records the
 * result as `media_extractor_golden.json`. This test rebuilds the exact same
 * two fixtures in Kotlin (same entry/sibling names, same content bytes --
 * mirrored 1:1 from that generator function) and asserts [MediaExtractor]
 * produces the same `found`/bytes/mimeType for every recorded query.
 *
 * There is no equivalent standalone Python test for this comparison (it is
 * inherently cross-language, like [MimeGoldenParityTest]/
 * [NormaliseCutoffGoldenParityTest]); this file is new, not a port of an
 * existing Python test.
 */
class MediaExtractorGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun createTempDirLocal(): File {
        val dir = File.createTempFile("mediaextractortest", "dir")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    // -----------------------------------------------------------------
    // Fixture builders -- mirror tools/generate_kotlin_core_golden_fixtures.py's
    // _build_media_extractor_zip_case / _build_media_extractor_plain_case
    // entry-for-entry, byte-for-byte.
    // -----------------------------------------------------------------

    private fun buildZipFixture(dir: File): File {
        val zipFile = File(dir, "WhatsApp Chat with Meera Iyer.zip")
        val entries = listOf(
            "IMG-20250314-WA0001.jpg" to "jpeg-bytes-android-style",
            "Photo.PNG" to "png-bytes-case-variant",
            "originals/IMG-20250314-WA0001.jpg" to "jpeg-bytes-DUPLICATE-must-not-win",
            "Café ☕ снимок.jpg" to "unicode-filename-bytes",
            "00003-PHOTO-2023-06-01-12-34-56.HEIC" to "heic-bytes-ios-style",
            "attachment_no_ext" to "no-extension-bytes",
            "voice_note.m4a" to "m4a-bytes-unmapped-extension",
            "passwd.jpg" to "ordinary-attachment-named-passwd",
            "Note.pdf" to "note-bytes-first-occurrence",
            "NOTE.PDF" to "note-bytes-must-not-win",
        )
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            // Explicit stored directory entry -- Python's Path("sub_dir/").name == "",
            // must not crash indexing (see MediaExtractor.kt's pathBasename KDoc).
            zos.putNextEntry(ZipEntry("sub_dir/"))
            zos.closeEntry()
        }
        return zipFile
    }

    private fun buildPlainFixture(dir: File): File {
        val plainDir = File(dir, "plain_export")
        plainDir.mkdirs()
        val sourcePath = File(plainDir, "_chat.txt")
        sourcePath.writeText("plain-file-mode source placeholder\n", Charsets.UTF_8)

        val siblings = listOf(
            "IMG-20250101-WA0009.jpg" to "jpeg-bytes-plain-mode",
            "Vacation Video.mp4" to "mp4-bytes-with-space-in-name",
            "Priya Nair Voice Note.opus" to "opus-bytes-space-in-name",
            "ROHAN-DOC.PDF" to "pdf-bytes-case-variant",
            "passwd" to "ordinary-sibling-file-named-passwd",
        )
        for ((name, content) in siblings) {
            File(plainDir, name).writeBytes(content.toByteArray(Charsets.UTF_8))
        }
        return sourcePath
    }

    private val zipQueries = listOf(
        "IMG-20250314-WA0001.jpg",
        "img-20250314-wa0001.jpg",
        "photo.png",
        "originals/IMG-20250314-WA0001.jpg",
        "Café ☕ снимок.jpg",
        "CAFÉ ☕ СНИМОК.jpg",
        "00003-PHOTO-2023-06-01-12-34-56.HEIC",
        "attachment_no_ext",
        "voice_note.m4a",
        "../../etc/passwd.jpg",
        "../../../etc/shadow",
        "note.pdf",
        "does_not_exist.jpg",
    )

    private val plainQueries = listOf(
        "IMG-20250101-WA0009.jpg",
        "Vacation Video.mp4",
        "Priya Nair Voice Note.opus",
        "rohan-doc.pdf",
        "../../etc/passwd",
        "../../../etc/shadow",
        "missing_on_disk.png",
    )

    private data class GoldenQuery(val filename: String, val found: Boolean, val bytesBase64: String?, val mimeType: String?)

    private fun parseQueries(node: JsonNode): Map<String, GoldenQuery> =
        node.asArr().items.map { it.asObj() }.associate { obj ->
            val filename = obj["filename"].asString()
            filename to GoldenQuery(
                filename = filename,
                found = obj["found"].asBoolean(),
                bytesBase64 = obj["bytesBase64"].asStringOrNull(),
                mimeType = obj["mimeType"].asStringOrNull(),
            )
        }

    private fun assertMatchesGolden(extractor: MediaExtractor, golden: Map<String, GoldenQuery>, queries: List<String>) {
        val mismatches = mutableListOf<String>()
        for (filename in queries) {
            val expected = golden[filename] ?: error("no golden entry recorded for query '$filename'")
            val actual = extractor.resolve(filename)
            if (expected.found) {
                if (actual == null) {
                    mismatches.add("filename=$filename: python found it, kotlin returned null")
                    continue
                }
                val (actualBytes, actualMime) = actual
                val expectedBytes = Base64.getDecoder().decode(expected.bytesBase64!!)
                if (!expectedBytes.contentEquals(actualBytes)) {
                    mismatches.add("filename=$filename: byte content differs")
                }
                if (actualMime != expected.mimeType) {
                    mismatches.add("filename=$filename: mimeType python=${expected.mimeType} kotlin=$actualMime")
                }
            } else {
                if (actual != null) {
                    mismatches.add("filename=$filename: python did NOT find it, kotlin returned $actual")
                }
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("MediaExtractor diverged from Python on ${mismatches.size}/${queries.size} queries:\n${mismatches.joinToString("\n")}")
        }
    }

    @Test
    fun zipModeMatchesPythonForEveryGoldenQuery() {
        val root = parseJson(readGoldenResource("media_extractor_golden.json")).asObj()
        val zipNode = root["zip"].asObj()
        val golden = parseQueries(zipNode["queries"])
        assertTrue("expected a non-trivial zip golden sweep", golden.size >= 10)

        val dir = createTempDirLocal()
        val zipFile = buildZipFixture(dir)
        MediaExtractor(zipFile).use { extractor ->
            assertMatchesGolden(extractor, golden, zipQueries)
        }
    }

    @Test
    fun plainFileModeMatchesPythonForEveryGoldenQuery() {
        val root = parseJson(readGoldenResource("media_extractor_golden.json")).asObj()
        val plainNode = root["plainFile"].asObj()
        val golden = parseQueries(plainNode["queries"])
        assertTrue("expected a non-trivial plain-file golden sweep", golden.size >= 5)

        val dir = createTempDirLocal()
        val sourcePath = buildPlainFixture(dir)
        MediaExtractor(sourcePath).use { extractor ->
            assertMatchesGolden(extractor, golden, plainQueries)
        }
    }

    // -----------------------------------------------------------------
    // Negative tests -- assert the wrong behaviour does NOT happen.
    // -----------------------------------------------------------------

    /**
     * A traversal-looking query whose basename coincides with a real ZIP
     * entry must resolve to THAT entry's own bytes -- proving the lookup
     * never escapes the archive's basename index, no matter how the query
     * is spelled.
     */
    @Test
    fun traversalLookingZipQueryNeverEscapesTheArchiveIndex() {
        val dir = createTempDirLocal()
        val zipFile = buildZipFixture(dir)
        MediaExtractor(zipFile).use { extractor ->
            val result = extractor.resolve("../../../../etc/passwd.jpg")
            assertTrue("a traversal-looking name with a real matching basename must still resolve", result != null)
            assertEquals("ordinary-attachment-named-passwd", String(result!!.first, Charsets.UTF_8))
        }
    }

    /** A traversal-looking query with NO matching basename must resolve to nothing -- not throw, not fall through to something else. */
    @Test
    fun traversalLookingZipQueryWithNoMatchingBasenameFindsNothing() {
        val dir = createTempDirLocal()
        val zipFile = buildZipFixture(dir)
        MediaExtractor(zipFile).use { extractor ->
            assertNull(extractor.resolve("../../../etc/shadow"))
            assertNull(extractor.resolve("../../../../../root/.ssh/id_rsa"))
        }
    }

    /** Same guarantee in plain-file mode: traversal never escapes the source directory. */
    @Test
    fun traversalLookingPlainFileQueryNeverEscapesTheSourceDirectory() {
        val dir = createTempDirLocal()
        val sourcePath = buildPlainFixture(dir)
        MediaExtractor(sourcePath).use { extractor ->
            val result = extractor.resolve("../../etc/passwd")
            assertTrue(result != null)
            assertEquals("ordinary-sibling-file-named-passwd", String(result!!.first, Charsets.UTF_8))

            // No sibling named "shadow" exists -- must not resolve to anything,
            // and in particular must never read a file from outside plain_export/.
            assertNull(extractor.resolve("../../../etc/shadow"))
        }
    }

    /** A filename that simply doesn't exist anywhere must resolve to null, in both modes -- never throw. */
    @Test
    fun unresolvableFilenameReturnsNullInBothModes() {
        val dir = createTempDirLocal()
        MediaExtractor(buildZipFixture(dir)).use { extractor ->
            assertNull(extractor.resolve("nonexistent-attachment.jpg"))
        }
        val dir2 = createTempDirLocal()
        MediaExtractor(buildPlainFixture(dir2)).use { extractor ->
            assertNull(extractor.resolve("nonexistent-attachment.jpg"))
        }
    }

    /** First-occurrence-wins: a later duplicate basename (same or different case) must never override the first. */
    @Test
    fun duplicateBasenameInZipNeverResolvesToTheLaterEntry() {
        val dir = createTempDirLocal()
        MediaExtractor(buildZipFixture(dir)).use { extractor ->
            val result = extractor.resolve("note.pdf")
            assertTrue(result != null)
            assertEquals("note-bytes-first-occurrence", String(result!!.first, Charsets.UTF_8))
            assertFalse(
                "the later NOTE.PDF entry's bytes must never win",
                String(result.first, Charsets.UTF_8) == "note-bytes-must-not-win",
            )
        }
    }

    /** An unresolvable extension (present but absent from the MIME table) must fall back to octet-stream, never null/crash. */
    @Test
    fun unmappedExtensionFallsBackToOctetStreamRatherThanFailing() {
        val dir = createTempDirLocal()
        MediaExtractor(buildZipFixture(dir)).use { extractor ->
            val result = extractor.resolve("voice_note.m4a")
            assertTrue(result != null)
            assertEquals("application/octet-stream", result!!.second)
        }
    }

    /**
     * An explicit stored ZIP directory entry must not crash construction.
     * Verified against the real Python `MediaExtractor` directly (not just
     * inferred): `Path("sub_dir/").name == "sub_dir"`, so the directory
     * entry IS indexed under basename "sub_dir" like any other entry, and
     * resolving "sub_dir/" or "sub_dir" legitimately returns `(b"", "application/octet-stream")`
     * in Python -- this is real, matched behaviour, not a bug. An empty
     * filename query, which no ZIP entry's basename can ever equal, finds
     * nothing in both languages.
     */
    @Test
    fun explicitZipDirectoryEntryDoesNotBreakIndexingAndMatchesPythonExactly() {
        val dir = createTempDirLocal()
        MediaExtractor(buildZipFixture(dir)).use { extractor ->
            assertNull(extractor.resolve(""))

            val bySlash = extractor.resolve("sub_dir/")
            assertTrue("Python resolves the directory entry itself -- verified directly", bySlash != null)
            assertEquals(0, bySlash!!.first.size)
            assertEquals("application/octet-stream", bySlash.second)

            val noSlash = extractor.resolve("sub_dir")
            assertTrue(noSlash != null)
            assertEquals(0, noSlash!!.first.size)
            assertEquals("application/octet-stream", noSlash.second)
        }
    }

    /** A ZIP whose declared uncompressed size exceeds the safety ceiling must fail construction outright, not silently fall back. */
    @Test
    fun oversizedZipTripsTheBombGuardOnConstruction() {
        val dir = createTempDirLocal()
        val zipFile = File(dir, "bomb.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("huge.bin"))
            val chunk = "0".repeat(1024 * 1024)
            repeat(600) { zos.write(chunk.toByteArray(Charsets.UTF_8)) } // 600 MiB > 500 MiB ceiling
            zos.closeEntry()
        }

        try {
            MediaExtractor(zipFile)
            fail("expected IllegalArgumentException for an oversized ZIP")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("safety limit"))
        }
    }

    /** A corrupt/non-ZIP file at a `.zip`-shaped path must fall back to plain-file mode, not throw. */
    @Test
    fun corruptZipFallsBackToPlainFileModeRatherThanThrowing() {
        val dir = createTempDirLocal()
        val notReallyAZip = File(dir, "export.zip")
        notReallyAZip.writeText("this is not a zip file", Charsets.UTF_8)
        val sibling = File(dir, "IMG-1.jpg")
        sibling.writeBytes("sibling-bytes".toByteArray(Charsets.UTF_8))

        MediaExtractor(notReallyAZip).use { extractor ->
            val result = extractor.resolve("IMG-1.jpg")
            assertTrue("a corrupt zip must fall back to plain-file mode alongside it", result != null)
            assertEquals("sibling-bytes", String(result!!.first, Charsets.UTF_8))
        }
    }
}
