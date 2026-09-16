package com.chatmailsync.app

/**
 * Strips all whitespace from a Gmail app password before it is used to
 * connect or saved (D2). Google shows an app password as four groups of
 * four letters with spaces between them ("abcd efgh ijkl mnop") so it is
 * easy to read on screen; pasting that exact text should work rather than
 * silently failing a login because of the spaces. No other provider is
 * touched here -- Yahoo and custom IMAP passwords come back exactly as
 * typed, because there is no confirmed provider convention of showing
 * spaces there, and silently rewriting a password the user typed on
 * purpose would be the wrong default.
 *
 * Deliberately a pure function of the already-typed value, not something
 * that rewrites the field as the user types: it runs once, at the moment
 * the password is used to test a connection or saved, so what the user
 * sees in the field is always exactly what they typed.
 */
fun normalizeAppPassword(provider: String, raw: String): String =
    if (provider == "gmail") raw.filterNot { it.isWhitespace() } else raw

/**
 * A gentle, non-blocking hint shown under an app-password field when the
 * normalized password does not look like the shape Gmail and Yahoo actually
 * issue -- 16 letters, no digits or punctuation. Returns null when the
 * password looks right, when it is blank (nothing to judge yet), and for
 * any provider this app does not know a confirmed shape for (iCloud,
 * Fastmail, custom IMAP) -- a wrong guess there would be worse than no hint.
 *
 * This must never gate Save or Test connection: providers occasionally
 * issue a password in a different shape, and the hint is advisory only.
 * It also never includes [normalized] itself -- this is rendered as
 * supportingText directly under the field, and the one thing it must never
 * do is echo the secret back onto the screen.
 */
fun appPasswordHint(provider: String, normalized: String): String? {
    if (normalized.isBlank()) return null
    if (provider != "gmail" && provider != "yahoo") return null
    val looksRight = normalized.length == 16 && normalized.all { it.isLetter() }
    return if (looksRight) {
        null
    } else {
        "App passwords are usually 16 letters. Check you copied the whole thing."
    }
}
