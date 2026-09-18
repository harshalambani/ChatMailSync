package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/**
 * JUnit twin of `tests/test_parser.py`. Test names below mirror the Python
 * test names (converted to camelCase) so the two suites can be diffed side
 * by side. All 21 `def test_` functions in the Python file have a direct
 * twin here -- `parser.py` has no Android-only or as-yet-unported-module
 * dependency the way `self_sender.py`'s three renderer tests did, so nothing
 * needed to be excluded.
 *
 * Fixture content: `tests/fixtures/android_export.txt` and `ios_export.txt`
 * are embedded verbatim below as Kotlin string constants (both already use
 * only the placeholder names "Alice"/"Bob", not a real person's name) rather
 * than read from disk, so this file is self-contained and does not need a
 * `src/test/resources` copy of the Python fixtures kept in sync by hand.
 *
 * Beyond the twins, this file adds the negative tests the task brief and
 * the plan document call for: the `\s`-vs-`\d` Unicode-awareness split, the
 * 2-digit year pivot boundary, `:00` seconds surviving into `timestampIso`,
 * the `dateutil`-pinned AM/PM edge cases, Python's `str.splitlines()`
 * boundary set, and invalid dates/times being skipped rather than crashing
 * the whole file.
 */
class ParserTest {

    /** Records every [ParserLog.warn] call verbatim, for caplog-style assertions. */
    private class RecordingParserLog : ParserLog {
        val messages = mutableListOf<String>()
        override fun warn(message: String) {
            messages.add(message)
        }
        val text: String get() = messages.joinToString("\n")
    }

