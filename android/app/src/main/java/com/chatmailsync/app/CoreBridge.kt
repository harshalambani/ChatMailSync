package com.chatmailsync.app

import com.chatmailsync.core.mail.CoreApi

/**
 * CUT-02: the one [CoreApi] for the whole process (the Kotlin twin of the old process-wide
 * Python runtime). The Stop flag, the progress tracker and the single-flight lock live inside
 * the instance, so the UI's Cancel, SyncWorker and every screen must all share it.
 *
 * Built lazily by [ChatMailApplication.core]; nothing here starts Python.
 */
internal object CoreHolder {
    @Volatile
    private var instance: CoreApi? = null

    internal fun get(build: () -> CoreApi): CoreApi =
        instance ?: synchronized(this) { instance ?: build().also { instance = it } }

    /** Tests only: swap in a CoreApi (or null to forget it). */
    internal fun setForTest(api: CoreApi?) {
        instance = api
    }
}

/** A result field as text, or null when absent or null. */
internal fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()

/** A result field as a flag: true, a non-zero number, or the text "true"/"1". */
internal fun Map<String, Any?>.flag(key: String): Boolean =
    when (val v = this[key]) {
        null -> false
        is Boolean -> v
        is Number -> v.toLong() != 0L
        else -> v.toString().equals("true", ignoreCase = true) || v.toString() == "1"
    }

internal fun Map<String, Any?>.long(key: String): Long? =
    when (val v = this[key]) {
        null -> null
        is Number -> v.toLong()
        else -> v.toString().toLongOrNull()
    }
