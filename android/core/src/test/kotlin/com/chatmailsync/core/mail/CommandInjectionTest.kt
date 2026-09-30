package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SEC-02: a control character in a credential or a folder name must be
 * refused BEFORE any byte reaches the server, and the error text must never
 * contain the rejected value or any part of it.
 */
class CommandInjectionTest {

    private val email = JvmImapConnectionWireTest.EMAIL
    private val goodPassword = JvmImapConnectionWireTest.FAKE_PASSWORD

    private fun assertRefusedBeforeAnyByte(email: String, password: String, vararg mustNotAppear: String) {
        ScriptedImapServer { it.serve() }.use { server ->
            try {
                JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 5)
                fail("must be refused")
            } catch (e: MailTransportError) {
                assertEquals(e.message, 400, e.status)
                for (part in mustNotAppear) assertFalse(e.message, e.message!!.contains(part))
            }
            assertEquals("not one byte may reach the server", "", server.received)
        }
    }

    // NEGATIVE: CR/LF in the password would end the LOGIN line and start a second command.
    @Test(timeout = 10_000)
    fun passwordWithCrLfIsRefusedBeforeAnyByteAndIsNotEchoed() {
        assertRefusedBeforeAnyByte(email, "hunter2-FAKE\r\nX999 DELETE INBOX", "hunter2", "DELETE", "X999")
    }

    // NEGATIVE: the same for the email.
    @Test(timeout = 10_000)
    fun emailWithCrLfIsRefusedBeforeAnyByteAndIsNotEchoed() {
        assertRefusedBeforeAnyByte("meera\r\nX999 DELETE INBOX@example.com", goodPassword, "meera", "DELETE", "X999")
    }

    // NEGATIVE: NUL and other control characters in a credential.
    @Test(timeout = 10_000)
    fun nulAndOtherControlCharactersInACredentialAreRefused() {
        assertRefusedBeforeAnyByte(email, "abc\u0000def-FAKE", "abc", "def")
        assertRefusedBeforeAnyByte(email, "abc\u0007def-FAKE", "abc", "def")
        assertRefusedBeforeAnyByte(email, "abc\u007Fdef-FAKE", "abc", "def")
    }

    // NEGATIVE: a non-ASCII password is refused with a clear message, and no part of it is in the text.
    @Test(timeout = 10_000)
    fun nonAsciiPasswordIsRefusedWithAClearMessage() {
        ScriptedImapServer { it.serve() }.use { server ->
            try {
                JvmImapConnection.connectVia(
                    server.plainOpener(), "127.0.0.1", server.port, email, "zqäx-FAKE-中", 5,
                )
                fail("must be refused")
            } catch (e: MailTransportError) {
                assertEquals(400, e.status)
                assertTrue(e.message, e.message!!.contains("plain ASCII"))
                assertFalse(e.message, e.message!!.contains("zq"))
                assertFalse(e.message, e.message!!.contains("FAKE"))
            }
            assertEquals("", server.received)
        }
    }

    // Positive control: quotes and backslashes are legal in a password and still get through, escaped.
    @Test(timeout = 10_000)
    fun quoteAndBackslashPasswordStillLogsIn() {
        ScriptedImapServer { it.serve() }.use { server ->
            val conn = JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, goodPassword, 5)
            assertTrue(server.received, server.received.contains("LOGIN \"$email\" \"p@ss\\\"w\\\\rd-FAKE\""))
            conn.logout()
        }
    }

    // NEGATIVE: a folder name containing NUL (or CR/LF) is refused, and no CREATE is sent.
    @Test(timeout = 10_000)
    fun folderNameWithControlCharactersIsRefusedAndNothingIsSent() {
        for (bad in listOf("Bad\u0000Name", "Bad\r\nX1 DELETE INBOX", "Bad\nName", "Bad\u007FName")) {
            ScriptedImapServer { it.serve() }.use { server ->
                val transport = ImapTransport(
                    host = "127.0.0.1", port = server.port, email = email, password = goodPassword,
                    connectionFactory = {
                        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, goodPassword, 5)
                    },
                )
                try {
                    transport.labelsCreate(bad)
                    fail("must be refused")
                } catch (e: MailTransportError) {
                    assertEquals(400, e.status)
                    assertFalse(e.message, e.message!!.contains("DELETE"))
                    assertFalse(e.message, e.message!!.contains("Bad"))
                }
                assertFalse(server.received, server.received.contains("CREATE"))
                assertFalse(server.received, server.received.contains("DELETE"))
            }
        }
    }

    // Unit level: quoteMailbox refuses every control character, keeps ordinary names.
    @Test
    fun quoteMailboxRefusesEveryControlCharacterButKeepsNormalNames() {
        for (code in (0x00..0x1F) + 0x7F) {
            try {
                ImapUtf7.quoteMailbox("a${code.toChar()}b")
                fail("U+%04X must be refused".format(code))
            } catch (e: MailTransportError) {
                assertEquals(400, e.status)
            }
        }
        assertEquals("\"WhatsApp/Rohan Mehta\"", ImapUtf7.quoteMailbox("WhatsApp/Rohan Mehta"))
        assertEquals("\"a\\\"b\\\\c\"", ImapUtf7.quoteMailbox("a\"b\\c"))
    }
}
