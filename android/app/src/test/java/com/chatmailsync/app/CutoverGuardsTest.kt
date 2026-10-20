package com.chatmailsync.app

import com.chatmailsync.core.mail.ChunkSize
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CUT-02: Python must never start at run time, and the worker never leaks the password. */
class CutoverGuardsTest {
    private fun mainSources(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        val root = File(dir ?: error("app/src/main not found"), "src/main/java")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    // NEGATIVE: nothing in :app may start or look up Python at run time.
    @Test
    fun noAppSourceStartsOrGetsPython() {
        val files = mainSources()
        assertTrue(files.size > 20)
        val bad = files.filter {
            val t = it.readText()
            "Python.start" in t || "Python.getInstance" in t || "com.chaquo.python" in t
        }
        assertTrue("Python reachable from: ${bad.map { it.name }}", bad.isEmpty())
    }

    // NEGATIVE: a planted password never reaches Data, the notification or the result text.
    @Test
    fun scrubSecretRemovesThePlantedPassword() {
        val planted = "zzPLANTEDzz1234"
        val out = scrubSecret("login failed for user with $planted at host", planted)
        assertFalse(planted in out)
        assertFalse(planted in scrubSecret("$planted$planted", planted))
    }

    @Test
    fun scrubSecretLeavesATextWithNoSecretAlone() {
        assertEquals("nothing here", scrubSecret("nothing here", "zzPLANTEDzz1234"))
        assertEquals("nothing here", scrubSecret("nothing here", null))
    }

    @Test
    fun parseChunkSizeFallsBackToADay() {
        assertEquals(ChunkSize.Day, parseChunkSize(null))
        assertEquals(ChunkSize.Day, parseChunkSize("bogus"))
        assertEquals(ChunkSize.Day, parseChunkSize("-3"))
        assertEquals(ChunkSize.Hour, parseChunkSize(" Hour "))
        assertEquals(ChunkSize.Count(50), parseChunkSize("50"))
    }

    @Test
    fun resetResultMessageNeverPromisesTheExportIsBack() {
        val ok = resetResultMessage(true, null)
        assertFalse("inbox" in ok)
        assertTrue("again" in ok)
        assertTrue(resetResultMessage(false, "boom").contains("boom"))
    }

    @Test
    fun stopMarkIsStoppedOnlyForTheMarkedWork() {
        var stored: String? = null
        val m = StopMark(read = { stored }, write = { stored = it }, clear = { stored = null })
        assertFalse(m.isStopped("A"))
        m.markStopped("A")
        assertTrue(m.isStopped("A"))
        assertFalse(m.isStopped("B"))
        assertTrue("isStopped must not consume the mark", m.isStopped("A"))
    }
}
