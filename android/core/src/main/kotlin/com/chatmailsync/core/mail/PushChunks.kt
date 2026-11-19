package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException

/**
 * Kotlin port of the push half of `src/mail_client.py`: `_size_split_cached`,
 * `_prepare_emails`, `_insert_with_backoff`, `PushResult`, `push_chunks` and
 * `push_chat`.
 *
 * No logging (the mail package has none; Python's
 * `log.*` calls are diagnostic only) and no terminal progress bar
 * (`_print_progress` only ever drew on a console; `onChunk` is the progress
 * channel off a console, exactly as the Python docstring says).
 */

/** What [insertWithBackoff] waits with. Tests inject one that records instead of sleeping. */
fun interface Sleeper {
    fun sleep(seconds: Double)

    /**
     * BUG-26: waits, but ends early once [shouldStop] says yes. The default just waits (tests record the
     * requested seconds); [SystemSleeper] checks in short slices.
     */
    fun sleep(seconds: Double, shouldStop: () -> Boolean) = sleep(seconds)

    /**
     * SEC-10: a wait that Stop can end. Sleeps in slices of [STOP_POLL_SECONDS], asking [shouldStop]
     * before each; returns true when the full time passed, false when stopped. Built on [sleep], so a
     * fake-clock sleeper in a test drives it without any real wait.
     */
    fun waitUnlessStopped(seconds: Double, shouldStop: () -> Boolean): Boolean {
        var left = seconds
        while (left > 0.0) {
            if (shouldStop()) return false
            val slice = if (left < STOP_POLL_SECONDS) left else STOP_POLL_SECONDS
            sleep(slice)
            left -= slice
        }
        return !shouldStop()
    }
}

/** How often a throttle backoff looks at Stop. */
const val STOP_POLL_SECONDS = 0.25

/** SEC-10: Stop arrived while waiting out a provider throttle; nothing more was sent. Ends the push quietly. */
class ThrottleWaitStopped : Exception("Stopped while waiting for the mail provider to allow uploads again.")

/** The real sleeper. Its stop-aware wait wakes within a quarter of a second of Stop. */
object SystemSleeper : Sleeper {
    override fun sleep(seconds: Double) = Thread.sleep((seconds * 1000.0).toLong())

    override fun sleep(seconds: Double, shouldStop: () -> Boolean) {
        var remainingMs = (seconds * 1000.0).toLong()
        while (remainingMs > 0 && !shouldStop()) {
            val slice = minOf(remainingMs, 250L)
            Thread.sleep(slice)
            remainingMs -= slice
        }
    }
}

/** Outcome of pushing one email. Mirrors `PushResult`. */
class PushResult(
    val messageId: String,
    val gmailMessageId: String,
    val threadId: String,
    val omissions: List<HtmlRenderer.MediaOmission> = emptyList(),
)

/**
 * One email's worth of messages and its rendering. [suffix] is the "Part k/N"
 * label the rendering was made with ("" when unlabelled); it is also what goes
 * on the Subject, so the separator and the Subject always agree.
 */
class PreparedEmail(
    val messages: List<ParsedMessage>,
    val rendered: HtmlRenderer.RenderedChunk,
    val suffix: String = "",
)

/** Progress callback: (email index 1-based, total emails, messages done, total messages, messages in this email). */
/**
 * BUG-11: called with an accepted email's [PushResult] and the label id it went under,
 * strictly AFTER the transport accepted it and right BEFORE the matching [OnChunk]. Lets the
 * caller save the thread id, label id and first anchor Message-ID per email.
 */
typealias OnAccepted = (PushResult, String) -> Unit

typealias OnChunk = (Int, Int, Int, Int, List<ParsedMessage>) -> Unit

/**
 * UX-11: where one email's upload stands. [emailNumber] of [totalEmails] (1-based), [msgsBefore] messages
 * already confirmed, [emailMsgs] messages in this email, [sent] of [total] bytes written. Only bytes that
 * were really written are counted.
 */
