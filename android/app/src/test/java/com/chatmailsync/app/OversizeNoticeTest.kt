package com.chatmailsync.app

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A watched-folder file over the cap raises one notice per set of skipped files, with a count and
 * no names, and is otherwise left exactly as before. Synthetic data, placeholder names only.
 */
class OversizeNoticeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cap = MAX_SHARED_EXPORT_BYTES

    /** The worker's tail in miniature: the stored key lives here between runs. */
    private class Prefs { var key = "" }

    private class Watched(val docId: String, val length: Long)

    private var posted = mutableListOf<String>()
    private var opened = 0
    private var queued = mutableListOf<String>()

    /** One periodic run over [files]: the real skip decision, then the real notice decision. */
    private fun run(prefs: Prefs, files: List<Watched>) {
        val identities = mutableListOf<String>()
        for (f in files) {
            if (shouldSkipOversizeWatchedFile(f.length)) {
                identities.add(oversizeIdentity(f.docId, f.length))
                continue
            }
            opened++
            queued.add(f.docId)
        }
        val notice = oversizeNoticeFor(identities, prefs.key)
        if (notice.key != prefs.key) prefs.key = notice.key
        notice.text?.let { posted.add(it) }
    }

    private val big = Watched("content://tree/WhatsApp Chat with Meera Iyer.zip", cap + 5)
    private val big2 = Watched("content://tree/WhatsApp Chat with Rohan Mehta.zip", cap + 9)
    private val small = Watched("content://tree/small.zip", 1000L)

    @Test
    fun aNewSkipRaisesOneNoticeWithACount() {
        val p = Prefs()
        run(p, listOf(big, small))
        assertEquals(listOf("1 export is over the 600 MB limit and was skipped."), posted)
        assertNotEquals("", p.key)
    }

    @Test
    fun aLaterRunWithTheSameSkippedSetDoesNotRepeat() {
        val p = Prefs()
        run(p, listOf(big))
        run(p, listOf(big))
        run(p, listOf(big, small))
        assertEquals(1, posted.size)
    }

    @Test
    fun aChangedSetRaisesANewNotice() {
        val p = Prefs()
        run(p, listOf(big))
        run(p, listOf(big, big2))
        assertEquals(2, posted.size)
        assertEquals("2 exports are over the 600 MB limit and were skipped.", posted[1])
        // A smaller set (one file removed) is a change too.
        run(p, listOf(big2))
        assertEquals(3, posted.size)
        // The same name re-exported at another size is a different file.
        run(p, listOf(Watched(big2.docId, cap + 77)))
        assertEquals(4, posted.size)
    }

    @Test
    fun noSkipMeansNoNoticeAndTheStoredKeyIsCleared() {
        val p = Prefs()
        run(p, listOf(small))
        assertEquals(emptyList<String>(), posted)
        run(p, listOf(big))
        run(p, emptyList())
        assertEquals("", p.key)
        // Once it has gone and come back, the user is told again.
        run(p, listOf(big))
        assertEquals(2, posted.size)
    }

    @Test
    fun noFileOrChatNameAppearsInTheNoticeOrTheKey() {
        val p = Prefs()
        run(p, listOf(big, big2))
        val all = posted.joinToString(" ") + " " + p.key
        for (name in listOf("Meera", "Iyer", "Rohan", "Mehta", "WhatsApp", ".zip", "content://")) {
            assertFalse(name, all.contains(name))
        }
        assertTrue(p.key.matches(Regex("[0-9a-f]{64}")))
        assertTrue(posted.all { t -> t.all { it.code in 32..126 } })
    }

    @Test
    fun theKeyDoesNotDependOnListingOrder() {
        assertEquals(oversizeSetKey(listOf("a", "b")), oversizeSetKey(listOf("b", "a")))
        assertNotEquals(oversizeSetKey(listOf("a")), oversizeSetKey(listOf("a", "b")))
        assertEquals("", oversizeSetKey(emptyList()))
    }

    @Test
    fun theSkipItselfIsUnchangedNeverOpenedNeverQueuedOriginalUntouched() {
        val data = tmp.newFolder("data")
        val inbox = File(data, "inbox").also { it.mkdirs() }
        val incoming = incomingDirFor(inbox)
        val original = tmp.newFile("original.zip").also { it.writeBytes(ByteArray(10) { 1 }) }
        var openCount = 0
        val p = Prefs()
        // A skipped file never reaches the copy; this mirrors the worker's order.
        val skipped = shouldSkipOversizeWatchedFile(cap + 1)
        if (!skipped) {
            importShared("content", "x.zip", { openCount++; ByteArrayInputStream(ByteArray(1)) }, inbox, incoming)
        }
        run(p, listOf(big))
        assertTrue(skipped)
        assertEquals(0, openCount)
        assertEquals(0, opened)
        assertEquals(emptyList<String>(), queued)
        assertEquals(0, inbox.listFiles()?.size ?: 0)
        assertTrue("the original is still there", original.exists())
        assertEquals(10L, original.length())
        assertNotNull(oversizeNoticeFor(listOf("x"), "").text)
        assertNull(oversizeNoticeFor(listOf("x"), oversizeSetKey(listOf("x"))).text)
    }
}
