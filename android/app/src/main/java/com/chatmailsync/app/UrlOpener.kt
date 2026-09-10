package com.chatmailsync.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Hand a URL to whatever the device uses for links, and survive there being
 * nothing there.
 *
 * `startActivity` with ACTION_VIEW throws [ActivityNotFoundException] when no
 * app claims http/https. On a phone that essentially never happens; on the
 * bare emulator images that app-store review farms run, it happens routinely,
 * because those images ship no browser at all. Unguarded, the tap does not
 * fail politely -- it takes the process down, and a reviewer reports that as
 * the link being broken. Indus Appstore held this app twice over its privacy
 * policy link, and an uncaught throw here is one of the few explanations that
 * fits a link which is demonstrably present and whose target is demonstrably
 * live.
 *
 * The fallback shows the address rather than a shrug, so someone who cannot
 * open it in place can still read it off the screen and type it elsewhere.
 */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No app on this device can open links. The address is: $url",
            Toast.LENGTH_LONG,
        ).show()
    }
}
