package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * UX-08: each chat's total filed (all runs) and the import preview's media line. The negative
 * assertions say what must NOT happen: a total that follows the last run, a total that mixes
 * chats, and any preview text that suggests exporting again.
 */
class ChatTotalAndPreviewTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val meeraFile = "WhatsApp Chat with Meera Iyer.txt"
    private val rohanFile = "WhatsApp Chat with Rohan Mehta.txt"

    private fun api(root: File) = CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) })

    private fun text(days: IntRange, who: String = "Meera Iyer"): String =
        days.joinToString("") { "%02d/03/25, 09:00 - %s: message %d\n".format(it, who, it) }

    private fun CoreApi.put(name: String, body: String) {
        paths.inboxDir.mkdirs()
        File(paths.inboxDir, name).writeText(body)
    }

    private fun CoreApi.row(chatId: String) = status().single { it["chat_id"] == chatId }

    private fun CoreApi.repo() =
        StateRepository({ StateRepository.now() }) { SqliteJdbcStateDb(paths.stateDbPath.absolutePath) }

    // ---- Fix 1: the total ---------------------------------------------------

    @Test
    fun aNothingNewRunAfterFiveFiledStillShowsFive() {
        val a = api(tmp.newFolder("r1"))
        a.put(meeraFile, text(20..24))
        a.sync(transport = CountingTransport())
        a.put(meeraFile, text(20..24))
        val second = a.sync(transport = CountingTransport())
        assertEquals(0, second["messages_synced"])
        val r = a.row("meera_iyer")
        assertEquals(5, r["total_filed"])
        // NEGATIVE: the total did not follow the empty last run down to 0.
        assertTrue((r["total_filed"] as Int) != 0)
        // The last-run field is untouched, so the CSV is unchanged.
        assertEquals(0, r["messages_synced"])
    }

    @Test
    fun aLastRunThatSentThreeOfFiveShowsFiveNotThree() {
        val a = api(tmp.newFolder("r2"))
        a.put(meeraFile, text(20..21))
        a.sync(transport = CountingTransport())
        a.put(meeraFile, text(20..24))
        a.sync(transport = CountingTransport())
        val r = a.row("meera_iyer")
        assertEquals(5, r["total_filed"])
        assertEquals(3, r["messages_synced"])
        // NEGATIVE: the total is not the last run's count.
        assertTrue(r["total_filed"] != r["messages_synced"])
    }

    @Test
    fun aFailedRunDoesNotZeroOrReduceTheTotal() {
        val a = api(tmp.newFolder("r3"))
        a.put(meeraFile, text(20..21))
        a.sync(transport = CountingTransport())
        a.put(meeraFile, text(20..24))
        a.sync(transport = CountingTransport(failAt = 1))
        val r = a.row("meera_iyer")
        assertEquals(2, r["total_filed"])
        // NEGATIVE: the failure did not wipe or shrink what was already filed.
        assertTrue((r["total_filed"] as Int) >= 2)
    }

    @Test
    fun aStoppedRunDoesNotZeroOrReduceTheTotal() {
        val a = api(tmp.newFolder("r4"))
        a.put(meeraFile, text(20..21))
        a.sync(transport = CountingTransport())
        a.put(meeraFile, text(20..24))
        val stopped = a.sync(
            transport = CountingTransport(),
            onProgress = { if (it["type"] == "syncing") a.requestStop() },
        )
        assertEquals(true, stopped["stopped"])
        val r = a.row("meera_iyer")
        assertEquals(2, r["total_filed"])
        assertTrue((r["total_filed"] as Int) >= 2)
    }

    @Test
    fun anotherChatsMessagesNeverCountTowardsThisOne() {
        val a = api(tmp.newFolder("r5"))
        a.put(meeraFile, text(20..24))
        a.put(rohanFile, text(20..21, "Rohan Mehta"))
        a.sync(transport = CountingTransport())
        assertEquals(5, a.row("meera_iyer")["total_filed"])
        assertEquals(2, a.row("rohan_mehta")["total_filed"])
        // NEGATIVE: neither chat carries the other's messages (7 would mean they were mixed).
        assertFalse(a.status().any { it["total_filed"] == 7 })
    }

    @Test
    fun theTotalEqualsWhatTheResetGuardCounts() {
        val a = api(tmp.newFolder("r6"))
        a.put(meeraFile, text(20..21))
        a.put(rohanFile, text(20..22, "Rohan Mehta"))
        a.sync(transport = CountingTransport())
        a.put(meeraFile, text(20..24))
        a.sync(transport = CountingTransport())
        val repo = a.repo()
        for (row in a.status()) {
            assertEquals(repo.countArchivedMessages(row["chat_id"] as String), row["total_filed"])
        }
    }

    @Test
    fun lastRunCountIsStillTheLastRunsCount() {
        val a = api(tmp.newFolder("r7"))
        a.put(meeraFile, text(20..24))
        val first = a.sync(transport = CountingTransport())
        val r = a.row("meera_iyer")
        assertEquals(first["messages_synced"], r["messages_synced"])
        assertEquals(5, r["messages_synced"])
        // NEGATIVE: adding the total did not remove or rename the last-run key the CSV reads.
        assertTrue(r.containsKey("messages_synced"))
        assertEquals(5, r["total_filed"])
    }

    // ---- Fix 2: the preview ---------------------------------------------------

    private fun previewOf(body: String): String {
        val a = api(tmp.newFolder())
        val f = File(tmp.newFolder(), meeraFile).also { it.writeText(body) }
        return a.previewText(f.path)
    }

    private val reexportWords = listOf("export again", "re-export", "reexport", "Include media")

    private fun assertNoReexportAdvice(text: String) {
        for (w in reexportWords) assertFalse("'$w' in: $text", text.contains(w, ignoreCase = true))
    }

    @Test
    fun anExportWithNoMediaAndNoMarkersGetsTheNeutralLine() {
        val out = previewOf(text(20..22))
        assertTrue(out, out.lines().contains(PREVIEW_NO_MEDIA_LINE))
        // NEGATIVE: not the "without media" line, since nothing says media was left out.
        assertFalse(out, out.contains("Exported without media"))
        assertNoReexportAdvice(out)
    }

    @Test
    fun anExportWithOmittedMarkersSaysHowManyWereLeftOut() {
        val body = "20/03/25, 09:00 - Meera Iyer: Reached the station\n" +
            "20/03/25, 09:05 - Meera Iyer: <Media omitted>\n" +
            "20/03/25, 09:10 - Rohan Mehta: image omitted\n" +
            "20/03/25, 09:15 - Rohan Mehta: Lovely view\n"
        val out = previewOf(body)
        assertTrue(out, out.lines().contains("Exported without media: 2 photos or files left out."))
        // The parser still drops the markers: the message count is the two real texts.
        assertTrue(out, out.contains("2 messages"))
        // NEGATIVE: not the neutral line, and no advice to export again.
        assertFalse(out, out.contains(PREVIEW_NO_MEDIA_LINE))
        assertNoReexportAdvice(out)
    }

    @Test
    fun oneOmittedMarkerReadsInTheSingular() {
        val body = "20/03/25, 09:00 - Meera Iyer: Reached the station\n" +
            "20/03/25, 09:05 - Meera Iyer: <Media omitted>\n"
        val out = previewOf(body)
        assertTrue(out, out.lines().contains("Exported without media: 1 photo or file left out."))
        assertFalse(out, out.contains("1 photos"))
    }

    @Test
    fun anExportWithAttachmentsAddsNoNewLine() {
        val body = "20/03/25, 09:00 - Meera Iyer: Reached the station\n" +
            "20/03/25, 09:05 - Meera Iyer: IMG-0001.jpg (file attached)\n"
        val out = previewOf(body)
        assertTrue(out, out.contains("1 media"))
        // NEGATIVE: neither new line appears when there is media.
        assertFalse(out, out.contains("No photos or files"))
        assertFalse(out, out.contains("Exported without media"))
        assertNoReexportAdvice(out)
    }

    @Test
    fun theOmittedCountDoesNotChangeWhatTheParserFeedsTheSync() {
        val body = "20/03/25, 09:00 - Meera Iyer: Reached the station\n" +
            "20/03/25, 09:05 - Meera Iyer: <Media omitted>\n"
        val f = File(tmp.newFolder(), meeraFile).also { it.writeText(body) }
        val before = parseFile(f, "meera_iyer").toList().map { it.body }
        api(tmp.newFolder()).preview(f.path)
        val after = parseFile(f, "meera_iyer").toList().map { it.body }
        assertEquals(listOf("Reached the station"), before)
        assertEquals(before, after)
    }
}