class UploadProgress(
    val emailNumber: Int,
    val totalEmails: Int,
    val msgsBefore: Int,
    val emailMsgs: Int,
    val sent: Long,
    val total: Long,
)

/** UX-11: called while an email uploads, and once with [UploadProgress.total] = 0 when it is over (landed or not). */
typealias OnUpload = (UploadProgress) -> Unit

/**
 * Mirrors `_size_split_cached`: recursively halve [messages] until each piece
 * fits [limitBytes] on the wire.
 *
 * Two lessons from the 2026-08-10 "[TOOBIG] Message too large" failures are
 * kept here because both produced the same live failure:
 *  - the comparison used RAW bytes while the server measures the base64
 *    encoded message (about 37% larger); `wireBytes` is the encoded
 *    projection, so the budget means what it says;
 *  - a depth cap silently returned an oversized piece after 32 splits. The
 *    split now ends on its own: [mediaBudget] guarantees a single message
 *    fits, so the recursion always reaches a legal piece.
 *
 * The index rides on the finished email but does not exist yet, so its size is
 * estimated: otherwise the decision is made against a total a few hundred
 * bytes per message short.
 */
fun sizeSplitCached(
    messages: List<ParsedMessage>,
    displayName: String,
    extractor: MediaExtractor?,
    limitBytes: Long = DEFAULT_MAX_MESSAGE_BYTES,
    selfSender: String? = null,
): List<PreparedEmail> {
    if (messages.isEmpty()) return emptyList()
    val budget = effectiveBudget(limitBytes)
    val rendered = HtmlRenderer.renderChunk(
        messages, displayName, extractor, "",
        maxMediaBytes = mediaBudget(limitBytes),
        selfSender = selfSender,
    )
    val projected = rendered.wireBytes +
        HtmlRenderer.encodedPartBytes(estimateIndexBytes(messages.size).toLong())
    // BUG-24: never halve between two messages with one fingerprint; with no legal cut the
    // piece stays whole (a server refusal is then handled where it happens).
    val mid = splitPoint(messages)
    if (projected <= budget || mid == null) {
        return listOf(PreparedEmail(messages, rendered))
    }
    return sizeSplitCached(messages.subList(0, mid), displayName, extractor, limitBytes, selfSender) +
        sizeSplitCached(messages.subList(mid, messages.size), displayName, extractor, limitBytes, selfSender)
}

/**
 * Mirrors `_prepare_emails`: flatten all chunks into emails. A chunk that had
 * to be split is re-rendered with its "Part k/N" label once N is known; a
 * chunk that was not split keeps its unlabelled rendering.
 */
fun prepareEmails(
    chunks: List<List<ParsedMessage>>,
    displayName: String,
    extractor: MediaExtractor?,
    limitBytes: Long = DEFAULT_MAX_MESSAGE_BYTES,
    selfSender: String? = null,
): List<PreparedEmail> {
    val result = mutableListOf<PreparedEmail>()
    for (chunk in chunks) {
        val pieces = sizeSplitCached(chunk, displayName, extractor, limitBytes, selfSender)
        val n = pieces.size
        for ((k, piece) in pieces.withIndex()) {
            if (n > 1) {
                val label = "Part ${k + 1}/$n"
                val rendered = HtmlRenderer.renderChunk(
                    piece.messages, displayName, extractor, label,
                    maxMediaBytes = mediaBudget(limitBytes),
                    selfSender = selfSender,
                )
                result.add(PreparedEmail(piece.messages, rendered, label))
            } else {
                result.add(piece)
            }
        }
    }
    return result
}

internal val RETRYABLE_STATUSES = setOf(500, 502, 503, 504)

