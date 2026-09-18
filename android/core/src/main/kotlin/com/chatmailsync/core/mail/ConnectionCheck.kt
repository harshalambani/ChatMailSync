package com.chatmailsync.core.mail

import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * Kotlin port of `check_connection` and its helpers (`src/mail_client.py`
 * lines ~858-1307): a five-stage staged connection test (DNS / TCP / TLS /
 * LOGIN / FOLDER) that never throws for a connection problem -- every
 * caller here is a UI that wants to *show* the failure, not catch an
 * exception.
 */

/** The five things that have to go right, in order. Mirrors `CONNECTION_STAGES`. */
val CONNECTION_STAGES: List<String> = listOf("DNS", "TCP", "TLS", "LOGIN", "FOLDER")

/** Seconds; mirrors `CONNECTION_TEST_STAGE_TIMEOUT` -- short because someone is watching a spinner. */
const val CONNECTION_TEST_STAGE_TIMEOUT_SECONDS = 8L

private val STAGE_LABELS: Map<String, String> = mapOf(
    "DNS" to "Finding the server",
    "TCP" to "Reaching the server",
    "TLS" to "Securing the connection",
    "LOGIN" to "Signing in",
    "FOLDER" to "Creating the mail folder",
)

data class ConnectionStagePlanEntry(val name: String, val label: String)

/** Mirrors `connection_stage_plan`: the five stages a UI can draw before the check starts. */
fun connectionStagePlan(): List<ConnectionStagePlanEntry> =
    CONNECTION_STAGES.map { ConnectionStagePlanEntry(it, STAGE_LABELS.getValue(it)) }

data class ConnectionStageResult(val name: String, val label: String, val ok: Boolean)

data class ConnectionResult(
    val ok: Boolean,
    val stage: String?,
    val failedStage: String?,
    val message: String,
    val stages: List<ConnectionStageResult>,
)

/** Called as each stage finishes -- mirrors the duck-typed `on_stage`/`StageListener.onStage`. Never lets a throw escape the check. */
fun interface StageListener {
    fun onStage(name: String, label: String, ok: Boolean)
}

private class StageTimedOut(message: String) : Exception(message)

/**
 * Runs [fn] on a daemon helper thread and waits up to [timeoutSeconds].
 * Mirrors `_run_with_timeout`: a backstop for every stage, not only a
 * convenience for the ones with their own socket timeout. The worker thread
 * is abandoned (never interrupted) if it does not finish in time, exactly
 * like the Python original's daemon thread.
 */
private fun <T> runWithTimeout(timeoutSeconds: Long, fn: () -> T): T {
    val box = ArrayBlockingQueue<Result<T>>(1)
    val thread = Thread {
        box.put(
            try {
                Result.success(fn())
            } catch (exc: Throwable) {
                Result.failure(exc)
            },
        )
    }
    thread.isDaemon = true
    thread.start()
    val outcome = box.poll(timeoutSeconds, TimeUnit.SECONDS)
        ?: throw StageTimedOut("no reply from the server within $timeoutSeconds seconds")
    return outcome.getOrThrow()
}

private fun probeDns(host: String) {
    InetAddress.getAllByName(host)
}

private fun probeTcp(host: String, port: Int): Socket {
    val socket = Socket()
    socket.connect(java.net.InetSocketAddress(host, port), (CONNECTION_TEST_STAGE_TIMEOUT_SECONDS * 1000).toInt())
    return socket
}

/** Owns [socket]'s lifetime from here on: always closes it, success or failure. */
private fun probeTls(socket: Socket, host: String) {
    try {
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(null, null, null)
        val wrapped = context.socketFactory.createSocket(socket, host, socket.port, true) as SSLSocket
        val params = SSLParameters()
        params.endpointIdentificationAlgorithm = "HTTPS"
        wrapped.sslParameters = params
        wrapped.enabledProtocols = wrapped.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        try {
            wrapped.startHandshake()
        } finally {
            wrapped.close()
        }
    } catch (exc: Exception) {
        try {
            socket.close()
        } catch (_: Exception) {
            // best-effort close
        }
        throw exc
    }
}

/**
 * Runs the five connection stages in order and reports where it stopped.
 * Mirrors `check_connection`. [onStage], when given, is notified as each
 * stage finishes so a UI can tick them off while the check is still running.
 */