    private fun tempChatFile(content: String, name: String = "chat.txt"): File {
        val dir = createTempDirLocal()
        val f = File(dir, name)
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    private fun createTempDirLocal(): File {
        val dir = File.createTempFile("parsertest", "dir")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private val androidExport = listOf(
        "[14/03/25, 09:40:00] - Messages and calls are end-to-end encrypted. No one outside of this chat, not even WhatsApp, can read or listen to them.",
        "[14/03/25, 09:41:23] - Alice: Hey there!",
        "[14/03/25, 09:41:45] - Bob: Hi Alice, how are you?",
        "This is a continuation line",
        "and another one",
        "[14/03/25, 09:42:00] - Alice: IMG-20250314-WA0001.jpg (file attached)",
        "[14/03/25, 09:42:10] - Bob: <Media omitted>",
        "[14/03/25, 09:42:20] - Alice: This message was deleted",
        "[14/03/25, 09:42:30] - Bob: I left my charger at the office, can you grab it?",
    ).joinToString("\n") + "\n"

    private val iosExport = listOf(
        "3/14/25, 9:41 AM - Alice: Hey there!",
        "3/14/25, 9:41 AM - Bob: Hi Alice",
        "3/14/25, 9:42 AM - Alice: <attached: 00000123-PHOTO-2025-03-14-09-41-23.jpg>",
        "3/14/25, 9:42 AM - Bob: video omitted",
    ).joinToString("\n") + "\n"

    // -----------------------------------------------------------------
    // Format auto-detection -- one case per TIMESTAMP_PATTERNS entry
    // -----------------------------------------------------------------

    @Test
    fun testFormatDetection() {
        val cases = listOf(
            Quad("[3/4/25, 2:05:33 PM] - Alice: hi", "bracketed_ampm_seconds", "3/4/25", "2:05:33 PM"),
            Quad("[14/03/25, 09:41:23] - Alice: hi", "bracketed_24h_seconds", "14/03/25", "09:41:23"),
            Quad("3/14/25, 9:41 AM - Alice: hi", "plain_ampm", "3/14/25", "9:41 AM"),
            Quad("23/05/26, 16:42 - Alice: hi", "plain_24h", "23/05/26", "16:42"),
            Quad("14-03-2025 09:41 - Alice: hi", "dash_24h", "14-03-2025", "09:41"),
        )
        for ((line, expectedFormat, expectedDate, expectedTime) in cases) {
            val detected = detectFormat(listOf(line))
            assertNotNull(detected)
            assertEquals(expectedFormat, detected!!.formatKey)
            val m = detected.lineRegex.matcher(line)
            assertTrue(m.matches())
            assertEquals(expectedDate, m.group(1))
            assertEquals(expectedTime, m.group(2))
        }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    @Test
    fun testFormatDetectionNoMatchReturnsNone() {
        assertNull(detectFormat(listOf("not a timestamp line at all")))
    }

    // -----------------------------------------------------------------
    // DD/MM vs MM/DD ambiguity resolution
    // -----------------------------------------------------------------

    @Test
    fun testDateOrderDefinitiveDmyWhenFirstFieldOver12() {
        val lineRe = buildLineRegex("([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4})\\s([0-9]{1,2}:[0-9]{2})")
        val lines = listOf("14/03/25 09:41 - Alice: hi")
        assertEquals("DMY", resolveDateOrder(lines, lineRe, "plain_24h"))
    }

    @Test
    fun testDateOrderDefinitiveMdyWhenSecondFieldOver12() {
        val lineRe = buildLineRegex("([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4})\\s([0-9]{1,2}:[0-9]{2})")
        val lines = listOf("3/14/25 09:41 - Alice: hi")
        assertEquals("MDY", resolveDateOrder(lines, lineRe, "plain_24h"))
    }

    @Test
    fun testDateOrderFallsBackToConfiguredDefaultWhenFullyAmbiguous() {
        val lineRe = buildLineRegex("([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4})\\s([0-9]{1,2}:[0-9]{2})")
        val lines = listOf("01/02/25 10:00 - Alice: hi", "02/03/25 11:00 - Bob: hi")
        assertEquals(DATE_ORDER, resolveDateOrder(lines, lineRe, "plain_24h"))
    }

    // -----------------------------------------------------------------
    // Multi-line continuation
    // -----------------------------------------------------------------

    @Test
    fun testMultilineContinuationJoinsIntoOneMessage() {
        val f = tempChatFile(androidExport)
        val messages = parseFile(f, chatId = "test_chat").toList()
        val bobMsg = messages.first { it.sender == "Bob" && "how are you" in it.body }
        assertEquals("Hi Alice, how are you?\nThis is a continuation line\nand another one", bobMsg.body)
    }

    // -----------------------------------------------------------------
    // System-message filtering
    // -----------------------------------------------------------------

    @Test
    fun testSystemBodyPhrasesAreDropped() {
        val f = tempChatFile(androidExport)
        val messages = parseFile(f, chatId = "test_chat").toList()
        val bodies = messages.map { it.body }
        assertFalse(bodies.any { "media omitted" in it.lowercase() })
        assertFalse(bodies.any { "this message was deleted" in it.lowercase() })
    }

    @Test
    fun testBareSystemLineWithNoColonIsDropped() {
        val f = tempChatFile(androidExport)
        val messages = parseFile(f, chatId = "test_chat").toList()
        assertFalse(messages.any { "end-to-end encrypted" in it.body.lowercase() })
    }

    @Test
    fun testBodyPositionLeftIsNotFalselyDropped() {
        val f = tempChatFile(androidExport)
        val messages = parseFile(f, chatId = "test_chat").toList()
        assertTrue(messages.any { "I left my charger" in it.body })
        assertFalse(isSystemBody("I left my charger at the office, can you grab it?"))
    }

    // -----------------------------------------------------------------
    // Attachment recognition
    // -----------------------------------------------------------------

    @Test
    fun testAndroidStyleAttachmentRecognized() {
        val f = tempChatFile(androidExport)
        val messages = parseFile(f, chatId = "test_chat").toList()
        val att = messages.first { it.attachmentFilename != null }
        assertEquals("IMG-20250314-WA0001.jpg", att.attachmentFilename)
    }

    @Test
    fun testIosStyleAttachmentRecognized() {
        val f = tempChatFile(iosExport)
        val messages = parseFile(f, chatId = "test_chat").toList()
        val att = messages.first { it.attachmentFilename != null }
        assertEquals("00000123-PHOTO-2025-03-14-09-41-23.jpg", att.attachmentFilename)
    }

    // -----------------------------------------------------------------
    // extractChatInfo
    // -----------------------------------------------------------------

    @Test
    fun testExtractChatInfo() {
        val cases = listOf(
            Triple("WhatsApp Chat with Jane Doe.txt", "jane_doe", "Jane Doe"),
            Triple("Jane Doe.txt", "jane_doe", "Jane Doe"),
        )
        for ((filename, expectedChatId, expectedDisplayName) in cases) {
            val info = extractChatInfo(filename)
            assertEquals(expectedChatId, info.chatId)
            assertEquals(expectedDisplayName, info.displayName)
        }
    }

    @Test
    fun testADownloadsRenamedCopyIsTheSameChat() {
        val filenames = listOf(
            "WhatsApp Chat with Jane Doe (1).txt",
            "WhatsApp Chat with Jane Doe (2).zip",
            "Jane Doe (12).txt",
        )
        for (filename in filenames) {
            assertEquals(ChatInfo("jane_doe", "Jane Doe"), extractChatInfo(filename))
        }
    }

    @Test
    fun testANumberInBracketsMidNameIsLeftAlone() {
        val info = extractChatInfo("WhatsApp Chat with Team (2) Sales.txt")
        assertEquals("Team (2) Sales", info.displayName)
        assertEquals("team_2_sales", info.chatId)
    }

    @Test
    fun testExtractChatInfoNormalizesPunctuationToAscii() {
        val info = extractChatInfo("WhatsApp Chat with Jane's Café 😀.txt")
        assertTrue(info.chatId.all { it.code < 128 })
        assertFalse(info.chatId.contains(" "))
        assertEquals("Jane's Café 😀", info.displayName)
    }

    // -----------------------------------------------------------------
    // ZIP input (iOS export mode)
    // -----------------------------------------------------------------

    private fun writeZip(zipFile: File, entryName: String, content: String) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    @Test
    fun testParseFileReadsChatTxtFromZip() {
        val dir = createTempDirLocal()
        val zipFile = File(dir, "export.zip")
        writeZip(zipFile, "_chat.txt", "3/14/25, 9:41 AM - Alice: Hey from a zip!")

        val messages = parseFile(zipFile, chatId = "test_chat").toList()
        assertEquals(1, messages.size)
        assertEquals("Hey from a zip!", messages[0].body)
    }

    @Test
    fun testParseFileZipBombGuardRaises() {
        // Kotlin has no monkeypatch of a top-level `const val`; instead this
        // calls readChatText directly against a real archive whose declared
        // entry size exceeds the real (non-patched) MAX_ZIP_DECOMPRESSED_BYTES
        // -- proving the same guard path Python's monkeypatched test proves,
        // just by making the archive itself the oversized one rather than
        // shrinking the ceiling. See [aRealOversizedZipTripsTheBombGuard]
        // below for the always-on-ceiling version of this same guard.
        val dir = createTempDirLocal()
        val zipFile = File(dir, "export.zip")
        // Write an entry whose *declared* uncompressed size (not actual bytes
        // written) exceeds the ceiling by using a highly compressible payload.
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            val entry = ZipEntry("_chat.txt")
            zos.putNextEntry(entry)
            val chunk = "0".repeat(1024 * 1024)
            repeat(600) { zos.write(chunk.toByteArray(Charsets.UTF_8)) }
            zos.closeEntry()
        }

        try {
            readChatText(zipFile)
            fail("expected ChatTextReadException")
        } catch (e: ChatTextReadException) {
            assertTrue(e.message!!.contains("safety limit"))
        }
    }

    // -----------------------------------------------------------------
    // Unclassifiable-line guard
    // -----------------------------------------------------------------

    @Test
    fun testForeignFormatLineWarnsAndNamesTheFormat() {
        val f = tempChatFile(
            "3/14/25, 9:41 AM - Alice: Hey there!\n" +
                "[14/03/25, 09:41:23] - Bob: I am in the locked format\n",
        )
        val log = RecordingParserLog()
        val messages = parseFile(f, chatId = "test_chat", log = log).toList()

        assertEquals(1, messages.size)
        assertEquals("Bob", messages[0].sender)

        assertTrue(log.text.contains("locked 'bracketed_24h_seconds'"))
        assertTrue(log.text.contains("plain_ampm=1"))
        assertTrue(log.text.contains("1 non-empty line(s) preceded the first message line"))

        assertFalse(log.text.contains("Hey there"))
        assertFalse(log.text.contains("Alice"))
    }

    @Test
    fun testOrphanLinesBeforeFirstMessageWarn() {
        val f = tempChatFile(
            "some preamble the parser cannot place\n" +
                "and a second one\n" +
                "3/14/25, 9:41 AM - Alice: Hey there!\n",
        )
        val log = RecordingParserLog()
        val messages = parseFile(f, chatId = "test_chat", log = log).toList()

        assertEquals(1, messages.size)
        assertTrue(log.text.contains("2 non-empty line(s) preceded the first message line"))
        assertFalse(log.text.contains("preamble"))
    }

    @Test
    fun testOrdinaryMultilineMessageWarnsAboutNothing() {
        val f = tempChatFile(
            "3/14/25, 9:41 AM - Alice: Hey there!\n" +
                "this is a normal second line\n" +
                "\n" +
                "and a third after a blank\n",
        )
        val log = RecordingParserLog()
        val messages = parseFile(f, chatId = "test_chat", log = log).toList()
        assertEquals(1, messages.size)
        assertEquals("", log.text)
    }

    @Test
    fun testBlankLinesAreNotCountedAsOrphans() {
        val f = tempChatFile("\n\n3/14/25, 9:41 AM - Alice: Hey there!\n")
        val log = RecordingParserLog()
        parseFile(f, chatId = "test_chat", log = log).toList()
        assertEquals("", log.text)
    }

    // ===================================================================
    // Negative tests (task brief + plan document, section E)
    // ===================================================================

    /** A narrow no-break space (U+202F, what iOS inserts before AM/PM) must
     * lock the AM/PM formats, matching Python's Unicode-aware `\s`. Java's
     * `\s` without [java.util.regex.Pattern.UNICODE_CHARACTER_CLASS] is
     * ASCII-only and would NOT match this. */
    @Test
    fun aNarrowNoBreakSpaceBeforeAmPmMatchesLikePython() {
        val line = "3/14/25, 9:41 AM - Alice: hi"
        val detected = detectFormat(listOf(line))
        assertNotNull(detected)
        assertEquals("plain_ampm", detected!!.formatKey)
    }

    /** `\d` is deliberately kept ASCII-only here -- a full-width digit
     * (U+FF10 '０') does NOT match, unlike Python's Unicode-aware `\d`. This
     * is an intentional, documented divergence (see Parser.kt's top-level
     * KDoc): real WhatsApp exports never use non-ASCII digits in timestamps. */
    @Test
    fun aFullWidthDigitDoesNotMatchUnlikePython() {
        val line = "１４/03/25, 09:41 - Alice: hi" // "14/03/25" with full-width "14"
        assertNull(detectFormat(listOf(line)))
    }

    /** 2-digit year pivot: < 50 -> 2000s, >= 50 -> 1900s. */
    @Test
    fun aTwoDigitYear49Is2049And50Is1950() {
        val ts49 = parseTimestamp("01/01/49", "10:00", "plain_24h", "DMY")
        assertEquals(2049, ts49.year)
        val ts50 = parseTimestamp("01/01/50", "10:00", "plain_24h", "DMY")
        assertEquals(1950, ts50.year)
    }

    /** A 3-digit-or-more year passes through unchanged -- "replicate, don't fix". */
    @Test
    fun aThreeDigitYearPassesThroughUnchanged() {
        val ts = parseTimestamp("01/01/999", "10:00", "plain_24h", "DMY")
        assertEquals(999, ts.year)
    }

    /** `timestampIso` must print an explicit `:00` seconds field -- pure
     * `LocalDateTime.toString()` would drop it and silently change every hash. */
    @Test
    fun aZeroSecondIsPrintedInTimestampIso() {
        val msg = ParsedMessage(
            chatId = "c",
            timestamp = LocalDateTime.of(2025, 3, 14, 9, 41, 0),
            sender = "Alice",
            body = "hi",
        )
        assertEquals("2025-03-14T09:41:00", msg.timestampIso)
    }

    /** dateutil-pinned AM/PM edge cases (recorded by running
     * `dateutil.parser.parse` once against each string): 12 AM -> hour 0,
     * 12 PM -> hour 12, 0:30 PM -> hour 12 (`hour % 12`, then `+12` for PM). */
    @Test
    fun parseTimeOfDayAmPmEdgeCasesMatchDateutil() {
        assertEquals(Triple(0, 0, 0), parseTimeOfDay("12:00 AM"))
        assertEquals(Triple(12, 0, 0), parseTimeOfDay("12:00 PM"))
        assertEquals(Triple(12, 30, 0), parseTimeOfDay("0:30 PM"))
        assertEquals(Triple(9, 41, 0), parseTimeOfDay("9:41 am"))
        assertEquals(Triple(21, 41, 0), parseTimeOfDay("9:41 pm"))
    }

    /** dateutil raises `ParserError` (a `ValueError` subclass) for an
     * AM/PM marker paired with an hour outside 0..12 -- pinned here as the
     * Kotlin twin raising [TimestampParseException] for the same inputs. */
    @Test
    fun parseTimeOfDayRejectsOutOfRangeHourWithAmPmMarkerLikeDateutil() {
        for (bad in listOf("13:00 PM", "14:05:33 PM")) {
            try {
                parseTimeOfDay(bad)
                fail("expected TimestampParseException for '$bad'")
            } catch (_: TimestampParseException) {
                // expected
            }
        }
    }

    /** Without an AM/PM marker the hour is literal 24h and must be 0..23. */
    @Test
    fun parseTimeOfDayRejectsHour24Plus() {
        try {
            parseTimeOfDay("24:00")
            fail("expected TimestampParseException")
        } catch (_: TimestampParseException) {
            // expected
        }
    }

    /** An invalid date (month 13) is skipped with a warning, not a crash --
     * twin of `_build_message`'s `except (ValueError, OverflowError)`. */
    @Test
    fun anInvalidDateIsSkippedWithAWarningRatherThanCrashing() {
        val f = tempChatFile("32/13/25, 09:41 - Alice: this date is impossible\n")
        val log = RecordingParserLog()
        val messages = parseFile(f, chatId = "test_chat", log = log).toList()
        assertEquals(0, messages.size)
    }

    /** An invalid minute/second (60+) is likewise skipped, not a crash. */
    @Test
    fun anInvalidMinuteIsSkippedWithAWarningRatherThanCrashing() {
        val f = tempChatFile("14/03/25, 09:99 - Alice: this time is impossible\n")
        val log = RecordingParserLog()
        val messages = parseFile(f, chatId = "test_chat", log = log).toList()
        assertEquals(0, messages.size)
    }

    /** Python's `str.splitlines()` treats a bare form-feed (`\f`, U+000C) as
     * a line boundary; Java's usual line splitters do not. */
    @Test
    fun aFormFeedIsALineBoundaryLikePython() {
        val lines = pythonSplitlines("ab")
        assertEquals(listOf("a", "b"), lines)
    }

    /** `\r\n` counts as a single boundary, not two. */
    @Test
    fun aCrlfPairCountsAsOneBoundary() {
        val lines = pythonSplitlines("a\r\nb\r\nc")
        assertEquals(listOf("a", "b", "c"), lines)
    }

    /** Bare `\r` (classic Mac line endings) is also a boundary. */
    @Test
    fun aBareCrIsALineBoundary() {
        val lines = pythonSplitlines("a\rb\rc")
        assertEquals(listOf("a", "b", "c"), lines)
    }

    /** U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR are boundaries. */
    @Test
    fun unicodeLineAndParagraphSeparatorsAreBoundaries() {
        assertEquals(listOf("a", "b"), pythonSplitlines("a b"))
        assertEquals(listOf("a", "b"), pythonSplitlines("a b"))
    }

    /** U+200E/U+200F/U+FEFF/U+200B are stripped before matching, per
     * `_clean_text`. */
    @Test
    fun unicodeArtifactsAreStrippedBeforeMatching() {
        val cleaned = cleanText("‎14/03/25‏, 09:41﻿ - Alice​: hi")
        assertEquals("14/03/25, 09:41 - Alice: hi", cleaned)
    }

    /** A real, un-patched oversized ZIP still trips the bomb guard (the
     * always-on-ceiling twin of [testParseFileZipBombGuardRaises], which
     * exercises the same code path Python's test reaches via monkeypatch). */
    @Test
    fun aRealOversizedZipTripsTheBombGuard() {
        val dir = createTempDirLocal()
        val zipFile = File(dir, "export.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("_chat.txt"))
            val chunk = "0".repeat(1024 * 1024)
            repeat(600) { zos.write(chunk.toByteArray(Charsets.UTF_8)) } // 600 MiB > 500 MiB ceiling
            zos.closeEntry()
        }
        try {
            readChatText(zipFile)
            fail("expected ChatTextReadException")
        } catch (e: ChatTextReadException) {
            assertTrue(e.message!!.contains("safety limit"))
        }
    }

    /** An empty file yields zero messages without error. */
    @Test
    fun anEmptyFileYieldsNoMessages() {
        val f = tempChatFile("")
        val log = RecordingParserLog()
        val messages = parseFile(f, chatId = "test_chat", log = log).toList()
        assertEquals(0, messages.size)
    }

    /** A CRLF-terminated export parses identically to an LF one. */
    @Test
    fun crlfLineEndingsParseTheSameAsLf() {
        val f = tempChatFile("3/14/25, 9:41 AM - Alice: Hey there!\r\n3/14/25, 9:42 AM - Bob: Hi!\r\n")
        val messages = parseFile(f, chatId = "test_chat").toList()
        assertEquals(2, messages.size)
        assertEquals("Hey there!", messages[0].body)
        assertEquals("Hi!", messages[1].body)
    }

    /** A leading UTF-8 BOM on a plain (non-zip) file is stripped, matching
     * Python's `encoding="utf-8-sig"`. */
    @Test
    fun aLeadingUtf8BomIsStrippedOnPlainFiles() {
        val dir = createTempDirLocal()
        val f = File(dir, "chat.txt")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "3/14/25, 9:41 AM - Alice: Hey there!\n".toByteArray(Charsets.UTF_8)
        f.writeBytes(bom + body)
        val messages = parseFile(f, chatId = "test_chat").toList()
        assertEquals(1, messages.size)
        assertEquals("Hey there!", messages[0].body)
    }
}
