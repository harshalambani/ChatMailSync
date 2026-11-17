package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** F-10 (folder ids): a bundle's stored folder ids are not trusted blindly. */
class MigrationTrustTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val openDb: OpenDb = { f -> SqliteJdbcStateDb(f.absolutePath) }

    private fun freshDb(name: String, dir: File): File {
        val f = File(dir, name)
        StateRepository { openDb(f) }.initDb()
        return f
    }

    private fun exec(db: File, sql: String, args: List<Any?> = emptyList()) =
        openDb(db).use { it.exec(sql, args) }

    private fun merged(seed: (File) -> Unit): File {
        val dir = tmp.newFolder()
        val incoming = freshDb("incoming.db", dir)
        val target = freshDb("target.db", dir)
        seed(incoming)
        mergeDb(target, incoming, openDb)
        return target
    }

    private fun chat(db: File, id: String, label: String?) = exec(
        db,
        "INSERT INTO chats (chat_id, display_name, gmail_label_id, source_filename, created_at, updated_at) " +
            "VALUES (?, ?, ?, 'a.txt', '2026-10-01T09:00:00', '2026-10-01T09:00:00')",
        listOf(id, id, label),
    )

    private fun labelOf(db: File, id: String): String? =
        openDb(db).use { it.query("SELECT gmail_label_id AS l FROM chats WHERE chat_id = ?", listOf(id)).single()["l"] as String? }

    @Test
    fun `a folder id under the app's parent folder is kept`() {
        val target = merged { chat(it, "c1", "WhatsApp/Meera Iyer") }
        assertEquals("WhatsApp/Meera Iyer", labelOf(target, "c1"))
    }

    @Test
    fun `a folder id outside the parent folder is imported as null`() {
        val target = merged {
            chat(it, "c1", "INBOX")
            chat(it, "c2", "Other/Meera")
            chat(it, "c3", "WhatsApp")
            chat(it, "c4", "whatsapp/lower")
            chat(it, "c5", "")
        }
        for (id in listOf("c1", "c2", "c3", "c4", "c5")) assertNull(id, labelOf(target, id))
    }

    @Test
    fun `a folder id with a control character is imported as null`() {
        val target = merged {
            chat(it, "c1", "WhatsApp/a\r\nb")
            chat(it, "c2", "WhatsApp/a\u0000b")
            chat(it, "c3", "WhatsApp/a\u007Fb")
        }
        for (id in listOf("c1", "c2", "c3")) assertNull(id, labelOf(target, id))
    }

    @Test
    fun `a null folder id stays null and the chat row itself is still imported`() {
        val target = merged { chat(it, "c1", null) }
        assertNull(labelOf(target, "c1"))
        val n = openDb(target).use { it.query("SELECT COUNT(*) AS n FROM chats").single()["n"] as Number }
        assertEquals(1, n.toInt())
    }
}
