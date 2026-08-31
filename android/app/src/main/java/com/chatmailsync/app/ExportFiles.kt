package com.chatmailsync.app

/**
 * What counts as a WhatsApp export, in one place.
 *
 * WhatsApp writes exports as .txt, or .zip when media is included. This used
 * to be a private set inside ImportPickerScreen, which meant the picker hid
 * the JPEGs and PDFs sitting beside an export while WatchFolderWorker -- the
 * path nobody is watching -- imported those same files anyway and queued them
 * as chats. The picker told the truth and the background worker contradicted
 * it. Two code paths cannot each hold their own opinion about this, so they
 * share one.
 *
 * Deliberately not applied inside ImportManager: the share-sheet is an
 * explicit human act on one named file, and rejecting an export somebody
 * renamed would be worse than importing it. A watched folder is not an
 * explicit act -- it sweeps whatever happens to be there -- so that is where
 * the gate belongs.
 */
object ExportFiles {

    val EXTENSIONS = setOf("txt", "zip")

    /** True if [name] has an export's extension. Case-insensitive; a file
     * with no extension at all is not an export. */
    fun looksLikeExport(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
