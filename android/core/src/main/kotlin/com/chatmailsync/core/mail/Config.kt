package com.chatmailsync.core.mail

import java.io.File

/**
 * Kotlin port of `src/config.py` (Phase 2 "config" of the Kotlin core port —
 * see `2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md`, sections
 * D/E). A faithful behavioural twin of the Python module's constants and
 * pure functions, except where a section below explicitly says otherwise.
 *
 * NOT wired into `:app` yet — `:app` still talks to `src/config.py` through
 * Chaquopy (`ChatMailApplication.kt`'s `config.set_root(...)` call). This
 * file exists so it can be swapped in later (Phase 4) without behavioural
 * surprises, and so `ImapTransport`/`checkConnection` (Phase 1) keep the
 * subset of constants they already depend on unchanged.
 */

// ---------------------------------------------------------------------------
// Project root and directory layout
// ---------------------------------------------------------------------------

/**
 * The root-derived path bundle — the Kotlin twin of `config.py`'s
 * `_apply_root()`-computed globals (`PROJECT_ROOT`, `AUTH_DIR`, `DATA_DIR`,
 * `INBOX_DIR`, `PROCESSED_DIR`, `STATE_DB_PATH`, `LEGACY_TOKEN_FILE`,
 * `IMAP_CREDENTIALS_FILE`).
 *
 * Deliberately **not** a Kotlin twin of `config.py`'s mutable-module-global
 * design (`_explicit_root` + `set_root()` rewriting globals in place so every
 * other `src.*` module sees the same root via `from src.config import
 * DATA_DIR`-style access). The plan document already decided this
 * (section D: "Root layout becomes a `Paths(root: File)` value passed
 * explicitly (Python's `set_root` global mutation goes away)") — a JVM
 * library has no import-time-binding trap to work around, and reproducing
 * global mutable state here would just reintroduce the exact class of bug
 * that docstring in `config.py` warns callers about. `ChatMailApplication.kt`
 * already owns the one Android root (`pythonRoot`); Phase 4 will construct a
 * `RootPaths` from it once and pass it down explicitly.
 *
 * Two things about `config.py` genuinely have no Kotlin twin here, both
 * because they only exist to serve a plain Python source checkout with no
 * `Context.filesDir()` to call `set_root()` with:
 *  - `CHATMAILSYNC_ROOT` env-var override — a JVM library on Android has no
 *    equivalent notion of "the current process's env vars pick a data root";
 *    the caller always supplies a `File` explicitly.
 *  - `Path(__file__).parent.parent` repo-relative default root — a compiled
 *    JVM class has no `__file__`; there is no faithful analogue.
 * `tests/test_config.py`'s `test_default_root_falls_back_to_project_dir_when_no_override`
 * and `test_env_root_uses_chatmailsync_root_and_ignores_the_legacy_name` are
 * therefore not twinned; see [ConfigTest] for the note next to their
 * (absent) counterparts.
 */
data class RootPaths(val projectRoot: File) {
    val authDir: File = File(projectRoot, "auth")
    val dataDir: File = File(projectRoot, "data")
    val inboxDir: File = File(dataDir, "inbox")
    val processedDir: File = File(dataDir, "processed")
    val stateDbPath: File = File(dataDir, "sync_state.db")

    /** Retained only to recognise (and clean up after) a pre-v2.0.0 Google
     * sign-in user; nothing authenticates with it. See [isLegacyOauthUser]. */
    val legacyTokenFile: File = File(authDir, "token.json")

    /** Reserved legacy path only. Nothing writes to it: IMAP app passwords
     * are kept exclusively in the Android Keystore-backed credential store,
     * never on disk under auth/. */
    val imapCredentialsFile: File = File(authDir, "imap_credentials.json")
}

// ---------------------------------------------------------------------------
// Mail backend selection
//
// IMAP + an app password is the only backend. The Gmail OAuth path was
// removed in v2.0.0 — see src/config.py's comment block for the full
// reasoning (CASA verification cost, 7-day consent expiry under Testing
// status). The constants below are retained ONLY so an existing settings
// file that still says "gmail_oauth" can be recognised and migrated.
// ---------------------------------------------------------------------------

const val LEGACY_MAIL_BACKEND_GMAIL_OAUTH = "gmail_oauth"
const val MAIL_BACKEND_IMAP = "imap"
const val DEFAULT_MAIL_BACKEND = MAIL_BACKEND_IMAP

