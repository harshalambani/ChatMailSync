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

    /**
     * BUG-05 / D29: renames the folder [oldName] to [newName], returning the new id. A refusal
     * throws [MailTransportError]; the caller then keeps the old folder. The default refuses.
     */
    fun labelsRename(oldName: String, newName: String): String =
        throw MailTransportError("RENAME is not supported by this transport")

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
 * BUG-05 / D29: the folder a push goes to. A stored folder that is the chat's own for [displayName]
 * is used as it is. A stored folder under the app's parent that is NOT (the chat was renamed) is
 * renamed to the new name; if the server refuses, the chat keeps filing into the old folder and
 * no mail is lost or split. Only a stored folder that no longer exists (or an id from another
 * backend, or none) gets a folder created, as before.
 *
 * [onRenamed] is told the outcome of a rename attempt (true = renamed, false = kept the old
 * folder), so the caller can report it without a name in the text.
 */
fun folderForPush(
    transport: MailTransport,
    labelId: String?,
    displayName: String,
    onRenamed: ((Boolean) -> Unit)? = null,
    /** False for the shared legacy record, whose stored folder may belong to a different chat. */
    allowRename: Boolean = true,
): String {
    if (labelIdIsUsable(transport, labelId, displayName)) return labelId!!
    val stored = labelId.orEmpty()
    if (allowRename && stored.startsWith("$LABEL_PARENT/")) {
        val target = fullLabelName(displayName)
        val renamed = try {
            transport.labelsRename(stored, target)
        } catch (_: MailTransportError) {
            null
        }
        if (renamed != null) {
            onRenamed?.invoke(true)
            return renamed
        }
        // Refused: keep the old folder if it is still there. Never create a second one while the
        // first holds the chat's history.
        val stillThere = try {
            transport.labelsList().any { it.name == stored }
        } catch (_: MailTransportError) {
            true
        }
        if (stillThere) {
            onRenamed?.invoke(false)
            return stored
        }
    }
    return getOrCreateLabel(transport, displayName)
}

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
