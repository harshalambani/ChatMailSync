package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopMarkTest {
    private class Fake {
        var stored: String? = null
        val writes = mutableListOf<String>()
        val mark = StopMark(
            read = { stored },
            write = { stored = it; writes += it },
            clear = { stored = null },
        )
    }

    @Test
    fun `stop then same work re-runs - engine not started and mark cleared`() {
        val f = Fake()
        f.mark.markStopped("A")
        assertTrue(f.mark.consumeIfStopped("A"))
        assertNull(f.stored)
    }

    @Test
    fun `no stop and same work re-runs - engine is started`() {
        val f = Fake()
        assertFalse(f.mark.consumeIfStopped("A"))
    }

    @Test
    fun `stop on A then new sync B - B runs normally`() {
        val f = Fake()
        f.mark.markStopped("A")
        f.mark.clearForFreshSync()
        assertFalse(f.mark.consumeIfStopped("B"))
        // Even without the clear, a mark for A never matches B.
        f.mark.markStopped("A")
        assertFalse(f.mark.consumeIfStopped("B"))
        assertEquals("A", f.stored)
    }

    @Test
    fun `stale mark for A never blocks or is cleared by an auto run`() {
        val f = Fake()
        f.mark.markStopped("A")
        assertFalse(f.mark.consumeIfStopped("auto-1"))
        f.mark.clearOnFinish("auto-1")
        assertEquals("A", f.stored)
    }

    @Test
    fun `after a normal finish the mark is gone`() {
        val f = Fake()
        f.mark.markStopped("A")
        f.mark.clearOnFinish("A")
        assertNull(f.stored)
        assertFalse(f.mark.consumeIfStopped("A"))
    }

    @Test
    fun `only the work id is ever written`() {
        val f = Fake()
        val id = java.util.UUID.randomUUID().toString()
        f.mark.markStopped(id)
        assertEquals(listOf(id), f.writes)
        assertTrue(f.writes.all { it.length == 36 && it.all { c -> c.isLetterOrDigit() || c == '-' } })
    }
}