/**
 * Whether this settings snapshot belonged to a Google sign-in user.
 *
 * True on either of the two pieces of evidence the OAuth era left behind: a
 * saved backend of `"gmail_oauth"`, or a `token.json` still sitting under
 * `auth/`. Twin of `config.py:is_legacy_oauth_user`.
 *
 * `saved` is read as a `Map<String, Any?>` rather than a typed settings
 * object because the Python side also takes a loosely-typed `dict` (whatever
 * JSON/sqlite handed it) — see [resolveMailBackend] for the same choice.
 */
fun isLegacyOauthUser(saved: Map<String, Any?>, paths: RootPaths): Boolean {
    if (saved["mail_backend"] == LEGACY_MAIL_BACKEND_GMAIL_OAUTH) return true
    return paths.legacyTokenFile.exists()
}

/**
 * Picks the backend for a settings snapshot, which may predate or postdate
 * OAuth. Twin of `config.py:resolve_mail_backend`.
 *
 * A saved `"gmail_oauth"` is deliberately NOT honoured: returning it would
 * hand the caller a backend name nothing can build a transport for.
 * [isLegacyOauthUser] is how the UI knows to explain the change instead.
 */
fun resolveMailBackend(saved: Map<String, Any?>): String {
    val backend = saved["mail_backend"] as? String
    if (!backend.isNullOrEmpty() && backend != LEGACY_MAIL_BACKEND_GMAIL_OAUTH) return backend
    return DEFAULT_MAIL_BACKEND
}

// ---------------------------------------------------------------------------
// IMAP provider presets (Road B, phase 1)
//
// Host/port presets for the "pick your provider" step of IMAP setup. Dict
// order is also picker order — Gmail/Yahoo lead (proven end to end), then
// iCloud/AOL, then Fastmail, then Custom last as the escape hatch. See
// config.py's comment block for the full reasoning; do not reorder without
// changing that comment and this one together.
// ---------------------------------------------------------------------------

data class ImapProviderPreset(val label: String, val host: String?, val port: Int)

val IMAP_PROVIDERS: Map<String, ImapProviderPreset> = linkedMapOf(
    "gmail" to ImapProviderPreset("Gmail", "imap.gmail.com", 993),
    "yahoo" to ImapProviderPreset("Yahoo", "imap.mail.yahoo.com", 993),
    "icloud" to ImapProviderPreset("iCloud", "imap.mail.me.com", 993),
    "aol" to ImapProviderPreset("AOL", "imap.aol.com", 993),
    "fastmail" to ImapProviderPreset("Fastmail", "imap.fastmail.com", 993),
    "custom" to ImapProviderPreset("Custom", null, 993),
)

/**
 * Provider keys earlier releases offered and this one does not, each mapped
 * to the key a settings file naming it should now be read as. Twin of
 * `config.py:RETIRED_IMAP_PROVIDERS`.
 */
val RETIRED_IMAP_PROVIDERS: Map<String, String> = mapOf(
    // Dropped in 2.1.4 — Microsoft accepts OAuth2 only for IMAP on personal
    // Outlook.com/Hotmail/Live/MSN mailboxes; this app does no OAuth.
    "outlook" to "custom",
)

/**
 * The provider key *this* build understands, for one read out of settings.
 * Twin of `config.py:resolve_provider_key`.
 *
 * Python's version accepts an arbitrary dynamically-typed `key` and treats
 * any Python-falsy value (`None`, `""`, `0`, `[]`, ...) as `""` before
 * looking it up — `tests/test_config.py`'s
 * `test_an_unknown_provider_key_falls_back_to_gmail` exercises that with
 * `("", None, "nonesuch", 0, [])`. Kotlin is statically typed and every real
 * call site (`Migration.kt`, `MainActivity.kt`) only ever passes a
 * `String?`, so this twin takes `String?` and treats `null`/blank as `""`;
 * the `0`/`[]` cases have no meaningful Kotlin equivalent and are not
 * twinned (there is no way to call this function with an `Int` or a `List`
 * — the compiler already rejects that, which is a strictly stronger
 * guarantee than the Python runtime check it replaces).
 */
fun resolveProviderKey(key: String?): String {
    val k = key ?: ""
    if (IMAP_PROVIDERS.containsKey(k)) return k
    return RETIRED_IMAP_PROVIDERS[k] ?: "gmail"
}

