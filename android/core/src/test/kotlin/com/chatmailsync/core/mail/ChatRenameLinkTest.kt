package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BUG-05: a renamed chat is detected from stored fingerprints, the user is asked once, and after
 * a "yes" nothing already archived is mailed again. Every name and number is made up.
 */
class ChatRenameLinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val self = "Meera Iyer"
    private val number = "+91 90000 00001"
    private val saved = "Rohan Mehta"
    private val renamed = "Rohan M"

    private inner class Env {
        val root: File = tmp.newFolder()
        val inbox = File(root, "inbox").also { it.mkdirs() }
        val repo = newRepo(root)
        val transport = FakeMailTransport()

        /** One message per day; even days are mine, odd days carry [other] as the sender label. */
        fun write(contactName: String, other: String, vararg days: Int) {
            val text = days.joinToString("\n") {
                val who = if (it % 2 == 0) self else other
                "%02d/03/25, 09:00 - %s: message %d".format(it, who, it)
            } + "\n"
            File(inbox, "WhatsApp Chat with $contactName.txt").writeText(text)
        }

        fun sync(): SyncStats =
            SyncManager(repo, transport, inbox, File(root, "processed"), sleeper = { }).run()

        fun proposals(): List<LinkProposal> = scanLinkProposals(repo, inbox)

        fun confirmOnly(): LinkProposal {
            val p = proposals().single()
            applyLink(repo, p, File(inbox, p.fileName))
            return p
        }

        fun sent(): Int = transport.inserted.size
    }

    @Test
    fun aNumberThenASavedNameThenARenameLinkInAChain() {
        val e = Env()
        e.write(number, number, 1, 2, 3, 4, 5, 6)
        assertEquals(6, e.sync().messagesSynced)
        assertEquals(6, e.sent())
        val firstThread = e.transport.inserted.first().id

        // The contact is saved: a brand new export name, and the other party's label changed.
        e.write(saved, saved, 1, 2, 3, 4, 5, 6)
        val held = e.sync()
        assertEquals(1, held.filesHeld)
        assertEquals("a held export sends nothing", 6, e.sent())
        assertTrue("the export stays in the inbox", File(e.inbox, "WhatsApp Chat with $saved.txt").exists())
        val p = e.proposals().single()
        assertEquals(LinkKind.RENAME, p.kind)
        assertEquals(3, p.matched)
        assertEquals(extractChatInfo("$number.txt").chatId, p.oldChatId)

        e.confirmOnly()
        val after = e.sync()
        assertEquals(0, after.filesHeld)
        assertEquals("after the link NOTHING already archived is mailed again", 6, e.sent())
        assertEquals(1, e.repo.listChats().size)
        assertEquals(saved, e.repo.listChats().single().displayName)
        assertEquals(setOf("WhatsApp/$saved"), e.transport.folders - "WhatsApp")

        // Renamed once more, with one genuinely new message.
        e.write(renamed, renamed, 1, 2, 3, 4, 5, 6, 7)
        e.confirmOnly()
        val third = e.sync()
        assertEquals(1, third.messagesSynced)
        assertEquals("only the one new message is mailed", 7, e.sent())
        assertEquals(1, e.repo.listChats().size)
        assertEquals(renamed, e.repo.listChats().single().displayName)
        assertEquals("the thread carries on", firstThread, e.transport.inserted.last().threadId)
        assertEquals(setOf("WhatsApp/$renamed"), e.transport.folders - "WhatsApp")
    }

    @Test
    fun aGroupRenameLinks() {
        val e = Env()
        val text = (1..6).joinToString("\n") {
            val who = listOf(self, "Rohan Mehta", "Asha Rao")[it % 3]
            "%02d/03/25, 09:00 - %s: message %d".format(it, who, it)
        } + "\n"
        File(e.inbox, "WhatsApp Chat with Family Group.txt").writeText(text)
        assertEquals(6, e.sync().messagesSynced)
        File(e.inbox, "WhatsApp Chat with Family Group Mumbai.txt").writeText(text)
        assertEquals(1, e.sync().filesHeld)
        e.confirmOnly()
        e.sync()
        assertEquals(6, e.sent())
        assertEquals(1, e.repo.listChats().size)
        assertEquals("Family Group Mumbai", e.repo.listChats().single().displayName)
    }

    @Test
    fun aCapitalsOnlyChangeKeepsOneChatAndShowsTheNewName() {
        val e = Env()
        e.write(saved, saved, 1, 2, 3, 4)
        e.sync()
        val id = e.repo.listChats().single().chatId
        File(e.inbox, "WhatsApp Chat with $saved.txt").delete()
        e.write("ROHAN MEHTA", saved, 1, 2, 3, 4, 5)
        assertEquals("same id, so no question", 0, e.proposals().size)
        val s = e.sync()
        assertEquals(0, s.filesHeld)
        assertEquals(5, e.sent())
        assertEquals(1, e.repo.listChats().size)
        assertEquals(id, e.repo.listChats().single().chatId)
        assertEquals("ROHAN MEHTA", e.repo.listChats().single().displayName)
        assertEquals(
            "mail follows the new name into ONE folder",
            setOf("WhatsApp/ROHAN MEHTA"),
            e.transport.folders - "WhatsApp",
        )
    }

    @Test
    fun twoDifferentChatsSharingTimesAreNotLinked() {
        val e = Env()
        e.write(saved, saved, 1, 2, 3, 4, 5, 6)
        e.sync()
        // Same times, different people and different words.
        val other = (1..6).joinToString("\n") {
            "%02d/03/25, 09:00 - %s: other words %d".format(it, if (it % 2 == 0) "Asha Rao" else "Kiran Shah", it)
        } + "\n"
        File(e.inbox, "WhatsApp Chat with Kiran Shah.txt").writeText(other)
        assertEquals(0, e.proposals().size)
        val s = e.sync()
        assertEquals(0, s.filesHeld)
        assertEquals(12, e.sent())
        assertEquals(2, e.repo.listChats().size)
    }

    @Test
    fun oneSharedMessageIsNotEnoughToLink() {
        val e = Env()
        e.write(saved, saved, 1, 2, 3, 4)
        e.sync()
        // Only message 2 (sent by me) repeats; the rest differ.
        val text = "02/03/25, 09:00 - $self: message 2\n" +
            "03/03/25, 09:00 - Kiran Shah: something else\n" +
            "04/03/25, 09:00 - $self: yet another\n"
        File(e.inbox, "WhatsApp Chat with Kiran Shah.txt").writeText(text)
        assertEquals(0, e.proposals().size)
    }

    @Test
    fun aDeclinedLinkStaysSeparateAndIsNotAskedAgain() {
        val e = Env()
        e.write(number, number, 1, 2, 3, 4, 5, 6)
        e.sync()
        e.write(saved, saved, 1, 2, 3, 4, 5, 6)
        val p = e.proposals().single()
        declineLink(e.repo, p)
        assertEquals("not asked again", 0, e.proposals().size)
        val s = e.sync()
        assertEquals(0, s.filesHeld)
        assertEquals("kept separate: a chat of its own", 2, e.repo.listChats().size)
        assertTrue(e.repo.isLinkDeclined(p.newChatId, p.oldChatId))
        assertFalse(e.repo.isAlias(p.newChatId))
    }

    @Test
    fun aRefusedFolderRenameKeepsTheOldFolderAndLosesNoMail() {
        val e = Env()
        e.write(number, number, 1, 2, 3, 4, 5, 6)
        e.sync()
        val oldFolder = e.transport.folders.single { it.startsWith("WhatsApp/") }
        e.transport.refuseRename = true
        e.write(saved, saved, 1, 2, 3, 4, 5, 6, 7)
        e.confirmOnly()
        val s = e.sync()
        assertEquals(1, s.messagesSynced)
        assertEquals(7, e.sent())
        assertEquals("the new mail went to the old folder", oldFolder, e.transport.inserted.last().folder)
        assertEquals(setOf(oldFolder), e.transport.folders - "WhatsApp")
        assertFalse("no second folder was created", e.transport.createCalls.any { it == "WhatsApp/$saved" })
        assertEquals("the app shows the new name", saved, e.repo.listChats().single().displayName)
    }

    @Test
    fun anUnrelatedChatsFolderIsNeverRenamed() {
        val e = Env()
        e.write(saved, saved, 1, 2, 3, 4)
        File(e.inbox, "WhatsApp Chat with Kiran Shah.txt").writeText(
            "01/03/25, 09:00 - Kiran Shah: hello\n02/03/25, 09:00 - Asha Rao: hi\n",
        )
        e.sync()
        assertEquals("two chats, two folders, no rename", 0, e.transport.renameCalls.size)
        assertEquals(2, (e.transport.folders - "WhatsApp").size)
    }

    @Test
    fun anOldNamedExportAfterTheLinkDoesNotRenameTheChatBack() {
        val e = Env()
        e.write(number, number, 1, 2, 3, 4, 5, 6)
        e.sync()
        e.write(saved, saved, 1, 2, 3, 4, 5, 6)
        e.confirmOnly()
        e.sync()
        // A stale export under the old number arrives again, with nothing new.
        e.write(number, number, 1, 2, 3, 4, 5, 6)
        e.sync()
        assertEquals(6, e.sent())
        assertEquals(saved, e.repo.listChats().single().displayName)
        assertEquals(0, e.transport.renameCalls.count { it.second == "WhatsApp/$number" })
    }

    @Test
    fun deletingTheChatDropsItsLinks() {
        val e = Env()
        e.write(number, number, 1, 2, 3, 4)
        e.sync()
        e.write(saved, saved, 1, 2, 3, 4)
        val p = e.confirmOnly()
        assertTrue(e.repo.isAlias(p.newChatId))
        e.repo.deleteChat(p.oldChatId)
        assertFalse(e.repo.isAlias(p.newChatId))
        assertNull(e.repo.getChat(p.oldChatId))
    }

    @Test
    fun aVersion291DatabaseGainsTheLinkTablesAndKeepsItsHistory() {
        val dir = tmp.newFolder()
        val path = File(dir, "old.db").absolutePath
        // A 2.9.1-shaped file: schema version 2, without the two new tables.
        SqliteJdbcStateDb(path).use { db ->
            db.execScript(StateRepository.DDL.substringBefore("-- BUG-05 (schema 3"))
            db.exec(
                "INSERT INTO chats VALUES ('rohan_mehta','Rohan Mehta',NULL,NULL,NULL,'x.txt'," +
                    "'2025-03-01T00:00:00','2025-03-01T00:00:00')",
            )
            db.setUserVersion(2)
        }
        val repo = StateRepository(clock = { SyncTestSupport.FROZEN_NOW }) { SqliteJdbcStateDb(path) }
        repo.initDb()
        assertEquals("rohan_mehta", repo.getChat("rohan_mehta")?.chatId)
        repo.addChatLink("rohan_m", "rohan_mehta")
        assertEquals("rohan_mehta", repo.resolveChatId("rohan_m"))
        assertEquals(3, StateRepository.SCHEMA_VERSION)
    }

    @Test
    fun linkingNeverFormsAChainOrACycle() {
        val e = Env()
        e.repo.addChatLink("b", "a")
        e.repo.addChatLink("c", "b")
        assertEquals("a", e.repo.resolveChatId("c"))
        e.repo.addChatLink("a", "c") // would close a loop
        assertEquals("a", e.repo.resolveChatId("c"))
        assertEquals("a", e.repo.resolveChatId("a"))
    }
}
