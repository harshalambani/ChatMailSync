package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.util.Base64

/**
 * Kotlin port of `tests/test_imap_transport.py` against [ImapTransport],
 * using [FakeImapConnection] (the Kotlin equivalent of that file's
 * `FakeImapConn`) instead of a real socket. Each ported test carries a
 * `mapping:` comment naming its Python original; tests exercising
 * `get_or_create_label`/`push_chat`/`push_chunks`/`_insert_with_backoff` are
 * out of scope for this phase (those callers are not ported -- see the PR
 * body) and are listed, not ignored, at the bottom of this file.
 */
class ImapTransportTest {

    private fun sampleChunk(): List<ParsedMessage> = listOf(
        ParsedMessage(
            chatId = "test_chat",
            timestamp = LocalDateTime.of(2019, 5, 3, 10, 15, 0),
            sender = "Meera Iyer",
            body = "hello from the past",
        ),
    )

    /** Builds a real message via MimeBuilder, the same pipeline messagesInsert() feeds, per the brief. */
    private fun realMessageBody(labelId: String = "WhatsApp/Meera Iyer", messageId: String = "<anchor@local>"): ByteArray {
        val chunk = sampleChunk()
        val (raw, _) = MimeBuilder.buildMimeMessage(
            displayName = "Meera Iyer",
            chunk = chunk,
            chunkSize = ChunkSize.Day,
            labelId = labelId,
            messageId = messageId,
        )
        return Base64.getUrlDecoder().decode(raw)
    }

    private fun makeTransport(conn: FakeImapConnection = FakeImapConnection()): Pair<ImapTransport, FakeImapConnection> {
        val transport = ImapTransport(
            host = "imap.example.com",
            port = 993,
            email = "me@example.com",
            password = "s3cret",
            connectionFactory = { conn },
        )
        return transport to conn
    }

    // ---------------------------------------------------------------------
    // labelsList
    // ---------------------------------------------------------------------

    // mapping: test_labels_list_parses_quoted_name_with_space_and_sets_id_equal_to_name
    @Test
    fun labelsListParsesQuotedNameWithSpaceAndSetsIdEqualToName() {
        val (transport, conn) = makeTransport()
        conn.listResponse = ImapResult(
            "OK",
            listOf(
                "(\\HasNoChildren) \"/\" \"INBOX\"",
                "(\\HasNoChildren) \"/\" \"WhatsApp/Alice Smith\"",
            ),
        )

        val labels = transport.labelsList()

        val names = labels.map { it.name }.toSet()
        assertTrue("WhatsApp/Alice Smith" in names)
        for (l in labels) assertEquals(l.name, l.id)
    }

    // mapping: test_labels_list_translates_wire_delimiter_back_to_slash
    // (test_labels_list_detects_and_caches_non_slash_delimiter is NOT ported
    // separately: it asserts a private `_delimiter` field with no public
    // Kotlin equivalent -- the same behaviour is proven end-to-end here.)
    @Test
    fun labelsListTranslatesWireDelimiterBackToSlash() {
        val (transport, conn) = makeTransport()
        conn.listResponse = ImapResult(
            "OK",
            listOf(
                "(\\HasNoChildren) \".\" \"INBOX\"",
                "(\\HasNoChildren) \".\" \"WhatsApp.Alice\"",
            ),
        )

        val labels = transport.labelsList()

        val names = labels.map { it.name }.toSet()
        assertTrue("WhatsApp/Alice" in names)
        assertFalse("WhatsApp.Alice" in names)
    }

    // Mandatory negative test from the brief: a malformed LIST line must be
    // skipped, not crash the whole labelsList() call.
    // mapping: none in Python (parseListResponse/_parse_list_response returning
    // None for an unparseable line is documented there but not asserted
    // through labels_list end-to-end) -- added per the brief's mandatory
    // negative-test list.
    @Test
    fun labelsListSkipsMalformedLineWithoutCrashing() {
        val (transport, conn) = makeTransport()
        conn.listResponse = ImapResult(
            "OK",
            listOf(
                "this is not a valid LIST response at all",
                "(\\HasNoChildren) \"/\" \"INBOX\"",
            ),
        )

        val labels = transport.labelsList()

        assertEquals(listOf("INBOX"), labels.map { it.name })
    }

