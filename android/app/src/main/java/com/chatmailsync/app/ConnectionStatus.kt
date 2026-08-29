package com.chatmailsync.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Whether the mailbox is actually reachable, as one value the whole app can
 * read.
 *
 * The feedback this exists to answer was that connecting "feels like a
 * non-event": the only sign a mailbox had been set up at all was a transient
 * line of text under [MailAccountScreen]'s Save button, gone on the next
 * screen, and Home's "Connected as ..." which only ever meant *an address is
 * stored*. Nothing anywhere said the app had ever successfully reached the
 * mailbox.
 *
 * Four states, deliberately, because "saved" and "works" are different facts
 * and collapsing them is the thing that misled:
 *
 *  - [NONE] — no account set up. Grey.
 *  - [UNVERIFIED] — credentials saved, but no connection attempt has ever
 *    succeeded or failed on record. Amber. This is where an upgrade from an
 *    older version lands, since nothing before this release wrote the fact
 *    down.
 *  - [OK] — saved, and the last attempt reached the mailbox. Green.
 *  - [FAILED] — saved, and the last attempt did not. Red.
 */
enum class ConnectionStatus { NONE, UNVERIFIED, OK, FAILED }

/**
 * Derive the status from the two facts that make it up: is there an account
 * at all, and what did the last attempt do (null = never attempted).
 *
 * A stored verdict without an account is ignored rather than shown - after
 * "Forget saved password" the verdict is cleared anyway, but if the two ever
 * disagree the account is the one that decides, because a green dot over no
 * credentials would be the worst of the four lies available here.
 */
fun connectionStatusOf(hasAccount: Boolean, lastOk: Boolean?): ConnectionStatus = when {
    !hasAccount -> ConnectionStatus.NONE
    lastOk == null -> ConnectionStatus.UNVERIFIED
    lastOk -> ConnectionStatus.OK
    else -> ConnectionStatus.FAILED
}

/**
 * The single live copy of the answer, read straight by [ChatMailTopBar].
 *
 * A small observable singleton rather than a parameter: the banner is on all
 * eight screens and not one of them otherwise knows or cares about the
 * mailbox, so threading this down would put a mail concept into the signature
 * of the chat list and the sync log. It is not a CompositionLocal either,
 * because the value is written from a background thread's completion callback
 * as often as from composition, and a provider would have to wrap the entire
 * app scaffold to reach the same places.
 *
 * Starts at [ConnectionStatus.NONE] - the honest state before anything has
 * been read - and is refreshed from [AppPrefs] by the app shell on start and
 * after every connection attempt.
 */
object ConnectionState {
    var current: ConnectionStatus by mutableStateOf(ConnectionStatus.NONE)
        private set

    /** Recompute from what is currently stored. [hasAccount] is passed in
     *  rather than derived here because that logic already exists in the app
     *  shell. */
    fun refresh(context: Context, hasAccount: Boolean) {
        current = connectionStatusOf(hasAccount, AppPrefs.getLastConnectionOk(context))
    }

    /** Record the outcome of a real attempt to reach the mailbox, and light
     *  the dot to match. Only ever called where an actual login was tried -
     *  never on a validation failure that stopped before the network. */
    fun record(context: Context, ok: Boolean) {
        AppPrefs.setLastConnectionResult(context, ok)
        current = if (ok) ConnectionStatus.OK else ConnectionStatus.FAILED
    }

    /** The account itself changed underneath the verdict, so the verdict is
     *  no longer about the credentials in use. */
    fun forget(context: Context) {
        AppPrefs.clearLastConnectionResult(context)
        current = ConnectionStatus.NONE
    }
}

/**
 * The dot's colour on the navy masthead, and the words beside it.
 *
 * These are the palette's own colours, taken from the *dark* scheme rather
 * than the light one: the band is postmark blue whatever the system theme is,
 * so the dot is always drawn on a dark ground and needs the same treatment a
 * dark theme gives it. ArchiveGreen (#3F6B52) on #2E4374 is barely a colour -
 * #9FCBB2 is that same green, and is already the theme's `tertiary` in dark
 * mode. Same story for the red, which is the dark scheme's `error`.
 */
val ConnectionStatus.dotColor: Color
    get() = when (this) {
        ConnectionStatus.OK -> Color(0xFF9FCBB2)
        ConnectionStatus.UNVERIFIED -> Color(0xFFE3B872)
        ConnectionStatus.FAILED -> Color(0xFFF2B8B2)
        ConnectionStatus.NONE -> Color(0xFF93A8D6)
    }

/**
 * Short enough for an 88dp band, and phrased as what is true rather than what
 * to do - the fix belongs on the Mail account screen, not in three words on a
 * banner the user sees on every screen.
 */
val ConnectionStatus.label: String
    get() = when (this) {
        ConnectionStatus.OK -> "Connected"
        ConnectionStatus.UNVERIFIED -> "Not tested"
        ConnectionStatus.FAILED -> "No connection"
        ConnectionStatus.NONE -> "No account"
    }

/** Spoken form, since "Connected" next to a coloured circle loses its meaning
 *  the moment the circle is not visible. */
val ConnectionStatus.contentDescription: String
    get() = when (this) {
        ConnectionStatus.OK -> "Mailbox connected"
        ConnectionStatus.UNVERIFIED -> "Mailbox set up but not yet tested"
        ConnectionStatus.FAILED -> "Could not reach the mailbox on the last attempt"
        ConnectionStatus.NONE -> "No mail account set up"
    }
