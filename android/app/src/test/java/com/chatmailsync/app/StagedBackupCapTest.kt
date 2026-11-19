package com.chatmailsync.app

import com.chatmailsync.core.mail.MAX_BUNDLE_DB_BYTES
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-03: staging a picked backup is bounded. Plain JUnit, no Android types. */
class StagedBackupCapTest {

    private fun tmp(): File = File.createTempFile("staged", ".cmsbackup").also { it.deleteOnExit() }

    /** An endless stream: the original unbounded copy would never return. */
    private class Endless : InputStream() {
        var served = 0L
        override fun read(): Int { served++; return 7 }
        override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
    }

    @Test
    fun `a file within the cap is copied whole`() {
        val data = ByteArray(1000) { it.toByte() }
        val f = tmp()
        assertTrue(copyBounded(ByteArrayInputStream(data), f, 1000))
        assertEquals(data.toList(), f.readBytes().toList())
    }

    @Test
    fun `a file over the cap is refused and the partial copy is removed`() {
        val f = tmp()
        assertFalse(copyBounded(ByteArrayInputStream(ByteArray(1001)), f, 1000))
        assertFalse("partial staged file must be deleted", f.exists())
    }

    @Test
    fun `an endless source stops shortly after the cap instead of filling storage`() {
        val src = Endless()
        val f = tmp()
        assertFalse(copyBounded(src, f, 100_000))
        assertTrue("read ${src.served} bytes", src.served <= 100_000 + 64 * 1024)
        assertFalse(f.exists())
    }

    @Test
    fun `the cap sits just above the core database limit, not below it`() {
        assertTrue(MAX_STAGED_BACKUP_BYTES > MAX_BUNDLE_DB_BYTES)
        assertTrue(MAX_STAGED_BACKUP_BYTES < MAX_BUNDLE_DB_BYTES + 64L * 1_048_576L)
    }
}
