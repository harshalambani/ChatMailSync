package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PAR-03 + SEC-04: the greeting/CAPABILITY/LOGIN exchange ends in a MailTransportError, promptly. */
class JvmImapConnectionHandshakeTest {

    private val email = JvmImapConnectionWireTest.EMAIL
    private val password = JvmImapConnectionWireTest.FAKE_PASSWORD

    private fun connect(server: ScriptedImapServer, timeoutSeconds: Long = 5): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, timeoutSeconds)

    private fun expect503(server: ScriptedImapServer): MailTransportError {
        try {
            connect(server)
        } catch (e: MailTransportError) {
            assertEquals(e.message, 503, e.status)
            assertFalse(e.message, e.message!!.contains(password))
            return e
        }
        fail("expected a MailTransportError 503")
        throw IllegalStateException()
    }

    // NEGATIVE: a BYE greeting is a refusal, not something to log in through.
    @Test(timeout = 10_000)
    fun byeGreetingGives503WithoutSendingLogin() {
        ScriptedImapServer { c ->
            c.send("* BYE server shutting down")
            c.readLine() // returns null once the client closes
        }.use { server ->
            val e = expect503(server)
            assertTrue(e.message, e.message!!.contains("refused"))
            assertFalse("no LOGIN may be sent to a BYE greeting", server.received.contains("LOGIN"))
        }
    }

    // NEGATIVE: something that is not an IMAP greeting at all.
    @Test(timeout = 10_000)
    fun nonImapGreetingGives503() {
        ScriptedImapServer { c ->
            c.send("HTTP/1.1 400 Bad Request")
            c.readLine()
        }.use { server ->
            expect503(server)
            assertFalse(server.received.contains("LOGIN"))
        }
    }

    // NEGATIVE: server hangs up straight after the greeting, mid-CAPABILITY. Used to spin forever.
    @Test(timeout = 10_000)
    fun eofMidCapabilityEndsInAnError() {
        ScriptedImapServer { c ->
            c.send("* OK hello")
            c.readLine() // the CAPABILITY command, then hang up without answering
        }.use { server ->
            val e = expect503(server)
            assertTrue(e.message, e.message!!.contains("closed"))
        }
    }

    // NEGATIVE: server hangs up mid-LOGIN. Used to spin forever; must be a MailTransportError, not a raw IOException.
    @Test(timeout = 10_000)
    fun eofMidLoginEndsInAMailTransportError() {
        ScriptedImapServer { c ->
            c.send("* OK [CAPABILITY IMAP4rev1] hello")
            c.readLine() // the LOGIN, then hang up
        }.use { server ->
            val e = expect503(server)
            assertTrue(e.message, e.message!!.contains("closed"))
            assertTrue(server.received.contains("LOGIN"))
        }
    }

    // NEGATIVE: a server that goes silent mid-login times out as a 503, not a raw SocketTimeoutException.
    @Test(timeout = 15_000)
    fun silenceMidLoginIsATimeout503() {
        ScriptedImapServer { c ->
            c.send("* OK [CAPABILITY IMAP4rev1] hello")
            c.readLine()
            Thread.sleep(4_000) // never answers within the client's 1 s timeout
        }.use { server ->
            try {
                connect(server, timeoutSeconds = 1)
                fail("expected a timeout")
            } catch (e: MailTransportError) {
                assertEquals(503, e.status)
            }
        }
    }

    // Positive controls: PREAUTH skips LOGIN; a normal OK greeting still logs in.
    @Test(timeout = 10_000)
    fun preauthGreetingSkipsLogin() {
        ScriptedImapServer { c ->
            c.serve(greeting = "* PREAUTH [CAPABILITY IMAP4rev1] already in")
        }.use { server ->
            val conn = connect(server)
            assertFalse(server.received, server.received.contains("LOGIN"))
            conn.logout()
        }
    }

    @Test(timeout = 10_000)
    fun okGreetingStillLogsIn() {
        ScriptedImapServer { it.serve() }.use { server ->
            val conn = connect(server)
            assertTrue(server.received.contains("LOGIN"))
            conn.logout()
        }
    }

    // NEGATIVE: a refused login is still the 401 MailTransportError, not turned into 503.
    @Test(timeout = 10_000)
    fun refusedLoginStaysA401() {
        ScriptedImapServer { c ->
            c.send("* OK [CAPABILITY IMAP4rev1] hello")
            val line = c.readLine()!!
            c.send("${line.substringBefore(' ')} NO [AUTHENTICATIONFAILED] invalid credentials")
        }.use { server ->
            try {
                connect(server)
                fail("login must fail")
            } catch (e: MailTransportError) {
                assertEquals(401, e.status)
            }
        }
    }

    // NEGATIVE: after a command has started, a server drop is an ImapAbortError, never an endless loop.
    @Test(timeout = 10_000)
    fun eofMidCommandIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ -> conn.close() }
        }.use { server ->
            val conn = connect(server)
            try {
                conn.list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                // expected
            }
        }
    }
}
