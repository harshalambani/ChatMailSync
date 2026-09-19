package com.chatmailsync.core.mail

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Kotlin port of `src/media_extractor.py`.
 *
 * Resolves an attachment filename to `(bytes, mimeType)` from either a
 * WhatsApp export ZIP archive (opened once, case-insensitive basename index)
 * or a plain-file directory (a sibling file next to the source `.txt`).
 *
 * A [MediaExtractor] is constructed once per source file and reused across
 * every chunk parsed from that file, exactly like the Python class's own
 * docstring describes. It must be closed when done -- prefer Kotlin's `use {}`
 * (this class implements [AutoCloseable]), the direct analogue of Python's
 * `with MediaExtractor(source_path) as extractor: ...` context manager.
 *
 * Which mode is active is controlled by a single field ([zipFile]) being
 * non-null or null after construction -- never a separate mode enum -- the
 * same single-field-controls-branching design the Python class uses
 * (`self._zipfile is not None`). See section D/E of
 * `2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md` for the wider
 * Phase 2 porting rationale.
 *
 * NOT wired into `:app` yet -- `:app` still talks to `src/media_extractor.py`
 * through Chaquopy. This is a byte-parity port only.
 *
 * ## ZIP detection
 * The ZIP is detected by attempting to open it as one (the same
 * `tryOpenZip`-style idiom already used by [readChatText] in `Parser.kt`,
 * itself the Kotlin analogue of Python's `zipfile.is_zipfile()` pre-check
 * followed by a `zipfile.BadZipFile` catch). Both of Python's distinct
 * "not a zip" and "corrupt zip" outcomes collapse into a single
 * `ZipFile(file)` try/catch here, exactly as `Parser.kt` already does --
 * both end in the same "fall back to plain-file mode" state.
 *
 * ## Zip-bomb guard
 * Total uncompressed size across all ZIP entries is checked against
 * [MAX_ZIP_DECOMPRESSED_BYTES] *before* the basename index is built.
 * Exceeding it is a **constructor failure** (twin of Python's `ValueError`,
 * raised even though the ZIP itself opened fine) -- not a null/fallback
 * outcome -- so this constructor throws [IllegalArgumentException] in that
 * case, the same ValueError-to-IllegalArgumentException mapping used
 * elsewhere in this port (e.g. `State.kt`).
 *
 * ## Path traversal
 * Both ZIP-mode lookup and plain-file-mode lookup use only the *basename* of
 * the requested filename ([pathBasename], the Kotlin twin of
 * `pathlib.Path(filename).name`) -- this is the actual traversal-prevention
 * mechanism, not any explicit blocklist. A filename like
 * `"../../etc/passwd"` resolves to the bare basename `"passwd"`, which is
 * then looked up strictly inside the ZIP's basename index or strictly
 * alongside the source file -- it can never escape either scope.
 *
 * ## MIME type resolution
 * Python resolves MIME types via the stdlib `mimetypes.guess_type()`. On the
 * machine used to generate this port's golden fixtures (Windows), the
 * module-level `mimetypes.guess_type()` lazily merges Windows-registry MIME
 * associations that do not exist on the real production target (Android via
 * Chaquopy, a Linux-based CPython with no Windows registry) -- so the golden
 * was generated from a fresh, registry-free `mimetypes.MimeTypes(filenames=())`
 * instance instead of the polluted module-level singleton. [guessMimeType]
 * below is a hand-curated Kotlin table restricted to the extensions actually
 * reachable from real WhatsApp export attachments (images, video, audio,
 * documents, contact cards) verified byte-for-byte against that same
 * registry-free instance -- it is not a full port of Python's `mimetypes`
 * module (which covers hundreds of unrelated extensions).
 *
 * No logging framework exists in `:core` (see e.g. `Parser.kt`); Python's
 * `log.debug`/`log.warning` calls are diagnostic-only and are not twinned
 * here.
 */
class MediaExtractor(private val sourcePath: File) : AutoCloseable {

    /** Non-null in ZIP mode; null in plain-file mode. Controls all branching. */
    private var zipFile: ZipFile? = null

    /** Lowercase basename -> full ZIP entry name. First occurrence wins. */
    private val zipIndex: MutableMap<String, String> = mutableMapOf()

    init {
        val zf = tryOpenZip(sourcePath)
        if (zf != null) {
            // Zip-bomb guard: check total uncompressed size before indexing.
            // Tripping this throws out of the constructor entirely (twin of
            // Python's ValueError escaping __init__) -- it is not caught
            // below, since that catch is only for the "not a valid zip"
            // open failure, which tryOpenZip already handled.
            val entries = zf.entries().toList()
            val totalUncompressed = entries.sumOf { if (it.size >= 0) it.size else 0L }
            if (totalUncompressed > MAX_ZIP_DECOMPRESSED_BYTES) {
                zf.close()
                throw IllegalArgumentException(
                    "ZIP archive '${sourcePath.name}' would decompress to " +
                        "${"%,d".format(totalUncompressed)} bytes, which exceeds the " +
                        "${"%,d".format(MAX_ZIP_DECOMPRESSED_BYTES)}-byte safety limit.",
                )
            }
            for (entry in entries) {
                val basenameLower = pathBasename(entry.name).lowercase(Locale.ROOT)
                zipIndex.putIfAbsent(basenameLower, entry.name)
            }
            zipFile = zf
        }
        // else: plain-file mode -- look alongside sourcePath.parent.
    }

    override fun close() {
        zipFile?.close()
        zipFile = null
    }

