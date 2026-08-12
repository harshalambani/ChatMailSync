package com.chatmailsync.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Copies a WhatsApp export received via share-sheet (ACTION_SEND) or the SAF
 * file picker into the shared core's data/inbox/ directory. Kotlin owns this
 * copy (not android_api.py) because content:// Uris are an Android-only
 * concept — Chaquopy's Python side only ever sees plain files already
 * sitting in inbox/, exactly like the Windows drop-folder flow.
 */
/** Result of an import attempt: the file now sitting in the inbox, and
 * whether it was already there before this call (vs. freshly copied in). */
data class ImportOutcome(val file: File, val alreadyQueued: Boolean)

object ImportManager {

    /** Returns the queued File on success, or null if the Uri couldn't be
     * read (caller should show an error). Picking the same export twice
     * (same filename) is treated as "already queued" rather than creating a
     * second numbered copy — the old `_1`/`_2` suffixing let one file get
     * queued (and synced) multiple times under different names. */
    fun importUri(context: Context, uri: Uri): ImportOutcome? {
        val rawName = queryDisplayName(context, uri) ?: return null
        // rawName comes straight from another app's ContentProvider metadata
        // (DISPLAY_NAME / lastPathSegment) and is untrusted: File(parent, child)
        // does not strip ".." traversal, so take only the leaf name here,
        // mirroring android_api.py's Path(name).name pattern.
        val displayName = File(rawName).name
        if (displayName.isBlank()) return null
        val dest = File(ChatMailApplication.inboxDir(context), displayName)

        if (dest.exists()) return ImportOutcome(dest, alreadyQueued = true)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            ImportOutcome(dest, alreadyQueued = false)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        // content:// Uris carry their filename as metadata, not in the Uri
        // itself (unlike file:// Uris) — must query the ContentResolver.
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment
    }
}