fun checkConnection(
    host: String,
    port: Int,
    email: String,
    password: String,
    onStage: StageListener? = null,
): ConnectionResult {
    val results = mutableListOf<ConnectionStageResult>()
    var reached: String? = null

    fun record(name: String, ok: Boolean) {
        val stage = ConnectionStageResult(name, STAGE_LABELS.getValue(name), ok)
        results.add(stage)
        if (onStage != null) {
            try {
                onStage.onStage(stage.name, stage.label, stage.ok)
            } catch (_: Exception) {
                // A listener is a progress indicator; a broken one must not fail the check.
            }
        }
    }

    fun outcome(failed: String?, message: String) = ConnectionResult(
        ok = failed == null,
        stage = reached,
        failedStage = failed,
        message = message,
        stages = results,
    )

    val trimmedHost = host.trim()
    val trimmedEmail = email.trim()
    if (trimmedHost.isEmpty() || port == 0 || trimmedEmail.isEmpty() || password.isEmpty()) {
        return outcome("DNS", "Fill in the server, port, email address and app password first.")
    }

    // 1. DNS
    reached = "DNS"
    try {
        runWithTimeout(CONNECTION_TEST_STAGE_TIMEOUT_SECONDS) { probeDns(trimmedHost) }
    } catch (exc: StageTimedOut) {
        record("DNS", false)
        return outcome(
            "DNS",
            "Looking up $trimmedHost timed out after $CONNECTION_TEST_STAGE_TIMEOUT_SECONDS " +
                "seconds. Check the server name and your network connection.",
        )
    } catch (exc: Exception) {
        record("DNS", false)
        return outcome(
            "DNS",
            "Could not find $trimmedHost. Check the server name for a typo — " +
                "nothing was sent. (${stripSecret(exc.message ?: exc.toString(), password)})",
        )
    }
    record("DNS", true)

    // 2. TCP
    reached = "TCP"
    val socket: Socket
    try {
        socket = runWithTimeout(CONNECTION_TEST_STAGE_TIMEOUT_SECONDS) { probeTcp(trimmedHost, port) }
    } catch (exc: StageTimedOut) {
        record("TCP", false)
        return outcome(
            "TCP",
            "Opening port $port on $trimmedHost timed out after " +
                "$CONNECTION_TEST_STAGE_TIMEOUT_SECONDS seconds. A firewall or VPN may be " +
                "silently dropping the connection.",
        )
    } catch (exc: Exception) {
        record("TCP", false)
        return outcome(
            "TCP",
            "Found $trimmedHost but could not open port $port. A firewall, VPN or " +
                "the provider may be blocking it. (${stripSecret(exc.message ?: exc.toString(), password)})",
        )
    }
    record("TCP", true)

    // 3. TLS
    reached = "TLS"
    try {
        runWithTimeout(CONNECTION_TEST_STAGE_TIMEOUT_SECONDS) { probeTls(socket, trimmedHost) }
    } catch (exc: StageTimedOut) {
        record("TLS", false)
        try {
            socket.close()
        } catch (_: Exception) {
            // best-effort
        }
        return outcome(
            "TLS",
            "Negotiating security with $trimmedHost:$port timed out after " +
                "$CONNECTION_TEST_STAGE_TIMEOUT_SECONDS seconds. No password was sent.",
        )
    } catch (exc: Exception) {
        record("TLS", false)
        return outcome(
            "TLS",
            "Reached $trimmedHost:$port but its security certificate could not be " +
                "verified, so no password was sent. This is normal on some " +
                "corporate networks that inspect traffic. (${stripSecret(exc.message ?: exc.toString(), password)})",
        )
    }
    record("TLS", true)

    // 4. LOGIN -- real transport from here on, with the short UI-appropriate timeout.
    reached = "LOGIN"
    val transport = ImapTransport(
        host = trimmedHost,
        port = port,
        email = trimmedEmail,
        password = password,
        timeoutSeconds = CONNECTION_TEST_STAGE_TIMEOUT_SECONDS,
    )
    try {
        try {
            runWithTimeout(CONNECTION_TEST_STAGE_TIMEOUT_SECONDS) { transport.probeConnect() }
        } catch (exc: StageTimedOut) {
            record("LOGIN", false)
            return outcome(
                "LOGIN",
                "Signing in to $trimmedHost:$port timed out after " +
                    "$CONNECTION_TEST_STAGE_TIMEOUT_SECONDS seconds. The server accepted " +
                    "the connection but never replied to sign-in.",
            )
        } catch (exc: MailTransportError) {
            record("LOGIN", false)
            if (exc.status == 401) {
                return outcome("LOGIN", loginFailureHint(trimmedHost, trimmedEmail))
            }
            return outcome(
                "LOGIN",
                "Connected to $trimmedHost:$port but signing in failed. " +
                    stripSecret(exc.message ?: exc.toString(), password),
            )
        } catch (exc: Exception) {
            record("LOGIN", false)
            return outcome(
                "LOGIN",
                "Connected to $trimmedHost:$port but signing in failed. " +
                    stripSecret(exc.message ?: exc.toString(), password),
            )
        }
        record("LOGIN", true)

        // 5. FOLDER
        reached = "FOLDER"
        try {
            runWithTimeout(CONNECTION_TEST_STAGE_TIMEOUT_SECONDS) { transport.labelsCreate(LABEL_PARENT) }
        } catch (exc: StageTimedOut) {
            record("FOLDER", false)
            return outcome(
                "FOLDER",
                "Signed in as $trimmedEmail, but creating the '$LABEL_PARENT' " +
                    "folder timed out after $CONNECTION_TEST_STAGE_TIMEOUT_SECONDS " +
                    "seconds.",
            )
        } catch (exc: Exception) {
            record("FOLDER", false)
            return outcome(
                "FOLDER",
                "Signed in as $trimmedEmail, but the mailbox would not create the " +
                    "'$LABEL_PARENT' folder, so chats could not be filed. " +
                    stripSecret(exc.message ?: exc.toString(), password),
            )
        }
        record("FOLDER", true)
    } finally {
        try {
            transport.close()
        } catch (_: Exception) {
            // best-effort logout only
        }
    }

    return outcome(
        null,
        "All good — signed in as $trimmedEmail and the '$LABEL_PARENT' folder is ready.",
    )
}

/** Mirrors `format_connection_result`: a [ConnectionResult] flattened to one display string. */
fun formatConnectionResult(result: ConnectionResult): String {
    if (result.ok) return result.message
    val label = STAGE_LABELS[result.failedStage] ?: "Connecting"
    return "$label failed. ${result.message}"
}

/** Mirrors `check_connection_text`: [checkConnection] flattened to one display string. */
fun checkConnectionText(host: String, port: Int, email: String, password: String): String =
    formatConnectionResult(checkConnection(host, port, email, password))
