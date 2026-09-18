package com.chatmailsync.core.mail

import java.time.OffsetDateTime

/**
 * In-process scripted test double for [ImapConnection] -- the Kotlin
 * equivalent of `tests/test_imap_transport.py`'s `FakeImapConn`. Records
 * every call it receives and returns pre-programmed [ImapResult]s, so tests
 * assert on the *exact* wire arguments handed to it (mailbox quoting, mUTF-7
 * encoding, flags, internaldate) rather than on `ImapTransport`'s internals.
 *
 * No sockets, no threads, no real IMAP server anywhere in this file or in
 * any test that uses it.
 */
class FakeImapConnection(override val capabilities: List<String> = emptyList()) : ImapConnection {
    sealed class Call {
        data class ListCall(val reference: String, val pattern: String) : Call()
        data class CreateCall(val mailboxWireArg: String) : Call()
        data class SubscribeCall(val mailboxWireArg: String) : Call()
        data class AppendCall(
            val mailboxWireArg: String,
            val flags: String?,
            val internalDate: OffsetDateTime?,
            val message: ByteArray,
        ) : Call()
        object Logout : Call()
    }

    val calls = mutableListOf<Call>()

    var listResponse = ImapResult("OK", listOf("(\\HasNoChildren) \"/\" \"INBOX\""))
    var createResponse = ImapResult("OK", listOf("Completed"))
    var subscribeResponse = ImapResult("OK", listOf("Completed"))
    var appendResponse = ImapResult("OK", listOf("Completed"))

    /** Set to make the *next* call to that method throw instead of returning its canned response. Key: "list"/"create"/"subscribe"/"append". */
    val raiseOn = mutableMapOf<String, Throwable>()

    private fun <T> respond(name: String, response: T): T {
        val exc = raiseOn.remove(name)
        if (exc != null) throw exc
        return response
    }

    override fun list(reference: String, pattern: String): ImapResult {
        calls.add(Call.ListCall(reference, pattern))
        return respond("list", listResponse)
    }

    override fun create(mailboxWireArg: String): ImapResult {
        calls.add(Call.CreateCall(mailboxWireArg))
        return respond("create", createResponse)
    }

    override fun subscribe(mailboxWireArg: String): ImapResult {
        calls.add(Call.SubscribeCall(mailboxWireArg))
        return respond("subscribe", subscribeResponse)
    }

    override fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray): ImapResult {
        calls.add(Call.AppendCall(mailboxWireArg, flags, internalDate, message))
        return respond("append", appendResponse)
    }

    override fun logout() {
        calls.add(Call.Logout)
    }
}
