package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the made-up sample chat the public site offers (docs/sample/).
 * The store reviewer imports it, so it must keep parsing to the same chat and
 * must hold nobody but the two placeholder people. Test-only: reads the files
 * from the repository checkout, no production code involved.
 */
class SampleChatTest {

    private val allowed = setOf("Meera Iyer", "Rohan Mehta")
    private val zipName = "WhatsApp Chat with Meera Iyer.zip"

    private fun sample(name: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/sample/$name")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("docs/sample/$name not found from ${File("").absolutePath}")
    }

    private fun parse(f: File, chatId: String) = parseFile(f, chatId).toList()

    @Test
    fun zipParsesToTheExpectedChat() {
        val f = sample(zipName)
        val info = extractChatInfo(f.name)
        assertEquals("Meera Iyer", info.displayName)
        val msgs = parse(f, info.chatId)
        assertEquals(17, msgs.size)
        assertEquals(allowed, msgs.map { it.sender }.toSet())
        assertEquals(4, msgs.map { it.timestampIso.take(10) }.toSet().size)
        assertTrue(msgs.all { it.timestampIso < "2026-01-01" })
    }

    @Test
    fun aWronglyNamedCopyDoesNotGiveTheSampleChatName() {
        val wrong = extractChatInfo("sample-chat.zip")
        assertNotEquals("Meera Iyer", wrong.displayName)
        assertFalse(wrong.chatId == extractChatInfo(zipName).chatId)
    }

    @Test
    fun sampleFilesNameNobodyButTheTwoPlaceholderPeople() {
        val text = readChatText(sample(zipName))
        val senders = Regex("""^\[[^\]]+] - ([^:]+): """, RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(allowed, senders)
        assertTrue(Regex("""\d{6,}""").find(text) == null)
        assertFalse(text.contains("@"))
    }
}