/**
 * The key a *retired* provider should now be read as, or `""` if this key
 * was never one of ours. Twin of `config.py:retired_provider_landing`.
 *
 * Unlike [resolveProviderKey], this must NOT default to `"gmail"` for an
 * unrecognised key — an unknown key is left exactly as it is, because
 * guessing at it would hand the user some other provider's host without
 * saying so. See the negative test in `ConfigTest`.
 */
fun retiredProviderLanding(key: String?): String = RETIRED_IMAP_PROVIDERS[key ?: ""] ?: ""

/**
 * Whether the destination mailbox is Gmail, however we authenticate to it.
 * Twin of `config.py:is_gmail_mailbox`.
 */
fun isGmailMailbox(saved: Map<String, Any?>): Boolean {
    val host = ((saved["imap_host"] as? String) ?: "").lowercase()
    return host.contains("gmail") || host.contains("googlemail")
}

/**
 * The steps a user must actually follow to empty `folder` before a reset.
 * Twin of `config.py:mailbox_clear_steps`. Gmail's wording must never say
 * "delete the folder" — Gmail has no folders, only labels, and unlabelling a
 * conversation leaves it sitting in All Mail; the next sync would duplicate
 * it. Kept in step with `ChatDetailScreen.kt`'s copy of this wording.
 */
fun mailboxClearSteps(folder: String, gmail: Boolean): List<String> =
    if (gmail) {
        listOf(
            "In Gmail, open the label '$folder' and select every conversation.",
            "Delete them. Deleting the label itself is not enough - the mail " +
                "stays in All Mail.",
            "Empty the Bin.",
        )
    } else {
        listOf(
            "In your mail client, delete the folder '$folder' (or all mail inside it).",
            "Empty the trash, if your provider keeps one.",
        )
    }

// ---------------------------------------------------------------------------
// Gmail label hierarchy
// ---------------------------------------------------------------------------

const val LABEL_PARENT = "WhatsApp"
const val LABEL_MAX_LENGTH = 225 // Gmail's hard limit for label name length

// ---------------------------------------------------------------------------
// Message chunking defaults
// ---------------------------------------------------------------------------

/** Accepted values: "day", "hour", "week", or a positive integer (messages per email). */
const val DEFAULT_CHUNK_SIZE = "day"

// ---------------------------------------------------------------------------
// Date / timestamp parsing
// ---------------------------------------------------------------------------

/** Default date order when ambiguous. "DMY" = DD/MM/YY | "MDY" = MM/DD/YY. */
const val DATE_ORDER = "DMY"

/** Number of leading file lines to inspect when detecting the timestamp format. */
const val FORMAT_DETECTION_LINES = 20

/** Number of messages to scan when resolving DD/MM vs MM/DD ambiguity. */
const val DATE_ORDER_SCAN_MESSAGES = 50

/**
 * Ranked list of (format_key, regex_pattern) pairs tried during format
 * detection — verbatim copy of `config.py:TIMESTAMP_PATTERNS`'s pattern
 * strings, unmodified. Patterns are tried in order; the first match locks
 * the format for the whole file. Each pattern must capture exactly two
 * groups: (date_part, time_part).
 *
 * Kept as raw pattern strings rather than compiled `Regex`/`Pattern` values
 * here deliberately — the Phase 2 "parser" port (not this PR) is where
 * `Pattern.UNICODE_CHARACTER_CLASS` gets applied, per the plan document's
 * note that Java's `\s`/`\d` do not match the same character classes as
 * Python's without it. Compiling here with the wrong flags would bake in a
 * silent behavioural drift before the parser even exists.
 */
data class TimestampPattern(val key: String, val pattern: String)

val TIMESTAMP_PATTERNS: List<TimestampPattern> = listOf(
    // Format 6: bracketed, US-style with AM/PM — [3/4/25, 2:05:33 PM]
    TimestampPattern(
        "bracketed_ampm_seconds",
        "\\[(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2}:\\d{2}\\s[APap][Mm])\\]",
    ),
    // Format 1/2: bracketed, 24-hour with seconds — [14/03/25, 09:41:23]
    TimestampPattern(
        "bracketed_24h_seconds",
        "\\[(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2}:\\d{2})\\]",
    ),
    // Format 3/4: no brackets, AM/PM, no seconds — 3/14/25, 9:41 AM
    TimestampPattern(
        "plain_ampm",
        "(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2}\\s[APap][Mm])",
    ),
    // Format 7: no brackets, 24-hour, no seconds — 23/05/26, 16:42
    TimestampPattern(
        "plain_24h",
        "(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2})",
    ),
    // Format 5: dash-separated date, 24-hour no seconds — 14-03-2025 09:41
    TimestampPattern(
        "dash_24h",
        "(\\d{1,2}-\\d{1,2}-\\d{4})\\s(\\d{1,2}:\\d{2})",
    ),
)

