package com.chatmailsync.core.mail

/**
 * Kotlin port of `src/progress.py` (Phase 2 "progress" of the Kotlin core
 * port — see `2026-09-17-kotlin-core-fdroid-plan-and-windows-audit.md`,
 * sections D/E, which lists `progress` as a pure-Kotlin, no-I/O direct port
 * right after `config`). A faithful behavioural twin of the Python module,
 * except where a section below explicitly says otherwise.
 *
 * NOT wired into `:app` yet — `:app` still reads `android_api.progress_state()`
 * through Chaquopy (`SyncWorker.kt:129`). This file exists so a later phase
 * can swap that call for [ProgressTracker.state]`.asDict()` without a
 * behavioural surprise.
 *
 * Android watches the event stream that `ProgressSyncManager` in
 * `src/sync_manager.py` emits (not yet ported — that is a later Phase 2 PR).
 * The interpretation of that stream lives here once, in the shared core, so
 * a wording change reaches every consumer in the same commit. The vocabulary
 * stays small on purpose: phase, the chat being worked on, how far along,
 * and one line of text.
 */

// Phases. A sync moves IDLE -> SCANNING -> SYNCING -> one of DONE/FAILED, and
// a stopped run ends at DONE with `stopped` set: it really did finish, it
// just finished early, and calling that a failure would bury the real
// failures.
const val PHASE_IDLE = "idle"
const val PHASE_SCANNING = "scanning"
const val PHASE_SYNCING = "syncing"
const val PHASE_DONE = "done"
const val PHASE_FAILED = "failed"

/** Fraction sentinel for "no honest number yet" — before the first file, or
 * during a run that is all dedup-skips with nothing to push. Front-ends show
 * an indeterminate bar rather than inventing a percentage. */
const val UNKNOWN = -1.0

/** How many milestone lines to keep. Android ships these through WorkManager
 * Data, which has a ~10KB limit for the whole payload, so the ring buffer is
 * bounded rather than "the whole run". */
const val MAX_MILESTONES = 50

/**
 * The event Kotlin folds into a [ProgressState]. Twin of the Python
 * dictionaries `ProgressSyncManager` puts on its queue (`{"type": ..., ...}`)
 * — kept as a loosely-typed `Map<String, Any?>` rather than a sealed class
 * hierarchy for the same reason `Config.kt`'s `isLegacyOauthUser`/
 * `resolveMailBackend` take `Map<String, Any?>`: the Python side is a plain
 * dict crossing (eventually) a bridge, and a sealed hierarchy per event type
 * would be a Kotlin-only invention this port is not supposed to make.
 */
typealias ProgressEvent = Map<String, Any?>

/**
 * The dynamically-typed `stats` object `_done_headline` reads via
 * `getattr(stats, "messages_synced", None)` in Python, where the real
 * production caller (`android_api.run`, `android_api.py:353`) never actually
 * supplies one — it publishes a bare `{"type": "done", "stopped": stopped}`
 * because `SyncStats` is not JSON-marshallable across Chaquopy and Kotlin
 * already reads the real numbers off the worker's `Data`. Only
 * `tests/test_progress.py` ever populates it, with a `types.SimpleNamespace`.
 *
 * Kotlin has no runtime `getattr`-with-default equivalent that stays
 * statically typed, so this is the twin of that duck-typed object: a small
 * value holder carrying exactly the one field `_done_headline` reads. A
 * caller that wants the Python behaviour passes `event["stats"] =
 * SyncStatsSummary(messagesSynced = n)`; a caller that (like every real
 * production path) omits `"stats"` entirely gets the same `null` that
 * `getattr(None, "messages_synced", None)` would have produced.
 */
data class SyncStatsSummary(val messagesSynced: Int?)

/** A renderable snapshot of an in-flight sync. Twin of `progress.py`'s
 * `ProgressState` dataclass.
 *
 * Deliberately a mutable class with `var` fields, not a Kotlin `data class`
 * with `copy()`-on-every-field-change semantics: Python's dataclass is
 * mutated in place by `ProgressTracker.feed` (`st.phase = ...`,
 * `st.milestones.append(...)`), and [ProgressTracker.state] is read by
 * reference on every poll (see [test_polling_the_state_consumes_nothing] in
 * `ProgressTest` for why re-reading it must be side-effect-free, not why it
 * must be immutable).
 */
