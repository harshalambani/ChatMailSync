package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit twin of `tests/test_self_sender.py`. Test names below mirror the
 * Python test names (converted to camelCase) so the two suites can be diffed
 * side by side. The Python file has 21 `def test_` functions (two of them
 * `@pytest.mark.parametrize`d over three blank-ish values, ported here as a
 * loop inside one `@Test`, matching the style already used for looped cases
 * in `ConfigTest.kt`).
 *
 * 18 of the 21 have a direct twin here. The other three do NOT, and are not
 * twinned anywhere else in `:core` yet:
 *
 *   - `test_renderer_puts_the_named_owner_on_the_outgoing_side`
 *   - `test_renderer_without_a_name_still_honours_the_literal_you`
 *   - `test_renderer_without_a_name_draws_a_real_export_entirely_incoming`
 *
 * All three exercise `src/html_renderer.py:render_chunk`, not
 * `src/self_sender.py` itself -- they pin that the renderer actually calls
 * `self_sender.is_outgoing` with the resolved name rather than hard-coding
 * the literal "You". `html_renderer` has not been ported yet: the plan's
 * Phase 2 dependency order (section E) is
 * `config -> progress -> self_sender -> parser -> state -> media_extractor
 * -> html_renderer -> ...`, i.e. self_sender is a leaf `html_renderer`
 * depends on, not the other way round, so a Kotlin `render_chunk` does not
 * exist yet for these three to twin against. They belong in
 * `HtmlRendererTest.kt` when that module's PR lands, calling this file's
 * [isOutgoing] the same way the Python renderer calls
 * `self_sender.is_outgoing`. [isOutgoing] itself -- the function those three
 * tests actually exercise underneath the HTML assertions -- is twinned and
 * negative-tested directly below instead.
 */
class SelfSenderTest {

    // -----------------------------------------------------------------
    // The one-to-one rule
    // -----------------------------------------------------------------

    @Test
    fun oneToOneLeavesExactlyOneNameOver() {
        // "WhatsApp Chat with Priya Nair.txt" names the other party, so
        // whichever of the two speakers is not her is necessarily the owner.
        assertEquals(
            "Harshal Ambani",
            deriveFromOneToOne("Priya Nair", listOf("Priya Nair", "Harshal Ambani", "Priya Nair")),
        )
    }

    @Test
    fun groupChatDerivesNothing() {
        // A group export's filename is the group's name and matches no
        // sender. Four people speak and none of them is identified, which is
        // precisely why a group may only consume a name a one-to-one has
        // already proved.
        assertNull(deriveFromOneToOne("ShyamKunj201", listOf("Ramesh", "Farah", "Sunil", "Harshal Ambani")))
    }

    @Test
    fun oneToOneWhereOnlyOnePersonSpokeDerivesNothing() {
        // Nobody is left over, so there is nothing to conclude.
        assertNull(deriveFromOneToOne("Priya Nair", listOf("Priya Nair")))
    }

    @Test
    fun filenameMatchingNeitherSenderDerivesNothing() {
        // A hand-renamed file. Two senders, but the export has not said
        // which of them is the counterparty, so guessing would be a coin
        // toss on identity.
        assertNull(deriveFromOneToOne("holiday chat", listOf("Priya Nair", "Harshal Ambani")))
    }

    @Test
    fun derivationIgnoresCaseAndTheLtrMark() {
        // WhatsApp prepends U+200E to fields in some exports, and the casing
        // of a profile name is not stable between them.
        assertEquals(
            "Harshal Ambani",
            deriveFromOneToOne("Priya Nair", listOf("‎PRIYA NAIR", "Harshal Ambani")),
        )
    }

    // -----------------------------------------------------------------
    // Resolution order
    // -----------------------------------------------------------------

    @Test
    fun overrideBeatsADerivationThatDisagrees() {
        val result = resolve(
            override = "Harshal",
            displayName = "Priya Nair",
            senders = listOf("Priya Nair", "Harshal Ambani"),
        )
        assertEquals("Harshal", result.name)
    }

