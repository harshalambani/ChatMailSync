package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.msg
import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-23: WhatsApp stamps messages to the minute, and the sync mark is the time of the last
 * message the app filed. Rule 2 of the filter used to be `<=`, so a NEW message in that same
 * minute (or a photo stamped exactly then) was thrown away unseen. It is now `<`: a message
 * exactly at the mark goes to the hash check, which skips what was filed and passes what was
 * not. Every test here fails with the old `<=` rule except the ones that guard against the
 * opposite mistake (re-sending), which fail if the hash check were dropped.
 * Synthetic messages, placeholder names only.
 */
class Bug23SameMinuteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chat = "test_chat"
    private val meera = "Meera Iyer"
    private val rohan = "Rohan Mehta"
    private val mark = "2025-03-14T10:10:00"

    private fun filed(repo: StateRepository, vararg msgs: ParsedMessage) {
        repo.upsertChat(chat, meera, "chat.txt")
        val runId = repo.startSyncRun(chat)
        repo.insertMessageHashes(buildHashEntries(msgs.toList(), runId))
    }

    private fun repoWithFiledMessageAtTheMark(): Pair<StateRepository, ParsedMessage> {
        val repo = newRepo(tmp.newFolder())
        val atMark = msg(chat, mark, rohan, "Lovely view")
        filed(repo, msg(chat, "2025-03-14T10:00:00", meera, "Reached the station"), atMark)
        return repo to atMark
    }

    @Test
    fun aMessageAlreadyFiledInTheMarkMinuteIsNotSentAgain() {
        val (repo, atMark) = repoWithFiledMessageAtTheMark()
        val again = msg(chat, mark, rohan, "Lovely view")
        val r = filterMessages(repo, listOf(atMark, again), chat, mark, null)
        // NEGATIVE: not re-sent (the hash vouches for it), counted as skipped.
        assertTrue(r.newMessages.isEmpty())
        assertEquals(2, r.skipped)
    }

    @Test
    fun aMessageStrictlyOlderThanTheMarkWithNoHashIsStillSkipped() {
        val (repo, _) = repoWithFiledMessageAtTheMark()
        // Never filed (for example a photo the first export left out), but older than the mark.
        val older = msg(chat, "2025-03-14T10:05:00", meera, "IMG-0001.jpg (file attached)", "IMG-0001.jpg")
        val r = filterMessages(repo, listOf(older), chat, mark, null)
        assertTrue(r.newMessages.isEmpty())
        assertEquals(1, r.skipped)
    }

    @Test
    fun aNewTextInTheSameMinuteAsTheLastFiledMessageIsNotLost() {
        val (repo, atMark) = repoWithFiledMessageAtTheMark()
        val fresh = msg(chat, mark, rohan, "A brand new reply sent in the same minute")
        val r = filterMessages(repo, listOf(atMark, fresh), chat, mark, null)
        assertEquals(listOf("A brand new reply sent in the same minute"), r.newMessages.map { it.body })
        assertEquals(1, r.skipped)
    }

    @Test
    fun aPhotoStampedExactlyAtTheMarkArrivesWithItsAttachment() {
        val (repo, _) = repoWithFiledMessageAtTheMark()
        val photo = msg(chat, mark, rohan, "IMG-0002.jpg (file attached)", "IMG-0002.jpg")
        val r = filterMessages(repo, listOf(photo), chat, mark, null)
        assertEquals(1, r.newMessages.size)
        assertEquals("IMG-0002.jpg", r.newMessages.single().attachmentFilename)
        assertEquals(0, r.skipped)
    }

    @Test
    fun aMessageAtTheMarkStillObeysTheCutoff() {
        // Rule 3 is unchanged: a message at the mark that is before the cutoff is withheld.
        val (repo, _) = repoWithFiledMessageAtTheMark()
        val fresh = msg(chat, mark, rohan, "Same minute, but before the cutoff day")
        val r = filterMessages(repo, listOf(fresh), chat, mark, "2025-03-15")
        assertTrue(r.newMessages.isEmpty())
        assertEquals(1, r.cutoff)
    }

    @Test
    fun theOldInclusiveRuleIsStillAvailableForAChatWithLinkedNames() {
        val (repo, _) = repoWithFiledMessageAtTheMark()
        val atMark = msg(chat, mark, rohan, "A message under a new sender label")
        val kept = filterMessages(repo, listOf(atMark), chat, mark, null, exactMarkIsSkipped = true)
        assertTrue(kept.newMessages.isEmpty())
        assertEquals(1, kept.skipped)
        val fixed = filterMessages(repo, listOf(atMark), chat, mark, null)
        assertEquals(1, fixed.newMessages.size)
    }

    // ---- the whole run loop -------------------------------------------------------------

    private class Env(val root: File) {
        val inbox = File(root, "inbox").also { it.mkdirs() }
        val repo = newRepo(root)
        val transport = FakeMailTransport()
        fun write(vararg lines: String) {
            File(inbox, "WhatsApp Chat with Meera Iyer.txt").writeText(lines.joinToString("\n") + "\n")
        }
        fun sync(): SyncStats =
            SyncManager(repo, transport, inbox, File(root, "processed"), sleeper = { }).run()
    }

    private fun line(time: String, who: String, text: String) = "14/03/25, $time - $who: $text"

    @Test
    fun aSameMinuteReplyIsMailedOnTheNextExportAndNothingIsMailedTwice() {
        val e = Env(tmp.newFolder())
        e.write(line("10:00", meera, "Reached the station"), line("10:10", rohan, "Lovely view"))
        assertEquals(2, e.repo.let { e.sync().messagesSynced })
        val firstEmails = e.transport.inserted.size

        // The second export repeats both and adds a reply stamped in the same minute as the last.
        e.write(
            line("10:00", meera, "Reached the station"),
            line("10:10", rohan, "Lovely view"),
            line("10:10", meera, "Sent in the very same minute"),
        )
        val second = e.sync()
        assertEquals("only the same-minute reply is new", 1, second.messagesSynced)
        assertEquals(2, second.messagesSkipped)
        assertEquals("one more email, for the one new message", firstEmails + 1, e.transport.inserted.size)

        // The same export again: nothing is new, nothing more is mailed.
        val emailsNow = e.transport.inserted.size
        e.write(
            line("10:00", meera, "Reached the station"),
            line("10:10", rohan, "Lovely view"),
            line("10:10", meera, "Sent in the very same minute"),
        )
        val third = e.sync()
        assertEquals(0, third.messagesSynced)
        assertEquals(emailsNow, e.transport.inserted.size)
    }

    @Test
    fun aRenamedChatDoesNotMailItsLastMessageAgainBecauseOfTheSenderLabel() {
        // Guard for the one place the hash cannot vouch for the mark: after a rename link the
        // other party's label changes, so the last archived message has a new fingerprint.
        // Day 5 is the other party's, so it is the message AT the mark.
        val self = "Meera Iyer"
        val number = "+91 90000 00001"
        val saved = "Rohan Mehta"
        val root = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        val repo = newRepo(root)
        val transport = FakeMailTransport()
        fun write(contact: String, other: String) {
            val text = (1..5).joinToString("\n") {
                val who = if (it % 2 == 0) self else other
                "%02d/03/25, 09:00 - %s: message %d".format(it, who, it)
            } + "\n"
            File(inbox, "WhatsApp Chat with $contact.txt").writeText(text)
        }
        fun sync() = SyncManager(repo, transport, inbox, File(root, "processed"), sleeper = { }).run()

        write(number, number)
        assertEquals(5, sync().messagesSynced)
        assertEquals(5, transport.inserted.size)

        write(saved, saved)
        sync()
        val p = scanLinkProposals(repo, inbox).single()
        applyLink(repo, p, File(inbox, p.fileName))
        val after = sync()
        assertEquals("nothing already archived is mailed again", 5, transport.inserted.size)
        assertEquals(0, after.messagesSynced)
        assertNotNull(repo.listChats().singleOrNull())
    }
}
