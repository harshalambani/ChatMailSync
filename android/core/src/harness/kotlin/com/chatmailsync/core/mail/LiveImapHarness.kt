package com.chatmailsync.core.mail

import java.time.LocalDateTime
import java.util.Base64

/**
 * Interactive, human-run harness for exercising [ImapTransport] /
 * [checkConnection] against a *real* Yahoo mailbox -- the `harness` source
 * set (see `android/core/build.gradle.kts`), never part of `main`, `test`,
 * or anything CI runs. It lives in its own source set specifically so it
 * can never end up compiled into the `:core` jar a later phase wires the
 * app to -- a human-only network+password entry point has no business
 * shipping inside the library artifact.
 *
 * Gradle's `JavaExec` never attaches a real console -- `System.console()`
 * is always null under `./gradlew ... --console=plain`, even with stdin
 * wired through -- so a masked password prompt is impossible from inside
 * Gradle. Rather than fall back to an echoed-in-the-clear prompt, this
 * harness refuses to run unless it detects a real console and tells the
 * caller how to get one. Build a runnable jar and launch it directly from
 * an actual terminal, bypassing the Gradle daemon entirely:
 *
 *     cd android
 *     ./gradlew :core:harnessJar
 *     java -jar core/build/libs/core-harness.jar
 *
 * Run from a plain terminal (PowerShell, cmd, or a POSIX shell), never
 * through the Gradle daemon or an IDE's "Run" button -- both of those also
 * fail to attach a real console.
 *
 * The password is read with `Console.readPassword()` straight into a
 * `CharArray` -- never echoed, never captured into shell history, never
 * printed or logged anywhere in this file. `ImapTransport`/`checkConnection`
 * only accept a `String` password (out of scope to change in this phase),
 * so a `String` is built from the `CharArray` at the last possible moment
 * to satisfy that API; the `CharArray` itself is always wiped (filled with
 * spaces) in a `finally` block regardless of how the run ends.
 *
 * Yahoo only, enforced: this refuses to run against anything but
 * `imap.mail.yahoo.com` (see the project's standing test-account rule --
 * never Gmail).
 */
fun main() {
    val console = System.console()
    if (console == null) {
        println("Run this from a real terminal.")
        println("(java -jar core/build/libs/core-harness.jar, built via './gradlew :core:harnessJar' --")
        println(" Gradle's own JavaExec, and most IDE run buttons, never attach a real console, so a")
        println(" masked password prompt is impossible there. See the class doc comment for the exact steps.)")
        return
    }

    console.printf("Chat Mail Sync -- live IMAP harness (Kotlin :core spike)%n")
    console.printf("This talks to a REAL mailbox over a REAL network connection.%n")
    console.printf("Yahoo only -- never Gmail (see the project's standing test-account rule).%n%n")

    val host = console.readLine("IMAP host [imap.mail.yahoo.com]: ")?.trim().orEmpty().ifEmpty { "imap.mail.yahoo.com" }
    if (host.all { it.isDigit() }) {
        console.printf("Expected a host name here (for example imap.mail.yahoo.com), but got a number. The port is asked next.%n")
        return
    }
    if (host != "imap.mail.yahoo.com") {
        console.printf("Refusing: this harness only talks to imap.mail.yahoo.com (never Gmail). Got: %s%n", host)
        return
    }

    val portInput = console.readLine("IMAP port [993]: ")?.trim().orEmpty()
    val port = portInput.toIntOrNull() ?: 993

    val email = console.readLine("Email address: ")?.trim().orEmpty()
    if (email.isEmpty()) {
        console.printf("Email is required. Aborting -- nothing was sent.%n")
        return
    }

    val passwordChars: CharArray = console.readPassword("App password (input is not echoed): ")
        ?: run {
            console.printf("No password entered. Aborting -- nothing was sent.%n")
            return
        }

    try {
        if (passwordChars.isEmpty()) {
            console.printf("Password is required. Aborting -- nothing was sent.%n")
            return
        }
        // Built only here, at the last moment: ImapTransport/checkConnection
        // require a String. The source CharArray is wiped in the finally
        // block below regardless of how this function returns.
        val password = String(passwordChars)

        console.printf("%nRunning the five-stage connection check...%n")
        val result = checkConnection(
            host = host,
            port = port,
            email = email,
            password = password,
            onStage = StageListener { name, label, ok -> println("  [$name] $label: ${if (ok) "OK" else "FAILED"}") },
        )
        console.printf("%n%s%n", formatConnectionResult(result))

        if (!result.ok) return

        console.printf("%nAlso append two throwaway test messages to WhatsApp/LiveHarness? [y/N]: ")
        val doAppend = console.readLine()?.trim()?.lowercase()
        if (doAppend != "y" && doAppend != "yes") {
            console.printf("Skipped. Done.%n")
            return
        }

        runAppends(host, port, email, password)
    } finally {
        // Wipe the password from memory the moment this function is done
        // with it, success or failure.
        java.util.Arrays.fill(passwordChars, ' ')
    }
    println("Done.")
}