/**
 * Mirrors `_insert_with_backoff`: insert with exponential backoff on 5xx
 * and on network errors. Delays are [BACKOFF_BASE_DELAY], doubling each time,
 * at most [BACKOFF_MAX_ATTEMPTS] attempts. Any other [MailTransportError] (a
 * 4xx other than a throttle, including a 413 size refusal) is thrown at once: a
 * retry cannot fix it, and the size case is the caller's cue to split.
 * A pause of [API_CALL_DELAY_SECONDS] follows every success.
 *
 * SEC-10: a provider throttle reply ([THROTTLE_STATUS]) is waited out on its own schedule,
 * [THROTTLE_BACKOFF_SECONDS] (30, 60, 120 s), then the chat fails loudly. The wait looks at
 * [shouldStop] every [STOP_POLL_SECONDS]; on Stop it throws [ThrottleWaitStopped] and sends nothing
 * more. Each retry goes back through the transport, which looks up an upload of unknown outcome
 * (F-02) before it would send again.
 */
fun insertWithBackoff(
    transport: MailTransport,
    raw: ByteArray,
    folder: String,
    threadId: String?,
    sleeper: Sleeper = SystemSleeper,
    shouldStop: () -> Boolean = { false },
): ImapTransport.InsertResult {
    var delay = BACKOFF_BASE_DELAY
    var attempt = 1
    var throttled = 0
    while (true) {
        // BUG-26: Stop is honoured before every attempt and the waits below end early on it.
        if (shouldStop()) throw StopRequestedException()
        try {
            val response = transport.messagesInsert(raw, folder, threadId)
            sleeper.sleep(API_CALL_DELAY_SECONDS, shouldStop)
            return response
        } catch (exc: MailTransportError) {
            if (exc.status == THROTTLE_STATUS) {
                if (throttled >= THROTTLE_BACKOFF_SECONDS.size) {
                    // Fresh text on purpose: the server's own words (and anything it echoed) stay out.
                    throw MailTransportError(
                        "The mail provider kept refusing new uploads (it is limiting how fast this " +
                            "account can send), even after waiting " +
                            THROTTLE_BACKOFF_SECONDS.joinToString(", ") { it.toInt().toString() } +
                            " seconds. Nothing was lost; sync again in a few minutes and it continues from here.",
                        THROTTLE_STATUS,
                    )
                }
                if (!sleeper.waitUnlessStopped(THROTTLE_BACKOFF_SECONDS[throttled], shouldStop)) {
                    throw ThrottleWaitStopped()
                }
                throttled++
                continue
            }
            val retry = exc.status in RETRYABLE_STATUSES && attempt < BACKOFF_MAX_ATTEMPTS
            if (!retry) throw exc
            sleeper.sleep(delay, shouldStop)
            delay *= 2
        } catch (exc: IOException) {
            if (attempt >= BACKOFF_MAX_ATTEMPTS) throw exc
            sleeper.sleep(delay, shouldStop)
            delay *= 2
        }
        attempt++
    }
}

/**
 * Mirrors `push_chunks`: push chunks as individual HTML emails in one thread.
 *
 * [onChunk] is called once per email, strictly AFTER that email's insert
 * returned successfully, with exactly the messages that email carried, so a
 * caller recording "this much is delivered" matches reality when a push is
 * interrupted. [dryRun] builds and renders but never calls the transport: no
 * byte reaches the wire. [selfSender] names the account owner as the export
 * writes them (null falls back to the literal "You").
 *
 * A day split into several emails carries "(Part k/N)" on each Subject. This
 * is a deliberate difference from Python, whose push_chunks never passes the
 * suffix on (BUG-08); the Python code is frozen and stays as it is.
 *
 * A size refusal (413 / a size marker) is not retried: the run's ceiling is
 * lowered below what was just tried, the email is halved (lossless) while it
 * holds more than one message, and only a lone message has its media dropped
 * for a placeholder. A lone message whose text alone is refused is a real
 * failure and is thrown. Everything still pending is re-split against the
 * lowered ceiling, so one refusal teaches the rest of the chat.
 *
 * [shouldStop] is asked before every insert. When it says yes the push ends
 * there and returns the emails delivered so far (BUG-01; Python only looks at
 * its stop event once per file).
 */
