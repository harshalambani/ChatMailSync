package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * What [CoreApi] must NOT do. Each test asserts that the wrong outcome does not occur, not
 * only that the right one does.
 */
class CoreApiNegativeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // A made-up secret with a quote and a backslash, so its IMAP-quoted form differs from the raw one.
    private val secret = "hunter2\"x\\yz"
    private val quoted = "hunter2\\\"x\\\\yz"

    private fun api(root: File, redact: String? = secret) =
        CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) }, { redact })

    private fun chatText(days: List<Int>): String =
        days.joinToString("") { "%02d/03/25, 09:00 - Meera Iyer: message %d\n".format(it, it) }

    private fun synced(root: File, redact: String? = secret): CoreApi {
        val a = api(root, redact)
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(listOf(20, 21)))
        a.sync(transport = CountingTransport())
        return a
    }

    /** Every string anywhere inside [v], joined. */
    private fun allText(v: Any?): String = when (v) {
        is Map<*, *> -> v.entries.joinToString("\n") { "${it.key}\n${allText(it.value)}" }
        is Iterable<*> -> v.joinToString("\n") { allText(it) }
        null -> ""
        else -> v.toString()
    }

    private fun assertClean(text: String) {
        assertFalse("raw secret leaked", secret in text)
        assertFalse("quoted secret leaked", quoted in text)
    }

    private fun snapshot(a: CoreApi): String =
        allText(listOf(a.status(), a.syncLog(), a.listCutoffs(), a.listChatSenders(), a.getSelfSender()))

    // ---- (i) the secret never leaves --------------------------------------

    @Test
    fun failingTransportTextNeverCarriesThePassword() {
        val a = api(tmp.newFolder("r1"))
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(listOf(20, 21)))
        val events = mutableListOf<Map<String, Any?>>()
        val stats = a.sync(
            transport = CountingTransport(failAt = 1, failText = "LOGIN failed for $secret and \"$quoted\""),
            onProgress = { events.add(HashMap(it)) },
        )
        val everything = allText(listOf(stats, events, a.progressState(), a.syncLog(), a.status()))
        assertClean(everything)
        assertTrue("the failure was recorded", (stats["errors"] as List<*>).isNotEmpty())
        assertTrue("and scrubbed, not dropped", "***" in everything)
    }

    @Test
    fun unknownChatErrorsNeverEchoThePassword() {
        val a = api(tmp.newFolder("r2"))
        val results = listOf(a.resetPreview(secret), a.reset(secret), a.deleteChat(secret))
        for (r in results) {
            assertEquals(false, r["ok"])
            assertClean(allText(r))
            assertTrue((r["error"] as String).startsWith("No chat found matching '***"))
        }
    }

    @Test
    fun anExceptionLeavesAsCoreApiExceptionWithoutThePasswordOrACause() {
        val a = CoreApi(tmp.newFolder("r3"), { throw RuntimeException("cannot open $secret ($quoted)") }, { secret })
        val calls = listOf<() -> Any?>({ a.status() }, { a.syncLog() }, { a.getSelfSender() }, { a.listCutoffs() })
        for (call in calls) {
            try {
                call()
                fail("expected CoreApiException")
            } catch (e: CoreApiException) {
                assertClean(e.message.orEmpty())
                assertTrue("***" in e.message.orEmpty())
                assertNull("a cause could carry the password", e.cause)
                assertClean(e.stackTraceToString())
            }
        }
    }

    @Test
    fun anUnwritableBackupPathErrorNeverCarriesThePassword() {
        val plain = "hunter2xyz"
        val a = synced(tmp.newFolder("r4"), redact = plain)
        val blocker = File(tmp.root, "has-$plain").also { it.writeText("x") }
        // A path the secret is part of: whatever comes back must not repeat it.
        val dest = File(blocker, "$plain.cmsbackup")
        val text = try {
            allText(a.exportBackup(dest.path, "{}", "1.0"))
        } catch (e: CoreApiException) {
            e.message.orEmpty()
        }
        assertFalse(plain in text)
    }

    // ---- (ii) a backup never carries credentials ------------------------

    @Test
    fun exportBackupDropsCredentialsEvenWhenSettingsJsonHoldsThem() {
        val a = synced(tmp.newFolder("r5"), redact = null)
        val clean = """{"chunk_size": "day", "app_password": "pw-zero", "imap_password": "pw-one",
            "password": "pw-two", "key": "key-one", "token": "tok-one"}"""
        val bundle = File(tmp.root, "out.cmsbackup")
        val r = a.exportBackup(bundle.path, clean, "1.0")
        assertEquals(true, r["ok"])
        assertEquals(listOf("chunk_size"), r["settings_keys"])

        var bytes = ""
        ZipFile(bundle).use { z ->
            for (e in z.entries()) bytes += e.name + "\n" + z.getInputStream(e).readBytes().toString(Charsets.UTF_8) + "\n"
        }
        for (leaked in listOf("pw-zero", "pw-one", "pw-two", "key-one", "tok-one", "app_password", "imap_password")) {
            assertFalse("$leaked is in the bundle", leaked in bytes)
        }

        val b = api(tmp.newFolder("r5b"), redact = null)
        val imported = b.importBackup(bundle.path)
        assertEquals(true, imported["ok"])
        assertEquals(mapOf("chunk_size" to "day"), imported["settings"])
        assertFalse("pw-one" in allText(imported))
    }

    // ---- (iii) a malformed bundle changes nothing ------------------------

    @Test
    fun malformedBundlesAreRefusedWithASentenceAndLeaveTheDatabaseAlone() {
        val a = synced(tmp.newFolder("r6"), redact = null)
        val before = snapshot(a)
        val dir = tmp.newFolder("bundles")
        val files = listOf(
            File(dir, "text.cmsbackup").also { it.writeText("this is not a zip") },
            File(dir, "empty.cmsbackup").also { it.writeBytes(ByteArray(0)) },
            File(dir, "nomanifest.cmsbackup").also { SyncRunSupport.writeZip(it, mapOf("hello.txt" to "hi")) },
            File(dir, "badmanifest.cmsbackup").also { SyncRunSupport.writeZip(it, mapOf("manifest.json" to "{not json")) },
            File(dir, "listmanifest.cmsbackup").also { SyncRunSupport.writeZip(it, mapOf("manifest.json" to "[]")) },
            File(dir, "missing.cmsbackup"),
        )
        for (f in files) {
            val d = a.describeBackup(f.path)
            val i = a.importBackup(f.path)
            for (r in listOf(d, i)) {
                assertEquals("${f.name} ok", false, r["ok"])
                val msg = r["error"] as String
                assertTrue("${f.name} gives a sentence: '$msg'", msg.length > 10 && msg.trimEnd().endsWith("."))
            }
            assertEquals("${f.name} changed the database", before, snapshot(a))
        }
    }

    // ---- ported edges: the wrong thing does not happen --------------------

    @Test
    fun removeFromInboxStaysInsideTheInbox() {
        val a = api(tmp.newFolder("r7"), redact = null)
        a.paths.inboxDir.mkdirs()
        val outside = File(a.paths.dataDir, "keep.txt").also { it.writeText("keep") }
        val r = a.removeFromInbox("../keep.txt")
        assertEquals(true, r["ok"])
        assertTrue("a file outside the inbox was removed", outside.exists())
        val sub = File(a.paths.inboxDir, "folder").also { it.mkdirs() }
        val d = a.removeFromInbox("folder")
        assertEquals(false, d["ok"])
        assertTrue("a folder was removed", sub.exists())
    }

    @Test
    fun anUnconfirmedResetChangesNothing() {
        val a = synced(tmp.newFolder("r8"), redact = null)
        val before = snapshot(a)
        val r = a.reset("Meera Iyer")
        assertEquals(false, r["ok"])
        assertEquals(true, r["needs_confirmation"])
        assertEquals(false, r["file_restored"])
        assertEquals(before, snapshot(a))
        assertTrue(a.listInbox().isEmpty())
        assertEquals(1, a.paths.processedDir.listFiles()?.size)
    }

    @Test
    fun aBadCutoffKeepsTheOneAlreadySet() {
        val a = synced(tmp.newFolder("r9"), redact = null)
        assertEquals(true, a.setCutoff("Meera Iyer", "2025-03-21")["ok"])
        val bad = a.setCutoff("Meera Iyer", "next tuesday")
        assertEquals(false, bad["ok"])
        assertNull(bad["cutoff_date"])
        assertEquals("2025-03-21", a.getCutoff("Meera Iyer")["cutoff_date"])
    }

    @Test
    fun aStopRequestedBeforeSyncDoesNotStopIt() {
        val a = api(tmp.newFolder("r10"), redact = null)
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(listOf(20)))
        File(a.paths.inboxDir, "WhatsApp Chat with Rohan Mehta.txt").writeText(chatText(listOf(20)))
        a.requestStop()
        val r = a.sync(transport = CountingTransport())
        assertEquals(false, r["stopped"])
        assertEquals(2, r["files_synced"])
    }

    @Test
    fun aStopRequestIsOnlyHonouredBetweenFiles() {
        val a = api(tmp.newFolder("r11"), redact = null)
        a.paths.inboxDir.mkdirs()
        File(a.paths.inboxDir, "WhatsApp Chat with Meera Iyer.txt").writeText(chatText(listOf(20, 21, 22)))
        File(a.paths.inboxDir, "WhatsApp Chat with Rohan Mehta.txt").writeText(chatText(listOf(20)))
        val r = a.sync(transport = CountingTransport(), onProgress = { if (it["type"] == "syncing") a.requestStop() })
        assertEquals(true, r["stopped"])
        // The file in hand finished completely, and the next one never started.
        assertEquals(1, r["files_synced"])
        assertEquals(3, r["messages_synced"])
    }

    @Test
    fun pyReprMatchesPythonQuoting() {
        assertEquals("'abc'", pyRepr("abc"))
        assertEquals("\"it's\"", pyRepr("it's"))
        assertEquals("'say \"hi\"'", pyRepr("say \"hi\""))
        assertEquals("'it\\'s \"x\"'", pyRepr("it's \"x\""))
        assertEquals("'a\\nb\\tc\\\\d'", pyRepr("a\nb\tc\\d"))
        assertEquals("'\\x01'", pyRepr("\u0001"))
        assertEquals("'caf\u00e9'", pyRepr("caf\u00e9"))
        assertEquals("'a\\u200bb'", pyRepr("a\u200bb"))
        assertEquals("'\\xa0'", pyRepr("\u00a0"))
    }
}
