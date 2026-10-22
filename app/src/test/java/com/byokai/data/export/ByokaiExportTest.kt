package com.byokai.data.export

import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.ui.conversations.FakeConversationRepository
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export/import tests with an in-memory [FakeConversationRepository].
 *
 * Covers the format envelope, the round-trip fidelity, the
 * no-credentials guarantee, and every documented rejection path.
 */
class ByokaiExportTest {

    private val plainJson = Json { ignoreUnknownKeys = true }

    /**
     * Throwaway attachment storage for tests that never touch
     * attachments. The v2 tests use their own seeded
     * [FakeAttachmentStorage].
     */
    private fun testStorage(): FakeAttachmentStorage =
        FakeAttachmentStorage(
            Files.createTempDirectory("byokai-att-test-").toFile()
                .also { it.deleteOnExit() },
        )

    private suspend fun seededRepo(): FakeConversationRepository {
        val repo = FakeConversationRepository()
        val c1 = (repo.create("Chat one") as Result.Success).data
        repo.setSystemPrompt(c1.id, "Be brief.")
        repo.appendMessage(c1.id, ChatRole.USER, "Hello", isPartial = false)
        repo.appendMessage(c1.id, ChatRole.ASSISTANT, "Hi there", isPartial = false)
        val c2 = (repo.create("Chat two") as Result.Success).data
        repo.appendMessage(c2.id, ChatRole.USER, "Second", isPartial = false)
        return repo
    }

    @Test
    fun `export produces the versioned envelope`() = runTest {
        val manager = ConversationExportManager(seededRepo(), testStorage())
        val json = (manager.exportToJson() as Result.Success).data

        val root = plainJson.parseToJsonElement(json).jsonObject
        assertEquals("byokai-export", root["format"]!!.toString().trim('"'))
        assertEquals("2", root["version"]!!.toString())
        val parsed = ByokaiExportFormat.parse(json) as Result.Success
        assertEquals(2, parsed.data.conversations.size)
        // Order follows the repository (recency); assert as a set.
        assertEquals(
            setOf("Chat one", "Chat two"),
            parsed.data.conversations.map { it.title }.toSet(),
        )
        val chatOne = parsed.data.conversations.first { it.title == "Chat one" }
        assertEquals(2, chatOne.messages.size)
        assertEquals("Be brief.", chatOne.systemPrompt)
    }

    @Test
    fun `export contains no credential material`() = runTest {
        val manager = ConversationExportManager(seededRepo(), testStorage())
        val json = (manager.exportToJson() as Result.Success).data.lowercase()

        // The export schema has a fixed key set; anything resembling
        // key material must never appear.
        val forbidden = listOf(
            "api_key", "apikey", "api-key", "secret", "token",
            "password", "credential", "keystore", "privatekey", "private_key",
        )
        for (word in forbidden) {
            assertFalse("export contained '$word'", json.contains(word))
        }
        // Top-level keys are exactly the documented envelope.
        val root = plainJson.parseToJsonElement(
            (manager.exportToJson() as Result.Success).data,
        ).jsonObject
        assertEquals(
            setOf("format", "version", "exportedAt", "appVersion", "conversations"),
            root.keys,
        )
    }

    @Test
    fun `round trip preserves content with fresh ids`() = runTest {
        val source = seededRepo()
        val manager = ConversationExportManager(source, testStorage())
        val json = (manager.exportToJson() as Result.Success).data

        val target = FakeConversationRepository()
        val summary = (ConversationExportManager(target, testStorage()).importFromJson(json) as Result.Success).data
        assertEquals(2, summary.importedConversations)
        assertEquals(3, summary.importedMessages)
        assertTrue(summary.skippedConversations.isEmpty())

        val imported = target.conversations().first()
        assertEquals(2, imported.size)
        val titles = imported.map { it.title }.toSet()
        assertEquals(setOf("Chat one", "Chat two"), titles)

        // Ids are fresh — no collision with the source rows.
        val sourceIds = source.conversations().first().map { it.id }.toSet()
        assertTrue(imported.none { it.id in sourceIds })

        // Content, order, roles, and system prompt survive.
        val chatOne = imported.first { it.title == "Chat one" }
        assertEquals("Be brief.", chatOne.systemPrompt)
        val messages = target.messages(chatOne.id).first()
        assertEquals(2, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
        assertEquals("Hi there", messages[1].content)
    }

    @Test
    fun `import into non-empty repo keeps existing conversations`() = runTest {
        val manager = ConversationExportManager(seededRepo(), testStorage())
        val json = (manager.exportToJson() as Result.Success).data

        val target = FakeConversationRepository()
        target.create("Existing")
        val summary =
            (ConversationExportManager(target, testStorage()).importFromJson(json) as Result.Success).data
        assertEquals(2, summary.importedConversations)
        assertEquals(3, target.conversations().first().size)
    }

    @Test
    fun `import rejects malformed json`() = runTest {
        val manager = ConversationExportManager(FakeConversationRepository(), testStorage())
        val result = manager.importFromJson("{ not json")
        assertTrue(result is Result.Error)
    }

    @Test
    fun `import rejects wrong format id`() = runTest {
        val manager = ConversationExportManager(FakeConversationRepository(), testStorage())
        val json = """{"format":"something-else","version":1,"exportedAt":0,"appVersion":"x","conversations":[]}"""
        val result = manager.importFromJson(json)
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).cause.message!!.contains("format"))
    }

    @Test
    fun `import rejects future version`() = runTest {
        val manager = ConversationExportManager(FakeConversationRepository(), testStorage())
        val json = """{"format":"byokai-export","version":99,"exportedAt":0,"appVersion":"x","conversations":[]}"""
        val result = manager.importFromJson(json)
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).cause.message!!.contains("newer"))
    }

    @Test
    fun `import rejects oversized input`() {
        // Build the check without allocating 50MB: the size gate is on
        // bytes, so pad just past the limit with a small repeat trick.
        val padding = "x".repeat(1024)
        val reps = ByokaiExportFormat.MAX_BYTES / 1024 + 1
        val big = buildString {
            append("""{"format":"byokai-export","version":1,"exportedAt":0,"appVersion":"x","conversations":[]}""")
            repeat(reps) { append(padding) }
        }
        // The padding breaks JSON, but the size gate fires first.
        val result = ByokaiExportFormat.parse(big)
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).cause.message!!.contains("50"))
    }
}
