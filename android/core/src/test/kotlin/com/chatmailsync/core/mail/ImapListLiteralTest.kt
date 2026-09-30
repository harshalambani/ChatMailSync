package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PAR-04: IMAP `{n}` literals in LIST replies, through the REAL
 * [JvmImapConnection] over a [ScriptedImapServer]. The literal is read through
 * the same [ImapLineReader] as every line, so the SEC-03 caps apply to it.
 */
class ImapListLiteralTest {

    private val email = JvmImapConnectionWireTest.EMAIL
    private val password = JvmImapConnectionWireTest.FAKE_PASSWORD

    private fun connect(server: ScriptedImapServer): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, email, password, 10)

    private fun transport(server: ScriptedImapServer) = ImapTransport(
        host = "127.0.0.1",
        port = server.port,
        email = email,
        password = password,
        connectionFactory = { connect(server) },
    )

    private fun ScriptedImapServer.Conn.sendLiteralList(tag: String, name: ByteArray, tail: String = "") {
        sendBytes("* LIST (\\HasNoChildren) \"/\" {${name.size}}\r\n".toByteArray(Charsets.ISO_8859_1))
        sendBytes(name)
        sendBytes("$tail\r\n".toByteArray(Charsets.ISO_8859_1))
        send("$tag OK LIST completed")
    }

    // A literal folder name yields the name, not the text "{n}".
    @Test(timeout = 30_000)
    fun aLiteralFolderNameYieldsTheNameNotTheLengthMarker() {
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ -> conn.sendLiteralList(tag, "WhatsApp/Meera Iyer".toByteArray(Charsets.UTF_8)) }
        }.use { server ->
            val labels = transport(server).labelsList()
            assertEquals(listOf("WhatsApp/Meera Iyer"), labels.map { it.name })
            assertFalse(labels.any { it.name.contains("{") })
        }
    }

    // The literal's bytes are decoded as UTF-8: Devanagari survives, it is not one char per byte.
    @Test(timeout = 30_000)
    fun aLiteralFolderNameIsDecodedAsUtf8() {
        val name = "WhatsApp/मीरा"
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ -> conn.sendLiteralList(tag, name.toByteArray(Charsets.UTF_8)) }
        }.use { server ->
            assertEquals(listOf(name), transport(server).labelsList().map { it.name })
        }
    }

    // The same for a quoted-string name that carries raw UTF-8 bytes (no literal).
    @Test(timeout = 30_000)
    fun aQuotedFolderNameWithRawUtf8BytesIsDecodedAsUtf8() {
        val name = "WhatsApp/Café मीरा"
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ ->
                conn.sendBytes("* LIST (\\HasNoChildren) \"/\" \"$name\"\r\n".toByteArray(Charsets.UTF_8))
                conn.send("$tag OK LIST completed")
            }
        }.use { server ->
            assertEquals(listOf(name), transport(server).labelsList().map { it.name })
        }
    }

    // Plain replies still carry no literals, and a literal's tail line is not lost.
    @Test(timeout = 30_000)
    fun aPlainListReplyHasNoLiteralsAndANonBlankTailIsKept() {
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ ->
                conn.send("* LIST (\\HasNoChildren) \"/\" \"WhatsApp/A\"")
                conn.sendLiteralList(tag, "WhatsApp/B".toByteArray(Charsets.UTF_8), tail = " extra")
            }
        }.use { server ->
            val result = connect(server).list("\"\"", "*")
            assertEquals(setOf(1), result.literals.keys)
            assertEquals("WhatsApp/B", result.literals[1])
            assertEquals(3, result.data.size) // line, head, tail
            assertEquals("extra", result.data[2])
        }
    }

    // NEGATIVE: a literal larger than MAX_LINE_BYTES aborts, and the client never waits for (or allocates) the body.
    @Test(timeout = 30_000)
    fun aLiteralLargerThanTheLineCapIsAnAbortWithoutReadingIt() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ ->
                // Only the announcement is sent. If the client tried to read 2,000,000 bytes it would block here.
                conn.send("* LIST (\\HasNoChildren) \"/\" {${MAX_LINE_BYTES + 1}}")
                conn.readLine()
            }
        }.use { server ->
            val conn = connect(server)
            try {
                conn.list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("literal longer than"))
            }
        }
    }

    // NEGATIVE: an absurd length that does not even fit an Int is an abort too.
    @Test(timeout = 30_000)
    fun aLiteralLengthBeyondIntRangeIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ ->
                conn.send("* LIST (\\HasNoChildren) \"/\" {99999999999999999999}")
                conn.readLine()
            }
        }.use { server ->
            try {
                connect(server).list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("literal longer than"))
            }
        }
    }

    // NEGATIVE: a lying literal length (says 100, sends 5, then closes) ends in ImapAbortError.
    @Test(timeout = 30_000)
    fun aLyingLiteralLengthThenEofIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ ->
                conn.sendBytes("* LIST (\\HasNoChildren) \"/\" {100}\r\n".toByteArray(Charsets.ISO_8859_1))
                conn.sendBytes("short".toByteArray(Charsets.ISO_8859_1))
                conn.close()
            }
        }.use { server ->
            try {
                connect(server).list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("inside a literal"))
            }
        }
    }

    // NEGATIVE: EOF immediately after the announcement, inside a literal of zero bytes received.
    @Test(timeout = 30_000)
    fun eofRightAfterTheLiteralAnnouncementIsAnAbort() {
        ScriptedImapServer { c ->
            c.serve { conn, _, _ ->
                conn.sendBytes("* LIST (\\HasNoChildren) \"/\" {10}\r\n".toByteArray(Charsets.ISO_8859_1))
                conn.close()
            }
        }.use { server ->
            try {
                connect(server).list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("inside a literal"))
            }
        }
    }

    // NEGATIVE: many legal-size literals cannot add up to an unbounded amount of memory for one command.
    @Test(timeout = 60_000)
    fun manyLiteralsAddingUpPastTheCommandCapAreAnAbort() {
        val each = 1_000_000
        val count = (MAX_LITERAL_BYTES_PER_COMMAND / each).toInt() + 2
        ScriptedImapServer { c ->
            c.serve { conn, _, _ ->
                repeat(count) {
                    conn.sendBytes("* LIST (\\HasNoChildren) \"/\" {$each}\r\n".toByteArray(Charsets.ISO_8859_1))
                    conn.sendRaw(each)
                    conn.sendBytes("\r\n".toByteArray(Charsets.ISO_8859_1))
                }
            }
        }.use { server ->
            try {
                connect(server).list("\"\"", "*")
                fail("expected an abort")
            } catch (e: ImapAbortError) {
                assertTrue(e.message, e.message!!.contains("literal bytes"))
            }
        }
    }

    // Positive control: a literal right at the cap is accepted.
    @Test(timeout = 30_000)
    fun aLiteralExactlyAtTheLineCapIsAccepted() {
        ScriptedImapServer { c ->
            c.serve { conn, tag, _ -> conn.sendLiteralList(tag, ByteArray(MAX_LINE_BYTES) { 'x'.code.toByte() }) }
        }.use { server ->
            val result = connect(server).list("\"\"", "*")
            assertEquals("OK", result.status)
            assertEquals(MAX_LINE_BYTES, result.literals[0]!!.length)
        }
    }
}