    /**
     * Return `(bytes, mimeType)` for [filename], or null if not found.
     * Lookup is case-insensitive on the basename.
     */
    fun resolve(filename: String): Pair<ByteArray, String>? {
        val key = pathBasename(filename).lowercase(Locale.ROOT)

        val data: ByteArray = run {
            val zf = zipFile
            if (zf != null) {
                val entryName = zipIndex[key] ?: return null
                try {
                    val entry = zf.getEntry(entryName) ?: return null
                    zf.getInputStream(entry).use { it.readBytes() }
                } catch (_: Exception) {
                    return null
                }
            } else {
                // Plain-file mode: check same directory as the source .txt.
                // Only the basename is used, preventing path-traversal via a
                // crafted filename (see the class KDoc).
                val parent = sourcePath.parentFile
                var candidate: File? = parent?.let { File(it, pathBasename(filename)) }
                if (candidate == null || !candidate.exists()) {
                    candidate = null
                    try {
                        val siblings = parent?.listFiles()
                        if (siblings != null) {
                            for (sibling in siblings) {
                                if (sibling.isFile && sibling.name.lowercase(Locale.ROOT) == key) {
                                    candidate = sibling
                                    break
                                }
                            }
                        }
                    } catch (_: SecurityException) {
                        // Twin of Python's `except OSError: pass`.
                    }
                }
                val found = candidate
                if (found == null || !found.exists()) {
                    return null
                }
                try {
                    found.readBytes()
                } catch (_: Exception) {
                    return null
                }
            }
        }

        val mimeType = guessMimeType(filename) ?: "application/octet-stream"
        return data to mimeType
    }
}

/**
 * Twin of `pathlib.Path(s).name`: the final path component after splitting
 * on forward slashes only, with trailing slashes ignored and leading dots
 * of an all-dots run before a separator producing an empty result. Real
 * WhatsApp export filenames -- and every filename `resolve()` above ever
 * sees from the real ingestion path -- never contain a backslash, so unlike
 * `java.io.File.getName()` (which splits on the *platform* separator, `\`
 * on Windows and `/` on Android/Linux -- a real cross-platform divergence
 * for backslash-containing input) this helper always splits on `/` only,
 * matching Python's `pathlib.PurePosixPath` semantics on every platform this
 * code ever runs on, including the Windows machine used to generate golden
 * fixtures.
 */
internal fun pathBasename(path: String): String {
    var trimmed = path
    while (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length - 1)
    }
    val idx = trimmed.lastIndexOf('/')
    return if (idx >= 0) trimmed.substring(idx + 1) else trimmed
}

/** Twin of `Parser.kt`'s `tryOpenZip` -- detect a ZIP by attempting to open it. */
private fun tryOpenZip(filepath: File): ZipFile? = try {
    ZipFile(filepath)
} catch (_: ZipException) {
    null
} catch (_: IOException) {
    null
}

/**
 * Twin of `mimetypes.guess_type(filename)[0]`, evaluated against a fresh,
 * registry-free `mimetypes.MimeTypes(filenames=())` instance (see the class
 * KDoc above), restricted to the extensions reachable from real WhatsApp
 * export attachments. Returns null when Python's `guess_type` would also
 * return `None` for the suffix (the caller then falls back to
 * `"application/octet-stream"`, exactly as Python's `mime_type or
 * "application/octet-stream"` does).
 */
internal fun guessMimeType(filename: String): String? {
    val ext = guessExtension(filename) ?: return null
    return MIME_TYPES_BY_EXTENSION[ext]
}

/**
 * Twin of the extension Python's `os.path.splitext` (as used internally by
 * `mimetypes.guess_type`) derives from a filename: the last `.`-delimited
 * suffix after the last `/`, lowercased -- except when every character
 * between the last separator and that dot is itself a dot, in which case
 * there is no extension (matching `Path(".jpg").suffix == ""`).
 */
private fun guessExtension(filename: String): String? {
    val sepIndex = filename.lastIndexOf('/')
    val dotIndex = filename.lastIndexOf('.')
    if (dotIndex <= sepIndex) return null
    if (dotIndex == filename.length - 1) return null // trailing dot: no extension
    var onlyDots = true
    for (i in (sepIndex + 1) until dotIndex) {
        if (filename[i] != '.') {
            onlyDots = false
            break
        }
    }
    if (onlyDots) return null
    return filename.substring(dotIndex).lowercase(Locale.ROOT)
}

/**
 * Hand-curated subset of Python's `mimetypes` default `types_map`, verified
 * against a fresh `mimetypes.MimeTypes(filenames=())` instance, restricted
 * to extensions reachable from real WhatsApp export attachments. Extensions
 * intentionally absent here (e.g. `.m4a`, `.docx`, `.ogg`, `.amr`, `.caf`)
 * are absent from Python's own default table too -- `guessMimeType` returns
 * null for them, matching Python's `None`, and the caller falls back to
 * `"application/octet-stream"`.
 */
private val MIME_TYPES_BY_EXTENSION: Map<String, String> = mapOf(
    ".jpg" to "image/jpeg",
    ".jpeg" to "image/jpeg",
    ".png" to "image/png",
    ".gif" to "image/gif",
    ".webp" to "image/webp",
    ".bmp" to "image/bmp",
    ".tif" to "image/tiff",
    ".tiff" to "image/tiff",
    ".heic" to "image/heic",
    ".heif" to "image/heif",
    ".mp4" to "video/mp4",
    ".3gp" to "audio/3gpp",
    ".mov" to "video/quicktime",
    ".avi" to "video/x-msvideo",
    ".webm" to "video/webm",
    ".opus" to "audio/opus",
    ".mp3" to "audio/mpeg",
    ".mp2" to "audio/mpeg",
    ".aac" to "audio/aac",
    ".wav" to "audio/x-wav",
    ".pdf" to "application/pdf",
    ".doc" to "application/msword",
    ".zip" to "application/zip",
    ".vcf" to "text/x-vcard",
    ".txt" to "text/plain",
)
