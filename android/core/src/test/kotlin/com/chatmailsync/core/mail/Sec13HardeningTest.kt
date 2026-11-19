package com.chatmailsync.core.mail

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SEC-13 (F-11, F-14, F-15): each fix has a test that the wrong behaviour does NOT occur. */
class Sec13HardeningTest {

    private val email = JvmImapConnectionWireTest.EMAIL
    private val password = JvmImapConnectionWireTest.FAKE_PASSWORD

    private fun connectAs(server: ScriptedImapServer, host: String): JvmImapConnection =
        JvmImapConnection.connectVia(server.plainOpener(), host, server.port, email, password, 5)

    // F-14 NEGATIVE: a preset provider never gets to skip LOGIN with a PREAUTH greeting.
    @Test(timeout = 10_000)
    fun preauthIsRefusedForPresetHosts() {
        for (host in listOf("imap.mail.yahoo.com", "IMAP.GMAIL.COM", "imap.fastmail.com")) {
            ScriptedImapServer { c ->
                c.serve(greeting = "* PREAUTH [CAPABILITY IMAP4rev1] already in")
            }.use { server ->
                try {
                    connectAs(server, host)
                    fail("PREAUTH must be refused for $host")
                } catch (e: MailTransportError) {
                    assertEquals(503, e.status)
                    assertTrue(e.message, e.message!!.contains("pre-authenticated"))
                    assertFalse(e.message, e.message!!.contains(password))
                }
            }
        }
    }

    // F-14 positive control: a custom host keeps the old PREAUTH behaviour.
    @Test(timeout = 10_000)
    fun preauthStillWorksForACustomHost() {
        ScriptedImapServer { c ->
            c.serve(greeting = "* PREAUTH [CAPABILITY IMAP4rev1] already in")
        }.use { server ->
            val conn = connectAs(server, "mail.example.test")
            assertFalse(server.received, server.received.contains("LOGIN"))
            conn.logout()
        }
    }

    // F-15 NEGATIVE: a server that sends "+" twice must not make the client send the literal twice.
    @Test(timeout = 10_000)
    fun literalIsSentExactlyOnceEvenIfTheServerAsksTwice() {
        val marker = "UNIQUELITERALMARKER"
        val message = "Subject: x\r\n\r\n$marker\r\n".toByteArray(Charsets.ISO_8859_1)
        ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                if (cmd.startsWith("APPEND ")) {
                    conn.send("+ ready")
                    conn.send("+ ready again")
                    conn.readExactly(message.size)
                    conn.readLine() // the CRLF that ends the literal
                    conn.send("$tag OK APPEND completed")
                } else {
                    conn.send("$tag OK done")
                }
            }
        }.use { server ->
            val conn = connectAs(server, "mail.example.test")
            val result = conn.append("\"Box\"", null, null, message)
            assertEquals("OK", result.status)
            conn.logout()
            val count = Regex(marker).findAll(server.received).count()
            assertEquals("literal bytes on the wire", 1, count)
        }
    }

    // F-11 NEGATIVE: the database snapshot is copied in small pieces, never as one whole-file write.
    @Test
    fun snapshotIsStreamedInBoundedChunks() {
        val file = File.createTempFile("cms_f11_", ".bin")
        try {
            val data = ByteArray(1_000_000) { (it % 251).toByte() }
            file.writeBytes(data)
            val sizes = ArrayList<Int>()
            val sink = ByteArrayOutputStream()
            val recorder = object : OutputStream() {
                override fun write(b: Int) {
                    sizes.add(1)
                    sink.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    sizes.add(len)
                    sink.write(b, off, len)
                }
            }
            streamFileTo(recorder, file, chunkBytes = 64 * 1024)
            assertArrayEquals(data, sink.toByteArray())
            assertTrue("largest write ${sizes.max()}", sizes.max() <= 64 * 1024)
            assertTrue("number of writes ${sizes.size}", sizes.size >= 15)
        } finally {
            file.delete()
        }
    }
}
