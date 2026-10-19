package com.chatmailsync.app

/**
 * BUG-02: a persisted "the user tapped Stop on THIS manual sync" mark.
 *
 * Why it exists: Stop only set an in-memory flag in the engine. If the user
 * then force-closed the app, the worker never reported a result, so
 * WorkManager re-ran the same unique work ("manual_sync") when the process
 * returned, and the sync the user had cancelled started again.
 *
 * The mark is keyed by the WorkRequest id (a UUID). A WorkManager re-run
 * after process death keeps the same id; a fresh sync gets a new one. So a
 * mark can only ever swallow the exact work it was written for.
 *
 * The stored value is that id string and nothing else: no account, no
 * password, no message data. The password stays in SecretStore.
 *
 * Pure on purpose: storage is passed in as read/write/clear lambdas so the
 * decision logic is unit-testable on the JVM without Android.
 */
class StopMark(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val clear: () -> Unit,
) {
    /** Called by the Stop button BEFORE the engine is asked to stop. */
    fun markStopped(workId: String) {
        write(workId)
    }

    /**
     * First thing in the worker. True when this exact work was stopped by the
     * user: the mark is cleared and the caller must not start the engine.
     * A mark for any other id is left alone and ignored (so a periodic/auto
     * run, or a newer manual sync, is never blocked).
     */
    fun consumeIfStopped(workId: String): Boolean {
        if (read() != workId) return false
        clear()
        return true
    }

    /** A fresh manual sync is about to be enqueued: drop any stale mark. */
    fun clearForFreshSync() {
        clear()
    }

    /** Normal finish (complete, failed or stopped): drop the mark if it is ours. */
    fun clearOnFinish(workId: String) {
        if (read() == workId) clear()
    }
}
