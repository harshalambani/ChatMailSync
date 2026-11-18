package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * STO-10: guard for the made-up sample exports in docs/sample/ that the F-Droid
 * reviewer imports. Every zip there must parse through the real parser to the
 * expected counts, hold nobody but the placeholder people, and carry no personal
 * data (no address or number pattern, no picture metadata). Test-only: reads the
 * files from the repository checkout, no production code involved.
 */
class SampleExportsTest {

    private val people = setOf("Meera Iyer", "Rohan Mehta", "Tara Menon")
    private val groupTitles = setOf("Garden Club")

    /** zip name -> (chat name, messages, attachments, senders). One row per zip in docs/sample. */
    private data class Expect(
        val chat: String,
        val messages: Int,
        val attachments: Int,
        val senders: Set<String>,
    )

    private val expected = mapOf(
        "WhatsApp Chat with Meera Iyer.zip" to
            Expect("Meera Iyer", 17, 0, setOf("Meera Iyer", "Rohan Mehta")),
        "WhatsApp Chat with Garden Club.zip" to
            Expect("Garden Club", 14, 0, setOf("Meera Iyer", "Rohan Mehta", "Tara Menon")),
        "WhatsApp Chat with Rohan Mehta.zip" to
            Expect("Rohan Mehta", 7, 2, setOf("Meera Iyer", "Rohan Mehta")),
    )