/**
 * Appends two throwaway messages to the "WhatsApp/LiveHarness" folder: one
 * small message, and one padded to sit just under Yahoo's effective
 * per-message budget (see [PROVIDER_MAX_MESSAGE_BYTES] / [effectiveBudget]
 * / [maxRawBytesFor]) -- Phase 1's live check needs both sizes exercised
 * against a real server, not just the small/happy-path case. No real data:
 * the "near-limit" body is a repeated placeholder character, not content
 * from an actual conversation.
 */
private fun runAppends(host: String, port: Int, email: String, password: String) {
    val transport = ImapTransport(host = host, port = port, email = email, password = password)
    try {
        val labelName = fullLabelName("LiveHarness") // "WhatsApp/LiveHarness"
        val labelId = transport.labelsCreate(labelName)

        val smallResult = appendOne(
            transport = transport,
            labelId = labelId,
            body = "This is a throwaway message sent by the :core live IMAP harness (small case).",
        )
        println("Appended small message: id=${smallResult.id} threadId=${smallResult.threadId} bytes=${smallResult.rawByteCount}")

        val yahooLimit = PROVIDER_MAX_MESSAGE_BYTES.getValue("yahoo")
        val targetRawBytes = maxRawBytesFor(effectiveBudget(yahooLimit))
        // Leave a little headroom under the computed ceiling for the rest
        // of the MIME envelope (subject, headers, index attachment) that
        // this budget helper doesn't itself account for in a plain
        // single-part text message.
        val paddedBodySize = (targetRawBytes - 4096).coerceAtLeast(0)
        val paddedBody = "x".repeat(paddedBodySize.toInt())

        val nearLimitResult = appendOne(transport = transport, labelId = labelId, body = paddedBody)
        println(
            "Appended near-limit message: id=${nearLimitResult.id} threadId=${nearLimitResult.threadId} " +
                "bytes=${nearLimitResult.rawByteCount} (target raw body bytes=$paddedBodySize, " +
                "Yahoo effective budget=${effectiveBudget(yahooLimit)}, Yahoo max_message_bytes=$yahooLimit)",
        )
    } finally {
        transport.close()
    }
}

private data class AppendOutcome(val id: String, val threadId: String, val rawByteCount: Int)

private fun appendOne(transport: ImapTransport, labelId: String, body: String): AppendOutcome {
    val message = ParsedMessage(
        chatId = "live-harness-test",
        timestamp = LocalDateTime.now(),
        sender = "Live Harness",
        body = body,
    )
    val (raw, returnedLabelId) = MimeBuilder.buildMimeMessage(
        displayName = "LiveHarness",
        chunk = listOf(message),
        chunkSize = ChunkSize.Day,
        labelId = labelId,
        messageId = MimeBuilder.newMessageId(),
    )
    val rawBytes = Base64.getUrlDecoder().decode(raw)
    val result = transport.messagesInsert(rawMessageBytes = rawBytes, folder = returnedLabelId)
    return AppendOutcome(id = result.id, threadId = result.threadId, rawByteCount = rawBytes.size)
}
