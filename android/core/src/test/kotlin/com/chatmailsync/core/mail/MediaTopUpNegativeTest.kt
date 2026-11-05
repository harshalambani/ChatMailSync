package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DOC-03: a chat first synced from a "Without media" export cannot be topped up
 * with photos by exporting again "Include media". A message the app has already
 * handled is never sent again, and a photo message missing from the first export
 * (WhatsApp wrote "<Media omitted>", the parser drops that line) is older than
 * the last sync, so it is skipped too. Only newer messages bring attachments.
 *
 * These tests go through the parser and the filter together. Synthetic exports,
 * placeholder names only.
 */
class MediaTopUpNegativeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val chatId = "test_chat"
    private val meera = "Meera Iyer"
    private val rohan = "Rohan Mehta"

    private fun line(time: String, who: String, text: String) = "[14/03/25, $time] - $who: $text"

    private fun parse(vararg lines: String): List<ParsedMessage> {
        val f = File(tmp.newFolder(), "chat.txt")
        f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return parseFile(f, chatId = chatId).toList()
    }

    // The first export: no media. Photo messages come out as "<Media omitted>".
    private fun withoutMedia() = arrayOf(
        line("10:00:00", meera, "Reached the station"),
        line("10:05:00", meera, "<Media omitted>"),
        line("10:10:00", rohan, "Lovely view"),
        line("10:10:00", rohan, "<Media omitted>"),
    )

    // The later export: same older messages, now with media, plus newer ones.
    private fun includeMedia() = arrayOf(
        line("10:00:00", meera, "Reached the station"),
        line("10:05:00", meera, "IMG-0001.jpg (file attached)"),
        line("10:10:00", rohan, "Lovely view"),
        line("10:10:00", rohan, "IMG-0002.jpg (file attached)"),
        line("10:20:00", meera, "IMG-0003.jpg (file attached)"),
        line("10:25:00", rohan, "Thanks for the photos"),
    )

    /** Sync the first export for real: filter, then record hashes, return the last synced time. */
    private fun firstSync(repo: StateRepository): String {
        val first = parse(*withoutMedia())
        // The placeholder lines are gone at parse time, not carried as messages.
        assertEquals(listOf("Reached the station", "Lovely view"), first.map { it.body })
        val f = filterMessages(repo, first, chatId, null, null)
        assertEquals(2, f.newMessages.size)
        repo.insertMessageHashes(
            f.newMessages.map {
                StateRepository.HashEntry(
                    StateRepository.computeMessageHash(it.chatId, it.timestampIso, it.sender, it.body),
                    chatId, it.timestampIso, 1L,
                )
            },
        )
        return f.newMessages.maxOf { it.timestampIso }
    }

    @Test
    fun laterIncludeMediaExportOnlyBringsAttachmentsForNewerMessages() {
        val repo = SyncTestSupport.newRepo(tmp.newFolder())
        val lastSynced = firstSync(repo)
        assertTrue(lastSynced.startsWith("2025-03-14T10:10:00"))

        val second = parse(*includeMedia())
        assertEquals(6, second.size)
        val result = filterMessages(repo, second, chatId, lastSynced, null)
        val sent = result.newMessages

        // NEGATIVE: no older message is sent a second time (text already handled).
        assertFalse(sent.any { it.body == "Reached the station" })
        assertFalse(sent.any { it.body == "Lovely view" })
        // NEGATIVE: no older photo message picks up an attachment, including the
        // one stamped exactly at the last synced time.
        assertFalse(sent.any { it.attachmentFilename == "IMG-0001.jpg" })
        assertFalse(sent.any { it.attachmentFilename == "IMG-0002.jpg" })
        assertTrue(sent.none { it.timestampIso <= lastSynced })
        assertEquals(4, result.skipped)

        // POSITIVE: the newer messages are sent and keep their attachment.
        assertEquals(2, sent.size)
        assertEquals("IMG-0003.jpg", sent[0].attachmentFilename)
        assertNotNull(sent[0].attachmentFilename)
        assertEquals("Thanks for the photos", sent[1].body)
        assertNull(sent[1].attachmentFilename)
    }

    @Test
    fun aPhotoStampedExactlyAtTheLastSyncedTimeIsStillSkipped() {
        val repo = SyncTestSupport.newRepo(tmp.newFolder())
        val lastSynced = firstSync(repo)
        val atTheMark = parse(line("10:10:00", rohan, "IMG-0002.jpg (file attached)"))
        assertEquals(lastSynced, atTheMark.single().timestampIso)
        val result = filterMessages(repo, atTheMark, chatId, lastSynced, null)
        assertTrue(result.newMessages.isEmpty())
        assertEquals(1, result.skipped)
    }
}
