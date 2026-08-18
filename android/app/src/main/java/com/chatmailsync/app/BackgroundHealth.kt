package com.chatmailsync.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Whether the things automatic syncing quietly depends on are actually in
 * place -- and if not, saying so *before* the sync that never happens.
 *
 * Turning on "Watch a folder" schedules a periodic [WatchFolderWorker], and
 * WorkManager honours the schedule only as far as the system lets it. Two
 * settings, both outside this app and both off-by-default in the user's
 * favour, can stop it dead:
 *
 *  - Battery optimisation. On stock Android this delays a periodic worker into
 *    a doze maintenance window; on the aggressive OEM skins (Samsung's is the
 *    one this app is actually tested on) an unoptimised app can be put to
 *    sleep outright after a few days unused, and the worker simply stops
 *    running. Nothing surfaces. The user sees an app that "worked at first".
 *  - Notifications. Sync runs as a foreground service, so it still runs -- but
 *    a blocked notification means the progress and the outcome are both
 *    invisible, which for a background job is nearly the same as it not having
 *    happened.
 *
 * Neither can be fixed from inside the app: both are system screens the user
 * has to visit. So the card states the consequence, and the button is a
 * shortcut to the right screen, not a promise that tapping it fixes anything.
 */
enum class BackgroundIssue { BATTERY_OPTIMISED, NOTIFICATIONS_BLOCKED }

/**
 * Which warnings are worth showing, from the three facts that decide it.
 *
 * Pure so it can be asserted without an Android runtime -- and so the one rule
 * that matters is written once: **nothing is reported while automatic syncing
 * is off**. Manual syncs happen with the app in the foreground and the user
 * watching, where neither setting can hurt them, and warning about the
 * background health of a background feature nobody has switched on is exactly
 * the sort of permanent yellow banner people learn to scroll past.
 */
fun backgroundHealthIssues(
    autoWatchOn: Boolean,
    batteryExempt: Boolean,
    notificationsAllowed: Boolean,
): List<BackgroundIssue> {
    if (!autoWatchOn) return emptyList()
    val issues = mutableListOf<BackgroundIssue>()
    // Battery first: it stops the sync entirely, where a blocked notification
    // only hides it. Worst consequence, top of the card.
    if (!batteryExempt) issues.add(BackgroundIssue.BATTERY_OPTIMISED)
    if (!notificationsAllowed) issues.add(BackgroundIssue.NOTIFICATIONS_BLOCKED)
    return issues
}

/** Is this app exempt from battery optimisation right now? */
fun isBatteryExempt(context: Context): Boolean {
    // Below M there is no such thing as an exemption, so there is nothing to
    // warn about -- reported as "exempt" rather than as a problem with no fix.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** Can this app post the foreground-service notification a sync runs under? */
fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The system screen that can fix [issue], or null if there isn't one to open.
 *
 *  The battery one is deliberately ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
 *  -- the *list* -- and not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which
 *  is the one-tap dialog. The dialog needs the REQUEST_IGNORE_BATTERY_-
 *  OPTIMIZATIONS permission, and Play restricts that permission to a short
 *  list of app categories a WhatsApp archiver is not on; asking for it is a
 *  policy rejection waiting for the store phase. The list screen needs no
 *  permission at all, which is why the card spells out what to pick once it
 *  opens.
 */
fun backgroundIssueIntent(context: Context, issue: BackgroundIssue): Intent? = when (issue) {
    BackgroundIssue.BATTERY_OPTIMISED ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            null
        }
    BackgroundIssue.NOTIFICATIONS_BLOCKED ->
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

/** Headline: the consequence, in the user's terms, not the setting's name. */
val BackgroundIssue.title: String
    get() = when (this) {
        BackgroundIssue.BATTERY_OPTIMISED -> "Automatic sync may be stopped"
        BackgroundIssue.NOTIFICATIONS_BLOCKED -> "You won't see automatic syncs"
    }

/** What to do once the system screen opens, since neither of these lands on a
 *  single switch and both screens are a list of every app on the phone. */
val BackgroundIssue.detail: String
    get() = when (this) {
        BackgroundIssue.BATTERY_OPTIMISED ->
            "Android can put this app to sleep, and a sleeping app can't check " +
                "your watched folder. On the screen that opens, find Chat Mail " +
                "Sync and set it to \"Don't optimise\" (or \"Unrestricted\")."
        BackgroundIssue.NOTIFICATIONS_BLOCKED ->
            "Syncs will still run, but with notifications off there's nothing " +
                "to tell you one happened, or that it failed. Turn notifications " +
                "on for this app to see them."
    }

/** On the button. A verb about the screen it opens, not "Fix" -- nothing here
 *  is fixed by this app, and a button that claims otherwise is the reason the
 *  user comes back thinking they already dealt with it. */
val BackgroundIssue.actionLabel: String
    get() = when (this) {
        BackgroundIssue.BATTERY_OPTIMISED -> "Open battery settings"
        BackgroundIssue.NOTIFICATIONS_BLOCKED -> "Open notification settings"
    }
