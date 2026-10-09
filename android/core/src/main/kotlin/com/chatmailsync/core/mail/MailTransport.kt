package com.chatmailsync.core.mail

/**
 * Kotlin mirror of Python's `MailTransport` Protocol (`src/mail_client.py`
 * lines ~83-88) plus the one optional method the callers probe for,
 * `owns_label_id`.
 *
 * The signatures are Kotlin-shaped rather than dict-shaped: the Python
 * transport takes `{"raw": <base64url>, "labelIds": [label]}` and the IMAP
 * implementation immediately decodes it again, so here the raw RFC 822 bytes
 * and the folder go straight through. Nothing about the wire changes.
 *
 * [ImapTransport] is the production implementation. Anything else (a test
 * double) has to behave like it: NO comes back as data inside the connection,
 * BAD throws, and every failure reaches the caller as a [MailTransportError].
 * `MailTransportContractTest` runs one script against a double and the real
 * class so they cannot drift.
 */
interface MailTransport {
    /**
     * Largest message the server will take, in bytes. Python reads this with
     * `getattr(transport, "max_message_bytes", DEFAULT_MAX_MESSAGE_BYTES)`; a
     * transport that cannot tell inherits the default.
     */
    val maxMessageBytes: Long
        get() = DEFAULT_MAX_MESSAGE_BYTES

    fun labelsList(): List<ImapTransport.Label>

    /** Creates the label/folder [name] and returns its id. An existing one is success. */
    fun labelsCreate(name: String): String

    /** Files [rawMessageBytes] under [folder]. A refusal throws [MailTransportError]. */
    fun messagesInsert(rawMessageBytes: ByteArray, folder: String, threadId: String? = null): ImapTransport.InsertResult

    /**
     * Mirrors the optional `owns_label_id`: can this stored id still be handed
     * to this transport? Python treats a missing method as "assume yes", which
     * is the default here.
     */
    fun ownsLabelId(labelId: String, displayName: String): Boolean = true
}

/**
 * Mirrors `_label_id_is_usable`. A stored label id belongs to the backend that
 * minted it; one from another backend (or a blank one) must NOT be passed
 * through as a folder name, or APPEND files into a folder that is not the
 * chat's. Blank is never usable.
 */
fun labelIdIsUsable(transport: MailTransport, labelId: String?, displayName: String): Boolean {
    if (labelId.isNullOrEmpty()) return false
    return transport.ownsLabelId(labelId, displayName)
}

/**
 * Mirrors `mailbox_folder_for`: the folder a chat's mail is filed under, with
 * the same sanitising as the write path.
 */
fun mailboxFolderFor(displayName: String): String = fullLabelName(displayName)

/**
 * Mirrors `get_or_create_label`: returns the id of `WhatsApp/<display name>`,
 * creating it (and the `WhatsApp` parent) when absent. A later entry with the
 * same name wins, like the dict comprehension in Python.
 */
fun getOrCreateLabel(transport: MailTransport, displayName: String): String {
    val target = fullLabelName(displayName)

    val existing = LinkedHashMap<String, String>()
    for (label in transport.labelsList()) existing[label.name] = label.id

    if (LABEL_PARENT !in existing) {
        existing[LABEL_PARENT] = transport.labelsCreate(LABEL_PARENT)
    }

    existing[target]?.let { return it }
    return transport.labelsCreate(target)
}
