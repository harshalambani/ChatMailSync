package com.chatmailsync.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-09: the Chats CSV export neutralises spreadsheet formulas and clears old exports. */
class CsvExportGuardTest {

    @Test
    fun `each formula trigger character gets a leading apostrophe`() {
        for (c in listOf('=', '+', '-', '@', '\t', '\r')) {
            assertEquals("trigger ${c.code}", "\"'${c}cmd\"", csvField("${c}cmd"))
        }
    }

    @Test
    fun `a hyperlink formula is neutralised and its quotes still double`() {
        assertEquals(
            "\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"",
            csvField("=HYPERLINK(\"http://x\",\"y\")"),
        )
    }

    @Test
    fun `ordinary names are unchanged apart from quoting`() {
        assertEquals("\"Meera Iyer\"", csvField("Meera Iyer"))
        assertEquals("\"\"", csvField(""))
        assertEquals("\"a=b\"", csvField("a=b"))
        assertEquals("\"x-ray\"", csvField("x-ray"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
    }

    @Test
    fun `a trigger character that is not first is left alone`() {
        assertFalse(csvField("name =x").contains("'"))
        assertFalse(csvField(" =x").contains("'"))
    }

    @Test
    fun `stale files in the exports directory are removed, the directory stays`() {
        val dir = File.createTempFile("exports", "").also { it.delete(); it.mkdirs() }
        try {
            val a = File(dir, "old.csv").apply { writeText("x") }
            val b = File(dir, "chat_mail_sync_chats.csv").apply { writeText("y") }
            val sub = File(dir, "nested").apply { mkdirs() }
            val inner = File(sub, "keep.txt").apply { writeText("z") }
            removeStaleExports(dir, keep = null)
            assertFalse(a.exists())
            assertFalse(b.exists())
            // Negative: only regular files directly inside; subdirectories and their contents survive.
            assertTrue(sub.isDirectory)
            assertTrue(inner.exists())
            assertTrue(dir.isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the kept file is not removed and a missing directory is harmless`() {
        val dir = File.createTempFile("exports", "").also { it.delete(); it.mkdirs() }
        try {
            val keep = File(dir, "keep.csv").apply { writeText("k") }
            removeStaleExports(dir, keep)
            assertTrue(keep.exists())
        } finally {
            dir.deleteRecursively()
        }
        removeStaleExports(File(dir, "does-not-exist"), null)
    }
}