    private fun sampleDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/sample")
            if (f.isDirectory) return f
            dir = dir.parentFile
        }
        throw AssertionError("docs/sample not found from ${File("").absolutePath}")
    }

    private fun zips(): List<File> =
        sampleDir().listFiles { f -> f.name.endsWith(".zip") }!!.sortedBy { it.name }

    private fun parse(f: File): List<ParsedMessage> =
        parseFile(f, extractChatInfo(f.name).chatId).toList()

    @Test
    fun everyZipInTheSampleFolderHasAnExpectation() {
        assertEquals(expected.keys, zips().map { it.name }.toSet())
    }

    @Test
    fun everyZipParsesToTheExpectedChatAndCounts() {
        for (f in zips()) {
            val want = expected.getValue(f.name)
            assertEquals(f.name, want.chat, extractChatInfo(f.name).displayName)
            val msgs = parse(f)
            assertEquals(f.name, want.messages, msgs.size)
            assertEquals(f.name, want.attachments, msgs.count { it.attachmentFilename != null })
            assertEquals(f.name, want.senders, msgs.map { it.sender }.toSet())
            assertTrue(f.name, msgs.size >= 2)
        }
    }

    @Test
    fun theNewSamplesAreDatedIn2026() {
        for (name in listOf("WhatsApp Chat with Garden Club.zip", "WhatsApp Chat with Rohan Mehta.zip")) {
            val msgs = parse(File(sampleDir(), name))
            assertTrue(name, msgs.all { it.timestampIso.startsWith("2026-") })
        }
    }

    @Test
    fun theGroupHasThreeMembersAndAMultiLineMessage() {
        val msgs = parse(File(sampleDir(), "WhatsApp Chat with Garden Club.zip"))
        assertEquals(3, msgs.map { it.sender }.toSet().size)
        assertTrue(msgs.any { it.body.contains("\n") })
    }

    @Test
    fun theAttachmentsOfThePictureChatResolveToSmallImages() {
        val f = File(sampleDir(), "WhatsApp Chat with Rohan Mehta.zip")
        val named = parse(f).mapNotNull { it.attachmentFilename }
        assertEquals(2, named.size)
        MediaExtractor(f).use { media ->
            for (n in named) {
                val (bytes, mime) = media.resolve(n) ?: throw AssertionError("$n not in the zip")
                assertEquals("image/png", mime)
                assertTrue(n, bytes.size in 1..16_000)
            }
        }
    }

    // --- Negative tests: nothing real may be in a sample -----------------------------

    private fun chatText(f: File): String = readChatText(f)

    @Test
    fun noNameOutsideThePlaceholderSetAppearsInAnySample() {
        val allowed = people + groupTitles
        val pair = Regex("""\b[A-Z][a-z]+ [A-Z][a-z]+\b""")
        for (f in zips()) {
            val text = chatText(f)
            val senders = Regex("""^\[[^\]]+] - ([^:\n]+): """, RegexOption.MULTILINE)
                .findAll(text).map { it.groupValues[1] }.toSet()
            assertTrue("${f.name}: $senders", people.containsAll(senders))
            // The older Meera Iyer sample is guarded by SampleChatTest; the free-text
            // capitalised-pair scan applies to the two samples added with STO-10.
            if (f.name != "WhatsApp Chat with Meera Iyer.zip") {
                val found = pair.findAll(text).map { it.value }.toSet()
                assertTrue("${f.name}: unexpected capitalised pair", allowed.containsAll(found))
            }
            // The file name's chat name must also be a placeholder.
            assertTrue(f.name, extractChatInfo(f.name).displayName in allowed)
        }
    }

    @Test
    fun noSampleTextHasAnEmailAddressOrPhoneNumberPattern() {
        for (f in zips()) {
            val text = chatText(f)
            assertFalse("${f.name}: @", text.contains("@"))
            assertFalse("${f.name}: plus number", Regex("""\+\s?\d""").containsMatchIn(text))
            // A picture file name such as IMG-20260412-WA0001 holds 8 digits; mask those,
            // then no run of 9 or more digits (a phone number) may remain.
            val masked = text.replace(Regex("""\b(IMG|VID|PTT)-\d{8}-WA\d{4}"""), "MEDIA")
            assertFalse("${f.name}: long digit run", Regex("""\d{9,}""").containsMatchIn(masked))
            assertFalse("${f.name}: phone-like", Regex("""\b\d{3}[ -]\d{3}[ -]\d{4}\b""").containsMatchIn(masked))
            assertFalse("${f.name}: web address", Regex("""(?i)https?://|www\.""").containsMatchIn(text))
        }
    }

    @Test
    fun noImageCarriesExifOrAnyMetadata() {
        var images = 0
        for (f in zips()) {
            ZipFile(f).use { zf ->
                for (e in zf.entries().toList()) {
                    if (e.name.endsWith(".txt")) continue
                    images++
                    val bytes = zf.getInputStream(e).use { it.readBytes() }
                    assertTrue(e.name, bytes.size < 16_000)
                    assertPngWithoutMetadata(e.name, bytes)
                    val asText = String(bytes, Charsets.ISO_8859_1)
                    for (marker in listOf("Exif", "GPS", "xmpmeta", "JFIF", "ICC_PROFILE")) {
                        assertFalse("${e.name}: $marker", asText.contains(marker))
                    }
                }
            }
        }
        assertEquals(2, images)
    }

    @Test
    fun theZipsHoldOnlyTheChatTextAndPictures() {
        for (f in zips()) {
            ZipFile(f).use { zf ->
                val names = zf.entries().toList().map { it.name }
                assertTrue(f.name, names.count { it.endsWith(".txt") } == 1)
                assertTrue(f.name, names.none { it.contains("/") || it.contains("\\") })
                assertTrue(f.name, names.all { it.endsWith(".txt") || it.endsWith(".png") })
            }
        }
    }

    /** Only the four critical chunks may be present: no tEXt, eXIf, tIME, pHYs, gAMA, iCCP. */
    private fun assertPngWithoutMetadata(name: String, bytes: ByteArray) {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("$name: not a PNG", bytes.copyOfRange(0, 8).contentEquals(sig))
        val buf = ByteBuffer.wrap(bytes)
        buf.position(8)
        val kinds = mutableListOf<String>()
        while (buf.remaining() >= 12) {
            val len = buf.int
            val kind = ByteArray(4).also { buf.get(it) }
            kinds += String(kind, Charsets.US_ASCII)
            buf.position(buf.position() + len + 4)
        }
        assertEquals(name, listOf("IHDR", "PLTE", "IDAT", "IEND"), kinds)
        assertNotNull(kinds)
    }
}
