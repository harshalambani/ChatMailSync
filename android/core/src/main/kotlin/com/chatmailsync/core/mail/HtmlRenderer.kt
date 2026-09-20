package com.chatmailsync.core.mail

import java.math.BigInteger
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Kotlin port of `src/html_renderer.py`: the WhatsApp-light HTML email
 * renderer. Converts a list of [ParsedMessage] into a self-contained HTML
 * email body that visually mimics the WhatsApp chat interface -- speech
 * bubbles (incoming left/white, outgoing right/light green), inline images
 * embedded via `cid:` references, non-image attachments rendered as a
 * download card, and placeholders when a referenced media file is missing.
 *
 * Design constraints mirrored from the Python module: inline `style="\\u2026"`
 * only (no `<style>` blocks -- Gmail strips them in some rendering
 * contexts), no external CSS, no web fonts, no JavaScript.
 *
 * NOT wired into `:app` yet -- `:app` still talks to `src/html_renderer.py`
 * through Chaquopy (via `_build_html_mime_message`, itself still unported --
 * see `MimeBuilder.kt`'s class KDoc). This is a byte-parity port only.
 *
 * There is no autolinking anywhere in the Python source (verified by
 * reading the file in full and by running `render_chunk` on a message body
 * containing a bare URL through the real Python interpreter): a URL in a
 * message body is HTML-escaped like any other text and left as plain text,
 * never wrapped in an `<a href>` tag. This port does the same -- no anchor
 * generation exists here at all.
 */
object HtmlRenderer {

    // -----------------------------------------------------------------
    // Public data structures -- twins of the Python dataclasses.
    // -----------------------------------------------------------------

    /** An image embedded inline via a `cid:` reference. */
    data class InlinePart(val cid: String, val data: ByteArray, val mimeType: String)

    /** A non-image file attached to the email (download card in the body). */
    data class AttachmentPart(val filename: String, val data: ByteArray, val mimeType: String)

    /**
     * One media file left out because no single email could ever carry it.
     * Not an error and not a silent drop: the message itself is archived,
     * the body text survives, and the HTML carries a visible placeholder in
     * the file's place.
     */
    data class MediaOmission(val filename: String, val sizeBytes: Long, val limitBytes: Long)

    /** Complete render output for one email. */
    data class RenderedChunk(
        val htmlBody: String,
        val inlineParts: List<InlinePart> = emptyList(),
        val attachments: List<AttachmentPart> = emptyList(),
        // HTML + all media, RAW -- for logs and diagnostics.
        val totalBytes: Long = 0,
        // What the provider will actually receive -- see encodedPartBytes.
        val wireBytes: Long = 0,
        val omissions: List<MediaOmission> = emptyList(),
    )

    // -----------------------------------------------------------------
    // Wire-size projection
    // -----------------------------------------------------------------
    //
    // totalBytes above counts raw payload. Nothing is sent raw: inline
    // images and attachments are base64-encoded by the MIME builder, and so
    // is the HTML body, because a utf-8-charset text part uses base64 as its
    // transfer encoding. Comparing raw bytes against a provider's limit
    // therefore understates the real size by ~37%.

    private const val B64_LINE_LENGTH = 76 // chars per line, per RFC 2045
    private const val B64_LINE_ENDING = 2 // CRLF

    // Rough per-part cost of a MIME boundary plus Content-Type/
    // Transfer-Encoding/Disposition headers. Approximate on purpose.
    private const val PART_HEADER_BYTES = 220L

    /**
     * Bytes one payload of [rawLength] occupies once base64-encoded: 4 chars
     * per 3 bytes, rounded up, plus a CRLF every 76 chars, plus the part's
     * own headers and boundary. Mirrors `encoded_part_bytes`.
     */
    fun encodedPartBytes(rawLength: Long): Long {
        if (rawLength <= 0) return PART_HEADER_BYTES
        val b64Chars = ((rawLength + 2) / 3) * 4
        val lineBreaks = (b64Chars / B64_LINE_LENGTH) * B64_LINE_ENDING
        return b64Chars + lineBreaks + PART_HEADER_BYTES
    }

    /**
     * Largest raw payload that still fits inside [wireBudget] once encoded.
     * The inverse of [encodedPartBytes]. Mirrors `max_raw_bytes_for`.
     */
    fun maxRawBytesFor(wireBudget: Long): Long {
        val usable = wireBudget - PART_HEADER_BYTES
        if (usable <= 0) return 0
        // Undo the CRLF padding first, then the 4/3 expansion.
        val b64Chars = (usable / (1.0 + B64_LINE_ENDING.toDouble() / B64_LINE_LENGTH)).toLong()
        return maxOf(0L, (b64Chars / 4) * 3)
    }

    // -----------------------------------------------------------------
    // MIME helpers
    // -----------------------------------------------------------------

    /** Images that Gmail renders inline via `cid:` references. */
    private val INLINE_IMAGE_TYPES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
    )

    /**
     * Emoji icons for the media download card, written as explicit `\uXXXX`
     * surrogate-pair escapes (no literal non-ASCII characters in this
     * source file): video (a film-camera emoji), audio (a musical-note
     * emoji), application (a page-facing-up emoji), text (a memo emoji).
     */
    private val TYPE_ICONS: Map<String, String> = mapOf(
        "video" to "\uD83C\uDFA5",
        "audio" to "\uD83C\uDFB5",
        "application" to "\uD83D\uDCC4",
        "text" to "\uD83D\uDCDD",
    )

    /** Fallback/paperclip icon. */
    private const val PAPERCLIP_ICON = "\uD83D\uDCCE"

    /** Middle-dot separator used between metadata fields, U+00B7. */
    private const val MIDDOT = "\u00B7"

    /** Em-dash separator used in placeholder text, U+2014. */
    private const val EM_DASH = "\u2014"

    private fun isInlineImage(mimeType: String): Boolean = mimeType in INLINE_IMAGE_TYPES

    private fun typeIcon(mimeType: String): String =
        TYPE_ICONS[mimeType.substringBefore('/')] ?: PAPERCLIP_ICON

    // -----------------------------------------------------------------
    // Sender colour palette (group chats -- deterministic per sender name)
    // -----------------------------------------------------------------

    private val SENDER_COLORS = listOf(
        "#128C7E", "#7B68EE", "#E91E63", "#FF9800",
        "#4CAF50", "#2196F3", "#9C27B0", "#00BCD4",
    )

    /**
     * Mirrors `_sender_color`: `int(hashlib.md5(sender.encode("utf-8")).
     * hexdigest(), 16) % len(_SENDER_COLORS)`. Python's `int(hex, 16)`
     * treats the 128-bit digest as an unsigned magnitude, so the Kotlin twin
     * uses `BigInteger(1, digestBytes)` (an explicit positive sign) rather
     * than the signed two's-complement constructor, then takes the modulus
     * against a [BigInteger] the same way Python's `%` would -- both are
     * non-negative here since both operands are non-negative, so there is no
     * floor-vs-truncating-division divergence to worry about.
     */
    private fun senderColor(sender: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(sender.toByteArray(Charsets.UTF_8))
        val idx = BigInteger(1, digest).mod(BigInteger.valueOf(SENDER_COLORS.size.toLong())).toInt()
        return SENDER_COLORS[idx]
    }

    // -----------------------------------------------------------------
    // Attachment marker stripping
    // -----------------------------------------------------------------

    private val ATTACHMENT_MARKER_RES = listOf(
        Regex("\\s*\\(file attached\\)\\s*$", RegexOption.IGNORE_CASE),
        Regex("^<attached:\\s*.+?>\\s*$", RegexOption.IGNORE_CASE),
    )

    /** Remove "(file attached)" / "<attached: ...>" suffixes from display text. */
    private fun stripAttachmentMarkers(text: String): String {
        var result = text
        for (pattern in ATTACHMENT_MARKER_RES) {
            result = pattern.replace(result, "").trim()
        }
        return result
    }

    // -----------------------------------------------------------------
    // HTML escaping -- twin of Python's `html.escape(s, quote=True)`.
    // -----------------------------------------------------------------

    /**
     * Mirrors CPython's `html.escape` with its default `quote=True`: `&`
     * first (so the subsequent substitutions' own `&`-prefixed replacement
     * text is never re-escaped), then `<`, `>`, `"`, and `'` (the last as
     * the numeric entity `&#x27;`, exactly as Python emits it -- not the
     * named `&apos;` entity, which Python's implementation never uses).
     */
    internal fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")

    // -----------------------------------------------------------------
    // Inline styles -- all hardcoded; no <style> blocks
    // -----------------------------------------------------------------

    private const val PAGE =
        "margin:0;padding:0;background:#f0f2f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif"
    private const val WRAP = "max-width:640px;margin:0 auto;padding:8px 14px 14px 14px"
    private const val SEP_ROW = "text-align:center;margin:12px 0 14px"
    private const val SEP_PILL =
        "background:#ffffff;padding:4px 14px;border-radius:8px;font-size:12px;color:#667781;box-shadow:0 1px 1px rgba(0,0,0,.13)"

    private const val TIME = "font-size:11px;color:#667781;text-align:right;margin-top:3px"
    private const val BODY =
        "font-size:14px;color:#111b21;white-space:pre-wrap;line-height:1.4;word-break:break-word"
    private const val IMG =
        "max-width:240px;max-height:240px;border-radius:4px;display:block;margin-bottom:4px"

    private const val PLACEHOLDER =
        "font-size:12px;color:#667781;font-style:italic;" +
            "background:#f0f2f5;padding:4px 8px;border-radius:4px;margin-bottom:4px"
    private const val CARD =
        "display:flex;align-items:center;gap:8px;background:#f0f2f5;border-radius:6px;padding:8px 10px;margin-bottom:4px"
    private const val CARD_ICON = "font-size:22px;line-height:1"
    private const val CARD_NAME = "font-size:12px;font-weight:600;color:#111b21"
    private const val CARD_META = "font-size:11px;color:#667781;margin-top:1px"

    private fun rowStyle(outgoing: Boolean): String {
        val align = if (outgoing) "flex-end" else "flex-start"
        return "display:flex;justify-content:$align;margin-bottom:2px"
    }

    private fun bubbleStyle(outgoing: Boolean): String {
        val bg = if (outgoing) "#DCF8C6" else "#ffffff"
        val radius = if (outgoing) "8px 0 8px 8px" else "0 8px 8px 8px"
        return "background:$bg;border-radius:$radius;" +
            "padding:7px 10px 5px 10px;max-width:72%;" +
            "box-shadow:0 1px 1px rgba(0,0,0,.13)"
    }

    // -----------------------------------------------------------------
    // Day-separator pill date formatting
    // -----------------------------------------------------------------
    //
    // Mirrors Python's `timestamp.strftime("%d %B %Y").lstrip("0")`. A
    // single "d" pattern letter (rather than "dd") already omits the
    // leading zero on the day, producing the same result as the strip --
    // verified against real Python output for days 1, 3, 9, 10 and 31 (e.g.
    // day 3 of May 2025 -> "3 May 2025" on both sides; the year is never
    // affected since lstrip only ever touches the very start of the whole
    // string, i.e. only the day's own leading zero).
    private val DAY_PILL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    private val TIME_HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    /**
     * Render [messages] into a WhatsApp-light HTML email. Mirrors
     * `render_chunk`.
     *
     * @param messages Messages for this chunk (already time-bounded by the
     *   caller).
     * @param displayName Chat name, shown in the day-separator pill.
     * @param extractor Open [MediaExtractor] for this chat's source file, or
     *   null.
     * @param label Optional suffix added to the separator (e.g. "Part 2/3").
     * @param maxMediaBytes Largest single media file that can be carried,
     *   raw. A file over this is replaced by a visible placeholder and
     *   recorded in [RenderedChunk.omissions]. Null disables the check
     *   entirely (the default).
     * @param selfSender The account owner's name as it appears in the sender
     *   position of this export. Messages from that name are drawn as
     *   outgoing. Null falls back to [SelfSender]'s own fallback constant.
     */
    fun renderChunk(
        messages: List<ParsedMessage>,
        displayName: String,
        extractor: MediaExtractor?,
        label: String = "",
        maxMediaBytes: Long? = null,
        selfSender: String? = null,
    ): RenderedChunk {
        if (messages.isEmpty()) {
            return RenderedChunk(htmlBody = "")
        }

        val inlineParts = mutableListOf<InlinePart>()
        val attachments = mutableListOf<AttachmentPart>()
        val omissions = mutableListOf<MediaOmission>()
        val bodyParts = mutableListOf<String>()

        // Day-separator pill.
        var dayStr = messages[0].timestamp.format(DAY_PILL_FORMAT)
        if (label.isNotEmpty()) {
            dayStr += "  $MIDDOT  $label"
        }
        bodyParts.add(
            "<div style=\"$SEP_ROW\">" +
                "<span style=\"$SEP_PILL\">${escapeHtml(dayStr)}</span>" +
                "</div>",
        )

        for (msg in messages) {
            val outgoing = isOutgoing(msg.sender, selfSender)
            bodyParts.add(
                renderBubble(
                    msg, outgoing, extractor, inlineParts, attachments,
                    maxMediaBytes, omissions,
                ),
            )
        }

        val htmlBody =
            "<!DOCTYPE html><html><head>" +
                "<meta charset=\"UTF-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "</head>" +
                "<body style=\"$PAGE\">" +
                "<div style=\"$WRAP\">" +
                bodyParts.joinToString("") +
                "</div></body></html>"

        val htmlBytes = htmlBody.toByteArray(Charsets.UTF_8).size.toLong()

        val totalBytes = htmlBytes +
            inlineParts.sumOf { it.data.size.toLong() } +
            attachments.sumOf { it.data.size.toLong() }

        // What the provider will actually be handed -- see encodedPartBytes.
        val wireBytes = encodedPartBytes(htmlBytes) +
            inlineParts.sumOf { encodedPartBytes(it.data.size.toLong()) } +
            attachments.sumOf { encodedPartBytes(it.data.size.toLong()) }

        return RenderedChunk(
            htmlBody = htmlBody,
            inlineParts = inlineParts,
            attachments = attachments,
            totalBytes = totalBytes,
            wireBytes = wireBytes,
            omissions = omissions,
        )
    }

    // -----------------------------------------------------------------
    // Bubble rendering
    // -----------------------------------------------------------------

    private fun renderBubble(
        msg: ParsedMessage,
        outgoing: Boolean,
        extractor: MediaExtractor?,
        inlineParts: MutableList<InlinePart>,
        attachments: MutableList<AttachmentPart>,
        maxMediaBytes: Long?,
        omissions: MutableList<MediaOmission>,
    ): String {
        val timeStr = msg.timestamp.format(TIME_HHMM)

        // Sender name label (incoming only).
        var senderHtml = ""
        if (!outgoing) {
            val color = senderColor(msg.sender)
            senderHtml =
                "<div style=\"font-size:12px;font-weight:600;color:$color;margin-bottom:3px\">" +
                    "${escapeHtml(msg.sender)}</div>"
        }

        // Media content.
        var mediaHtml = ""
        val attachmentFilename = msg.attachmentFilename
        if (!attachmentFilename.isNullOrEmpty()) {
            mediaHtml = renderMedia(
                attachmentFilename, extractor, inlineParts, attachments,
                maxMediaBytes, omissions,
            )
        }

        // Text body (strip the attachment marker for display).
        val displayBody = if (!attachmentFilename.isNullOrEmpty()) {
            stripAttachmentMarkers(msg.body)
        } else {
            msg.body
        }
        val bodyHtml = if (displayBody.trim().isNotEmpty()) {
            "<div style=\"$BODY\">${escapeHtml(displayBody)}</div>"
        } else {
            ""
        }

        return "<div style=\"${rowStyle(outgoing)}\">" +
            "<div style=\"${bubbleStyle(outgoing)}\">" +
            senderHtml +
            mediaHtml +
            bodyHtml +
            "<div style=\"$TIME\">$timeStr</div>" +
            "</div></div>\n"
    }

    /** Mirrors `_format_size`: used only for the too-large-to-email placeholder. */
    private fun formatSize(numBytes: Long): String {
        val mb = numBytes / 1_000_000.0
        return if (mb >= 1) {
            String.format(Locale.ROOT, "%.1f MB", mb)
        } else {
            String.format(Locale.ROOT, "%.0f KB", numBytes / 1000.0)
        }
    }

    /**
     * Return the HTML fragment for one media item, and populate the part
     * lists. Mirrors `_render_media`'s four branches: no extractor, file not
     * found, file found but over the size cap, and file found and within the
     * cap (embedded inline or attached as a download card).
     */
    private fun renderMedia(
        filename: String,
        extractor: MediaExtractor?,
        inlineParts: MutableList<InlinePart>,
        attachments: MutableList<AttachmentPart>,
        maxMediaBytes: Long?,
        omissions: MutableList<MediaOmission>,
    ): String {
        if (extractor == null) {
            return "<div style=\"$PLACEHOLDER\">" +
                "$PAPERCLIP_ICON ${escapeHtml(filename)}" +
                "</div>"
        }

        val result = extractor.resolve(filename)
        if (result == null) {
            // Never leaks the caller's actual (possibly absolute) local
            // filesystem path -- only the export-relative `filename` the
            // message itself referenced is ever echoed back here.
            return "<div style=\"$PLACEHOLDER\">" +
                "[${escapeHtml(filename)} $EM_DASH not found in export]" +
                "</div>"
        }

        val (data, mimeType) = result

        // One file too big for any single email.
        if (maxMediaBytes != null && data.size.toLong() > maxMediaBytes) {
            omissions.add(
                MediaOmission(
                    filename = filename,
                    sizeBytes = data.size.toLong(),
                    limitBytes = maxMediaBytes,
                ),
            )
            return "<div style=\"$PLACEHOLDER\">" +
                "$PAPERCLIP_ICON ${escapeHtml(filename)} $EM_DASH ${formatSize(data.size.toLong())}, " +
                "too large to email (limit ${formatSize(maxMediaBytes)}). " +
                "Not included; it stays in your WhatsApp export." +
                "</div>"
        }

        if (isInlineImage(mimeType)) {
            val cid = "img-${UUID.randomUUID().toString().replace("-", "").substring(0, 12)}"
            inlineParts.add(InlinePart(cid = cid, data = data, mimeType = mimeType))
            return "<img src=\"cid:$cid\" alt=\"${escapeHtml(filename)}\" style=\"$IMG\">"
        }

        // Non-image: render a download card and attach the file.
        val icon = typeIcon(mimeType)
        val sizeKb = data.size / 1024.0
        val sizeStr = if (sizeKb < 1024) {
            String.format(Locale.ROOT, "%.0f KB", sizeKb)
        } else {
            String.format(Locale.ROOT, "%.1f MB", sizeKb / 1024.0)
        }
        attachments.add(AttachmentPart(filename = filename, data = data, mimeType = mimeType))
        return "<div style=\"$CARD\">" +
            "<span style=\"$CARD_ICON\">$icon</span>" +
            "<div>" +
            "<div style=\"$CARD_NAME\">${escapeHtml(filename)}</div>" +
            "<div style=\"$CARD_META\">$sizeStr $MIDDOT ${escapeHtml(mimeType)}</div>" +
            "</div></div>"
    }
}