fun pushChunks(
    transport: MailTransport,
    displayName: String,
    chunks: List<List<ParsedMessage>>,
    labelId: String,
    chunkSize: ChunkSize = ChunkSize.Day,
    anchorMessageId: String? = null,
    threadId: String? = null,
    dryRun: Boolean = false,
    sourcePath: File? = null,
    onChunk: OnChunk? = null,
    selfSender: String? = null,
    sleeper: Sleeper = SystemSleeper,
    shouldStop: () -> Boolean = { false },
    onAccepted: OnAccepted? = null,
    /** CUT-02: stamped into the traceability index of every email; "unknown" only when none is given. */
    appVersion: String = UNKNOWN_VERSION,
    /** D185: remembers messages whose upload outcome was never learned, across runs. */
    pendingUploads: PendingUploads? = null,
    pendingChatId: String = "",
    /** UX-11: see [OnUpload]. */
    onUpload: OnUpload? = null,
): List<PushResult> {
    val results = mutableListOf<PushResult>()
    var currentAnchor = anchorMessageId
    var currentThread = threadId
    var limitBytes = transport.maxMessageBytes
    val lookedUp = mutableMapOf<String, Boolean>()

    val extractor = if (sourcePath != null) MediaExtractor(sourcePath) else null
    try {
        val emailList = prepareEmails(chunks, displayName, extractor, limitBytes, selfSender)
        var totalEmails = emailList.size
        val totalMsgs = emailList.sumOf { it.messages.size }
        var msgsDone = 0

        val worklist = emailList.toMutableList()
        var i = 0
        while (i < worklist.size) {
            // BUG-01: a stop request is honoured here, at the boundary between two
            // emails: the previous one is accepted and its hashes are recorded
            // (onChunk ran), the next one has not been built or sent. Never
            // mid-insert. A dry run sends nothing, so it never stops early.
            if (!dryRun && shouldStop()) break
            val subChunk = worklist[i].messages
            val rendered = worklist[i].rendered
            // D185: an earlier run may have uploaded some of these messages without learning the outcome,
            // packed into different emails. Ask the server about each old id (once), then send only the rest.
            if (pendingUploads != null && !dryRun) {
                val hashes = subChunk.map { messageHashOf(it) }
                val olds = pendingUploads.idsFor(pendingChatId, hashes)
                if (olds.isNotEmpty()) {
                    val storedHashes = mutableSetOf<String>()
                    for (old in olds) {
                        val found = lookedUp.getOrPut(old) { transport.lookupStored(old, labelId) }
                        if (found) storedHashes += pendingUploads.hashesFor(pendingChatId, old) else pendingUploads.clear(old)
                    }
                    if (storedHashes.isNotEmpty()) {
                        val stored = subChunk.filter { messageHashOf(it) in storedHashes }
                        val rest = subChunk.filter { messageHashOf(it) !in storedHashes }
                        msgsDone += stored.size
                        // Recorded as delivered without any send; then nothing is pending for them.
                        onChunk?.invoke(i + 1, totalEmails, msgsDone, totalMsgs, stored)
                        pendingUploads.clearHashes(pendingChatId, stored.map { messageHashOf(it) })
                        worklist.removeAt(i)
                        if (rest.isNotEmpty()) {
                            worklist.addAll(i, prepareEmails(listOf(rest), displayName, extractor, limitBytes, selfSender))
                        }
                        totalEmails = worklist.size
                        continue
                    }
                }
            }
            val newMid = MimeBuilder.newMessageId()
            val inReplyTo = currentAnchor

            if (dryRun) {
                results.add(PushResult(newMid, "dry-run-$i", currentThread ?: "dry-run-thread", rendered.omissions))
                if (currentAnchor == null) {
                    currentAnchor = newMid
                    currentThread = "dry-run-thread"
                }
                i++
                continue
            }

            val raw = HtmlMimeBuilder.buildRaw(
                displayName = displayName,
                chunk = subChunk,
                chunkSize = chunkSize,
                rendered = rendered,
                messageId = newMid,
                suffix = worklist[i].suffix,
                inReplyTo = inReplyTo,
                references = currentAnchor,
                appVersion = appVersion,
            )

            pendingUploads?.record(pendingChatId, newMid, subChunk.map { messageHashOf(it) })

            val emailNumber = i + 1
            val emailTotal = totalEmails
            val before = msgsDone
            val emailMsgCount = subChunk.size
            var uploadReported = false
            if (onUpload != null) {
                transport.setUploadListener { sent, total ->
                    uploadReported = true
                    onUpload(UploadProgress(emailNumber, emailTotal, before, emailMsgCount, sent, total))
                }
            }
            val response = try {
                try {
                    insertWithBackoff(transport, raw, labelId, currentThread, sleeper, shouldStop)
                } finally {
                    // UX-11: however this email ended (landed, failed, cut by Stop), its MB line is over.
                    // Only when bytes were reported, so a run that never wrote a byte emits nothing extra.
                    if (onUpload != null) {
                        transport.setUploadListener(null)
                        if (uploadReported) onUpload(UploadProgress(emailNumber, emailTotal, before, emailMsgCount, 0, 0))
                    }
                }
            } catch (exc: StopRequestedException) {
                // BUG-26: Stop cut this email off (or was seen between two attempts). Not a failure:
                // the push ends here with what was delivered, and the run reports "stopped".
                shouldStop()
                break
            } catch (_: ThrottleWaitStopped) {
                // SEC-10: Stop arrived during a throttle wait. Nothing more is sent; the email's pending
                // row stays, so the next run looks it up before sending it.
                break
            } catch (exc: Exception) {
                if (!isTooLarge(exc)) throw exc
                // A size refusal means nothing was stored: nothing is pending for this email.
                pendingUploads?.clear(newMid)

                // Measured as our own projection (wireBytes), the same unit
                // the budget uses; never the length of the encoded message.
                val attempted = rendered.wireBytes
                limitBytes = minOf(limitBytes, (attempted * 0.9).toLong())

                val cut = splitPoint(subChunk)
                val replacement: List<PreparedEmail> = if (cut != null) {
                    val mid = cut
                    sizeSplitCached(subChunk.subList(0, mid), displayName, extractor, limitBytes, selfSender) +
                        sizeSplitCached(subChunk.subList(mid, subChunk.size), displayName, extractor, limitBytes, selfSender)
                } else {
                    val retry = HtmlRenderer.renderChunk(
                        subChunk, displayName, extractor, "",
                        maxMediaBytes = mediaBudget(limitBytes),
                        selfSender = selfSender,
                    )
                    if (retry.omissions.size <= rendered.omissions.size) {
                        // Dropping media changed nothing: a single message
                        // whose text the server will not take. Retrying would
                        // rebuild the same email forever.
                        throw exc
                    }
                    listOf(PreparedEmail(subChunk, retry))
                }

                worklist.removeAt(i)
                worklist.addAll(i, replacement)

                val pendingStart = i + replacement.size
                val budget = effectiveBudget(limitBytes)
                val resplit = mutableListOf<PreparedEmail>()
                for (pending in worklist.subList(pendingStart, worklist.size)) {
                    if (pending.rendered.wireBytes <= budget) {
                        resplit.add(pending)
                    } else {
                        resplit.addAll(sizeSplitCached(pending.messages, displayName, extractor, limitBytes, selfSender))
                    }
                }
                while (worklist.size > pendingStart) worklist.removeAt(worklist.size - 1)
                worklist.addAll(resplit)

                totalEmails = worklist.size
                continue
            }

            pendingUploads?.clear(newMid)
            msgsDone += subChunk.size
            val accepted = PushResult(newMid, response.id, response.threadId, rendered.omissions)
            results.add(accepted)
            onAccepted?.invoke(accepted, labelId)
            onChunk?.invoke(i + 1, totalEmails, msgsDone, totalMsgs, subChunk)

            if (currentAnchor == null) currentAnchor = newMid
            if (currentThread == null) currentThread = response.threadId
            i++
        }
    } finally {
        extractor?.close()
    }
    return results
}

