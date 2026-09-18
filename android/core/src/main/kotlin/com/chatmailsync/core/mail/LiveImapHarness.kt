package com.chatmailsync.core.mail

/**
 * Interactive, human-run harness for exercising [ImapTransport] /
 * [checkConnection] against a *real* mailbox -- the Gradle `liveImapHarness`
 * task (see `android/core/build.gradle.kts`), not part of `test` and never
 * run by CI. It exists because this PR's automated suite deliberately never
 * touches the network: the fake-IMAP-server JUnit tests prove the protocol
 * logic, and a human runs this by hand, later, against Yahoo (never Gmail --
 * see the standing rule in the project notes) with a real app password
 * typed at the prompt below.
 *
 * Deliberately does not read a password from a file or an environment
 * variable -- the whole point is that nothing here can accidentally pick up
 * a stored credential and log it, print it, or ship it in a commit.
 *
 *     ./gradlew :core:liveImapHarness --console=plain
 *
 * `--console=plain` keeps Gradle from swallowing the prompts.
 */
fun main() {
    println("Chat Mail Sync -- live IMAP harness (Kotlin :core spike)")
    println("This talks to a REAL mailbox over a REAL network connection.")
    println("Yahoo only -- never Gmail (see the project's standing test-account rule).")
    println()

    print("IMAP host [imap.mail.yahoo.com]: ")
    val hostInput = readlnOrNull()?.trim().orEmpty()
    val host = hostInput.ifEmpty { "imap.mail.yahoo.com" }

    print("IMAP port [993]: ")
    val portInput = readlnOrNull()?.trim().orEmpty()
    val port = portInput.toIntOrNull() ?: 993

    print("Email address: ")
    val email = readlnOrNull()?.trim().orEmpty()

    print("App password (typed in the clear -- this terminal is not echo-safe on every platform): ")
    val password = readlnOrNull()?.trim().orEmpty()

    if (email.isEmpty() || password.isEmpty()) {
        println("Email and password are required. Aborting -- nothing was sent.")
        return
    }

    println()
    println("Running the five-stage connection check...")
    val result = checkConnection(
        host = host,
        port = port,
        email = email,
        password = password,
        onStage = StageListener { name, label, ok -> println("  [$name] $label: ${if (ok) "OK" else "FAILED"}") },
    )
    println()
    println(formatConnectionResult(result))

    if (!result.ok) return

    println()
    print("Also append one test message to the WhatsApp/LiveHarness folder? [y/N]: ")
    val doAppend = readlnOrNull()?.trim()?.lowercase()
    if (doAppend != "y" && doAppend != "yes") {
        println("Skipped. Done.")
        return
    }

    val transport = ImapTransport(host = host, port = port, email = email, password = password)
    try {
        val label = transport.labelsCreate("LiveHarness")
        val message = ParsedMessage(
            chatId = "live-harness-test",
            timestamp = java.time.LocalDateTime.now(),
            sender = "Meera Iyer",
            body = "This is a throwaway message sent by the :core live IMAP harness.",
        )
        val (raw, labelId) = MimeBuilder.buildMimeMessage(
            displayName = "Live Harness Test",
            chunk = listOf(message),
            chunkSize = ChunkSize.Day,
            labelId = label,
            messageId = MimeBuilder.newMessageId(),
        )
        val result2 = transport.messagesInsert(
            rawMessageBytes = java.util.Base64.getUrlDecoder().decode(raw),
            folder = labelId,
        )
        println("Appended message id=${result2.id} threadId=${result2.threadId}")
    } finally {
        transport.close()
    }
    println("Done.")
}
