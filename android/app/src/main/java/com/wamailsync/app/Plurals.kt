package com.wamailsync.app

/**
 * "1 message" / "68 messages", instead of "1 message(s)" / "68 message(s)".
 *
 * "(s)" is form-filling language. It is the kind of thing that reads as
 * unfinished everywhere, but it reads worst exactly where this app used it
 * most - in the reset warnings, which are the app's most serious sentences and
 * the ones a user most needs to trust. A dialog that says "you will end up
 * with 68 duplicate message(s) that only you can clean up" is asking someone
 * to take a warning seriously in the register of a tax form.
 *
 * Kept deliberately dumb: English-only, and only for the handful of counted
 * nouns this app actually shows. If the app is ever localised this is the one
 * place that has to change, which is the other reason it isn't inlined.
 */
fun plural(count: Int, singular: String, pluralForm: String = "${singular}s"): String =
    if (count == 1) "$count $singular" else "$count $pluralForm"

/** The noun alone, correctly formed, when the count is rendered separately. */
fun pluralNoun(count: Int, singular: String, pluralForm: String = "${singular}s"): String =
    if (count == 1) singular else pluralForm