    // ---------------------------------------------------------------------
    // labelsCreate
    // ---------------------------------------------------------------------

    // mapping: test_labels_create_success_returns_id_equal_to_name
    @Test
    fun labelsCreateSuccessReturnsIdEqualToName() {
        val (transport, conn) = makeTransport()

        val id = transport.labelsCreate("WhatsApp/Alice")

        assertEquals("WhatsApp/Alice", id)
        assertTrue(conn.calls.any { it is FakeImapConnection.Call.CreateCall && it.mailboxWireArg == "\"WhatsApp/Alice\"" })
        assertTrue(conn.calls.any { it is FakeImapConnection.Call.SubscribeCall && it.mailboxWireArg == "\"WhatsApp/Alice\"" })
    }

    // mapping: test_labels_create_already_exists_is_success_not_error
    @Test
    fun labelsCreateAlreadyExistsIsSuccessNotError() {
        val (transport, conn) = makeTransport()
        conn.createResponse = ImapResult("NO", listOf("[ALREADYEXISTS] Mailbox already exists."))

        val id = transport.labelsCreate("WhatsApp/Alice")

        assertEquals("WhatsApp/Alice", id)
    }

    // mapping: test_labels_create_translates_name_to_wire_delimiter
    @Test
    fun labelsCreateTranslatesNameToWireDelimiter() {
        val (transport, conn) = makeTransport()
        conn.listResponse = ImapResult("OK", listOf("(\\HasNoChildren) \".\" \"INBOX\""))
        transport.labelsList() // learns "." delimiter

        transport.labelsCreate("WhatsApp/Alice")

        assertTrue(conn.calls.any { it is FakeImapConnection.Call.CreateCall && it.mailboxWireArg == "\"WhatsApp.Alice\"" })
    }

    // mapping: test_labels_create_real_no_error_is_permanent_not_retried
    @Test
    fun labelsCreateRealNoErrorIsPermanentNotRetried() {
        val (transport, conn) = makeTransport()
        conn.createResponse = ImapResult("NO", listOf("[TRYCREATE] invalid mailbox"))

        try {
            transport.labelsCreate("Bad/Name")
            fail("expected MailTransportError")
        } catch (exc: MailTransportError) {
            assertTrue(exc.status !in setOf(429, 500, 502, 503, 504))
        }
    }

    // ---------------------------------------------------------------------
    // Wire syntax: quoting + modified UTF-7
    // ---------------------------------------------------------------------

    // mapping: test_labels_create_quotes_name_with_space_on_the_wire
    @Test
    fun labelsCreateQuotesNameWithSpaceOnTheWire() {
        val (transport, conn) = makeTransport()

        transport.labelsCreate("WhatsApp/Parity Test")

        assertTrue(conn.calls.any { it is FakeImapConnection.Call.CreateCall && it.mailboxWireArg == "\"WhatsApp/Parity Test\"" })
        assertTrue(conn.calls.any { it is FakeImapConnection.Call.SubscribeCall && it.mailboxWireArg == "\"WhatsApp/Parity Test\"" })
    }

    // mapping: test_labels_create_escapes_backslash_and_quote_on_the_wire
    @Test
    fun labelsCreateEscapesBackslashAndQuoteOnTheWire() {
        val (transport, conn) = makeTransport()

        transport.labelsCreate("WhatsApp/Weird\\\"Name")

        assertTrue(conn.calls.any { it is FakeImapConnection.Call.CreateCall && it.mailboxWireArg == "\"WhatsApp/Weird\\\\\\\"Name\"" })
    }

    // Mandatory negative/positive test from the brief: non-ASCII UTF-7
    // round-trip through the real ImapTransport wire path.
    // mapping: test_labels_create_encodes_non_ascii_name_as_modified_utf7
    @Test
    fun labelsCreateEncodesNonAsciiNameAsModifiedUtf7() {
        val (transport, conn) = makeTransport()

        transport.labelsCreate("WhatsApp/😀")

        assertTrue(conn.calls.any { it is FakeImapConnection.Call.CreateCall && it.mailboxWireArg == "\"WhatsApp/&2D3eAA-\"" })
    }

