package com.chatmailsync.core.mail

import java.time.OffsetDateTime

/** One untagged/tagged IMAP response: `status` is "OK", "NO", or "BAD"; `data` is the response's data lines. */
data class ImapResult(val status: String, val data: List<String?>)

/**
 * The slice of `imaplib.IMAP4`'s surface `ImapTransport` actually calls
 * (`list`/`create`/`subscribe`/`append`/`logout`/`capabilities`), abstracted
 * so tests can inject an in-process fake (see `FakeImapConnection` under
 * `src/test`) instead of touching a real socket. [JvmImapConnection] is the
 * production implementation, built on `javax.net.ssl` only.
 *
 * Implementations throw [ImapAbortError] to mirror `imaplib.IMAP4.abort`
 * (dropped/aborted connection -- `ImapTransport` reconnects once and
 * retries), [ImapCommandError] to mirror `imaplib.IMAP4.error` (a self-raised
 * protocol error, not retried), or let network exceptions
 * ([java.io.IOException] / [java.net.SocketTimeoutException]) propagate
 * directly.
 */
interface ImapConnection {
    /** Raw capability strings from the greeting/login (e.g. "APPENDLIMIT=35651584"). */
    val capabilities: List<String>

    fun list(reference: String, pattern: String): ImapResult
    fun create(mailboxWireArg: String): ImapResult
    fun subscribe(mailboxWireArg: String): ImapResult
    fun append(mailboxWireArg: String, flags: String?, internalDate: OffsetDateTime?, message: ByteArray): ImapResult
    fun logout()
}