// ---------------------------------------------------------------------------
// System message filtering
//
// Two separate lists, verbatim copies of config.py's, to avoid false
// positives — see config.py's comment block for the full reasoning.
// ---------------------------------------------------------------------------

/** Phrases that appear as the body in "Sender: <body>" lines. */
val SYSTEM_BODY_PHRASES: List<String> = listOf(
    "media omitted",
    "<media omitted>",
    "image omitted",
    "video omitted",
    "audio omitted",
    "sticker omitted",
    "document omitted",
    "gif omitted",
    "this message was deleted",
    "you deleted this message",
    "missed voice call",
    "missed video call",
)

/** Phrases that appear as bare system lines (no "Sender: " prefix). */
val SYSTEM_BARE_PHRASES: List<String> = listOf(
    "messages and calls are end-to-end encrypted",
    "joined using this group's invite link",
    " left",
    " was added",
    "changed the subject to",
    "changed the group description",
    "changed this group's icon",
    "security code changed",
)

/** Combined list, kept for parity with `config.py:SYSTEM_MESSAGE_PHRASES`. */
val SYSTEM_MESSAGE_PHRASES: List<String> = SYSTEM_BODY_PHRASES + SYSTEM_BARE_PHRASES

// ---------------------------------------------------------------------------
// Attachment recognition (Phase 2.5)
//
// Raw regex strings matched against the body of a parsed message line.
// Group 1 must capture the bare filename (no surrounding markers).
// ---------------------------------------------------------------------------

val ATTACHMENT_PATTERNS: List<String> = listOf(
    // Android: "IMG-20250314-WA0001.jpg (file attached)"
    "^(.+?)\\s+\\(file attached\\)$",
    // iOS: "<attached: 00000123-PHOTO-2025-03-14-09-41-23.jpg>"
    "^<attached:\\s*(.+?)>\\s*$",
)

// ---------------------------------------------------------------------------
// Message size limits
//
// Every number below is a WIRE size — see config.py's comment block for the
// full "[TOOBIG]" incident writeup this table exists to prevent a repeat of.
// ---------------------------------------------------------------------------

/** Fallback ceiling for a server that neither advertises RFC 7889
 * APPENDLIMIT nor appears in [PROVIDER_MAX_MESSAGE_BYTES]. */
const val DEFAULT_MAX_MESSAGE_BYTES = 25_000_000L

/** Per-provider wire limits, keyed to match [IMAP_PROVIDERS]. Only consulted
 * when the server does not advertise APPENDLIMIT. Deliberately has no entry
 * for "custom" (its host is user-supplied and unknown) — callers must fall
 * through to [DEFAULT_MAX_MESSAGE_BYTES] for it, same as Python. */
val PROVIDER_MAX_MESSAGE_BYTES: Map<String, Long> = mapOf(
    "gmail" to 25_000_000L,
    "yahoo" to 25_000_000L,
    // AOL Mail runs on the same backend as Yahoo Mail; borrows Yahoo's figure.
    "aol" to 25_000_000L,
    "icloud" to 20_000_000L,
    "fastmail" to 70_000_000L,
)

// MESSAGE_SIZE_SAFETY_FACTOR (fraction of the provider limit we will
// actually fill) already lives in Budgets.kt, ported there in Phase 1 —
// see effectiveBudget(). Not redeclared here to avoid a duplicate-const
// compile error; ConfigTest asserts its value via that declaration.

/** Maximum total decompressed bytes allowed from a single ZIP archive
 * (zip-bomb guard). */
const val MAX_ZIP_DECOMPRESSED_BYTES = 500L * 1_048_576L

// ---------------------------------------------------------------------------
// Rate limiting
// ---------------------------------------------------------------------------

/** Socket timeout for mail-server calls, in seconds; matches `config.MAIL_SOCKET_TIMEOUT`. */
const val MAIL_SOCKET_TIMEOUT_SECONDS = 180L

/** Pause between consecutive APPEND calls (seconds). */
const val API_CALL_DELAY_SECONDS = 0.1

/** Exponential backoff base delay (seconds) on 429 / 5xx. */
const val BACKOFF_BASE_DELAY = 1.0

/** Exponential backoff maximum attempts on 429 / 5xx. */
const val BACKOFF_MAX_ATTEMPTS = 5