    @Test
    fun overrideStillLetsTheLearnedNameStayCurrent() {
        // The override wins for rendering, but the derivation underneath it
        // is still true and is still worth storing -- otherwise clearing the
        // override later would drop the app back to a stale name, or to none
        // at all.
        val result = resolve(
            override = "Harshal",
            learned = null,
            displayName = "Priya Nair",
            senders = listOf("Priya Nair", "Harshal Ambani"),
        )
        assertEquals("Harshal", result.name)
        assertEquals("Harshal Ambani", result.newlyDerived)
    }

    @Test
    fun groupChatUsesTheNameAOneToOneEstablished() {
        val result = resolve(
            learned = "Harshal Ambani",
            displayName = "ShyamKunj201",
            senders = listOf("Ramesh", "Farah", "Harshal Ambani"),
        )
        assertEquals("Harshal Ambani", result.name)
        assertNull(result.newlyDerived)
    }

    @Test
    fun nothingKnownFallsBackToYouRatherThanGuessing() {
        // The fallback is not a failure: exports that really do write "You"
        // exist, and rendering as this always did is the recoverable
        // outcome. Attributing somebody else's messages to the user is not.
        val result = resolve(
            displayName = "ShyamKunj201",
            senders = listOf("Ramesh", "Farah", "Harshal Ambani"),
        )
        assertEquals(SELF_SENDER_FALLBACK, result.name)
        assertNull(result.newlyDerived)
    }

    @Test
    fun theMostFrequentSenderIsNeverTakenAsTheOwner() {
        // The rule deliberately not implemented. Ramesh sends most of the
        // messages in this group; inferring identity from message counts
        // would label every one of them as the user's own, in an archive
        // kept because it is trusted.
        val result = resolve(
            displayName = "Building Society",
            senders = listOf("Ramesh", "Ramesh", "Ramesh", "Farah", "Harshal Ambani"),
        )
        assertEquals(SELF_SENDER_FALLBACK, result.name)
    }

    @Test
    fun aNewerOneToOneCorrectsAStaleLearnedName() {
        // Changing your WhatsApp profile name is ordinary, and the export
        // proves the new one, so the newer derivation wins rather than being
        // treated as a conflict to be reported.
        val result = resolve(
            learned = "H. Ambani",
            displayName = "Priya Nair",
            senders = listOf("Priya Nair", "Harshal Ambani"),
        )
        assertEquals("Harshal Ambani", result.name)
        assertEquals("Harshal Ambani", result.newlyDerived)
    }

    @Test
    fun aDerivationConfirmingTheStoredNameIsNotANewFact() {
        val result = resolve(
            learned = "Harshal Ambani",
            displayName = "Priya Nair",
            senders = listOf("Priya Nair", "‎harshal ambani"),
        )
        assertNull(result.newlyDerived)
    }

    @Test
    fun aBlankOverrideIsAnAbsentOverride() {
        for (blank in listOf("", "   ", null)) {
            val result = resolve(
                override = blank,
                learned = "Harshal Ambani",
                displayName = "ShyamKunj201",
                senders = listOf("Ramesh", "Harshal Ambani"),
            )
            assertEquals("blank=$blank", "Harshal Ambani", result.name)
        }
    }

    // -----------------------------------------------------------------
    // What the front-ends are told
    // -----------------------------------------------------------------

    @Test
    fun anUnknownOwnerSaysSoRatherThanShowingAnEmptyBox() {
        // The defect was invisible partly because nothing on screen claimed
        // anything. A blank field reads as "nothing to set here", not as
        // "the app has not worked out which messages are yours".
        val described = describe()
        assertNull(described.name)
        assertEquals("unknown", described.source)
        assertEquals("Not worked out yet", described.summary)
        assertTrue(described.detail.contains("one-to-one"))
    }