    // mapping: test_labels_list_round_trips_encoded_non_ascii_name_back_to_canonical
    @Test
    fun labelsListRoundTripsEncodedNonAsciiNameBackToCanonical() {
        val (transport, conn) = makeTransport()
        conn.listResponse = ImapResult("OK", listOf("(\\HasNoChildren) \"/\" \"WhatsApp/&2D3eAA-\""))

        val labels = transport.labelsList()

        val names = labels.map { it.name }.toSet()
        assertTrue("WhatsApp/😀" in names)
        for (l in labels) assertEquals(l.name, l.id)
    }

    // mapping: test_non_slash_delimiter_combines_correctly_with_utf7_encoding
    @Test
    fun nonSlashDelimiterCombinesCorrectlyWithUtf7Encoding() {
        val (transport, conn) = makeTransport()
        conn.listResponse = ImapResult("OK", listOf("(\\HasNoChildren) \".\" \"INBOX\""))
        transport.labelsList() // learns "." delimiter

        transport.labelsCreate("WhatsApp/😀 Alice")

        assertTrue(conn.calls.any { it is FakeImapConnection.Call.CreateCall && it.mailboxWireArg == "\"WhatsApp.&2D3eAA- Alice\"" })
    }

    // ---------------------------------------------------------------------
    // messagesInsert -- CRLF normalization
    // ---------------------------------------------------------------------

    // mapping: test_messages_insert_produces_crlf_terminated_bytes_from_real_message
    @Test
    fun messagesInsertProducesCrlfTerminatedBytesFromRealMessage() {
        val (transport, conn) = makeTransport()
        val body = realMessageBody()

        transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")

        val appended = conn.calls.last() as FakeImapConnection.Call.AppendCall
        val appendedText = String(appended.message, Charsets.UTF_8)
        assertTrue(appendedText.contains("\r\n"))
        assertFalse(appendedText.replace("\r\n", "").contains("\n"))
    }

    // ---------------------------------------------------------------------
    // messagesInsert -- threadId contract
    // ---------------------------------------------------------------------

    // mapping: test_messages_insert_returns_stable_thread_id_on_first_insert
    @Test
    fun messagesInsertReturnsStableThreadIdOnFirstInsert() {
        val (transport, _) = makeTransport()
        val body = realMessageBody(messageId = "<anchor-123@local>")

        val result = transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer", threadId = null)

        assertEquals("<anchor-123@local>", result.threadId)
    }

    // mapping: test_messages_insert_echoes_thread_id_on_subsequent_insert
    @Test
    fun messagesInsertEchoesThreadIdOnSubsequentInsert() {
        val (transport, _) = makeTransport()
        val body = realMessageBody(messageId = "<chunk-2@local>")

        val result = transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer", threadId = "<anchor-123@local>")

        assertEquals("<anchor-123@local>", result.threadId)
    }

    // mapping: test_messages_insert_prefers_appenduid_when_available
    @Test
    fun messagesInsertPrefersAppenduidWhenAvailable() {
        val (transport, conn) = makeTransport()
        conn.appendResponse = ImapResult("OK", listOf("[APPENDUID 38505 3955] APPEND completed"))
        val body = realMessageBody()

        val result = transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")

        assertEquals("38505-3955", result.id)
    }

    // Mandatory negative test from the brief: APPENDUID absent -> falls back
    // to the message's own Message-ID, never invented.
    // mapping: test_messages_insert_falls_back_to_message_id_without_appenduid
    @Test
    fun messagesInsertFallsBackToMessageIdWithoutAppenduid() {
        val (transport, conn) = makeTransport()
        conn.appendResponse = ImapResult("OK", listOf("Completed"))
        val body = realMessageBody(messageId = "<anchor-fallback@local>")

        val result = transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")

        assertEquals("<anchor-fallback@local>", result.id)
    }

