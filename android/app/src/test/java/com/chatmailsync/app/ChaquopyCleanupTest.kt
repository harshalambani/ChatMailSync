package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class ChaquopyCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var flag = false
    private val logs = mutableListOf<String>()

    private fun run(filesDir: File) =
        ChaquopyCleanup.run(filesDir, { flag }, { flag = true }, { logs += it })

    private fun file(dir: File, rel: String, text: String = "x"): File {
        val f = File(dir, rel)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        return f
    }

    @Test
    fun `removes the chaquopy folder with nested contents and sets the flag`() {
        val files = tmp.newFolder("files")
        file(files, "chaquopy/a.txt")
        file(files, "chaquopy/lib/python3.13/site.py")
        file(files, "chaquopy/lib/deep/er/still/b.bin")
        File(files, "chaquopy/empty").mkdirs()

        val out = run(files)

        assertFalse(File(files, "chaquopy").exists())
        assertTrue(flag)
        assertTrue(out.done)
        assertEquals(0, out.failures)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `sync_state db, its wal and shm files and other siblings survive`() {
        val files = tmp.newFolder("files")
        file(files, "chaquopy/a.txt")
        val db = file(files, "chatmailsync/data/sync_state.db", "db")
        val wal = file(files, "chatmailsync/data/sync_state.db-wal", "wal")
        val shm = file(files, "chatmailsync/data/sync_state.db-shm", "shm")
        val inbox = file(files, "chatmailsync/data/inbox/Meera Iyer.zip", "chat")
        val other = file(files, "datastore/prefs.pb", "p")
        val loose = file(files, "sync_state.db", "top")

        run(files)

        for (f in listOf(db, wal, shm, inbox, other, loose)) {
            assertTrue("must survive: ${f.name}", f.exists())
        }
        assertEquals("db", db.readText())
        assertEquals("chat", inbox.readText())
    }

    @Test
    fun `chaquopy-old and chaquopy txt and look-alike siblings survive`() {
        val files = tmp.newFolder("files")
        file(files, "chaquopy/a.txt")
        val oldDir = file(files, "chaquopy-old/keep.txt", "old")
        val txt = file(files, "chaquopy.txt", "t")
        val two = file(files, "chaquopy2/keep.txt", "two")
        val upper = file(files, "Chaquopy-data/keep.txt", "u")

        run(files)

        assertFalse(File(files, "chaquopy").exists())
        assertEquals("old", oldDir.readText())
        assertEquals("t", txt.readText())
        assertEquals("two", two.readText())
        assertEquals("u", upper.readText())
    }

    @Test
    fun `a symlink inside chaquopy is unlinked and its outside target survives`() {
        val files = tmp.newFolder("files")
        file(files, "chaquopy/a.txt")
        val outsideFile = file(files, "chatmailsync/data/sync_state.db", "precious")
        val outsideDir = tmp.newFolder("elsewhere")
        val outsideChild = file(outsideDir, "child.txt", "child")
        try {
            Files.createSymbolicLink(
                File(files, "chaquopy/link-to-file").toPath(),
                outsideFile.toPath(),
            )
            Files.createSymbolicLink(
                File(files, "chaquopy/link-to-dir").toPath(),
                outsideDir.toPath(),
            )
        } catch (e: Exception) {
            assumeNoException("symbolic links cannot be created here", e)
        }

        val out = run(files)

        assertFalse(File(files, "chaquopy").exists())
        assertTrue(out.done)
        assertEquals("precious", outsideFile.readText())
        assertTrue(outsideDir.isDirectory)
        assertEquals("child", outsideChild.readText())
    }

    @Test
    fun `a symlink named chaquopy is unlinked without touching its target`() {
        val files = tmp.newFolder("files")
        val outsideDir = tmp.newFolder("elsewhere")
        val outsideChild = file(outsideDir, "child.txt", "child")
        try {
            Files.createSymbolicLink(File(files, "chaquopy").toPath(), outsideDir.toPath())
        } catch (e: Exception) {
            assumeNoException("symbolic links cannot be created here", e)
        }

        run(files)

        assertFalse(Files.exists(File(files, "chaquopy").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("child", outsideChild.readText())
        assertTrue(flag)
    }

    @Test
    fun `with the flag already set nothing is touched`() {
        val files = tmp.newFolder("files")
        val a = file(files, "chaquopy/a.txt", "still here")
        flag = true
        var marked = 0

        val out = ChaquopyCleanup.run(files, { flag }, { marked += 1 }, { logs += it })

        assertTrue(out.skipped)
        assertTrue(a.exists())
        assertEquals("still here", a.readText())
        assertEquals(0, marked)
    }

    @Test
    fun `an absent folder is not an error and the flag is set`() {
        val files = tmp.newFolder("files")
        val keep = file(files, "chatmailsync/data/sync_state.db", "db")

        val out = run(files)

        assertTrue(flag)
        assertTrue(out.done)
        assertEquals(0, out.failures)
        assertTrue(keep.exists())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a failed delete leaves the flag unset, logs a count only and does not throw`() {
        val files = tmp.newFolder("files")
        val locked = file(files, "chaquopy/locked/a.txt", "x")
        val lockedDir = locked.parentFile!!
        // A read-only directory blocks deleting its entries on POSIX. On Windows, or when running
        // as root, it does not, and then this case cannot be set up and is skipped.
        lockedDir.setWritable(false)
        assumeTrue("cannot make a folder undeletable here", !lockedDir.canWrite())
        try {
            val out = run(files)

            assertFalse(flag)
            assertFalse(out.done)
            assertTrue(out.failures > 0)
            assertEquals(1, logs.size)
            assertFalse("log must carry no path", logs[0].contains(files.absolutePath))
            assertFalse(logs[0].contains("locked"))
        } finally {
            lockedDir.setWritable(true)
        }
    }
}
