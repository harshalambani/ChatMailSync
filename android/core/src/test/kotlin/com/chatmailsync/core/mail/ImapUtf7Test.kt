package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ImapUtf7] (RFC 3501 5.1.3 modified UTF-7).
 *
 * Python has no standalone UTF-7 unit-test module -- `_encode_imap_utf7`/
 * `_decode_imap_utf7` are exercised only indirectly, through
 * `test_imap_transport.py`'s `test_labels_create_encodes_non_ascii_name_as_modified_utf7`
 * and `test_non_slash_delimiter_combines_correctly_with_utf7_encoding` (both
 * ported as-is in ImapTransportTest.kt). These tests are additional,
 * finer-grained coverage of the same algorithm in isolation, plus the two
 * mandatory negative cases from the brief: a non-ASCII round trip, and
 * malformed input that must be kept verbatim rather than throw.
 */
class ImapUtf7Test {

    @Test
    fun encodeEmojiMatchesKnownVector() {
        // U+1F600 -> UTF-16BE surrogate pair 0xD83D 0xDE00 -> base64 "2D3eAA==" -> "," swap, "=" stripped -> "2D3eAA"
        // Same vector as test_labels_create_encodes_non_ascii_name_as_modified_utf7 in test_imap_transport.py.
        assertEquals("&2D3eAA-", ImapUtf7.encode("😀"))
    }

    // Mandatory negative/positive test from the brief: non-ASCII UTF-7 round-trip.
    @Test
    fun encodeDecodeRoundTripsNonAscii() {
        val original = "WhatsApp/😀 Café 中文"
        val encoded = ImapUtf7.encode(original)
        assertEquals(original, ImapUtf7.decode(encoded))
    }

    @Test
    fun encodeDecodeRoundTripsPlainAscii() {
        val original = "WhatsApp/Alice Smith"
        assertEquals(original, ImapUtf7.encode(original))
        assertEquals(original, ImapUtf7.decode(original))
    }

    @Test
    fun encodeEscapesLiteralAmpersand() {
        assertEquals("Tom &- Jerry", ImapUtf7.encode("Tom & Jerry"))
        assertEquals("Tom & Jerry", ImapUtf7.decode("Tom &- Jerry"))
    }

    // Mandatory negative test from the brief: bad/malformed UTF-7 input must
    // fail safe (kept verbatim), never throw -- no terminating '-'.
    @Test
    fun decodeMalformedNoTerminatorKeptVerbatim() {
        // "&2D0" is a lone high surrogate: undecodable, so kept verbatim.
        val malformed = "WhatsApp/&2D0"
        assertEquals(malformed, ImapUtf7.decode(malformed))
        // PAR-06: a well-formed sequence with no terminating '-' IS decoded, as Python does.
        assertEquals("WhatsApp/" + String(Character.toChars(0x1F600)), ImapUtf7.decode("WhatsApp/&2D3eAA"))
    }

    // Mandatory negative test from the brief: bad/malformed UTF-7 input must
    // fail safe (kept verbatim), never throw -- invalid base64 alphabet
    // characters inside a shifted block.
    @Test
    fun decodeMalformedBadBase64KeptVerbatim() {
        // One data character is never valid base64. (Python drops non-alphabet characters
        // rather than failing, so the earlier "not valid base64!" text decoded to garbage there too.)
        val malformed = "WhatsApp/&a-/Alice"
        assertEquals(malformed, ImapUtf7.decode(malformed))
    }

    @Test
    fun quoteMailboxEscapesBackslashAndQuote() {
        assertEquals("\"Weird\\\\\\\"Name\"", ImapUtf7.quoteMailbox("Weird\\\"Name"))
    }

    @Test
    fun decodeBareAmpersandAtEndOfStringIsDroppedLikePython() {
        // Python: an empty shift sequence decodes to "" (PAR-06), so the lone '&' vanishes.
        assertEquals("abc", ImapUtf7.decode("abc&"))
    }

    @Test
    fun unquoteImapTokenReturnsNullForNil() {
        // unquoteImapToken lives in ListResponse.kt, not ImapUtf7 -- exercised
        // here too since it's the companion piece of wire-token handling that
        // labelsList relies on (see ImapTransportTest for the end-to-end path).
        assertNull(unquoteImapToken("NIL"))
    }
}