    @Test
    fun aLearnedNameIsShownAsLearned() {
        val described = describe(learned = "Harshal Ambani")
        assertEquals("Harshal Ambani", described.name)
        assertEquals("learned", described.source)
        assertTrue(described.summary.contains("Harshal Ambani"))
    }

    @Test
    fun anOverrideIsShownAsTheUsersOwnChoice() {
        // It matters that these two read differently: one is a fact the app
        // worked out and might revise, the other is a decision it will not
        // touch.
        val described = describe(override = "Harshal", learned = "Harshal Ambani")
        assertEquals("Harshal", described.name)
        assertEquals("override", described.source)
        assertNotEquals(describe(learned = "Harshal Ambani").detail, described.detail)
    }

    @Test
    fun aBlankOverrideIsDescribedAsTheLearnedName() {
        for (blank in listOf("", "   ", null)) {
            val described = describe(override = blank, learned = "Harshal Ambani")
            assertEquals("blank=$blank", "learned", described.source)
        }
    }

    @Test
    fun describeAgreesWithResolveOnWhoTheOwnerIs() {
        // Two code paths, one answer: a screen that said one thing while the
        // renderer did another would be worse than saying nothing at all.
        val cases = listOf(
            null to null,
            null to "Harshal Ambani",
            "Harshal" to "Harshal Ambani",
        )
        for ((override, learned) in cases) {
            val resolved = resolve(override = override, learned = learned)
            val described = describe(override, learned)
            assertEquals(described.name ?: SELF_SENDER_FALLBACK, resolved.name)
        }
    }

    // -----------------------------------------------------------------
    // isOutgoing -- what the (not-yet-ported) renderer will call per message.
    // Twins the assertions underneath the three untwinned renderer tests
    // listed in this file's header, without depending on html_renderer.
    // -----------------------------------------------------------------

    @Test
    fun isOutgoingMatchesTheResolvedName() {
        assertTrue(isOutgoing("Harshal Ambani", "Harshal Ambani"))
        assertFalse(isOutgoing("Priya Nair", "Harshal Ambani"))
    }

    @Test
    fun isOutgoingWithNoNameEstablishedStillMatchesTheLiteralYou() {
        assertTrue(isOutgoing("You", null))
        assertFalse(isOutgoing("Harshal Ambani", null))
    }

    // -----------------------------------------------------------------
    // Negative tests -- Kotlin-specific decisions with no direct pytest
    // counterpart, plus parity pins for string semantics called out in
    // SelfSender.kt's KDoc.
    // -----------------------------------------------------------------

    // NEGATIVE: a name that merely looks similar -- same length, one
    // character different -- must never be treated as a match. Guards
    // against a sloppy substring/prefix comparison creeping in later.
    @Test
    fun aSimilarButDifferentNameIsNotTreatedAsSelf() {
        val result = resolve(
            learned = "Meera Iyer",
            displayName = "Group Chat",
            senders = listOf("Meera Iyer", "Meera Iyar", "Rohan Desai"),
        )
        assertFalse(isOutgoing("Meera Iyar", result.name))
        assertTrue(isOutgoing("Meera Iyer", result.name))
    }

    // NEGATIVE: derivation must not fire on three unique senders even if two
    // of them are near-duplicates of each other -- the one-to-one rule
    // requires exactly two, full stop, not "two that look alike".
    @Test
    fun threeSendersNeverDeriveEvenWhenTwoLookAlike() {
        assertNull(deriveFromOneToOne("Meera Iyer", listOf("Meera Iyer", "Meera Iyar", "Rohan Desai")))
    }

