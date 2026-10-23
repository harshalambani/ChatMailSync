package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** BUG-18: the "same chat?" question can no longer be lost to a stray tap, and a held export says so. */
class LinkQuestionsTest {

    private fun q(new: String, old: String, file: String) = mapOf<String, Any?>(
        "new_chat_id" to new,
        "old_chat_id" to old,
        "file_name" to file,
    )

    private val a = q("n1", "o1", "WhatsApp Chat with Meera Iyer.txt")
    private val b = q("n2", "o2", "WhatsApp Chat with Rohan Mehta.txt")

    @Test
    fun anOutsideTapDoesNotHideTheQuestion() {
        val src = File("src/main/java/com/chatmailsync/app/MainActivity.kt").readText()
        val dialog = src.substringAfter("val isLegacy").substringBefore("Is this the same chat?")
        assertTrue(dialog.contains("dismissOnClickOutside = false"))
        // Back is the only dismiss path and it is a snooze of that pair, not a decline or a confirm.
        assertTrue(dialog.contains("onDismissRequest = { linkSnoozed = snoozePair(linkSnoozed, qKey) }"))
        // With nothing snoozed (a tap outside changes nothing) the question is still there.
        assertEquals(a, pickOpenQuestion(listOf(a, b), emptySet()))
    }

    @Test
    fun askMeLaterHidesOnlyThatPair() {
        val snoozed = snoozePair(emptySet(), linkQuestionKey(a))
        assertEquals(b, pickOpenQuestion(listOf(a, b), snoozed))
        assertNull(pickOpenQuestion(listOf(a), snoozed))
        assertNotEquals(null, pickOpenQuestion(listOf(a, b), snoozed))
    }

    private fun assertNotEquals(x: Any?, y: Any?) = assertFalse(x == y)

    @Test
    fun reopeningFromHomeClearsTheSnoozeOfThatFileOnly() {
        val snoozed = setOf(linkQuestionKey(a), linkQuestionKey(b))
        val after = reopenFor(snoozed, listOf(a, b), "WhatsApp Chat with Meera Iyer.txt")
        assertEquals(setOf(linkQuestionKey(b)), after)
        assertEquals(a, pickOpenQuestion(listOf(a, b), after))
        // A file with no question changes nothing.
        assertEquals(snoozed, reopenFor(snoozed, listOf(a, b), "WhatsApp Chat with Nobody.txt"))
    }

    @Test
    fun aHeldExportWithAnOpenQuestionIsAlwaysShownAsWaiting() {
        // Still waiting while snoozed: the snooze hides the dialog, never the Home row.
        assertEquals(
            setOf("WhatsApp Chat with Meera Iyer.txt", "WhatsApp Chat with Rohan Mehta.txt"),
            waitingFiles(listOf(a, b)),
        )
        assertTrue("WhatsApp Chat with Meera Iyer.txt" in waitingFiles(listOf(a)))
    }

    @Test
    fun anAnsweredDeclinedOrNeverHeldExportIsNeverShownAsWaiting() {
        // An answered or declined pair is no longer in linkProposals(), a never-held file never was.
        assertTrue(waitingFiles(emptyList()).isEmpty())
        assertFalse("WhatsApp Chat with Rohan Mehta.txt" in waitingFiles(listOf(a)))
        assertFalse("WhatsApp Chat with Other.txt" in waitingFiles(listOf(a, b)))
        val home = File("src/main/java/com/chatmailsync/app/HomeScreen.kt").readText()
        assertTrue(home.contains("if (name in waitingNames)"))
    }

    @Test
    fun aManualSyncThatHeldFilesSaysSoInTheBackgroundNoticeWords() {
        val line = manualHeldLine(2, "manual", false)
        assertNotNull(line)
        assertTrue(line!!.contains(HELD_NOTICE_TITLE))
        assertTrue(line.contains(HELD_NOTICE_TEXT))
        assertEquals(HELD_NOTICE_TITLE, heldNotice(2, "watched_folder", false)!!.title)
        assertEquals(HELD_NOTICE_TEXT, heldNotice(2, "watched_folder", false)!!.text)
        for (name in listOf("Meera Iyer", "Rohan Mehta", ".txt")) assertFalse(line.contains(name))
        assertTrue(withHeldLine(line).startsWith("\n\n"))
    }

    @Test
    fun aSyncThatHeldNothingOrAnyOtherKindSaysNothingAboutIt() {
        assertNull(manualHeldLine(0, "manual", false))
        assertNull(manualHeldLine(2, "manual", true))
        assertNull(manualHeldLine(2, "watched_folder", false))
        assertEquals("", withHeldLine(null))
        // The background notice stays off for a manual sync.
        assertNull(heldNotice(2, "manual", false))
    }

    @Test
    fun everySyncEndRechecksForQuestions() {
        val src = File("src/main/java/com/chatmailsync/app/MainActivity.kt").readText()
        assertEquals(2, Regex("""refreshInbox\(\)\s*\n\s*linkTick\+\+""").findAll(src.replace("\r\n", "\n")).count())
    }
}
