package com.chatmailsync.app

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

// KT-13 step 2: the "Test connection" watchdog and its helpers, moved out of MainActivity.kt unchanged.

/**
 * Backstop for "Test connection": how long the UI waits for a result before
 * giving up on it and reporting a timeout of its own, regardless of what the
 * core is doing.
 *
 * check_connection()'s own worst case is five stages at
 * CONNECTION_TEST_STAGE_TIMEOUT (8s) each, about 40s -- this is set above
 * that so the core's own timeout is normally the one that fires and names
 * the actual stage, with this one only as insurance against the check itself
 * wedging (a non-Exception Throwable, a call that never returns control at
 * all) rather than the network call inside it.
 */
internal const val ConnectionWatchdogTimeoutMs = 50_000L

/** The line "Test connection" shows if the Kotlin-side watchdog above fires
 * before a real result arrives -- pulled out as a pure function so the exact
 * wording is pinned by a unit test without needing a running check. */
internal fun connectionWatchdogTimeoutText(): String =
    "Test connection timed out. Check your network and try again."

/** Log tag for the connection-test stage progress and failure path. Never
 * carries host, email or password -- only ever a stage name or a fixed,
 * already-redacted string, see [runConnectionCheck] and [redactSecretText]. */
internal const val ConnectionLogTag = "ChatMailSync.Connection"

/** The same substring redaction as ChatMailApp's local `redactSecret`, as a
 * top-level pure function so [runConnectionCheck] -- which runs outside any
 * Composable's scope -- can use it too without either duplicating the logic
 * or reaching back into composition state from a background thread. */
internal fun redactSecretText(text: String, secret: String?): String =
    if (!secret.isNullOrEmpty() && text.contains(secret)) text.replace(secret, "********") else text

/**
 * Runs [check] on a background thread and always delivers exactly one result
 * to [onResult], on the main thread -- whichever of two paths gets there
 * first:
 *
 *  - the worker thread finishes, whether [check] succeeds, returns a normal
 *    check_connection() failure outcome, or throws -- `catch (t: Throwable)`
 *    here, deliberately not `catch (e: Exception)`, so a non-Exception
 *    failure thrown by the check still reports back instead of
 *    silently ending the thread with the UI never told; or
 *  - [ConnectionWatchdogTimeoutMs] elapses with no result yet, in which case
 *    this reports its own timeout and leaves the worker thread to finish (or
 *    not) on its own.
 *
 * This is the fix for "test connection never returns": before this, the
 * *only* path to the UI was a `Handler.post` inside the worker thread's own
 * try block, so anything that kept that thread from ever reaching it left
 * the button and the screen exactly as they were, indefinitely. The
 * `finally` block (not a post at the end of `try`) plus the watchdog's own
 * independent post are what close that gap: something now always reaches
 * [onResult], on the main thread, at most once.
 */
internal fun runConnectionCheck(
    context: android.content.Context,
    password: String?,
    onResult: (connected: Boolean, text: String) -> Unit,
    check: () -> Pair<Boolean, String>,
) {
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    runConnectionCheckWithScheduler(
        password = password,
        post = { mainHandler.post(it) },
        postDelayed = { delayMs, runnable -> mainHandler.postDelayed(runnable, delayMs) },
        removeCallbacks = { mainHandler.removeCallbacks(it) },
        recordConnection = { connected -> ConnectionState.record(context, connected) },
        onResult = onResult,
        check = check,
        log = { Log.i(ConnectionLogTag, it) },
    )
}

/**
 * The actual exactly-once-delivery/watchdog race behind [runConnectionCheck],
 * with the main-thread scheduler (normally `Handler(Looper.getMainLooper())`)
 * and the [ConnectionState] write taken as parameters instead of reached for
 * directly.
 *
 * Pulled out for exactly one reason: this module has no Robolectric, so
 * nothing that touches a real `Handler`/`Looper` is reachable from a plain
 * JVM unit test -- before this split, the exactly-once guarantee this
 * function exists to provide (see [runConnectionCheck]'s doc comment) had no
 * test coverage of its own beyond the pure helpers it calls into
 * ([connectionWatchdogTimeoutText], [redactSecretText]). With the scheduler
 * injected, a test can supply a same-thread `post`/`postDelayed` and drive
 * both the "watchdog fires first" and "check finishes first" orderings
 * directly -- see ConnectionCheckHelpersTest.
 *
 * [log] is likewise injected rather than calling `android.util.Log` directly:
 * the JVM unit test stub for `android.jar` throws "not mocked" on every
 * framework call, and this function's watchdog/throwing-check paths used to
 * be the only reason the module needed `isReturnDefaultValues = true` in
 * build.gradle.kts. A plain lambda closes that gap without hiding other,
 * unrelated unmocked framework calls in the same test run.
 */
internal fun runConnectionCheckWithScheduler(
    password: String?,
    post: (Runnable) -> Unit,
    postDelayed: (delayMs: Long, runnable: Runnable) -> Unit,
    removeCallbacks: (Runnable) -> Unit,
    recordConnection: (connected: Boolean) -> Unit,
    onResult: (connected: Boolean, text: String) -> Unit,
    check: () -> Pair<Boolean, String>,
    log: (String) -> Unit,
) {
    val delivered = AtomicBoolean(false)
    val watchdog = Runnable {
        if (delivered.compareAndSet(false, true)) {
            log("watchdog fired before a result arrived")
            recordConnection(false)
            onResult(false, connectionWatchdogTimeoutText())
        }
    }
    postDelayed(ConnectionWatchdogTimeoutMs, watchdog)
    Thread {
        var connected = false
        var text = "Could not connect."
        try {
            val (c, t) = check()
            connected = c
            text = t
        } catch (t: Throwable) {
            log("connection check threw")
            text = redactSecretText("Could not connect: ${t.message ?: "unknown error"}", password)
        } finally {
            post {
                if (delivered.compareAndSet(false, true)) {
                    removeCallbacks(watchdog)
                    recordConnection(connected)
                    onResult(connected, text)
                }
            }
        }
    }.start()
}
