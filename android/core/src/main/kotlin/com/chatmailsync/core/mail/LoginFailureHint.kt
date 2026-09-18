package com.chatmailsync.core.mail

/**
 * Kotlin port of `src/mail_client.py`'s `_gmail_like`/`_microsoft_like`/
 * `login_failure_hint` (lines ~906-977). Used by both [JvmImapConnection]
 * (a rejected LOGIN) and the `check_connection` equivalent (the "Signing in"
 * stage) so a rejected password turns into a concrete next action instead of
 * "check your password".
 */

/**
 * True when a rejected password is most likely a *normal* Google password.
 * Matched on whole domain labels (not a bare substring) on both host and
 * mail address, exactly like the Python original -- so
 * "imap.notgmail.com.example.net" or "gmail.com.phish.example" do not
 * false-positive.
 */
fun gmailLike(host: String?, email: String?): Boolean {
    val domains = listOf("gmail.com", "googlemail.com")
    val hostname = (host ?: "").trim().lowercase().trimEnd('.')
    if (domains.any { hostname == it || hostname.endsWith(".$it") }) return true
    val mailDomain = (email ?: "").trim().lowercase().substringAfterLast("@", "")
    return domains.any { mailDomain == it || mailDomain.endsWith(".$it") }
}

/** True when the mailbox being signed in to is a Microsoft one. Same whole-domain-label matching as [gmailLike]. */
fun microsoftLike(host: String?, email: String?): Boolean {
    val hostDomains = listOf("office365.com", "outlook.com", "office.com", "hotmail.com")
    val mailDomains = listOf(
        "outlook.com", "hotmail.com", "hotmail.co.uk", "live.com",
        "msn.com", "passport.com", "windowslive.com",
    )
    val hostname = (host ?: "").trim().lowercase().trimEnd('.')
    if (hostDomains.any { hostname == it || hostname.endsWith(".$it") }) return true
    val mailDomain = (email ?: "").trim().lowercase().substringAfterLast("@", "")
    return mailDomains.any { mailDomain == it || mailDomain.endsWith(".$it") }
}

/** Mirrors `login_failure_hint`: the one sentence that turns a rejected login into a next action. */
fun loginFailureHint(host: String?, email: String?): String {
    if (gmailLike(host, email)) {
        return "Gmail rejected this password. Gmail needs a 16-character app " +
            "password, not your normal Google password — your account " +
            "password will always be rejected here."
    }
    if (microsoftLike(host, email)) {
        return "Microsoft mailboxes cannot be used with this app. Microsoft " +
            "switched off app-password sign-in for Outlook.com, Hotmail, " +
            "Live and MSN accounts in September 2024, and for work or school " +
            "Microsoft 365 accounts before that — they all need OAuth now, " +
            "which this app does not do. Use a mailbox that still issues an " +
            "app password (Gmail, Yahoo, iCloud or Fastmail), or any other " +
            "IMAP server, as the destination."
    }
    return "The server rejected this sign-in. That usually means a wrong app " +
        "password, but some providers also need IMAP switched on in their " +
        "own settings first."
}
