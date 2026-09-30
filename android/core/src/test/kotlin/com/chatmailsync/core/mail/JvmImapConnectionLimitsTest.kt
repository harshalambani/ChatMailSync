package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SEC-03: a hostile or broken server cannot make the client eat the heap. */
class JvmImapConnectionLimitsTest {

    private val email = JvmImapConnectionWireTest.EMAIL
    private val password = JvmImapConnectionWireTest.FAKE_PASSWORD

    private fun connect(server: ScriptedImapServer): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 10)

    // NEGATIVE: a 2 MB "line" with no line break ends in ImapAbortError, not an out-of-memory.
    @Test(timeout = 30_000)
    fun aTwoMegabyteLineWithNoBreakIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ -> conn.sendRaw(2_000_000) }
        }.use { server ->
            val conn = connect(server)
            try {
                conn.list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("longer than"))
            }
        }
    }

    // NEGATIVE (SEC-03 fixup): a CR is never stored, but it is still a byte read on the line, so an
    // endless run of CR with no LF must hit the cap instead of keeping the reader busy forever.
    @Test(timeout = 30_000)
    fun twoMillionCarriageReturnsWithNoLineFeedIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ -> conn.sendRaw(2_000_000, fill = '\r') }
        }.use { server ->
            val conn = connect(server)
            try {
                conn.list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("longer than"))
            }
        }
    }

    // NEGATIVE: the same over-long line inside the greeting ends the handshake as a 503.
    @Test(timeout = 30_000)
    fun anOverlongGreetingIsA503() {
        ScriptedImapServer { c ->
            c.sendRaw(2_000_000)
            c.readLine()
        }.use { server ->
            try {
                connect(server)
                fail("expected a 503")
            } catch (e: MailTransportError) {
                assertEquals(503, e.status)
            }
        }
    }

    // Positive control: a big but legitimate 900 KB LIST reply still parses.
    @Test(timeout = 30_000)
    fun aNineHundredKilobyteListReplyStillParses() {
        val name = "x".repeat(900_000)
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ ->
                conn.send("* LIST (\\HasNoChildren) \"/\" \"$name\"")
                conn.send("$tag OK LIST completed")
            }
        }.use { server ->
            val conn = connect(server)
            val result = conn.list("\"\"", "*")
            assertEquals("OK", result.status)
            assertEquals(1, result.data.size)
            assertTrue(result.data[0]!!.length > 900_000)
        }
    }

    // NEGATIVE: more than 100,000 untagged lines for one command is an abort.
    @Test(timeout = 60_000)
    fun moreThanOneHundredThousandUntaggedLinesIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ -> repeat(100_001) { conn.send("* LIST (\\HasNoChildren) \"/\" \"f\"") } }
        }.use { server ->
            val conn = connect(server)
            try {
                conn.list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("100000"))
            }
        }
    }

    // Positive control: a large reply just under the cap is fine.
    @Test(timeout = 60_000)
    fun ninetyNineThousandUntaggedLinesStillWork() {
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ ->
                repeat(99_000) { conn.send("* LIST (\\HasNoChildren) \"/\" \"f\"") }
                conn.send("$tag OK done")
            }
        }.use { server ->
            val conn = connect(server)
            assertEquals(99_000, conn.list("\"\"", "*").data.size)
        }
    }

    // NEGATIVE: an endless stream of untagged lines during CAPABILITY cannot spin the handshake forever.
    @Test(timeout = 60_000)
    fun endlessUntaggedLinesDuringCapabilityIsA503() {
        ScriptedImapServer { c ->
            c.send("* OK hello")
            c.readLine()
            repeat(100_001) { c.send("* junk") }
            c.readLine()
        }.use { server ->
            try {
                connect(server)
                fail("expected a 503")
            } catch (e: MailTransportError) {
                assertEquals(503, e.status)
            }
        }
    }
}
