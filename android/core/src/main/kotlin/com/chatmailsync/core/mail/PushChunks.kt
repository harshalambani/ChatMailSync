package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException

/**
 * Kotlin port of the push half of `src/mail_client.py`: `_size_split_cached`,
 * `_prepare_emails`, `_insert_with_backoff`, `PushResult`, `push_chunks` and
 * `push_chat`.
 *
 * Not wired into `:app`. No logging (the mail package has none; Python's
 * `log.*` calls are diagnostic only) and no terminal progress bar
 * (`_print_progress` only ever drew on a console; `onChunk` is the progress
 * channel off a console, exactly as the Python docstring says).
 */

/** What [insertWithBackoff] waits with. Tests inject one that records instead of sleeping. */
fun interface Sleeper {
    fun sleep(seconds: Double)
}

/** The real sleeper. */
val SystemSleeper = Sleeper { seconds -> Thread.sleep((seconds * 1000.0).toLong()) }

/** Outcome of pushing one email. Mirrors `PushResult`. */
class PushResult(
    val messageId: String,
    val gmailMessageId: String,
    val threadId: String,
    val omissions: List<HtmlRenderer.MediaOmission> = emptyList(),
)

/** One email's worth of messages and its rendering. */
class PreparedEmail(val messages: List<ParsedMessage>, val rendered: HtmlRenderer.RenderedChunk)

/** Progress callback: (email index 1-based, total emails, messages done, total messages, messages in this email). */
typealias OnChunk = (Int, Int, Int, Int, List<ParsedMessage>) -> Unit

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
    if (projected <= budget || messages.size <= 1) {
        return listOf(PreparedEmail(messages, rendered))
    }
    val mid = messages.size / 2
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
                val rendered = HtmlRenderer.renderChunk(
                    piece.messages, displayName, extractor, "Part ${k + 1}/$n",
                    maxMediaBytes = mediaBudget(limitBytes),
                    selfSender = selfSender,
                )
                result.add(PreparedEmail(piece.messages, rendered))
            } else {
                result.add(piece)
            }
        }
    }
    return result
}

private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504)

/**
 * Mirrors `_insert_with_backoff`: insert with exponential backoff on 429 / 5xx
 * and on network errors. Delays are [BACKOFF_BASE_DELAY], doubling each time,
 * at most [BACKOFF_MAX_ATTEMPTS] attempts. Any other [MailTransportError] (a
 * 4xx other than 429, including a 413 size refusal) is thrown at once: a
 * retry cannot fix it, and the size case is the caller's cue to split.
 * A pause of [API_CALL_DELAY_SECONDS] follows every success.
 */
fun insertWithBackoff(
    transport: MailTransport,
    raw: ByteArray,
    folder: String,
    threadId: String?,
    sleeper: Sleeper = SystemSleeper,
): ImapTransport.InsertResult {
    var delay = BACKOFF_BASE_DELAY
    var attempt = 1
    while (true) {
        try {
            val response = transport.messagesInsert(raw, folder, threadId)
            sleeper.sleep(API_CALL_DELAY_SECONDS)
            return response
        } catch (exc: MailTransportError) {
            val retry = exc.status in RETRYABLE_STATUSES && attempt < BACKOFF_MAX_ATTEMPTS
            if (!retry) throw exc
            sleeper.sleep(delay)
            delay *= 2
        } catch (exc: IOException) {
            if (attempt >= BACKOFF_MAX_ATTEMPTS) throw exc
            sleeper.sleep(delay)
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
 * A size refusal (413 / a size marker) is not retried: the run's ceiling is
 * lowered below what was just tried, the email is halved (lossless) while it
 * holds more than one message, and only a lone message has its media dropped
 * for a placeholder. A lone message whose text alone is refused is a real
 * failure and is thrown. Everything still pending is re-split against the
 * lowered ceiling, so one refusal teaches the rest of the chat.
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
): List<PushResult> {
    val results = mutableListOf<PushResult>()
    var currentAnchor = anchorMessageId
    var currentThread = threadId
    var limitBytes = transport.maxMessageBytes

    val extractor = if (sourcePath != null) MediaExtractor(sourcePath) else null
    try {
        val emailList = prepareEmails(chunks, displayName, extractor, limitBytes, selfSender)
        var totalEmails = emailList.size
        val totalMsgs = emailList.sumOf { it.messages.size }
        var msgsDone = 0

        val worklist = emailList.toMutableList()
        var i = 0
        while (i < worklist.size) {
            val subChunk = worklist[i].messages
            val rendered = worklist[i].rendered
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
                inReplyTo = inReplyTo,
                references = currentAnchor,
            )

            val response = try {
                insertWithBackoff(transport, raw, labelId, currentThread, sleeper)
            } catch (exc: Exception) {
                if (!isTooLarge(exc)) throw exc

                // Measured as our own projection (wireBytes), the same unit
                // the budget uses; never the length of the encoded message.
                val attempted = rendered.wireBytes
                limitBytes = minOf(limitBytes, (attempted * 0.9).toLong())

                val replacement: List<PreparedEmail> = if (subChunk.size > 1) {
                    val mid = subChunk.size / 2
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

            msgsDone += subChunk.size
            results.add(PushResult(newMid, response.id, response.threadId, rendered.omissions))
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

/** The three values [pushChat] hands back for the caller to persist. */
data class PushChatResult(val results: List<PushResult>, val labelId: String, val threadId: String)

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
): PushChatResult {
    if (messages.isEmpty()) return PushChatResult(emptyList(), labelId ?: "", threadId ?: "")

    var label = labelId
    if (!dryRun && !labelIdIsUsable(transport, label, displayName)) {
        label = getOrCreateLabel(transport, displayName)
    }
    val finalLabel = if (label.isNullOrEmpty()) "dry-run-label" else label

    val chunks = chunkMessages(messages, chunkSize)
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
        selfSender = selfSender,
        sleeper = sleeper,
    )
    val finalThread = if (results.isNotEmpty()) results.last().threadId else (threadId ?: "")
    return PushChatResult(results, finalLabel, finalThread)
}
