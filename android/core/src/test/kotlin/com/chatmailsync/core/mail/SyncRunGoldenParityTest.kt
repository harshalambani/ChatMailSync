package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncRunSupport.asRows
import com.chatmailsync.core.mail.SyncRunSupport.eventText
import com.chatmailsync.core.mail.SyncTestSupport.golden
import com.chatmailsync.core.mail.SyncTestSupport.newRepo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The run loop against the real `src/sync_manager.py` (ProgressSyncManager) on
 * Python 3.13: the same files, the same deterministic transport, the same
 * options. `sync_run_golden.json` holds, per step, the stats, the progress
 * events, the number of mailbox writes and what the run left in the state
 * store and in the two folders. Clock values are the only thing left out.
 */
class SyncRunGoldenParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val g = golden("sync_run_golden.json")

    @Test
    fun everyScenarioMatchesPython() {
        for (sc in g["scenarios"].asArr().items) {
            val name = sc["name"].asString()
            val root = tmp.newFolder(name)
            val inbox = File(root, "inbox").also { it.mkdirs() }
            val processed = File(root, "processed")
            val dbPath = File(root, "state.db").absolutePath
            val repo = newRepo(root, "state.db")

            val steps = sc["steps"].asArr().items
            val specSteps = sc["spec"]["steps"].asArr().items
            for ((n, step) in steps.withIndex()) {
                val label = "$name step ${n + 1}"
                val spec = specSteps[n]
                for ((fname, fileSpec) in spec["write"].asObj().fields) {
                    val f = File(inbox, fname)
                    val z = fileSpec.asObj().fields["zip"]
                    if (z != null) {
                        SyncRunSupport.writeZip(f, z.asObj().fields.mapValues { it.value.asString() })
                    } else {
                        f.writeText(fileSpec["text"].asString())
                    }
                }
                val opts = spec["options"].asObj().fields
                opts["chat_cutoffs"]?.asObj()?.fields?.forEach { (chat, cutoff) -> repo.setChatCutoff(chat, cutoff.asString()) }

                val events = mutableListOf<Map<String, Any?>>()
                val stopAfter = opts["stop_after_files"]?.asInt()
                val transport = CountingTransport(opts["fail_at"]?.asInt())
                val mgr = SyncManager(
                    repo = repo,
                    transport = transport,
                    inboxDir = inbox,
                    processedDir = processed,
                    chunkSize = opts["chunk_size"]?.let { c ->
                        when (c) {
                            is JsonNode.Num -> ChunkSize.Count(c.value.toInt())
                            else -> ChunkSize.Day
                        }
                    } ?: ChunkSize.Day,
                    dryRun = (opts["dry_run"] as? JsonNode.Bool)?.value ?: false,
                    trigger = opts["trigger"]?.asString() ?: "manual",
                    cutoffDate = opts["cutoff_date"]?.asString(),
                    onProgress = { events.add(it) },
                    shouldStop = {
                        stopAfter != null && events.count { it["type"] == "file_done" } >= stopAfter
                    },
                    sleeper = { },
                )
                val stats = mgr.run(opts["chat_filter"]?.asString())

                // stats
                val want = step["stats"].asObj().fields
                val got = stats.asMap()
                for ((k, v) in want) {
                    val actual = got[k]
                    when (v) {
                        is JsonNode.Arr -> assertEquals("$label $k", v.items.map { it.asString() }, actual)
                        else -> assertEquals("$label $k", v.asInt(), actual)
                    }
                }
                assertEquals("$label stats keys", want.keys, got.keys)
                assertEquals("$label text", step["statsText"].asString(), stats.toString())

                // events
                assertEquals(
                    "$label events",
                    step["events"].asArr().items.map { eventText(it) },
                    events.map { eventText(it) },
                )
                assertEquals("$label inserts", step["inserts"].asInt(), transport.insertCalls)

                // state and folders
                val d = SyncRunSupport.dump(dbPath, inbox, processed)
                val ws = step["state"]
                assertEquals("$label runs", asRows(ws["runs"]), d.runs)
                assertEquals("$label hashes", asRows(ws["hashes"]), d.hashes)
                assertEquals("$label chats", asRows(ws["chats"]), d.chats)
                assertEquals("$label senders", asRows(ws["senders"]), d.senders)
                assertEquals("$label appState", asRows(ws["appState"]), d.appState)
                assertEquals("$label inbox", ws["inbox"].asArr().items.map { it.asString() }, d.inbox)
                assertEquals("$label processed", ws["processed"].asArr().items.map { it.asString() }, d.processed)
            }
        }
    }
}
