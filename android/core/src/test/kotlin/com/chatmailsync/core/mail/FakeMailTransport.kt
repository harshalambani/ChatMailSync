package com.chatmailsync.core.mail

/**
 * Test double for [MailTransport]. It must behave like [ImapTransport]:
 * creating a folder that exists is success, a refusal reaches the caller as a
 * [MailTransportError] with the same status the real class derives, and an
 * insert without a server-assigned id answers with the Message-ID.
 * `MailTransportContractTest` runs one script against this class and the real
 * [ImapTransport] over a scripted server so the two cannot drift.
 *
 * Extra behaviour for the push tests: [insertOutcomes] queues what the next
 * inserts do (null = succeed, an error = throw it), and [lockedFolders] are
 * folders the server refuses with a permission error.
 */
class FakeMailTransport(
    override val maxMessageBytes: Long = DEFAULT_MAX_MESSAGE_BYTES,
    private val ownedIdFor: ((String, String) -> Boolean)? = null,
) : MailTransport {

    class Inserted(val folder: String, val bytes: ByteArray, val threadId: String?, val id: String)

    val folders = LinkedHashSet<String>()
    val inserted = mutableListOf<Inserted>()
    val createCalls = mutableListOf<String>()
    var insertAttempts = 0
        private set
    var listCalls = 0
        private set
    val lockedFolders = mutableSetOf<String>()
    val insertOutcomes = ArrayDeque<MailTransportError?>()

    override fun labelsList(): List<ImapTransport.Label> {
        listCalls++
        return folders.map { ImapTransport.Label(it, it) }
    }

    override fun labelsCreate(name: String): String {
        createCalls.add(name)
        folders.add(name)
        return name
    }

    override fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String?): ImapTransport.InsertResult {
        insertAttempts++
        val queued = if (insertOutcomes.isEmpty()) null else insertOutcomes.removeFirst()
        if (queued != null) throw queued
        if (folder in lockedFolders) {
            throw transportError("APPEND failed (NO): [PERMISSIONDENIED] folder is read-only", 401)
        }
        val headers = MessageHeaders.parse(normalizeCrlf(rawMessageBytes))
        val messageId = headers["Message-ID"]?.takeIf { it.isNotEmpty() } ?: MimeBuilder.newMessageId()
        inserted.add(Inserted(folder, rawMessageBytes, threadId, messageId))
        return ImapTransport.InsertResult(
            id = messageId,
            threadId = threadId?.takeIf { it.isNotEmpty() } ?: messageId,
        )
    }

    override fun ownsLabelId(labelId: String, displayName: String): Boolean =
        ownedIdFor?.invoke(labelId, displayName) ?: (labelId == fullLabelName(displayName))
}
