package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SEC-06 (1)+(2): the password never survives in an error, raw or IMAP-quoted. */
class SecretScrubTest {

    private val password = JvmImapConnectionWireTest.FAKE_PASSWORD // p@ss"w\rd-FAKE: has both " and \
    private val email = JvmImapConnectionWireTest.EMAIL
    private val quoted = ImapUtf7.quoteMailbox(password) // what LOGIN puts on the wire

    private fun assertNoSecret(text: String) {
        assertFalse(text, text.contains(password))
        assertFalse(text, text.contains(quoted))
        assertFalse(text, text.contains(quoted.trim('"')))
        assertFalse(text, text.contains("w\\\\rd-FAKE"))
        assertFalse(text, text.contains("ss\\\"w"))
    }

    @Test
    fun stripSecretRemovesRawAndEscapedForms() {
        assertPrecondition()
        val text = "raw=$password quoted=$quoted inner=${quoted.trim('"')}"
        val out = stripSecret(text, password)
        assertNoSecret(out)
        assertTrue(out, out.contains("***"))
    }

    // Precondition: the escaped form really differs, so the test is not vacuous.
    private fun assertPrecondition() {
        assertTrue(quoted.trim('"') != password)
    }

    // NEGATIVE: unrelated text is left alone, and a null/empty secret changes nothing.
    @Test
    fun stripSecretLeavesOtherTextAlone() {
        assertEquals("nothing to see", stripSecret("nothing to see", password))
        assertEquals("a b", stripSecret("a b", null))
        assertEquals("a b", stripSecret("a b", ""))
    }

    // NEGATIVE: a BAD reply to LOGIN that echoes the quoted command line must not leak either form.
    @Test(timeout = 20_000)
    fun loginBadReplyEchoingTheQuotedLineLeaksNothing() {
        ScriptedImapServer { c ->
            c.send("* OK [CAPABILITY IMAP4rev1] ready")
            val line = c.readLine() ?: return@ScriptedImapServer
            val tag = line.substringBefore(' ')
            c.send("$tag BAD Parse error near: ${line.substringAfter(' ')}")
        }.use { server ->
            try {
                JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 5)
                fail("login must fail")
            } catch (e: MailTransportError) {
                assertEquals(401, e.status)
                assertTrue(e.message, e.message!!.contains("LOGIN") || e.message!!.contains("login"))
                assertNoSecret(e.message!!)
            }
        }
    }

    // NEGATIVE: a NO reply on a later command that contains the password is scrubbed by mapResponse.
    @Test(timeout = 20_000)
    fun noReplyContainingThePasswordIsScrubbed() {
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                conn.send("$tag NO [SERVERBUG] rejected $password / $quoted")
            }
        }.use { server ->
            val transport = ImapTransport(
                host = "127.0.0.1", port = server.port, email = email, password = password,
                connectionFactory = {
                    JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 5)
                },
            )
            try {
                transport.labelsCreate("WhatsApp")
                fail("must fail")
            } catch (e: MailTransportError) {
                assertNoSecret(e.message!!)
                assertTrue(e.message, e.message!!.contains("SERVERBUG"))
            }
        }
    }

    // NEGATIVE: a BAD reply on a later command (thrown, then mapped by mapException) is scrubbed too.
    @Test(timeout = 20_000)
    fun badReplyContainingThePasswordIsScrubbed() {
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                conn.send("$tag BAD syntax near $quoted")
            }
        }.use { server ->
            val transport = ImapTransport(
                host = "127.0.0.1", port = server.port, email = email, password = password,
                connectionFactory = {
                    JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 5)
                },
            )
            try {
                transport.labelsCreate("WhatsApp")
                fail("must fail")
            } catch (e: MailTransportError) {
                assertNoSecret(e.message!!)
            }
        }
    }
}