    // ---------------------------------------------------------------------
    // messagesInsert -- internaldate fidelity
    // ---------------------------------------------------------------------

    // mapping: test_messages_insert_internaldate_comes_from_date_header_not_clock
    @Test
    fun messagesInsertInternaldateComesFromDateHeaderNotClock() {
        val (transport, conn) = makeTransport()
        val body = realMessageBody() // Date header is 2019-05-03 (see sampleChunk)

        transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")

        val appended = conn.calls.last() as FakeImapConnection.Call.AppendCall
        val date = appended.internalDate
        assertNotNull(date)
        assertEquals(2019, date!!.year)
        assertEquals(5, date.monthValue)
        assertEquals(3, date.dayOfMonth)
    }

    // mapping: test_messages_insert_sets_seen_flag_by_default
    @Test
    fun messagesInsertSetsSeenFlagByDefault() {
        val (transport, conn) = makeTransport()
        val body = realMessageBody()

        transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")

        val appended = conn.calls.last() as FakeImapConnection.Call.AppendCall
        assertTrue(appended.flags?.contains("\\Seen") == true)
    }

    // ---------------------------------------------------------------------
    // Error mapping
    // ---------------------------------------------------------------------

    // Mandatory negative test from the brief: a rejected login must map to a
    // non-retryable status and must never leak the password.
    // mapping: test_login_failure_maps_to_permanent_non_retryable_status
    @Test
    fun loginFailureMapsToPermanentNonRetryableStatus() {
        val transport = ImapTransport(
            host = "imap.example.com",
            port = 993,
            email = "me@example.com",
            password = "s3cret",
            connectionFactory = {
                throw MailTransportError(
                    "IMAP login failed for me@example.com @ imap.example.com:993 — " +
                        "provider blocked app-password IMAP. Server said: boom",
                    401,
                )
            },
        )

        try {
            transport.labelsList()
            fail("expected MailTransportError")
        } catch (exc: MailTransportError) {
            assertTrue(exc.status !in setOf(429, 500, 502, 503, 504))
            assertFalse((exc.message ?: "").contains("s3cret"))
        }
    }

    // mapping: test_transient_server_error_maps_to_503
    @Test
    fun transientServerErrorMapsTo503() {
        val (transport, conn) = makeTransport()
        conn.appendResponse = ImapResult("NO", listOf("[UNAVAILABLE] Server temporarily unavailable"))
        val body = realMessageBody()

        try {
            transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")
            fail("expected MailTransportError")
        } catch (exc: MailTransportError) {
            assertEquals(503, exc.status)
        }
    }

    // Mandatory negative test from the brief: the password must never appear
    // in any exception message, including one raised by the connection itself.
    // mapping: test_password_never_appears_in_any_exception_message
    @Test
    fun passwordNeverAppearsInAnyExceptionMessage() {
        val (transport, conn) = makeTransport()
        conn.raiseOn["append"] = ImapCommandError("auth failed with s3cret in it")
        val body = realMessageBody()

        try {
            transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")
            fail("expected MailTransportError")
        } catch (exc: MailTransportError) {
            assertFalse((exc.message ?: "").contains("s3cret"))
        }
    }

    // mapping: test_imap4_error_is_not_blanket_retried
    @Test
    fun imap4ErrorIsNotBlanketRetried() {
        val (transport, conn) = makeTransport()
        conn.raiseOn["append"] = ImapCommandError("BAD command syntax")
        val body = realMessageBody()

        try {
            transport.messagesInsert(body, folder = "WhatsApp/Meera Iyer")
            fail("expected MailTransportError")
        } catch (exc: MailTransportError) {
            assertTrue(exc.status !in setOf(429, 500, 502, 503, 504))
        }
    }

