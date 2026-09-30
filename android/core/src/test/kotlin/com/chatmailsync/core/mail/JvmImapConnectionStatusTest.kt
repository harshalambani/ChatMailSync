package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PAR-02 + BUG-03: the REAL [JvmImapConnection] returns a tagged NO as data
 * and throws only on BAD, like imaplib -- so `ImapTransport.labelsCreate`'s
 * "folder already exists" rescue can run, and a real NO still fails.
 *
 * Also the double-vs-real contract test: the same script is run against
 * [FakeImapConnection] and the real class, and the two must agree.
 */
class JvmImapConnectionStatusTest {

    private val fakeEmail = "meera.iyer@example.com"

    private fun connect(server: ScriptedImapServer): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, fakeEmail, "pw-FAKE", 5)

    /** An [ImapTransport] whose connection is the REAL class, over the scripted socket. */
    private fun realTransport(server: ScriptedImapServer) = ImapTransport(
        host = "127.0.0.1",
        port = server.port,
        email = fakeEmail,
        password = "pw-FAKE",
        connectionFactory = { connect(server) },
    )

    /** Replies to CREATE/SUBSCRIBE/APPEND/LIST with [reply] (a full tagged-status line body, e.g. "NO [X] text"). */
    private fun serverReplying(reply: (cmd: String) -> String?): ScriptedImapServer = ScriptedImapServer { c ->
        c.serve { conn, tag, cmd ->
            if (cmd.startsWith("APPEND ")) {
                val size = Regex("\\{(\\d+)}$").find(cmd)!!.groupValues[1].toInt()
                conn.send("+ Ready for literal data")
                conn.readExactly(size)
                conn.readLine() // the CRLF that ends the literal
            }
            conn.send("$tag ${reply(cmd) ?: "OK done"}")
        }
    }

    // ---- BUG-03: the connection check on a mailbox that already has the folder

    @Test(timeout = 20_000)
    fun createOfAnExistingFolderThroughTheRealConnectionReturnsTheName() {
        serverReplying { cmd -> if (cmd.startsWith("CREATE ")) "NO [ALREADYEXISTS] Mailbox exists" else null }.use { server ->
            val transport = realTransport(server)
            assertEquals("WhatsApp", transport.labelsCreate("WhatsApp"))
            transport.close()
            assertNull(server.failure)
        }
    }

    // The connection-check FOLDER stage goes through the same path.
    @Test(timeout = 20_000)
    fun connectionCheckFolderStagePassesWhenTheFolderAlreadyExists() {
        serverReplying { cmd -> if (cmd.startsWith("CREATE ")) "NO [ALREADYEXISTS] Mailbox exists" else null }.use { server ->
            val transport = realTransport(server)
            // labelsCreate is exactly what checkConnection's FOLDER stage runs.
            assertEquals(LABEL_PARENT, transport.labelsCreate(LABEL_PARENT))
            transport.close()
        }
    }

    // NEGATIVE: a real NO (permission denied) must still fail CREATE. Only "already exists" is rescued.
    @Test(timeout = 20_000)
    fun aRealNoOnCreateStillFails() {
        serverReplying { cmd -> if (cmd.startsWith("CREATE ")) "NO [PERMISSIONDENIED] you may not create folders" else null }.use { server ->
            val transport = realTransport(server)
            try {
                transport.labelsCreate("WhatsApp")
                fail("CREATE with a real NO must fail")
            } catch (e: MailTransportError) {
                assertEquals(401, e.status)
                assertTrue(e.message, e.message!!.contains("CREATE"))
            }
        }
    }

    // NEGATIVE: a real NO on APPEND still fails.
    @Test(timeout = 20_000)
    fun aRealNoOnAppendStillFails() {
        serverReplying { cmd -> if (cmd.startsWith("APPEND ")) "NO [PERMISSIONDENIED] read-only mailbox" else null }.use { server ->
            val transport = realTransport(server)
            try {
                transport.messagesInsert("Subject: x\n\nbody\n".toByteArray(), "WhatsApp/Rohan Mehta")
                fail("APPEND with a real NO must fail")
            } catch (e: MailTransportError) {
                assertEquals(401, e.status)
            }
        }
    }

    // NEGATIVE: an over-size APPEND rejection is still mapped to 413 (unchanged by PAR-02).
    @Test(timeout = 20_000)
    fun aTooBigNoOnAppendStillMapsTo413() {
        serverReplying { cmd -> if (cmd.startsWith("APPEND ")) "NO [TOOBIG] Message too large" else null }.use { server ->
            val transport = realTransport(server)
            try {
                transport.messagesInsert("Subject: x\n\nbody\n".toByteArray(), "WhatsApp/Rohan Mehta")
                fail("must fail")
            } catch (e: MailTransportError) {
                assertEquals(413, e.status)
            }
        }
    }

    // NEGATIVE: BAD still throws (as ImapCommandError from the connection itself).
    @Test(timeout = 20_000)
    fun badStillThrowsFromTheRealConnection() {
        serverReplying { cmd -> if (cmd.startsWith("CREATE ")) "BAD command syntax error" else null }.use { server ->
            val conn = connect(server)
            try {
                conn.create("\"WhatsApp\"")
                fail("BAD must throw")
            } catch (e: ImapCommandError) {
                assertTrue(e.message, e.message!!.contains("syntax error"))
            }
        }
    }

    // NEGATIVE: a NO is returned to the caller as a NO, not turned into an exception.
    @Test(timeout = 20_000)
    fun noIsReturnedAsDataNotThrown() {
        serverReplying { cmd -> if (cmd.startsWith("CREATE ")) "NO [ALREADYEXISTS] Mailbox exists" else null }.use { server ->
            val conn = connect(server)
            val result = conn.create("\"WhatsApp\"")
            assertEquals("NO", result.status)
            assertTrue(result.data.toString(), isAlreadyExistsResponse(result.data))
        }
    }

    // ---- The double and the real class must agree (contract test)

    private data class Step(val op: String, val status: String, val text: String, val untagged: List<String> = emptyList())

    private val script = listOf(
        Step("LIST", "OK", "LIST completed", untagged = listOf("LIST (\\HasNoChildren) \"/\" \"INBOX\"")),
        Step("LIST", "NO", "[UNAVAILABLE] try later"),
        Step("CREATE", "OK", "Completed"),
        Step("CREATE", "NO", "[ALREADYEXISTS] Mailbox exists"),
        Step("CREATE", "NO", "[PERMISSIONDENIED] no"),
        Step("CREATE", "BAD", "syntax error"),
        Step("SUBSCRIBE", "OK", "Completed"),
        Step("SUBSCRIBE", "NO", "[CANNOT] cannot subscribe"),
        Step("APPEND", "OK", "[APPENDUID 1 2] Append completed"),
        Step("APPEND", "NO", "[TOOBIG] Message too large"),
        Step("APPEND", "BAD", "bad append"),
    )

    private fun outcome(block: () -> ImapResult): String = try {
        val r = block()
        "RETURN ${r.status} ${r.data}"
    } catch (t: Throwable) {
        "THROW ${t.javaClass.simpleName} ${t.message}"
    }

    private fun run(conn: ImapConnection, step: Step): String = outcome {
        when (step.op) {
            "LIST" -> conn.list("\"\"", "*")
            "CREATE" -> conn.create("\"WhatsApp\"")
            "SUBSCRIBE" -> conn.subscribe("\"WhatsApp\"")
            else -> conn.append("\"WhatsApp\"", null, null, "Subject: x\r\n\r\nbody\r\n".toByteArray())
        }
    }

    @Test(timeout = 30_000)
    fun theFakeAndTheRealConnectionAgreeOnEveryStatus() {
        val remaining = script.toMutableList()
        val server = ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                val step = remaining.removeAt(0)
                if (cmd.startsWith("APPEND ")) {
                    val size = Regex("\\{(\\d+)}$").find(cmd)!!.groupValues[1].toInt()
                    conn.send("+ go")
                    conn.readExactly(size)
                    conn.readLine()
                }
                step.untagged.forEach { conn.send("* $it") }
                conn.send("$tag ${step.status} ${step.text}")
            }
        }
        server.use {
            val real = connect(server)
            val realOutcomes = script.map { run(real, it) }

            val fakeOutcomes = script.map { step ->
                val fake = FakeImapConnection()
                val data = step.untagged + if (step.status == "OK" && step.op != "LIST") emptyList() else listOf(step.text).filter { step.status != "OK" }
                val response = ImapResult(step.status, data)
                when (step.op) {
                    "LIST" -> fake.listResponse = response
                    "CREATE" -> fake.createResponse = response
                    "SUBSCRIBE" -> fake.subscribeResponse = response
                    else -> fake.appendResponse = response
                }
                run(fake, step)
            }
            assertEquals(fakeOutcomes.joinToString("\n"), realOutcomes.joinToString("\n"))
            // The script must actually contain all three behaviours, or the agreement proves nothing.
            assertTrue(realOutcomes.any { it.startsWith("RETURN NO") })
            assertTrue(realOutcomes.any { it.startsWith("THROW ImapCommandError") })
            assertTrue(realOutcomes.any { it.startsWith("RETURN OK") })
        }
    }

    // NEGATIVE: the double no longer returns BAD as data.
    @Test
    fun theFakeThrowsOnBadAndReturnsNoAsData() {
        val fake = FakeImapConnection()
        fake.createResponse = ImapResult("BAD", listOf("syntax"))
        try {
            fake.create("x")
            fail("the fake must throw on BAD like the real connection")
        } catch (_: ImapCommandError) {
        }
        fake.createResponse = ImapResult("NO", listOf("[ALREADYEXISTS] x"))
        assertEquals("NO", fake.create("x").status)
        assertFalse(fake.calls.isEmpty())
    }
}