/**
 * D185: where an upload whose outcome is not yet known is remembered across runs, one row per message.
 * Message-IDs and content hashes only.
 */
interface PendingUploads {
    /** The distinct old Message-IDs under which any of [hashes] was uploaded without a confirmed outcome. */
    fun idsFor(chatId: String, hashes: Collection<String>): List<String>
    /** Every message hash that was in the email uploaded as [messageId]. */
    fun hashesFor(chatId: String, messageId: String): Set<String>
    fun record(chatId: String, messageId: String, hashes: Collection<String>)
    fun clear(messageId: String)
    fun clearHashes(chatId: String, hashes: Collection<String>)
}

internal fun messageHashOf(m: ParsedMessage): String =
    computeMessageHash(m.chatId, m.timestampIso, m.sender, m.body)

/** The three values [pushChat] hands back for the caller to persist. */
data class PushChatResult(
    val results: List<PushResult>,
    val labelId: String,
    val threadId: String,
    /** True when [shouldStop] ended the push before every email was sent. */
    val stopped: Boolean = false,
)

/**
 * Mirrors `push_chat`: chunk, make sure the label exists, push. A stored label
 * id that this backend does not own is replaced, not passed through (see
 * [labelIdIsUsable]); a dry run never touches the transport, not even to list
 * folders.
 */
