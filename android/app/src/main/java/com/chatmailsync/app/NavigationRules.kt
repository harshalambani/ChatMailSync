package com.chatmailsync.app

// KT-13 step 2: pure navigation and first-run rules, moved out of MainActivity.kt unchanged.

/** Which tab a screen belongs under. The tabs are shown on every destination,
 * not just the three top-level ones -- a sub-screen used to hide them, so from
 * Mail account (where you land after entering credentials) there was no Home
 * button at all and the labelled back arrow was the only way out. Sub-screens
 * still light up the tab they were opened from, so the bar says where you are
 * rather than going blank.
 *
 * The exception is the sync log, which has three doors and so belongs to no
 * one tab -- see below. */
internal fun tabForRoute(route: String?): String? = when {
    route == null -> null
    // Its own full-window flow, not a tab -- same reasoning as the sync log
    // just below.
    route == "first_run" -> null
    route == "home" || route == "syncProgress" || route == "importPicker" -> "home"
    route == "chats" || route.startsWith("chat/") -> "chats"
    route == "settings" || route == "mailAccount" || route == "help" || route == "helpAbout" ||
        route == "advancedSettings" -> "settings"
    // The sync log is reachable from Settings, from the status card on Home,
    // and from the always-visible sync bar on every screen there is. Lighting
    // Settings told two thirds of its visitors they were somewhere they had
    // never been. No tab lit is honest; the wrong tab lit is not.
    route.startsWith("syncLog") -> null
    // Same reasoning as the sync log just above: Backup & restore has two
    // doors now, Settings' own nav row and the stale-backup banner on Home,
    // so lighting either tab would be wrong for whoever came from the other.
    route == "backupRestore" -> null
    else -> null
}

/**
 * Whether to scan the watched folder once at startup.
 *
 * Exactly the condition the periodic work is enqueued under, and that is the
 * point: launch is not a licence to scan a folder someone has switched the
 * watcher off for. "Check now" remains the way to force one.
 */
internal fun shouldScanAtLaunch(autoWatchOn: Boolean, watchedFolderUri: String?): Boolean =
    autoWatchOn && !watchedFolderUri.isNullOrBlank()

/**
 * Whether the four-step first-run setup (D7) is what a launch should open
 * on, instead of Home.
 *
 * Both a fresh install (neither flag) and an upgrader who already has a
 * working mailbox from before this release existed (mailbox configured, flag
 * never set) must land on Home -- the second clause is what keeps the
 * latter out of a "welcome" screen for an app they have used for months.
 * MainActivity sets the flag for that case on launch (see hasMailAccount's
 * use below) so it only has to be inferred once.
 */
internal fun shouldShowFirstRun(firstRunDone: Boolean, mailboxConfigured: Boolean): Boolean =
    !firstRunDone && !mailboxConfigured

/**
 * Whether the welcome step's quiet "Moving from another phone? Restore from
 * a backup" option should be offered.
 *
 * Deliberately the same fresh-install condition as [shouldShowFirstRun] --
 * nothing else would make sense, only a phone with nothing configured needs
 * restoring onto -- narrowed further by [cameFrom]: null exactly when
 * first_run is the nav graph's own start destination (a genuine fresh
 * install, with no previous back-stack entry to have arrived from), as
 * opposed to "Run setup again" (H7) reopening first_run from Advanced
 * settings, where restoring already lives on the Backup & restore screen
 * and does not need repeating here.
 */
internal fun shouldOfferRestoreOnFirstRun(
    cameFrom: String?,
    firstRunDone: Boolean,
    mailboxConfigured: Boolean,
): Boolean = cameFrom == null && shouldShowFirstRun(firstRunDone, mailboxConfigured)

/**
 * Whether a restore attempt reads as success, for the welcome step to
 * decide between staying put (offer the restore option again) and offering
 * "Continue" into mail setup, and for Backup & restore to decide whether to
 * draw the restore-confirmation lines below the result.
 *
 * Batch 7b: reads [Migration.RestoreOutcome.success] directly rather than
 * sniffing the message text for a "Restored " prefix -- that prefix match
 * broke the moment the message needed a multi-sentence, multi-line
 * confirmation to sit next to it. `null` covers both "nothing attempted yet"
 * and a cancelled picker (which never sets a result at all -- see
 * restoreBackup's launcher below, which only proceeds when a uri was
 * actually picked); neither is success.
 */
