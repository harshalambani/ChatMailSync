package com.chatmailsync.app

import java.io.File

/**
 * BUG-18: which "Is this the same chat?" question to show, and which queued export is waiting
 * for an answer. Pure (no Compose, no I/O) so JVM tests reach it. Reads only the already loaded
 * `linkProposals()` result; nothing here logs or stores a chat name.
 */

/** The shared words for "a chat is waiting": used by the background notice and the manual result. */
internal const val HELD_NOTICE_TITLE = "A chat is waiting for your answer"
internal const val HELD_NOTICE_TEXT = "Open Chat Mail Sync to confirm whether it's a renamed chat."

/** Shown on the Home queue row of a held export. */
internal const val WAITING_ROW_TEXT = "Waiting for your answer"

/** The per-pair key used for "Ask me later". */
internal fun linkQuestionKey(q: Map<String, Any?>): String = "${q["new_chat_id"]}|${q["old_chat_id"]}"

/** The first open question whose pair is not snoozed, or null. */
internal fun pickOpenQuestion(questions: List<Map<String, Any?>>, snoozed: Set<String>): Map<String, Any?>? =
    questions.firstOrNull { linkQuestionKey(it) !in snoozed }

/** "Ask me later" (or Back): hides only this pair. */
internal fun snoozePair(snoozed: Set<String>, key: String): Set<String> = snoozed + key

/** Reopening from the Home row: clears the snooze of every pair asked about this file. */
internal fun reopenFor(snoozed: Set<String>, questions: List<Map<String, Any?>>, fileName: String): Set<String> {
    val keys = questions.filter { sameFile(it["file_name"], fileName) }.map { linkQuestionKey(it) }.toSet()
    return snoozed - keys
}

/** Queued file names that have an open question, i.e. are held and waiting for the user. */
internal fun waitingFiles(questions: List<Map<String, Any?>>): Set<String> =
    questions.mapNotNull { (it["file_name"] as? String)?.let { n -> File(n).name } }.toSet()

private fun sameFile(a: Any?, b: String): Boolean = (a as? String)?.let { File(it).name == File(b).name } == true

/**
 * The line a sync the user started by hand adds to its result when it held files; null when
 * nothing was held or it was a test run. Same words as the background notice.
 */
internal fun manualHeldLine(filesHeld: Int, trigger: String, dryRun: Boolean): String? =
    if (filesHeld > 0 && !dryRun && trigger == "manual") "$HELD_NOTICE_TITLE. $HELD_NOTICE_TEXT" else null

/** Adds [line] (when there is one) under the result text, after a blank line. */
internal fun withHeldLine(line: String?): String = if (line == null) "" else "\n\n" + line
