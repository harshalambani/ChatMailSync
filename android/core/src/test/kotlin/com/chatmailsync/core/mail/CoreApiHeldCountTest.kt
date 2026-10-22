package com.chatmailsync.core.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** UX-02: CoreApi tells the app how many exports the last sync held, without touching the result map. */
class CoreApiHeldCountTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val self = "Meera Iyer"
    private val number = "+91 90000 00001"
    private val saved = "Rohan Mehta"

    private fun write(inbox: File, contactName: String, other: String) {
        val text = (1..6).joinToString("\n") {
            val who = if (it % 2 == 0) self else other
            "%02d/03/25, 09:00 - %s: message %d".format(it, who, it)
        } + "\n"
        File(inbox, "WhatsApp Chat with $contactName.txt").writeText(text)
    }

    @Test
    fun heldCountIsSetByAHoldAndNeverLeaksIntoTheResultMap() {
        val a = CoreApi(tmp.newFolder("r"), { SqliteJdbcStateDb(it.absolutePath) }, { null })
        a.paths.inboxDir.mkdirs()
        assertEquals(0, a.lastSyncFilesHeld)

        write(a.paths.inboxDir, number, number)
        val first = a.sync(transport = FakeMailTransport())
        assertEquals("a first export is not held", 0, a.lastSyncFilesHeld)
        assertFalse(first.containsKey("files_held"))

        write(a.paths.inboxDir, saved, saved)
        val second = a.sync(transport = FakeMailTransport())
        assertEquals(1, a.lastSyncFilesHeld)
        assertFalse("the result map keeps Python's keys", second.containsKey("files_held"))
    }

    @Test
    fun aLaterSyncThatHoldsNothingResetsTheCount() {
        val a = CoreApi(tmp.newFolder("r2"), { SqliteJdbcStateDb(it.absolutePath) }, { null })
        a.paths.inboxDir.mkdirs()
        write(a.paths.inboxDir, number, number)
        a.sync(transport = FakeMailTransport())
        write(a.paths.inboxDir, saved, saved)
        a.sync(transport = FakeMailTransport())
        assertEquals(1, a.lastSyncFilesHeld)
        File(a.paths.inboxDir, "WhatsApp Chat with $saved.txt").delete()
        a.sync(transport = FakeMailTransport())
        assertEquals(0, a.lastSyncFilesHeld)
    }

    @Test
    fun aHeldFileWithNoAnswerSendsNothingAndCountsOne() {
        val a = CoreApi(tmp.newFolder("r3"), { SqliteJdbcStateDb(it.absolutePath) }, { null })
        a.paths.inboxDir.mkdirs()
        write(a.paths.inboxDir, number, number)
        a.sync(transport = FakeMailTransport())
        write(a.paths.inboxDir, saved, saved)
        val t = FakeMailTransport()
        a.sync(transport = t)
        assertEquals(1, a.lastSyncFilesHeld)
        assertEquals("a held export sends nothing until it is answered", 0, t.inserted.size)
        val again = FakeMailTransport()
        a.sync(transport = again)
        assertEquals(1, a.lastSyncFilesHeld)
        assertEquals(0, again.inserted.size)
        assertEquals("the question stays open", 1, a.linkProposals().size)
    }
}
