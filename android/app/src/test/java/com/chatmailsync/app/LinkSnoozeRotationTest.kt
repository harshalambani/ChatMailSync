package com.chatmailsync.app

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-12: "Ask me later" on "Is this the same chat?" survives a rotation (saved instance state) and
 * does not survive a cold start (no saved state), which is by design: Help says the file stays waiting.
 */
class LinkSnoozeRotationTest {

    private fun q(new: String, old: String, file: String) = mapOf<String, Any?>(
        "new_chat_id" to new,
        "old_chat_id" to old,
        "file_name" to file,
    )

    private val a = q("n1", "o1", "WhatsApp Chat with Meera Iyer.txt")
    private val b = q("n2", "o2", "WhatsApp Chat with Rohan Mehta.txt")
    private val scope = SaverScope { true }

    /** What Android does on a rotation: the saved value is written to the bundle and read back. */
    private fun rotate(snoozed: Set<String>): Set<String> {
        val saved = with(LinkSnoozeSaver) { scope.save(snoozed) }!!
        return LinkSnoozeSaver.restore(saved)!!
    }

    @Test
    fun aRotationKeepsTheSnooze() {
        val snoozed = snoozePair(emptySet(), linkQuestionKey(a))
        val after = rotate(snoozed)
        assertEquals(snoozed, after)
        // The question stays hidden after the rotation; the other pair still shows.
        assertNull(pickOpenQuestion(listOf(a), after))
        assertEquals(b, pickOpenQuestion(listOf(a, b), after))
    }

    // Negative: a cold start has nothing saved, so the question comes back (by design).
    @Test
    fun aColdStartDoesNotKeepTheSnooze() {
        val coldStart = setOf<String>() // rememberSaveable's initial value when there is no saved state
        assertEquals(a, pickOpenQuestion(listOf(a, b), coldStart))
        // The saver writes only to the in-memory instance state: nothing on disk keeps a snooze.
        val src = File("src/main/java/com/chatmailsync/app/LinkQuestions.kt").readText()
        assertFalse(src.contains("SharedPreferences"))
        assertFalse(src.contains("getSharedPreferences"))
    }

    // Negative: the saved form carries chat ids only, never a chat or file name.
    @Test
    fun theSavedFormHoldsChatIdsOnly() {
        val snoozed = snoozePair(snoozePair(emptySet(), linkQuestionKey(a)), linkQuestionKey(b))
        val saved = with(LinkSnoozeSaver) { scope.save(snoozed) }!!
        assertEquals(setOf("n1|o1", "n2|o2"), saved.toSet())
        for (name in listOf("Meera", "Iyer", "Rohan", "Mehta", ".txt", "WhatsApp")) {
            for (s in saved) assertFalse("saved state leaks '$name'", s.contains(name))
        }
    }

    // Negative: an empty snooze round-trips as empty (nothing invented on restore).
    @Test
    fun anEmptySnoozeStaysEmpty() {
        assertTrue(rotate(emptySet()).isEmpty())
    }

    @Test
    fun theScreenKeepsTheSnoozeInSavedState() {
        val src = File("src/main/java/com/chatmailsync/app/MainActivity.kt").readText()
        assertTrue(src.contains("rememberSaveable(stateSaver = LinkSnoozeSaver)"))
        assertFalse("a plain remember forgets the snooze on rotation", src.contains("var linkSnoozed by remember {"))
    }
}
