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

/**
 * BUG-26: the user's Stop ended the work in progress (the live connection was cut, or Stop was seen
 * between two attempts). It is not a failure and not retryable: the run ends as stopped.
 */
class StopRequestedException : RuntimeException("stopped")

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

/** SEC-10: the status a provider throttle reply maps to; [insertWithBackoff] waits 30, 60, 120 s on it. */
const val THROTTLE_STATUS = 429

private val THROTTLE_WORD = Regex("""\b(RATE|LIMIT)\b""")

/**
 * SEC-10: true when an IMAP reply says the provider is slowing this client down: THROTTLED, a
 * [LIMIT] code, a rate limit, or "Too many simultaneous connections". A size refusal and a full
 * mailbox are NOT throttles (waiting cannot fix them), and "limit" next to SIZE / LARGE / BIG is a
 * size message. Whole words only, so "accurate" or "separate" never match RATE.
 */
fun isThrottleReply(text: String): Boolean {
    val upper = text.uppercase()
    if (isSizeRejection(upper) || "QUOTA" in upper) return false
    if ("THROTTL" in upper || "TOO MANY SIMULTANEOUS" in upper || "TOO MANY CONNECTIONS" in upper) return true
    if ("SIZE" in upper || "LARGE" in upper || "BIG" in upper) return false
    return THROTTLE_WORD.containsMatchIn(upper)
}

/** Mirrors `_status_for_imap_text`: maps IMAP response text to an HTTP-style status. */
fun statusForImapText(text: String): Int {
    val upper = text.uppercase()
    if (isSizeRejection(upper)) return 413
    if (isThrottleReply(upper)) return THROTTLE_STATUS
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

/**
 * Redacts [secret] out of [text], mirroring `_strip_secret`, and also its
 * IMAP quoted-string form (SEC-06): a server that echoes the LOGIN line back
 * in a BAD/NO reply shows the password with `\` and `"` backslash-escaped,
 * which the raw form alone would not match. The escaped form is scrubbed
 * first so a partial raw match cannot leave half of it behind.
 */
fun stripSecret(text: String, secret: String?): String {
    if (secret.isNullOrEmpty()) return text
    var out = text
    val escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"")
    if (escaped != secret && escaped in out) out = out.replace(escaped, "***")
    if (secret in out) out = out.replace(secret, "***")
    return out
}

/**
 * A header value that cannot be written without breaking out of its line.
 * Python's email package raises for the same inputs, which kills the whole
 * chat's push; Kotlin refuses the one message in the same place. The text
 * never carries the offending value: an attachment filename can be private.
 */
class UnsafeHeaderValueException(message: String) : IllegalArgumentException(message)
