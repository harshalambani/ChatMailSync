package com.chatmailsync.core.mail

/**
 * Kotlin mirror of the error-mapping surface in `src/mail_client.py`:
 * `MailTransportError`, `MessageTooLargeError`, `_status_for_imap_text`,
 * `_is_size_rejection`, `_strip_secret`, `is_too_large` (lines ~118-259).
 */
open class MailTransportError(
    message: String,
    val status: Int? = null,
) : Exception(message)

class MessageTooLargeError(message: String) : MailTransportError(message, 413)

/** Thrown by a fake/real IMAP connection to mirror `imaplib.IMAP4.error`. */
class ImapCommandError(message: String) : Exception(message)

/** Thrown by a fake/real IMAP connection to mirror `imaplib.IMAP4.abort`. */
class ImapAbortError(message: String) : Exception(message)

/**
 * Phrases that mean "too big" and cannot plausibly mean anything else.
 * Mirrors `_SIZE_REJECTION_MARKERS` exactly -- no bare "exceeds"/"limit",
 * both of which also appear in quota/rate messages that retrying smaller
 * would not fix.
 */
private val SIZE_REJECTION_MARKERS = listOf(
    "TOOBIG",
    "MESSAGE TOO LARGE",
    "MESSAGE TOO BIG",
    "MESSAGE SIZE EXCEEDS",
    "SIZE LIMIT EXCEEDED",
    "MAXIMUM MESSAGE SIZE",
)

/** Mirrors `_is_size_rejection`. */
fun isSizeRejection(text: String): Boolean {
    val upper = text.uppercase()
    return SIZE_REJECTION_MARKERS.any { it in upper }
}

/** Mirrors `_status_for_imap_text`: maps IMAP response text to an HTTP-style status. */
fun statusForImapText(text: String): Int {
    val upper = text.uppercase()
    if (isSizeRejection(upper)) return 413
    if (listOf("SERVERBUG", "UNAVAILABLE", "INUSE").any { it in upper }) return 503
    if ("OVERQUOTA" in upper) return 403
    if (listOf("AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED", "PERMISSIONDENIED").any { it in upper }) return 401
    if ("TRYCREATE" in upper) return 400
    return 400
}

/** Mirrors `_transport_error`: picks the right exception class for a status. */
fun transportError(message: String, status: Int?): MailTransportError =
    if (status == 413) MessageTooLargeError(message) else MailTransportError(message, status)

/**
 * Mirrors `is_too_large`: true when [exc] is a provider refusing a message
 * purely for its size -- covers both an IMAP [TOOBIG]-style response mapped
 * to 413, and any [MailTransportError] whose message itself still carries a
 * size-rejection marker.
 */
fun isTooLarge(exc: Throwable): Boolean {
    if (exc is MessageTooLargeError) return true
    if (exc is MailTransportError) {
        return exc.status == 413 || isSizeRejection(exc.message ?: "")
    }
    return false
}

/** Redacts [secret] out of [text], mirroring `_strip_secret`. */
fun stripSecret(text: String, secret: String?): String {
    if (secret.isNullOrEmpty()) return text
    if (secret !in text) return text
    return text.replace(secret, "***")
}
