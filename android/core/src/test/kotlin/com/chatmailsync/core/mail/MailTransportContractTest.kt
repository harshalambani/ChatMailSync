package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The BUG-03 / ST-02 lesson applied to [FakeMailTransport]: one script runs
 * against the fake and against the real [ImapTransport] over a scripted IMAP
 * server (real socket, real [JvmImapConnection]); every outcome must match.
 * Also the label-helper tests (`mailboxFolderFor`, `getOrCreateLabel`,
 * `labelIdIsUsable`), which are what the transport is for.
 */
class MailTransportContractTest {

    private val fakeEmail = "meera.iyer@example.com"
    private val lockedFolder = "WhatsApp/Locked"

    /** A small stateful IMAP server: folders, CREATE that refuses duplicates, APPEND that refuses the locked folder. */
    private fun statefulServer(): ScriptedImapServer {
        val folders = LinkedHashSet<String>()
        return ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                when {
                    cmd.startsWith("LIST ") -> {
                        for (f in folders) conn.send("* LIST (\\HasNoChildren) \"/\" \"$f\"")
                        conn.send("$tag OK LIST completed")
                    }
                    cmd.startsWith("CREATE ") -> {
                        val name = cmd.removePrefix("CREATE ").trim('"')
                        if (folders.add(name)) {
                            conn.send("$tag OK Completed")
                        } else {
                            conn.send("$tag NO [ALREADYEXISTS] Mailbox exists")
                        }
                    }
                    cmd.startsWith("SUBSCRIBE ") -> conn.send("$tag OK Completed")
                    cmd.startsWith("APPEND ") -> {
                        val size = Regex("\\{(\\d+)}$").find(cmd)!!.groupValues[1].toInt()
                        val folder = Regex("^APPEND (\"[^\"]*\"|\\S+)").find(cmd)!!.groupValues[1].trim('"')
                        conn.send("+ Ready for literal data")
                        conn.readExactly(size)
                        conn.readLine()
                        if (folder == lockedFolder) {
                            conn.send("$tag NO [PERMISSIONDENIED] folder is read-only")
                        } else {
                            conn.send("$tag OK Append completed")
                        }
                    }
                    else -> conn.send("$tag OK done")
                }
            }
        }
    }

    private fun realTransport(server: ScriptedImapServer) = ImapTransport(
        host = "127.0.0.1",
        port = server.port,
        email = fakeEmail,
        password = "pw-FAKE",
        connectionFactory = {
            JvmImapConnection.connectVia(server.plainOpener(), "127.0.0.1", server.port, fakeEmail, "pw-FAKE", 5)
        },
    )

    private fun raw(messageId: String) = "Message-ID: $messageId\nSubject: x\n\nbody\n".toByteArray()

    private fun attempt(block: () -> Any?): String = try {
        val r = block()
        "OK $r"
    } catch (e: MailTransportError) {
        // The message differs in server wording; the class and status are the contract.
        "ERR ${e.javaClass.simpleName} ${e.status} ${e.message!!.substringBefore(':')}"
    }

    /** The shared script. Every line is an outcome that must be identical on both sides. */
    private fun script(t: MailTransport): List<String> {
        val out = mutableListOf<String>()
        out.add(attempt { t.labelsList().map { it.name } })
        out.add(attempt { t.labelsCreate("WhatsApp") })
        // NO [ALREADYEXISTS] is success, not a failure.
        out.add(attempt { t.labelsCreate("WhatsApp") })
        out.add(attempt { t.labelsCreate("WhatsApp/Meera Iyer") })
        out.add(attempt { t.labelsList().map { it.name } })
        out.add(attempt { t.messagesInsert(raw("<m1@local>"), "WhatsApp/Meera Iyer").let { "${it.id}|${it.threadId}" } })
        out.add(attempt { t.messagesInsert(raw("<m2@local>"), "WhatsApp/Meera Iyer", "<anchor@local>").let { "${it.id}|${it.threadId}" } })
        out.add(attempt { t.messagesInsert(raw("<m3@local>"), "WhatsApp/Meera Iyer", "").let { "${it.id}|${it.threadId}" } })
        // A real NO is a failure with the same status.
        out.add(attempt { t.messagesInsert(raw("<m4@local>"), lockedFolder) })
        out.add(attempt { t.ownsLabelId("WhatsApp/Meera Iyer", "Meera Iyer") })
        out.add(attempt { t.ownsLabelId("Label_123", "Meera Iyer") })
        out.add(attempt { t.ownsLabelId("", "Meera Iyer") })
        return out
    }

    @Test(timeout = 30_000)
    fun theFakeAndTheRealTransportAgreeOnOneScript() {
        val fakeOutcome = script(FakeMailTransport().apply { lockedFolders.add(lockedFolder) })
        val realOutcome = statefulServer().use { server ->
            val t = realTransport(server)
            val r = script(t)
            t.close()
            assertNull(server.failure)
            r
        }
        assertEquals(realOutcome, fakeOutcome)
        // And the script really exercised the interesting cases.
        assertTrue(fakeOutcome.toString(), fakeOutcome.any { it.startsWith("ERR MailTransportError 401") })
        assertEquals("OK WhatsApp", fakeOutcome[2])
    }

    // ---- label helpers

    @Test
    fun mailboxFolderForSanitisesLikeTheWritePath() {
        assertEquals("WhatsApp/Meera Iyer", mailboxFolderFor("Meera Iyer"))
        assertEquals(fullLabelName("Rohan/Mehta"), mailboxFolderFor("Rohan/Mehta"))
        assertFalse("a slash in a chat name must not add a folder level", mailboxFolderFor("Rohan/Mehta").removePrefix("WhatsApp/").contains('/'))
    }

    @Test
    fun getOrCreateLabelCreatesParentThenChildOnAFreshMailbox() {
        val t = FakeMailTransport()
        assertEquals("WhatsApp/Meera Iyer", getOrCreateLabel(t, "Meera Iyer"))
        assertEquals(listOf("WhatsApp", "WhatsApp/Meera Iyer"), t.createCalls)
    }

    @Test
    fun getOrCreateLabelCreatesNothingWhenBothExist() {
        val t = FakeMailTransport()
        t.folders.addAll(listOf("WhatsApp", "WhatsApp/Meera Iyer"))
        assertEquals("WhatsApp/Meera Iyer", getOrCreateLabel(t, "Meera Iyer"))
        assertTrue("nothing may be created twice", t.createCalls.isEmpty())
    }

    @Test
    fun getOrCreateLabelCreatesOnlyTheChildWhenTheParentExists() {
        val t = FakeMailTransport()
        t.folders.add("WhatsApp")
        getOrCreateLabel(t, "Rohan Mehta")
        assertEquals(listOf("WhatsApp/Rohan Mehta"), t.createCalls)
    }

    @Test
    fun aLabelIdFromAnotherBackendOrBlankIsNotUsable() {
        val t = FakeMailTransport()
        assertFalse(labelIdIsUsable(t, "Label_5512345", "Meera Iyer"))
        assertFalse(labelIdIsUsable(t, "WhatsApp/Rohan Mehta", "Meera Iyer"))
        assertFalse(labelIdIsUsable(t, "", "Meera Iyer"))
        assertFalse(labelIdIsUsable(t, null, "Meera Iyer"))
        assertTrue(labelIdIsUsable(t, "WhatsApp/Meera Iyer", "Meera Iyer"))
    }

    @Test
    fun aTransportThatCannotTellAssumesTheIdIsUsable() {
        val unknown = object : MailTransport {
            override fun labelsList() = emptyList<ImapTransport.Label>()
            override fun labelsCreate(name: String) = name
            override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult =
                throw AssertionError("not used")
        }
        assertTrue(labelIdIsUsable(unknown, "anything", "Meera Iyer"))
        assertFalse("but never a blank one", labelIdIsUsable(unknown, "", "Meera Iyer"))
    }

    @Test
    fun aRealNoOnCreateIsAFailureThroughGetOrCreateLabel() {
        val server = ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                when {
                    cmd.startsWith("LIST ") -> conn.send("$tag OK LIST completed")
                    cmd.startsWith("CREATE ") -> conn.send("$tag NO [PERMISSIONDENIED] you may not create folders")
                    else -> conn.send("$tag OK done")
                }
            }
        }
        server.use {
            val t = realTransport(it)
            try {
                getOrCreateLabel(t, "Meera Iyer")
                fail("a real NO must fail")
            } catch (e: MailTransportError) {
                assertEquals(401, e.status)
            }
        }
    }

    @Test
    fun anExistingFolderOnCreateIsNotAFailureThroughGetOrCreateLabel() {
        val server = ScriptedImapServer { c ->
            c.serve { conn, tag, cmd ->
                when {
                    cmd.startsWith("LIST ") -> conn.send("$tag OK LIST completed")
                    cmd.startsWith("CREATE ") -> conn.send("$tag NO [ALREADYEXISTS] Mailbox exists")
                    else -> conn.send("$tag OK done")
                }
            }
        }
        server.use {
            assertEquals("WhatsApp/Meera Iyer", getOrCreateLabel(realTransport(it), "Meera Iyer"))
        }
    }
}