class ProgressState(
    var phase: String = PHASE_IDLE,
    /** Display name of the chat currently being pushed ("" when none yet). */
    var chat: String = "",
    /** 0.0-1.0, or [UNKNOWN]. */
    var fraction: Double = UNKNOWN,
    /** Primary line: what is happening. */
    var headline: String = "",
    /** Secondary line: how far into it ("128 / 500 messages"). May be "". */
    var detail: String = "",
    /** Milestone log, oldest first. Excludes per-chunk ticks by design —
     * those fire many times per file and would read as noise, not a log. */
    val milestones: MutableList<String> = mutableListOf(),
) {

    /** headline and detail as the single line the app has always shown
     * ("Syncing: Alice — 128 / 500 messages"). */
    val line: String
        get() = if (detail.isNotEmpty()) "$headline — $detail" else headline

    /**
     * Rounded percentage, or -1 when the fraction is unknown.
     *
     * Twin of Python's bare `round(self.fraction * 100)` — which, with no
     * `ndigits` argument, is banker's rounding (ties round to the nearest
     * even integer), not the "round half up" that `Math.round`/
     * `kotlin.math.roundToInt` perform. `fraction` is always a ratio of two
     * non-negative integers here, so an exact `x.5` is reachable (e.g.
     * `1/8 == 0.125` -> `12.5`), and the two rounding rules disagree on it:
     * Python's `round(12.5)` is `12`, `Math.round(12.5)` is `13`. See
     * [pythonRoundHalfToEven] and `ProgressTest.percentUsesBankersRoundingLikePython`
     * (a negative test — it would fail under naive `Math.round`).
     */
    val percent: Int
        get() = if (fraction < 0) -1 else pythonRoundHalfToEven(fraction * 100)

    /** Flat, JSON-ish shape for the (future) Kotlin<->Kotlin or
     * Kotlin<->UI bridge — twin of `progress.py`'s `ProgressState.as_dict()`.
     * Returns a fresh, independent snapshot: mutating the returned map's
     * `"milestones"` list can never affect this state's own list. */
    fun asDict(): Map<String, Any?> = mapOf(
        "phase" to phase,
        "chat" to chat,
        "fraction" to fraction,
        "percent" to percent,
        "headline" to headline,
        "detail" to detail,
        "line" to line,
        "milestones" to milestones.toList(),
        "log" to milestones.joinToString("\n"),
    )
}

/**
 * Folds the raw event stream into a [ProgressState]. Twin of `progress.py`'s
 * `ProgressTracker`.
 *
 * One instance per run; [reset] starts the next one. Feeding is
 * order-independent in the sense that a missed event only costs detail, not
 * correctness — every field is derived from the newest event that carries
 * it, and the fraction only ever moves forward (see [advance]).
 */
class ProgressTracker {

    var state: ProgressState = ProgressState()
        private set

    fun reset() {
        state = ProgressState()
    }

    /** Apply one event. Returns the milestone line it produced, if any. */
    fun feed(event: ProgressEvent): String? {
        val etype = event["type"] as? String
        val st = state
        var milestone: String? = null

        when (etype) {
            "files_total" -> {
                val n = intOf(event["n"])
                st.phase = PHASE_SCANNING
                st.headline = if (n == 0) "Inbox is empty" else "Found $n file(s)…"
                st.detail = ""
                milestone = if (n == 0) "Inbox is empty" else "Found $n file(s) to sync"
            }

            "syncing" -> {
                val name = pyStr(event, "name")
                st.phase = PHASE_SYNCING
                st.chat = name
                st.headline = "Syncing: $name"
                st.detail = ""
                milestone = "Starting: $name"
            }

            "chunk" -> {
                // The honest whole-sync percentage. The engine counts every
                // *new* message in a parse+dedup pre-scan before the first
                // network call (ProgressSyncManager._estimate_total_new_messages,
                // not yet ported), so this advances continuously while one
                // large chat is still uploading, instead of the bar sitting
                // at a file-count fraction -- 0/1 for the entire run when the
                // inbox holds a single file -- and only jumping at the very
                // end.
                val name = pyStr(event, "name")
                st.phase = PHASE_SYNCING
                st.chat = name
                st.headline = "Syncing: $name"
                st.detail = "${intOf(event["msgs_done"])} / ${intOf(event["total_msgs"])} messages"
                advance(intOf(event["global_done"]), intOf(event["global_total"]))
            }

            "file_done" -> {
                val done = intOf(event["done"])
                val total = intOf(event["total"])
                st.headline = "$done / $total files"
                st.detail = ""
                // Coarser than "chunk" and only used where chunk data does
                // not exist yet, because advance() never moves backwards.
                advance(done, total)
                milestone = "Finished $done / $total files"
            }

            "done" -> {
                st.phase = PHASE_DONE
                st.chat = ""
                st.fraction = 1.0
                st.detail = ""
                st.headline = doneHeadline(event)
            }

            "error" -> {
                st.phase = PHASE_FAILED
                st.chat = ""
                st.detail = ""
                st.headline = "Failed — see log"
            }
        }

        if (milestone != null) {
            st.milestones.add(milestone)
            while (st.milestones.size > MAX_MILESTONES) {
                st.milestones.removeAt(0)
            }
        }
        return milestone
    }

