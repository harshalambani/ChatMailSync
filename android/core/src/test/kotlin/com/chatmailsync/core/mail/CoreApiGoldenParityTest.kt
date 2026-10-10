package com.chatmailsync.core.mail

import com.chatmailsync.core.mail.SyncTestSupport.golden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [CoreApi] against the real `src/android_api.py` on Python 3.13. The script below is the
 * Kotlin twin of `generate_core_api_golden()`: same steps in the same order with the same
 * arguments. Each result goes through its JSON form ([CoreApiJson.render]) and is compared
 * with Python's, key for key. Clock values, the bundle id and temp paths are masked on both
 * sides; the progress fraction is compared in thousandths.
 */
class CoreApiGoldenParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val maskedKeys = setOf(
        "started_at", "completed_at", "last_run_at", "last_started_at", "last_completed_at",
        "created_at", "bundle_id", "path", "created_at_epoch_ms", "first_seen", "last_seen",
    )

    /** Doubles cannot go through the golden reader; the fraction is compared as thousandths. */
    private fun prep(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { (k, x) ->
            k as String to (if (k == "fraction" && x is Double) Math.rint(x * 1000).toLong() else prep(x))
        }
        is List<*> -> v.map { prep(it) }
        else -> v
    }

    private fun mask(node: JsonNode): JsonNode = when (node) {
        is JsonNode.Obj -> JsonNode.Obj(
            node.fields.mapValues { (k, x) ->
                when {
                    k == "settings_json" && x is JsonNode.Str -> parseJson(x.value)
                    k in maskedKeys && x !is JsonNode.Null -> JsonNode.Str("<masked>")
                    else -> mask(x)
                }
            },
        )
        is JsonNode.Arr -> JsonNode.Arr(node.items.map { mask(it) })
        else -> node
    }

    private fun chatText(days: List<Int>, perDay: Int = 1): String {
        val senders = listOf("Meera Iyer", "Rohan Mehta")
        val sb = StringBuilder()
        for (d in days) for (k in 0 until perDay) {
            sb.append("%02d/03/25, 09:%02d - %s: message %d.%d\n".format(d, k, senders[(d + k) % 2], d, k))
        }
        return sb.toString()
    }

    /** ORDER BY msg_count DESC leaves ties to the engine; pin them, as the generator does. */
    private fun tieSorted(rows: List<Map<String, Any?>>) = rows.sortedWith(
        compareBy<Map<String, Any?>>({ -(it["msg_count"] as Number).toLong() }, { it["chat_id"] as String }, { it["sender"] as String }),
    )

    private fun api(root: File) = CoreApi(root, { SqliteJdbcStateDb(it.absolutePath) })

    @Test
    fun everyStepMatchesPython() {
        val expected = golden("core_api_golden.json")["steps"].asArr().items
        var n = 0
        fun rec(name: String, value: Any?) {
            val step = expected[n++]
            assertEquals("step $n name", step["call"].asString(), name)
            val actual = mask(parseJson(CoreApiJson.render(prep(value))))
            assertEquals("step $n ($name)", mask(step["result"]), actual)
        }

        val root = tmp.newFolder("root")
        val root2 = tmp.newFolder("root2")
        val a = api(root)
        val meera = "WhatsApp Chat with Meera Iyer.txt"
        val rohan = "WhatsApp Chat with Rohan Mehta.txt"
        val asha = "WhatsApp Chat with Asha Rao.txt"
        val inbox = a.paths.inboxDir

        rec("list_inbox_missing_dir", a.listInbox())
        inbox.mkdirs()
        File(inbox, meera).writeText(chatText(listOf(20, 21, 22)))
        File(inbox, rohan).writeText(chatText(listOf(20, 22), 2))
        File(inbox, "notes.md").writeText("x")
        rec("list_inbox", a.listInbox())
        rec("remove_from_inbox_present", a.removeFromInbox("notes.md"))
        rec("remove_from_inbox_absent", a.removeFromInbox("notes.md"))
        rec("imap_providers", a.imapProviders())

        val side = File(root, "side").also { it.mkdirs() }
        File(side, meera).writeText(chatText(listOf(20, 21, 22)))
        File(side, asha).writeText("no timestamps here\n")
        val sideMeera = File(side, meera).path
        val sideAsha = File(side, asha).path
        rec("preview_meera", a.preview(sideMeera))
        rec("preview_meera_cutoff_mid", a.preview(sideMeera, "2025-03-21"))
        rec("preview_meera_cutoff_after", a.preview(sideMeera, "2025-04-01"))
        rec("preview_meera_bad_cutoff", a.preview(sideMeera, "soon"))
        rec("preview_empty_file", a.preview(sideAsha))
        rec("preview_text_meera", a.previewText(sideMeera))
        rec("preview_text_meera_cutoff_mid", a.previewText(sideMeera, "2025-03-21"))
        rec("preview_text_meera_cutoff_after", a.previewText(sideMeera, "2025-04-01"))
        rec("preview_text_empty_file", a.previewText(sideAsha))
        val base = linkedMapOf<String, Any?>(
            "ok" to true, "display_name" to "Meera Iyer", "message_count" to 1, "participant_count" to 1,
            "media_count" to 1, "first_message_ts" to "2025-03-20T09:00:00",
            "last_message_ts" to "2025-03-20T09:00:00", "cutoff_date" to null, "error" to null,
        )
        rec("format_not_ok_with_error", a.formatPreview(base + mapOf("ok" to false, "error" to "It broke.")))
        rec("format_not_ok_no_error", a.formatPreview(base + mapOf("ok" to false)))
        rec("format_ok_with_error", a.formatPreview(base + mapOf("error" to "Nothing in it.")))
        rec("format_ok_no_name", a.formatPreview(base + mapOf("display_name" to null)))
        rec("format_singular_media", a.formatPreview(base))
        rec(
            "format_plural_no_media",
            a.formatPreview(base + mapOf("message_count" to 3, "participant_count" to 2, "media_count" to 0)),
        )
        rec("format_all_older", a.formatPreview(base + mapOf("cutoff_date" to "2025-03-21")))
        rec(
            "format_part_older",
            a.formatPreview(base + mapOf("last_message_ts" to "2025-03-25T09:00:00", "cutoff_date" to "2025-03-22")),
        )

        rec("progress_state_fresh", a.progressState())
        val events = mutableListOf<Map<String, Any?>>()
        rec("sync_stats", a.sync(transport = CountingTransport(), onProgress = { events.add(HashMap(it)) }))
        rec("sync_events", events)
        rec("progress_state_after_sync", a.progressState())
        // Same-second runs tie on ORDER BY started_at; space them apart, as the generator does.
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + a.paths.stateDbPath.absolutePath).use { c ->
            for ((rid, hrs) in listOf(1 to 2L, 2 to 1L)) {
                c.prepareStatement("UPDATE sync_runs SET started_at = ? WHERE run_id = ?").use { st ->
                    st.setString(1, java.time.LocalDateTime.now().minusHours(hrs).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")))
                    st.setInt(2, rid)
                    st.executeUpdate()
                }
            }
        }
        rec("status", a.status())
        rec("sync_log", a.syncLog().sortedByDescending { it["run_id"] as Long })
        rec("sync_log_one_day", a.syncLog(1).sortedByDescending { it["run_id"] as Long })
        rec("sync_status", a.syncStatus())
        rec("sync_status_one_day", a.syncStatus(1))

        rec("list_chat_senders_all", tieSorted(a.listChatSenders()))
        rec("list_chat_senders_one", a.listChatSenders("meera_iyer"))
        rec("list_chat_senders_none", a.listChatSenders("nobody"))
        rec("get_self_sender_before", a.getSelfSender())
        rec("set_self_sender_name", a.setSelfSender("  Meera Iyer  "))
        rec("set_self_sender_clear", a.setSelfSender(""))
        StateRepository({ StateRepository.now() }) { SqliteJdbcStateDb(a.paths.stateDbPath.absolutePath) }
            .setAppState(StateRepository.SELF_SENDER_LEARNED_PENDING, "Rohan Mehta")
        rec("get_pending_banner", a.getPendingSelfSenderBanner())
        a.clearSelfSenderBanner()
        rec("get_pending_banner_cleared", a.getPendingSelfSenderBanner())

        rec("get_cutoff_none", a.getCutoff("Meera Iyer"))
        rec("set_cutoff_meera", a.setCutoff("meera iyer", "2025-03-21"))
        rec("get_cutoff_meera", a.getCutoff("Meera Iyer"))
        rec("set_cutoff_long_timestamp", a.setCutoff("rohan_mehta", " 2025-03-19T08:00:00 "))
        rec("set_cutoff_bad", a.setCutoff("rohan_mehta", "yesterday"))
        rec("set_cutoff_bad_quote", a.setCutoff("rohan_mehta", "it's"))
        rec("set_cutoff_unknown_chat", a.setCutoff("new_chat", "2025-01-01"))
        rec("get_cutoff_unknown", a.getCutoff("zzz"))
        rec("list_cutoffs", a.listCutoffs())
        rec("preview_with_chat_cutoff", a.preview(sideMeera, "2025-03-01"))
        rec("set_cutoff_clear", a.setCutoff("meera_iyer", "   "))
        rec("list_cutoffs_after_clear", a.listCutoffs())

        rec("reset_preview_meera", a.resetPreview("Meera Iyer"))
        rec("reset_preview_nobody", a.resetPreview("no body's"))
        rec("reset_unconfirmed", a.reset("Meera Iyer"))
        rec("reset_confirmed", a.reset("Meera Iyer", true))
        rec("list_inbox_after_reset", a.listInbox())
        rec("reset_again_nothing_to_restore", a.reset("meera_iyer", true))
        rec("reset_nobody", a.reset("nobody"))
        rec("delete_chat_rohan", a.deleteChat("Rohan Mehta"))
        rec("delete_chat_nobody", a.deleteChat("nobody"))
        rec("status_after_delete", a.status())

        File(inbox, rohan).writeText(chatText(listOf(23)))
        File(inbox, asha).writeText(chatText(listOf(23)))
        val stopEvents = mutableListOf<Map<String, Any?>>()
        rec(
            "sync_stopped_stats",
            a.sync(
                transport = CountingTransport(),
                onProgress = { e ->
                    stopEvents.add(HashMap(e))
                    if (e["type"] == "file_done") a.requestStop()
                },
            ),
        )
        rec("sync_stopped_events", stopEvents)
        rec("progress_state_after_stop", a.progressState())
        rec("list_inbox_after_stop", a.listInbox())
        rec(
            "sync_dry_run",
            a.sync(transport = CountingTransport(), dryRun = true, chatFilter = "asha", cutoffDate = "2025-03-22"),
        )

        val bundle = File(root, "out.cmsbackup")
        rec(
            "export_backup",
            a.exportBackup(
                bundle.path,
                """{"chunk_size": "day", "imap_port": 993, "theme_mode": "dark", "app_password": "hunter2xyz", "bogus": 1}""",
                "9.9.9",
            ),
        )
        rec("export_backup_bad_settings", a.exportBackup(File(root, "b2.cmsbackup").path, "not json", "9.9.9"))
        rec("export_backup_settings_list", a.exportBackup(File(root, "b3.cmsbackup").path, "[1, 2]", ""))
        rec("describe_backup", a.describeBackup(bundle.path))
        val junk = File(root, "junk.cmsbackup").also { it.writeText("this is not a zip") }
        rec("describe_backup_junk", a.describeBackup(junk.path))
        rec("import_backup_junk", a.importBackup(junk.path))
        rec("status_before_import", a.status())

        val b = api(root2)
        rec("import_backup_fresh_root", b.importBackup(bundle.path))
        rec("import_backup_again", b.importBackup(bundle.path))
        rec("status_after_import", b.status())
        rec("list_cutoffs_after_import", b.listCutoffs())
        rec("list_chat_senders_after_import", tieSorted(b.listChatSenders()))

        assertEquals("every golden step was run", expected.size, n)
    }

    @Test
    fun jsonFormRendersEveryValueKind() {
        val text = CoreApiJson.render(
            linkedMapOf(
                "s" to "a\"b\\c\n\u0001\u00e9",
                "i" to 3,
                "l" to 4_000_000_000L,
                "t" to true,
                "n" to null,
                "list" to listOf(1, "x", null),
                "map" to linkedMapOf("k" to false),
                "empty" to emptyList<Any?>(),
            ),
        )
        val node = parseJson(text)
        assertEquals("a\"b\\c\n\u0001\u00e9", node["s"].asString())
        assertEquals(4_000_000_000L, node["l"].asLong())
        assertTrue(node["t"].asBoolean())
        assertEquals(JsonNode.Null, node["n"])
        assertEquals(3, node["list"].asArr().items.size)
        assertEquals(listOf("s", "i", "l", "t", "n", "list", "map", "empty"), node.asObj().fields.keys.toList())
    }
}
