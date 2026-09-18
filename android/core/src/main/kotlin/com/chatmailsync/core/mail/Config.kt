package com.chatmailsync.core.mail

/** Kotlin port of the handful of `src/config.py` constants `ImapTransport`/`checkConnection` need. */

data class ImapProviderPreset(val label: String, val host: String?, val port: Int)

val IMAP_PROVIDERS: Map<String, ImapProviderPreset> = linkedMapOf(
    "gmail" to ImapProviderPreset("Gmail", "imap.gmail.com", 993),
    "yahoo" to ImapProviderPreset("Yahoo", "imap.mail.yahoo.com", 993),
    "icloud" to ImapProviderPreset("iCloud", "imap.mail.me.com", 993),
    "aol" to ImapProviderPreset("AOL", "imap.aol.com", 993),
    "fastmail" to ImapProviderPreset("Fastmail", "imap.fastmail.com", 993),
    "custom" to ImapProviderPreset("Custom", null, 993),
)

const val LABEL_PARENT = "WhatsApp"
const val LABEL_MAX_LENGTH = 225

const val DEFAULT_MAX_MESSAGE_BYTES = 25_000_000L

val PROVIDER_MAX_MESSAGE_BYTES: Map<String, Long> = mapOf(
    "gmail" to 25_000_000L,
    "yahoo" to 25_000_000L,
    "aol" to 25_000_000L,
    "icloud" to 20_000_000L,
    "fastmail" to 70_000_000L,
)

/** Seconds; matches `config.MAIL_SOCKET_TIMEOUT`. */
const val MAIL_SOCKET_TIMEOUT_SECONDS = 180L
