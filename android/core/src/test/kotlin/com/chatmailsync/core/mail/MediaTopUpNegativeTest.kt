package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * (WhatsApp wrote "<Media omitted>", the parser drops that line) that is older than
 * the last filed message is skipped too. Only messages later than the last filed one
 * bring attachments. (Since BUG-23 a message stamped in the very same minute as the last
 * filed one is judged by its hash, not skipped outright, so no test here pins that minute.)
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

    /**
     * Backs the FAQ claim: a later Include-media export only brings attachments for newer
     * messages. Uses only messages strictly older than the mark (10:00, 10:05) plus newer
     * ones (10:20, 10:25), so it holds for both `<=` and BUG-23's `<`. The 10:10 messages
     * at the mark are left out on purpose: Bug23SameMinuteTest pins that boundary.
     */
    @Test
    fun laterIncludeMediaExportOnlyBringsAttachmentsForNewerMessages() {
        val repo = SyncTestSupport.newRepo(tmp.newFolder())
        val lastSynced = firstSync(repo)
        assertTrue(lastSynced.startsWith("2025-03-14T10:10:00"))

        val all = includeMedia()
        val second = parse(all[0], all[1], all[4], all[5])
        assertEquals(4, second.size)
        for (exactMarkIsSkipped in listOf(true, false)) {
            val result = filterMessages(repo, second, chatId, lastSynced, null, exactMarkIsSkipped)
            val sent = result.newMessages

            // POSITIVE: the newer messages are sent and keep their attachment.
            assertEquals(2, sent.size)
            assertEquals("IMG-0003.jpg", sent[0].attachmentFilename)
            assertEquals("Thanks for the photos", sent[1].body)
            assertNull(sent[1].attachmentFilename)
            // NEGATIVE: the older text is not sent again and the older photo gets no attachment.
            assertFalse(sent.any { it.body == "Reached the station" })
            assertFalse(sent.any { it.attachmentFilename == "IMG-0001.jpg" })
            assertTrue(sent.none { it.timestampIso < lastSynced })
            // Exactly the two older messages are skipped.
            assertEquals(2, result.skipped)
        }
    }

    // The first export ends with a photo AFTER its last text. The photo line is
    // dropped at parse, so the last FILED message is the 10:10 text and the
    // trailing photo (10:15) is later than it.
    private fun withoutMediaEndingInAPhoto() = withoutMedia() + line("10:15:00", meera, "<Media omitted>")

    /**
     * Holds under both readings of the boundary: Python's `<=` (exactMarkIsSkipped = true)
     * and the BUG-23 `<`. The only message at the last-synced minute here is the text the
     * app already filed, which rule 1 (its stored hash) skips either way, so the outcome
     * does not depend on rule 2's treatment of that minute.
     */
    @Test
    fun aTrailingPhotoAfterTheLastFiledTextDoesArriveWithItsAttachment() {
        val repo = SyncTestSupport.newRepo(tmp.newFolder())
        val first = parse(*withoutMediaEndingInAPhoto())
        assertEquals(listOf("Reached the station", "Lovely view"), first.map { it.body })
        val f1 = filterMessages(repo, first, chatId, null, null)
        repo.insertMessageHashes(
            f1.newMessages.map {
                StateRepository.HashEntry(
                    StateRepository.computeMessageHash(it.chatId, it.timestampIso, it.sender, it.body),
                    chatId, it.timestampIso, 1L,
                )
            },
        )
        val lastSynced = f1.newMessages.maxOf { it.timestampIso }
        assertTrue(lastSynced.startsWith("2025-03-14T10:10:00"))

        val second = parse(
            includeMedia()[0], // 10:00 text, already filed
            includeMedia()[1], // 10:05 photo, older than the last filed message
            includeMedia()[2], // 10:10 text, already filed (rule 1 skips it by hash)
            line("10:15:00", meera, "IMG-0004.jpg (file attached)"),
        )
        for (exactMarkIsSkipped in listOf(true, false)) {
            val result = filterMessages(repo, second, chatId, lastSynced, null, exactMarkIsSkipped)
            val sent = result.newMessages
            // POSITIVE: the trailing photo is newer than the last filed message: it arrives, attached.
            assertEquals(1, sent.size)
            assertEquals("IMG-0004.jpg", sent.single().attachmentFilename)
            // NEGATIVE: the older photo does not pick up an attachment, and nothing already filed is sent again.
            assertFalse(sent.any { it.attachmentFilename == "IMG-0001.jpg" })
            assertFalse(sent.any { it.body == "Reached the station" || it.body == "Lovely view" })
            assertTrue(sent.none { it.timestampIso < lastSynced })
            assertEquals(3, result.skipped)
        }
    }
}
