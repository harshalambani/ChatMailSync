package com.chatmailsync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-09: tell users what the mail looks like and where it goes. */
class Ux09PostSyncExpectationsTest {

    // --- Sync complete line -------------------------------------------------

    @Test
    fun `line is absent on a test run even with messages synced`() {
        assertFalse(shouldShowMailboxHint(messagesSynced = 12, dryRun = true))
    }

    @Test
    fun `line is absent when nothing was synced`() {
        assertFalse(shouldShowMailboxHint(messagesSynced = 0, dryRun = false))
    }

    @Test
    fun `line is absent on a test run with nothing synced`() {
        assertFalse(shouldShowMailboxHint(messagesSynced = 0, dryRun = true))
    }

    @Test
    fun `line is present on a normal run with one or more synced`() {
        assertTrue(shouldShowMailboxHint(messagesSynced = 1, dryRun = false))
        assertTrue(shouldShowMailboxHint(messagesSynced = 40, dryRun = false))
    }

    @Test
    fun `a stopped run keeps the line because the rule never looks at stopped`() {
        // The worker sends messagesSynced for a stopped run too; the rule takes only
        // the count and the test-run flag, so a stop with >= 1 filed still shows it.
        assertTrue(shouldShowMailboxHint(messagesSynced = 3, dryRun = false))
    }

    @Test
    fun `the line says where the mail went and carries no secret or chat name`() {
        assertEquals(
            "Find them in your mailbox, in the WhatsApp folder: one folder per chat, named after " +
                "the chat. On Gmail these show as labels.",
            SYNC_MAILBOX_HINT,
        )
    }

    @Test
    fun `worker output keys for the line are distinct from the input keys`() {
        assertTrue(SyncWorker.KEY_MESSAGES_SYNCED != SyncWorker.KEY_DRY_RUN)
        assertTrue(SyncWorker.KEY_RESULT_DRY_RUN != SyncWorker.KEY_DRY_RUN)
    }

    // --- Welcome step -------------------------------------------------------

    @Test
    fun `welcome step has the heading, three bullets and the app password line`() {
        assertEquals("What you get", WELCOME_WHAT_YOU_GET_HEADING)
        assertEquals(
            listOf(
                "\u2022 A WhatsApp folder in your mailbox, with one folder for each chat",
                "\u2022 One email per day of conversation, with photos and files if you export with media",
                "\u2022 Searchable like any other mail, and stored by your mail provider",
            ),
            WELCOME_WHAT_YOU_GET_BULLETS,
        )
        assertEquals(
            "Setup needs an app password from your mail provider. It takes a few minutes, " +
                "and the app shows you where to make one.",
            WELCOME_APP_PASSWORD_LINE,
        )
    }

    // --- Help question ------------------------------------------------------

    @Test
    fun `the mailbox question is third, right after the send question`() {
        assertEquals("Where do my chats show up in my mailbox?", FAQ[2].first)
        assertEquals("Why doesn't the app \"send\" my messages anywhere?", FAQ[1].first)
    }

    @Test
    fun `the answer names the real settings path`() {
        val answer = FAQ[2].second
        assertTrue(answer.contains("Settings > Advanced > Chunk size"))
        assertTrue(answer.startsWith("In a folder called WhatsApp"))
    }

    @Test
    fun `the drawing appears only under the mailbox question`() {
        assertTrue(faqHasMailboxIllustration(FAQ[2].first))
        val others = FAQ.filter { faqHasMailboxIllustration(it.first) }
        assertEquals(1, others.size)
        FAQ.filterIndexed { i, _ -> i != 2 }.forEach {
            assertFalse("Drawing must not sit under: ${it.first}", faqHasMailboxIllustration(it.first))
        }
    }

    @Test
    fun `the drawing has a description and uses placeholder names only`() {
        assertTrue(MAILBOX_ILLUSTRATION_DESCRIPTION.isNotBlank())
        assertEquals(
            "Example: a WhatsApp folder in your mailbox with one folder per chat",
            MAILBOX_ILLUSTRATION_DESCRIPTION,
        )
        val text = MAILBOX_ILLUSTRATION_LINES.joinToString("\n")
        assertTrue(text.contains("Meera Iyer"))
        assertTrue(text.contains("Family Group"))
        listOf("Gmail", "Yahoo", "Outlook", "Google").forEach {
            assertFalse("No provider name in the drawing: $it", text.contains(it))
        }
    }

    // --- Source hygiene -----------------------------------------------------

    @Test
    fun `the UX-09 constants are plain ASCII in source`() {
        val base = java.io.File("src/main/java/com/chatmailsync/app")
        val checks = mapOf(
            "FirstRunScreen.kt" to listOf("WELCOME_WHAT_YOU_GET_HEADING", "WELCOME_WHAT_YOU_GET_BULLETS", "WELCOME_APP_PASSWORD_LINE"),
            "HelpScreen.kt" to listOf("MAILBOX_ILLUSTRATION_LINES", "MAILBOX_ILLUSTRATION_DESCRIPTION", "FAQ_MAILBOX_QUESTION"),
            "SyncProgressScreen.kt" to listOf("SYNC_MAILBOX_HINT"),
        )
        for ((file, names) in checks) {
            val lines = java.io.File(base, file).readLines()
            for (name in names) {
                val start = lines.indexOfFirst { it.contains("val $name") }
                assertTrue("$name not found in $file", start >= 0)
                // The declaration and the lines that continue it, up to the first blank line.
                val block = lines.drop(start).takeWhile { it.isNotBlank() }
                for (line in block) {
                    assertTrue(
                        "Non-ASCII character in $file ($name): $line",
                        line.all { it.code <= 0x7F },
                    )
                }
            }
        }
    }

    // --- UX-10 polish -------------------------------------------------------

    @Test
    fun `bullet split keeps wording word for word and never doubles the bullet`() {
        val bullet = "• Searchable like any other mail"
        val (glyph, body) = splitWelcomeBullet(bullet)
        assertEquals("• ", glyph)
        assertEquals("Searchable like any other mail", body)
        assertEquals(bullet, glyph + body)
        assertFalse(body.contains("•"))
        WELCOME_WHAT_YOU_GET_BULLETS.forEach { b ->
            val (g, t) = splitWelcomeBullet(b)
            assertEquals(b, g + t)
            assertFalse(t.contains("•"))
            assertEquals(1, b.count { it == '•' })
        }
    }

    @Test
    fun `envelope line text is unchanged and the drawing keeps its description`() {
        val line = MAILBOX_ILLUSTRATION_LINES[5]
        assertEquals(line, mailboxEnvelopeLine(line).text)
        assertTrue(MAILBOX_ILLUSTRATION_DESCRIPTION.isNotBlank())
    }
}