    // mapping: test_abort_reconnects_once_transparently
    @Test
    fun abortReconnectsOnceTransparently() {
        val firstConn = FakeImapConnection()
        firstConn.raiseOn["list"] = ImapAbortError("connection dropped")
        val secondConn = FakeImapConnection()
        val factories = mutableListOf<FakeImapConnection>()
        val transport = ImapTransport(
            host = "imap.example.com",
            port = 993,
            email = "me@example.com",
            password = "s3cret",
            connectionFactory = {
                val c = if (factories.isEmpty()) firstConn else secondConn
                factories.add(c)
                c
            },
        )

        val labels = transport.labelsList()

        assertEquals(listOf("INBOX"), labels.map { it.name })
        assertEquals(2, factories.size) // reconnected exactly once
    }

    // ---------------------------------------------------------------------
    // maxMessageBytes (mirrors `max_message_bytes`) -- mandatory negative
    // test: a server that never advertises APPENDLIMIT (no connection has
    // been opened at all, so `appendLimit()` can't have read one off the
    // wire either) must fall back to a fixed preset, never invent a number.
    // ---------------------------------------------------------------------

    // mapping: no direct Python unit test (max_message_bytes has no dedicated
    // test module); this is additional coverage for the "no-APPENDLIMIT
    // server doesn't invent one" requirement.
    @Test
    fun maxMessageBytesFallsBackToProviderPresetWhenServerHasNoAppendlimit() {
        // A known provider host with no live connection (so appendLimit()
        // is null) must fall back to that provider's fixed preset, not the
        // generic default and not anything invented.
        val yahooTransport = ImapTransport(
            host = "imap.mail.yahoo.com",
            port = 993,
            email = "me@example.com",
            password = "s3cret",
        )
        assertEquals(PROVIDER_MAX_MESSAGE_BYTES.getValue("yahoo"), yahooTransport.maxMessageBytes)

        // An unrecognised host with no live connection must fall back to the
        // generic default, still without inventing anything.
        val unknownTransport = ImapTransport(
            host = "imap.some-other-provider.example.com",
            port = 993,
            email = "me@example.com",
            password = "s3cret",
        )
        assertEquals(DEFAULT_MAX_MESSAGE_BYTES, unknownTransport.maxMessageBytes)
    }

    // ---------------------------------------------------------------------
    // ownsLabelId (the part of the stale-label-id story that is in scope --
    // get_or_create_label/push_chat, which the Python tests exercise this
    // through, are not ported; see the bottom of this file)
    // ---------------------------------------------------------------------

    // mapping: test_a_stored_oauth_label_id_is_not_sent_to_imap_as_a_folder_name (partial -- only the owns_label_id() assertions)
    @Test
    fun ownsLabelIdRejectsOpaqueHandleAndAcceptsItsOwn() {
        val (transport, _) = makeTransport()

        assertFalse(transport.ownsLabelId("Label_5928374102938", "Kavya Menon"))
        assertTrue(transport.ownsLabelId(fullLabelName("Kavya Menon"), "Kavya Menon"))
    }

    // ---------------------------------------------------------------------
    // Not ported -- out of scope for this phase (see PR body):
    //
    // - test_imap_transport_satisfies_the_mail_transport_protocol
    //     (MailTransport Protocol/interface conformance check; ImapTransport
    //     is a concrete class here, not checked against a shared interface
    //     other transports also implement -- there is only one transport.)
    // - test_labels_create_plain_ascii_name_still_works_unquoted_content
    //     (duplicate coverage of labelsCreateSuccessReturnsIdEqualToName)
    // - test_insert_with_backoff_retries_transient_imap_error_then_succeeds
    //     (_insert_with_backoff is a sync-pipeline retry wrapper, not part of
    //     ImapTransport itself; out of scope)
    // - test_get_or_create_label_end_to_end_with_imap_transport
    // - test_get_or_create_label_returns_existing_without_recreating
    // - test_a_stored_oauth_label_id_is_not_sent_to_imap_as_a_folder_name (push_chat half)
    // - test_a_transport_with_opaque_label_ids_keeps_reusing_its_stored_id
    //     (get_or_create_label/push_chat/_label_id_is_usable are sync-pipeline
    //     callers, out of scope for this phase -- see the PR body)
    // ---------------------------------------------------------------------
}
