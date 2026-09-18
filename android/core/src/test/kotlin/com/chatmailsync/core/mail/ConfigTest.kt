package com.chatmailsync.core.mail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JUnit twin of `tests/test_config.py`. Test names below mirror the Python
 * test names (converted to camelCase) so the two suites can be diffed
 * side by side; a short comment marks each pytest case with no twin here
 * and says why.
 *
 * Not twinned, deliberately (see [RootPaths]'s KDoc for the full reasoning):
 *  - `test_default_root_falls_back_to_project_dir_when_no_override` — no
 *    JVM analogue of `Path(__file__).parent.parent`.
 *  - `test_env_root_uses_chatmailsync_root_and_ignores_the_legacy_name` — no
 *    JVM analogue of the `CHATMAILSYNC_ROOT` env-var fallback; the Kotlin
 *    port always takes an explicit `File` root.
 *  - `test_sync_manager_picks_up_root_set_before_construction` — exercises
 *    `src/sync_manager.py`, which is a later Phase 2 PR; nothing to twin
 *    against yet.
 */
class ConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // -----------------------------------------------------------------
    // RootPaths (twin of test_set_root_rebinds_all_derived_paths)
    // -----------------------------------------------------------------

    @Test
    fun rootPathsRebindsAllDerivedPaths() {
        val root = tmp.newFolder("root")
        val paths = RootPaths(root)
        assertEquals(root, paths.projectRoot)
        assertEquals(File(root, "data"), paths.dataDir)
        assertEquals(File(root, "auth"), paths.authDir)
        assertEquals(File(root, "data/inbox"), paths.inboxDir)
        assertEquals(File(root, "data/processed"), paths.processedDir)
        assertEquals(File(root, "data/sync_state.db"), paths.stateDbPath)
        assertEquals(File(root, "auth/token.json"), paths.legacyTokenFile)
        assertEquals(File(root, "auth/imap_credentials.json"), paths.imapCredentialsFile)
    }

    // NEGATIVE: two roots never share derived paths. Guards against ever
    // reintroducing config.py's mutable-module-global design (the very
    // thing RootPaths was deliberately designed NOT to be — see its KDoc).
    @Test
    fun twoRootPathsInstancesForDifferentRootsDoNotShareDerivedPaths() {
        val rootA = tmp.newFolder("a")
        val rootB = tmp.newFolder("b")
        val a = RootPaths(rootA)
        val b = RootPaths(rootB)
        assertNotEquals(a.dataDir, b.dataDir)
        assertNotEquals(a.stateDbPath, b.stateDbPath)
        assertEquals(rootA, a.projectRoot)
        assertEquals(rootB, b.projectRoot)
    }

    // -----------------------------------------------------------------
    // Legacy Google sign-in
    // -----------------------------------------------------------------

    @Test
    fun aFreshUserIsNotTreatedAsALegacyOauthUser() {
        val paths = RootPaths(tmp.newFolder("fresh"))
        assertFalse(isLegacyOauthUser(emptyMap(), paths))
    }

    @Test
    fun aSavedGmailOauthBackendIsEvidence() {
        val paths = RootPaths(tmp.newFolder("oauth-backend"))
        val saved = mapOf("mail_backend" to LEGACY_MAIL_BACKEND_GMAIL_OAUTH)
        assertTrue(isLegacyOauthUser(saved, paths))
    }

    @Test
    fun aLeftoverTokenJsonIsEvidenceOnItsOwn() {
        val root = tmp.newFolder("leftover-token")
        val paths = RootPaths(root)
        paths.authDir.mkdirs()
        paths.legacyTokenFile.writeText("{}")
        assertTrue(isLegacyOauthUser(emptyMap(), paths))
    }

    // NEGATIVE: a token.json under a *different* root must not mark this
    // root's user as legacy — the whole point of passing RootPaths
    // explicitly instead of mutating a global.
    @Test
    fun aTokenJsonUnderADifferentRootIsNotEvidenceHere() {
        val rootWithToken = tmp.newFolder("has-token")
        val rootWithoutToken = tmp.newFolder("no-token")
        val pathsWithToken = RootPaths(rootWithToken)
        pathsWithToken.authDir.mkdirs()
        pathsWithToken.legacyTokenFile.writeText("{}")

        val pathsWithoutToken = RootPaths(rootWithoutToken)
        assertTrue(isLegacyOauthUser(emptyMap(), pathsWithToken))
        assertFalse(isLegacyOauthUser(emptyMap(), pathsWithoutToken))
    }

    @Test
    fun aSavedGmailOauthBackendResolvesToImapNotItself() {
        val saved = mapOf("mail_backend" to LEGACY_MAIL_BACKEND_GMAIL_OAUTH)
        assertEquals(MAIL_BACKEND_IMAP, resolveMailBackend(saved))
    }

    @Test
    fun resolveMailBackendDefaultsToImapAndHonoursAnythingElse() {
        assertEquals(MAIL_BACKEND_IMAP, resolveMailBackend(emptyMap()))
        assertEquals(
            MAIL_BACKEND_IMAP,
            resolveMailBackend(mapOf("mail_backend" to MAIL_BACKEND_IMAP)),
        )
    }

    // NEGATIVE: an empty-string backend must not be honoured as if it were
    // a real saved value (Python's `if backend` treats "" as falsy too).
    @Test
    fun resolveMailBackendTreatsAnEmptyStringAsUnsetNotAsALiteralBackend() {
        assertEquals(MAIL_BACKEND_IMAP, resolveMailBackend(mapOf("mail_backend" to "")))
    }

    // -----------------------------------------------------------------
    // is_gmail_mailbox / mailbox_clear_steps
    // -----------------------------------------------------------------

    @Test
    fun isGmailMailboxTrueForImapPointedAtGmail() {
        for (host in listOf("imap.gmail.com", "IMAP.GMAIL.COM", "imap.googlemail.com")) {
            assertTrue(host, isGmailMailbox(mapOf("mail_backend" to MAIL_BACKEND_IMAP, "imap_host" to host)))
        }
    }

    @Test
    fun isGmailMailboxFalseForOtherImapHosts() {
        for (host in listOf("imap.fastmail.com", "outlook.office365.com", "")) {
            assertFalse(host, isGmailMailbox(mapOf("mail_backend" to MAIL_BACKEND_IMAP, "imap_host" to host)))
        }
    }

    @Test
    fun isGmailMailboxToleratesMissingAndNullHost() {
        assertFalse(isGmailMailbox(mapOf("mail_backend" to MAIL_BACKEND_IMAP)))
        assertFalse(isGmailMailbox(mapOf("mail_backend" to MAIL_BACKEND_IMAP, "imap_host" to null)))
    }

    @Test
    fun mailboxClearStepsGmailNeverSaysDeleteTheFolder() {
        val steps = mailboxClearSteps("WhatsApp/Alice", gmail = true)
        val joined = steps.joinToString(" ").lowercase()
        assertFalse(joined.contains("delete the folder"))
        assertTrue(joined.contains("all mail"))
        assertTrue(steps.any { it.contains("WhatsApp/Alice") })
    }

    @Test
    fun mailboxClearStepsNonGmailNamesTheFolder() {
        val steps = mailboxClearSteps("WhatsApp/Alice", gmail = false)
        assertTrue(steps.any { it.contains("delete the folder 'WhatsApp/Alice'") })
    }

    // -----------------------------------------------------------------
    // Provider presets and retirement
    // -----------------------------------------------------------------

    @Test
    fun retiredProviderKeysAreNotAlsoOffered() {
        for (key in RETIRED_IMAP_PROVIDERS.keys) {
            assertFalse(key, IMAP_PROVIDERS.containsKey(key))
        }
    }

    @Test
    fun everyRetirementLandsOnAProviderThatExists() {
        for ((key, landing) in RETIRED_IMAP_PROVIDERS) {
            assertTrue("$key -> $landing", IMAP_PROVIDERS.containsKey(landing))
        }
    }

    @Test
    fun outlookIsRetiredOntoCustom() {
        assertEquals("custom", RETIRED_IMAP_PROVIDERS["outlook"])
        assertEquals("custom", resolveProviderKey("outlook"))
    }

    @Test
    fun aLiveProviderKeyResolvesToItself() {
        for (key in IMAP_PROVIDERS.keys) {
            assertEquals(key, resolveProviderKey(key))
        }
    }

    // NOTE: Python's `test_an_unknown_provider_key_falls_back_to_gmail` also
    // covers `0` and `[]` as inputs (both Python-falsy). resolveProviderKey
    // here takes a `String?`, so those two cases have no meaningful
    // equivalent call — see resolveProviderKey's KDoc.
    @Test
    fun anUnknownProviderKeyFallsBackToGmail() {
        for (key in listOf("", "nonesuch")) {
            assertEquals(key, "gmail", resolveProviderKey(key))
        }
        assertEquals("gmail", resolveProviderKey(null))
    }

    @Test
    fun noProviderIsOfferedWithoutAMessageSizeCap() {
        for ((key, preset) in IMAP_PROVIDERS) {
            if (preset.host != null) {
                assertTrue(key, PROVIDER_MAX_MESSAGE_BYTES.containsKey(key))
            }
        }
    }

    // NEGATIVE: "custom" must NOT have a size cap entry — its host is
    // user-supplied, so no provider-specific figure can be honest for it.
    @Test
    fun customProviderHasNoMessageSizeCapEntry() {
        assertFalse(PROVIDER_MAX_MESSAGE_BYTES.containsKey("custom"))
    }

    @Test
    fun retiredProviderLandingIsEmptyForKeysThatWereNeverOurs() {
        for (key in listOf("", "nonesuch", "gmail", "custom")) {
            assertEquals(key, "", retiredProviderLanding(key))
        }
        assertEquals("", retiredProviderLanding(null))
    }

    @Test
    fun retiredProviderLandingNamesTheReplacement() {
        for ((key, landing) in RETIRED_IMAP_PROVIDERS) {
            assertEquals(key, landing, retiredProviderLanding(key))
        }
    }

    @Test
    fun aolPresetMatchesYahooShape() {
        assertEquals(ImapProviderPreset("AOL", "imap.aol.com", 993), IMAP_PROVIDERS["aol"])
    }

    @Test
    fun existingProviderKeysStillPresent() {
        for (key in listOf("gmail", "yahoo", "icloud", "fastmail", "custom")) {
            assertTrue(key, IMAP_PROVIDERS.containsKey(key))
        }
    }

    @Test
    fun providerOrderIsProvenFirstThenExpectedThenFastmailThenCustom() {
        assertEquals(
            listOf("gmail", "yahoo", "icloud", "aol", "fastmail", "custom"),
            IMAP_PROVIDERS.keys.toList(),
        )
    }

    @Test
    fun noUndocumentedProviderPresetsWereAdded() {
        for (key in listOf("outlook", "hotmail", "zoho", "proton")) {
            assertFalse(key, IMAP_PROVIDERS.containsKey(key))
        }
    }

    // -----------------------------------------------------------------
    // Defaults must not silently drift (negative tests: these constants
    // are relied on by hashing/chunking/date-order decisions ported in
    // later Phase 2 PRs, so an accidental edit here would be a silent
    // behavioural break with no compiler signal).
    // -----------------------------------------------------------------

    @Test
    fun defaultChunkSizeIsDay() {
        assertEquals("day", DEFAULT_CHUNK_SIZE)
    }

    @Test
    fun defaultDateOrderIsDmy() {
        assertEquals("DMY", DATE_ORDER)
    }

    @Test
    fun formatDetectionAndDateOrderScanWindowsAreUnchanged() {
        assertEquals(20, FORMAT_DETECTION_LINES)
        assertEquals(50, DATE_ORDER_SCAN_MESSAGES)
    }

    @Test
    fun messageSizeSafetyFactorIsNinetyPercent() {
        assertEquals(0.90, MESSAGE_SIZE_SAFETY_FACTOR, 0.0)
    }

    @Test
    fun maxZipDecompressedBytesIsFiveHundredMebibytes() {
        assertEquals(500L * 1_048_576L, MAX_ZIP_DECOMPRESSED_BYTES)
    }

    @Test
    fun rateLimitingConstantsAreUnchanged() {
        assertEquals(180L, MAIL_SOCKET_TIMEOUT_SECONDS)
        assertEquals(0.1, API_CALL_DELAY_SECONDS, 0.0)
        assertEquals(1.0, BACKOFF_BASE_DELAY, 0.0)
        assertEquals(5, BACKOFF_MAX_ATTEMPTS)
    }

    // -----------------------------------------------------------------
    // Timestamp patterns — verbatim copy of config.py's regex strings.
    // -----------------------------------------------------------------

    @Test
    fun timestampPatternsMatchPythonVerbatimInOrder() {
        val expected = listOf(
            "bracketed_ampm_seconds" to
                "\\[(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2}:\\d{2}\\s[APap][Mm])\\]",
            "bracketed_24h_seconds" to
                "\\[(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2}:\\d{2})\\]",
            "plain_ampm" to
                "(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2}\\s[APap][Mm])",
            "plain_24h" to
                "(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s(\\d{1,2}:\\d{2})",
            "dash_24h" to
                "(\\d{1,2}-\\d{1,2}-\\d{4})\\s(\\d{1,2}:\\d{2})",
        )
        assertEquals(expected.size, TIMESTAMP_PATTERNS.size)
        expected.forEachIndexed { i, (key, pattern) ->
            assertEquals(key, TIMESTAMP_PATTERNS[i].key)
            assertEquals(pattern, TIMESTAMP_PATTERNS[i].pattern)
        }
    }

    // NEGATIVE: every pattern must actually compile as a regex (a typo'd
    // escape in a raw string copy would otherwise pass silently until the
    // parser port tries to use it).
    @Test
    fun everyTimestampPatternCompilesAndCapturesTwoGroups() {
        for (p in TIMESTAMP_PATTERNS) {
            val compiled = Regex(p.pattern)
            assertEquals(p.key, 2, compiled.toPattern().matcher("").groupCount())
        }
    }

    // -----------------------------------------------------------------
    // System message phrase lists — verbatim copy of config.py's lists.
    // -----------------------------------------------------------------

    @Test
    fun systemMessagePhrasesIsBodyThenBarePhrasesInOrder() {
        assertEquals(SYSTEM_BODY_PHRASES + SYSTEM_BARE_PHRASES, SYSTEM_MESSAGE_PHRASES)
    }

    @Test
    fun systemBodyPhrasesContainsMediaOmittedVariants() {
        for (phrase in listOf("media omitted", "<media omitted>", "image omitted", "video omitted")) {
            assertTrue(phrase, SYSTEM_BODY_PHRASES.contains(phrase))
        }
    }

    // NEGATIVE: a bare-only phrase must not leak into the body list, or a
    // real "Sender: <body>" line could be misclassified as a system line.
    @Test
    fun bareOnlyPhrasesAreNotInTheBodyList() {
        for (phrase in SYSTEM_BARE_PHRASES) {
            assertFalse(phrase, SYSTEM_BODY_PHRASES.contains(phrase))
        }
    }

    @Test
    fun attachmentPatternsMatchPythonVerbatim() {
        assertEquals(
            listOf(
                "^(.+?)\\s+\\(file attached\\)$",
                "^<attached:\\s*(.+?)>\\s*$",
            ),
            ATTACHMENT_PATTERNS,
        )
    }
}