    /**
     * Move the bar to done/total, but never backwards within a run.
     *
     * Two sources drive it and they disagree in scale: "chunk" counts
     * messages across the whole sync, "file_done" counts files. Finishing
     * the first of three files is 1/3, but if that file was most of the work
     * the message count may already be past half -- so taking each event at
     * face value made the bar visibly retreat at every file boundary.
     * Whichever source is further along is the honest answer; a bar that
     * goes backwards just reads as a bug.
     */
    private fun advance(done: Int, total: Int) {
        if (total <= 0) return
        val fraction = done.toDouble() / total.toDouble()
        if (fraction > state.fraction) {
            state.fraction = minOf(fraction, 1.0)
        }
    }
}

/**
 * "Done"/"Stopped" plus the count, when the event carries stats.
 *
 * Twin of `progress.py:_done_headline`. See [SyncStatsSummary] for how the
 * dynamically-typed `getattr(stats, "messages_synced", None)` read is
 * represented here.
 */
private fun doneHeadline(event: ProgressEvent): String {
    val synced = (event["stats"] as? SyncStatsSummary)?.messagesSynced
    val prefix = if (event["stopped"] == true) "Stopped" else "Done"
    if (synced == null) return prefix
    return "$prefix — $synced msg${if (synced != 1) "s" else ""} synced"
}

/**
 * Events cross into this tracker as whatever the producer put in them (from
 * Kotlin call sites today; from Chaquopy dicts once `android_api`/
 * `sync_manager` are ported), but a malformed one must not be able to kill a
 * poll loop. Twin of `progress.py:_int`.
 *
 * Mirrors Python's `int(value)` for the shapes that can actually appear in a
 * `Map<String, Any?>`: whole numbers pass through, a numeric string parses,
 * a fractional value truncates toward zero (`int(3.9) == 3`, matched by
 * `Double.toInt()`/`Float.toInt()`), and anything else — `null`, a
 * non-numeric string, a nested map/list — becomes `0` instead of throwing,
 * the same way Python's `except (TypeError, ValueError)` does. One
 * genuinely-Python-only case has no Kotlin analogue and is not twinned: a
 * *decimal-looking string* such as `"3.5"` raises `ValueError` in Python's
 * `int("3.5")` (Python does not truncate a numeric-string argument) and is
 * caught the same way `"lots"` is, landing on `0` — `toIntOrNull()` returns
 * `null` for `"3.5"` for the same underlying reason (it is not a valid
 * integer literal), so this already matches without special-casing it.
 */
internal fun intOf(value: Any?): Int = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Boolean -> if (value) 1 else 0 // Python's bool is an int subclass.
    is Double -> value.toInt()
    is Float -> value.toInt()
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull() ?: 0
    else -> 0
}

/**
 * Twin of Python's `str(event.get(key, default))`, which distinguishes a
 * *missing* key (returns `default`) from a key present with value `None`
 * (returns the literal string `"None"`, because `str(None) == "None"`).
 * Neither `test_progress.py` nor any real caller ever exercises the
 * "present but null" branch — every production event that carries `"name"`
 * carries a real string — but it is cheap to keep faithful rather than
 * silently coalescing null to `""` and drifting from Python if a future
 * caller ever does pass a null. See `ProgressTest.aNamePresentButNullRendersAsTheLiteralWordNone`.
 */
internal fun pyStr(event: ProgressEvent, key: String, default: String = ""): String {
    if (!event.containsKey(key)) return default
    return event[key]?.toString() ?: "None"
}

/**
 * Python's `round(x)` with no `ndigits` argument: round to the nearest
 * integer, ties to even ("banker's rounding"). Used only by
 * [ProgressState.percent]; see its KDoc for why `Math.round` is the wrong
 * primitive here.
 */
internal fun pythonRoundHalfToEven(x: Double): Int {
    val floor = kotlin.math.floor(x)
    val diff = x - floor
    val floorInt = floor.toInt()
    return when {
        diff < 0.5 -> floorInt
        diff > 0.5 -> floorInt + 1
        floorInt % 2 == 0 -> floorInt
        else -> floorInt + 1
    }
}
