package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** F-02: the wire form of the two lookup commands on the REAL [JvmImapConnection]. */
class JvmImapConnectionLookupTest {

    private fun connect(server: ScriptedImapServer): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, "meera.iyer@example.com", "pw-FAKE", 5)

    @Test(timeout = 30_000)
    fun examineAndUidSearchGoOutAsReadOnlyCommandsAndParseTheUids() {
        val seen = mutableListOf<String>()
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                seen.add(cmd)
                when {
                    cmd.startsWith("EXAMINE ") -> {
                        conn.send("* 3 EXISTS")
                        conn.send("$tag OK [READ-ONLY] EXAMINE completed")
                    }
                    cmd.startsWith("UID SEARCH ") -> {
                        conn.send("* SEARCH 41 42")
                        conn.send("$tag OK SEARCH completed")
                    }
                    else -> conn.send("$tag OK done")
                }
            }
        }.use { server ->
            val conn = connect(server)
            assertEquals("OK", conn.examine("\"WhatsApp/Meera Iyer\"").status)
            val found = conn.uidSearchMessageId("<wa-sync-abc@local>")
            assertEquals("OK", found.status)
            assertEquals(listOf("41 42"), found.data)
        }
        assertEquals(
            listOf("EXAMINE \"WhatsApp/Meera Iyer\"", "UID SEARCH HEADER Message-ID \"<wa-sync-abc@local>\""),
            seen,
        )
        assertTrue("never SELECT (read-write)", seen.none { it.startsWith("SELECT") })
    }

    // NEGATIVE: an id that could break out of the quoted argument never reaches the wire.
    @Test(timeout = 30_000)
    fun anUnsafeIdIsRefusedBeforeAnythingIsSent() {
        val seen = mutableListOf<String>()
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                seen.add(cmd)
                conn.send("$tag OK done")
            }
        }.use { server ->
            val conn = connect(server)
            for (bad in listOf("<a\" ALL@x>", "<a\r\nb@x>", "<a\\b@x>", "")) {
                try {
                    conn.uidSearchMessageId(bad)
                    fail("expected a refusal")
                } catch (e: ImapCommandError) {
                    assertTrue(e.message, e.message!!.contains("refused"))
                }
            }
        }
        assertTrue("nothing was sent: $seen", seen.isEmpty())
    }

    // SEC-03 still applies to the new commands: a hostile reply to SEARCH is an abort, not a heap blow-up.
    @Test(timeout = 30_000)
    fun anOverlongSearchReplyIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, cmd ->
                if (cmd.startsWith("UID SEARCH ")) conn.sendRaw(2_000_000)
            }
        }.use { server ->
            val conn = connect(server)
            try {
                conn.uidSearchMessageId("<wa-sync-abc@local>")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("longer than"))
            }
        }
    }
}
