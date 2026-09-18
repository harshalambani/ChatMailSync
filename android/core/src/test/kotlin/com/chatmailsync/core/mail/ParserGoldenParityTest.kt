package com.chatmailsync.core.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Cross-language parity test for the Kotlin [parseFile]/[extractChatInfo]
 * port against the *real* Python `src/parser.py`.
 *
 * `tools/generate_kotlin_core_golden_fixtures.py` runs the real Python
 * `parse_file`/`extract_chat_info` over the fixtures declared there
 * (`PARSER_FIXTURES`, `PARSER_CHAT_INFO_FIXTURES` -- one per
 * `TIMESTAMP_PATTERNS` entry, both date-order resolution paths, the 2-digit
 * year pivot boundary, system messages, Android/iOS attachments, and the
 * filename-normalisation edge cases) and writes the exact output as
 * `android/core/src/test/resources/golden/parser_golden.json`. This test
 * reads that same JSON from the classpath, re-runs the *Kotlin* parser over
 * the identical input text, and asserts field-for-field equality -- proving
 * the port, not just testing it in isolation.
 *
 * Follows the classpath-resource-reading pattern established in
 * `MimeGoldenParityTest.kt`.
 */
class ParserGoldenParityTest {

    private fun readGoldenResource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("golden/$name")
            ?: error("golden resource not found on test classpath: golden/$name")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun tempFile(content: String): File {
        val dir = File.createTempFile("parsergolden", "dir")
        dir.delete()
        dir.mkdirs()
        val f = File(dir, "chat.txt")
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    @Test
    fun parsedMessagesMatchThePythonParserForEveryFixture() {
        val root = parseJson(readGoldenResource("parser_golden.json")).asObj()
        val fixtureTexts = root["fixtureTexts"].asObj()
        val parsedMessages = root["parsedMessages"].asObj()

        for ((name, textNode) in fixtureTexts.fields) {
            val text = textNode.asString()
            val f = tempFile(text)
            val actual = parseFile(f, chatId = "golden_chat").toList()
            val expected = parsedMessages[name].asArr().items.map { it.asObj() }

            assertEquals("fixture '$name': message count", expected.size, actual.size)
            for (i in expected.indices) {
                val exp = expected[i]
                val act = actual[i]
                assertEquals("fixture '$name'[$i].chatId", exp["chatId"].asString(), act.chatId)
                assertEquals("fixture '$name'[$i].timestampIso", exp["timestampIso"].asString(), act.timestampIso)
                assertEquals("fixture '$name'[$i].sender", exp["sender"].asString(), act.sender)
                assertEquals("fixture '$name'[$i].body", exp["body"].asString(), act.body)
                assertEquals(
                    "fixture '$name'[$i].attachmentFilename",
                    exp["attachmentFilename"].asStringOrNull(),
                    act.attachmentFilename,
                )
            }
        }
    }

    @Test
    fun extractChatInfoMatchesThePythonParserForEveryFilename() {
        val root = parseJson(readGoldenResource("parser_golden.json")).asObj()
        val chatInfo = root["chatInfo"].asArr().items.map { it.asObj() }

        for (entry in chatInfo) {
            val filename = entry["filename"].asString()
            val expectedChatId = entry["chatId"].asString()
            val expectedDisplayName = entry["displayName"].asString()

            val actual = extractChatInfo(filename)
            assertEquals("chatId for '$filename'", expectedChatId, actual.chatId)
            assertEquals("displayName for '$filename'", expectedDisplayName, actual.displayName)
        }
    }
}