internal fun restoreOutcomeIsSuccess(migrationSuccess: Boolean?): Boolean = migrationSuccess == true

/**
 * The word beside a labelled back arrow, derived from the actual previous
 * back-stack entry's route rather than hard-coded per screen.
 *
 * Several screens (Me, Mail account -- reachable from the connection pill on
 * every masthead, not just Settings) can be opened from more than one place,
 * and a fixed "Home" or "Settings" string was right for only some of those
 * paths and silently wrong for the rest. Back itself already uses
 * `popBackStack()` everywhere (so it always lands where you actually came
 * from); this is only the label that names that destination.
 *
 * Deliberately narrow: unknown and "first_run" both fall through to plain
 * "Back" rather than guessing, and null (no previous entry -- e.g. this
 * route is the graph's start destination) does too.
 */
internal fun backLabelForRoute(route: String?): String = when {
    route == "home" -> "Home"
    route == "settings" -> "Settings"
    route == "advancedSettings" -> "Advanced"
    route == "chats" -> "Chats"
    // A single chat thread, not the list -- "Chats" here would name the
    // wrong screen for anyone who opened a thread from a share or a
    // notification and backed out of it.
    route?.startsWith("chat/") == true -> "Chat"
    route == "help" -> "Help"
    // UX-01: the Help sub-screen. Same word as "help" on purpose -- Help & FAQ,
    // Privacy and Licences all say "Help" when opened from it.
    route == "helpAbout" -> "Help"
    route == "privacy" -> "Privacy"
    route == "licenses" -> "Open-source licences"
    route == "mailAccount" -> "Mail account"
    route == "me" -> "Me"
    // Matches the route composable("backupRestore") navigates to, added
    // below -- the row id "backup_restore" (SettingsRowSpec) is a different
    // string on purpose, see BASIC_SETTINGS_ROWS' own comment, and must NOT
    // be matched here: falling through to plain "Back" for it is the point
    // of the guard test, not a gap to close.
    route == "backupRestore" -> "Backup & restore"
    route == "syncLog" || route?.startsWith("syncLog/") == true -> "Sync log"
    route == "queue" -> "Queue"
    route == "importPicker" -> "Import"
    route == "mailWizard" -> "Mail setup"
    else -> "Back"
}

/**
 * Where the four-step first-run flow (D7) returns to when it finishes or is
 * skipped, given the route it was entered from.
 *
 * Two distinct launches share this same "first_run" route: a fresh install,
 * where the flow is the nav graph's start destination and so has no previous
 * back-stack entry (`cameFrom == null`), and "Run setup again" from Advanced
 * settings (H7), which pushes "first_run" on top of "advancedSettings" and
 * so has one. The fresh-install case must always land on Home; the manual
 * case must always return to Advanced -- neither may leak into the other's
 * outcome, which is exactly what a shared boolean flag or a single default
 * target would risk. Kept pure and read once, at the moment "first_run" is
 * entered, rather than re-derived after the flow has popped itself off the
 * stack.
 */
internal fun firstRunFinishTarget(cameFrom: String?): String =
    if (cameFrom == "advancedSettings") "advancedSettings" else "home"

/** Where the bottom-bar tab handler (and the incoming-share handler) pop to
 * when resetting/returning to Home. This is deliberately NOT
 * `navController.graph.findStartDestination()`: for a fresh install the
 * graph's start destination is "first_run", and that stays true for the rest
 * of the process even after the flow finishes and pops itself off the back
 * stack (a NavGraph's start destination is fixed at graph-build time, not
 * recomputed as the back stack changes). Popping to a route that is no
 * longer on the stack is a silent no-op, so a literal "home" is required
 * here, not the graph's start destination. See FirstRunNavTest. */
internal fun tabPopTargetRoute(): String = "home"