fun pushChat(
    transport: MailTransport,
    displayName: String,
    messages: List<ParsedMessage>,
    chunkSize: ChunkSize = ChunkSize.Day,
    labelId: String? = null,
    anchorMessageId: String? = null,
    threadId: String? = null,
    dryRun: Boolean = false,
    sourcePath: File? = null,
    onChunk: OnChunk? = null,
    selfSender: String? = null,
    sleeper: Sleeper = SystemSleeper,
    shouldStop: () -> Boolean = { false },
    onAccepted: OnAccepted? = null,
    appVersion: String = UNKNOWN_VERSION,
    /** BUG-05: told whether a folder rename went through (true) or fell back to the old folder (false). */
    onFolderRenamed: ((Boolean) -> Unit)? = null,
    /** BUG-05: false when the stored folder cannot be trusted to be this chat's (shared legacy record). */
    allowFolderRename: Boolean = true,
    /** D177: see [pushChunks]. */
    pendingUploads: PendingUploads? = null,
    pendingChatId: String = "",
    /** UX-11: see [OnUpload]. */
    onUpload: OnUpload? = null,
): PushChatResult {
    if (messages.isEmpty()) return PushChatResult(emptyList(), labelId ?: "", threadId ?: "")

    var label = labelId
    if (!dryRun) {
        label = folderForPush(transport, label, displayName, onFolderRenamed, allowFolderRename)
    }
    val finalLabel = if (label.isNullOrEmpty()) "dry-run-label" else label

    val chunks = chunkMessages(messages, chunkSize)
    var stopped = false
    val results = pushChunks(
        transport = transport,
        displayName = displayName,
        chunks = chunks,
        labelId = finalLabel,
        chunkSize = chunkSize,
        anchorMessageId = anchorMessageId,
        threadId = threadId,
        dryRun = dryRun,
        sourcePath = sourcePath,
        onChunk = onChunk,
        onAccepted = onAccepted,
        selfSender = selfSender,
        sleeper = sleeper,
        appVersion = appVersion,
        pendingUploads = pendingUploads,
        pendingChatId = pendingChatId,
        onUpload = onUpload,
        shouldStop = {
            val stop = shouldStop()
            if (stop) stopped = true
            stop
        },
    )
    val finalThread = if (results.isNotEmpty()) results.last().threadId else (threadId ?: "")
    return PushChatResult(results, finalLabel, finalThread, stopped)
}