    // NEGATIVE: pin Python's str.strip() Unicode whitespace set on
    // normaliseSender -- both NBSP (U+00A0) and NARROW NO-BREAK SPACE
    // (U+202F) must be stripped, not just ASCII space. See SelfSender.kt's
    // KDoc on normaliseSender for why Kotlin's default trim() predicate
    // (isWhitespace() || isSpaceChar()) is required here, not just
    // isWhitespace() alone.
    @Test
    fun aNbspAndNarrowNbspAreStrippedLikePython() {
        // The comparison strips NBSP/NARROW-NBSP (normaliseSender), but the
        // *returned* name is the raw, unstripped sender string -- Python's
        // derive_from_one_to_one returns `s`, not `_normalise(s)`. Padding
        // the filename side (which is never returned) isolates the
        // comparison behaviour from that raw-passthrough behaviour.
        assertEquals(
            "Harshal Ambani",
            deriveFromOneToOne(" Priya Nair ", listOf("Priya Nair", "Harshal Ambani")),
        )
    }

    // NEGATIVE: casefold, not merely lowercase -- German ß casefolds to
    // "ss" in Python (str.casefold()), which str.lower() does not do. Pinned
    // so pythonCasefold cannot silently regress to a plain .lowercase() call.
    @Test
    fun germanEszettCasefoldsToDoubleSLikePythonsCasefoldNotLower() {
        assertEquals("strasse", pythonCasefold("straße"))
        assertNotEquals("strasse", "straße".lowercase(java.util.Locale.ROOT))
    }

    // NEGATIVE: a null sender list (Python's senders=None) must skip
    // derivation entirely, which is different from an empty list -- an
    // empty list is a real, if degenerate, zero-speaker export and still
    // runs derive_from_one_to_one (returning null because size != 2), it
    // just never has a chance to match anything.
    @Test
    fun aNullSenderListSkipsDerivationWhileAnEmptyListStillRuns() {
        val withNullSenders = resolve(learned = "Harshal Ambani", senders = null)
        assertEquals("Harshal Ambani", withNullSenders.name)
        assertNull(withNullSenders.newlyDerived)

        val withEmptySenders = resolve(learned = "Harshal Ambani", senders = emptyList())
        assertEquals("Harshal Ambani", withEmptySenders.name)
        assertNull(withEmptySenders.newlyDerived)
    }

    // NEGATIVE: an override that is present but literally the fallback word
    // "You" is still a real override (Python's truthiness check is on the
    // string itself, not on whether it differs from FALLBACK) -- it must win
    // over a derivation and be reported with source "override", not treated
    // as if nothing had been set.
    @Test
    fun anOverrideOfTheLiteralWordYouIsStillARealOverride() {
        val result = resolve(
            override = "You",
            displayName = "Priya Nair",
            senders = listOf("Priya Nair", "Harshal Ambani"),
        )
        assertEquals("You", result.name)
        val described = describe(override = "You")
        assertEquals("override", described.source)
    }

    // NEGATIVE: an override that is whitespace-only using the NBSP family
    // (not plain ASCII space) must still be treated as absent -- pins that
    // resolve()/describe() use isBlankLikePython/pythonStrip rather than
    // Kotlin's isNullOrBlank()/trim(), which under-detect NBSP as
    // whitespace (see SelfSender.kt's KDoc on pythonStrip).
    @Test
    fun anNbspOnlyOverrideIsStillTreatedAsAbsent() {
        val result = resolve(
            override = "  ",
            learned = "Harshal Ambani",
        )
        assertEquals("Harshal Ambani", result.name)
        assertEquals("learned", describe(override = "  ", learned = "Harshal Ambani").source)
    }

    // NEGATIVE: duplicate senders (the same person appearing many times in
    // the sender list, as a real chat transcript does) must collapse to one
    // unique entry before the two-person count is checked -- a naive
    // "senders.size == 2" check without dedup would wrongly refuse to derive
    // on a chat with more than two messages.
    @Test
    fun repeatedSendersCollapseToUniqueBeforeCountingToTwo() {
        assertEquals(
            "Harshal Ambani",
            deriveFromOneToOne(
                "Priya Nair",
                listOf("Priya Nair", "Harshal Ambani", "Priya Nair", "Harshal Ambani", "Priya Nair"),
            ),
        )
    }
}
