package com.byokai.data.export

import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.Conversation
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.Message
import com.byokai.domain.repository.AttachmentStorage
import com.byokai.domain.repository.ConversationRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File-backed fake of [AttachmentStorage] for the v2 ZIP tests.
 *
 * `fileFor` resolves into a real temp dir, so the export/import paths
 * exercise genuine file IO (the same calls the real implementation
 * serves). Package-visible so [ByokaiExportTest] can reuse it.
 *
 * Assumes the Worker-A contract: `readBytes(attachment)`,
 * `delete(fileName)`, `fileFor(fileName)`.
 */
class FakeAttachmentStorage(private val dir: File) : AttachmentStorage {

    override suspend fun readBytes(attachment: ImageAttachment): ByteArray =
        fileFor(attachment.fileName).readBytes()

    override suspend fun delete(fileName: String) {
        fileFor(fileName).delete()
    }

    override fun fileFor(fileName: String): File = File(dir, fileName)

    fun seed(fileName: String, bytes: ByteArray) {
        fileFor(fileName).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    fun storedFileNames(): List<String> =
        dir.listFiles()?.map { it.name }.orEmpty()
}

/**
 * In-memory [ConversationRepository] that persists attachments on its
 * messages.
 *
 * Assumes the Worker-A contract: `Message` carries
 * `attachments: List<ImageAttachment>` and `appendMessage` accepts
 * them as a trailing parameter.
 */
private class AttachmentAwareFakeRepo : ConversationRepository {

    private val conversations = mutableMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<Message>>()
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
    private val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private var clock = 1_000L

    /** When true, every appendMessage returns [Result.Error]. */
    var failAppends: Boolean = false

    private fun emitConversations() {
        conversationsFlow.value =
            conversations.values.sortedByDescending { it.updatedAt }
    }

    override fun conversations(): Flow<List<Conversation>> = conversationsFlow

    override suspend fun get(id: String): Conversation? = conversations[id]

    override suspend fun create(title: String): Result<Conversation> {
        val now = ++clock
        val conversation = Conversation(UUID.randomUUID().toString(), title, now, now)
        conversations[conversation.id] = conversation
        emitConversations()
        return Result.Success(conversation)
    }

    override suspend fun delete(id: String): Result<Unit> {
        conversations.remove(id)
        messages.remove(id)
        messageFlows.remove(id)
        emitConversations()
        return Result.Success(Unit)
    }

    override suspend fun rename(id: String, title: String): Result<Unit> {
        conversations[id]?.let {
            conversations[id] = it.copy(title = title, updatedAt = ++clock)
            emitConversations()
        }
        return Result.Success(Unit)
    }

    override suspend fun setSystemPrompt(
        id: String,
        systemPrompt: String?,
    ): Result<Unit> {
        conversations[id]?.let {
            conversations[id] = it.copy(systemPrompt = systemPrompt)
        }
        return Result.Success(Unit)
    }

    override fun messages(conversationId: String): Flow<List<Message>> =
        messageFlows.getOrPut(conversationId) {
            MutableStateFlow(messages[conversationId].orEmpty())
        }

    override suspend fun appendMessage(
        conversationId: String,
        role: ChatRole,
        content: String,
        isPartial: Boolean,
        attachments: List<ImageAttachment>,
    ): Result<Message> {
        if (failAppends) return Result.Error(RuntimeException("simulated IO failure"))
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = ++clock,
            isPartial = isPartial,
            attachments = attachments,
        )
        messages.getOrPut(conversationId) { mutableListOf() }.add(message)
        messageFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
            .update { it + message }
        conversations[conversationId]?.let {
            conversations[conversationId] = it.copy(updatedAt = message.createdAt)
            emitConversations()
        }
        return Result.Success(message)
    }

    override suspend fun deleteMessagesFrom(
        conversationId: String,
        messageId: String,
    ): Result<Unit> = Result.Success(Unit)

    override fun searchConversations(query: String): Flow<List<Conversation>> =
        conversationsFlow

    override suspend fun markComplete(messageId: String): Result<Unit> =
        Result.Success(Unit)
}

/**
 * v2 ZIP export/import tests (Phase 9B).
 *
 * `java.util.zip` works on the JVM, so the whole archive round-trip —
 * including real temp files — is covered here. The attachment model
 * ([ImageAttachment], [AttachmentStorage]) is the Worker-A contract;
 * these tests compile once that work lands in the tree.
 */
class ByokaiExportV2Test {

    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File =
        Files.createTempDirectory("byokai-v2-").toFile().also { tempDirs.add(it) }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /** Deterministic pseudo-JPEG bytes (SOI marker + filler). */
    private fun fakeJpeg(seed: Int): ByteArray =
        ByteArray(512) { i ->
            when (i) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                else -> ((i * 31 + seed) % 256).toByte()
            }
        }

    private fun zipEntryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return names
    }

    @Test
    fun `v2 round trip preserves attachments with fresh ids and files`() = runTest {
        val source = AttachmentAwareFakeRepo()
        val sourceStorage = FakeAttachmentStorage(newTempDir())
        val conversation = (source.create("Trip") as Result.Success).data
        val img1 = fakeJpeg(11)
        val img2 = fakeJpeg(22)
        sourceStorage.seed("aaa.jpg", img1)
        sourceStorage.seed("bbb.jpg", img2)
        source.appendMessage(
            conversation.id, ChatRole.USER, "look at this", false,
            listOf(
                ImageAttachment("att-1", "aaa.jpg", "image/jpeg", 800, 600),
                ImageAttachment("att-2", "bbb.jpg", "image/jpeg", 640, 480),
            ),
        )
        source.appendMessage(conversation.id, ChatRole.ASSISTANT, "nice", false)

        val zipFile =
            (ConversationExportManager(source, sourceStorage).exportToZip()
                as Result.Success).data
        try {
            assertTrue(zipFile.length() > 0)
            val names = zipEntryNames(zipFile)
            assertTrue(names.contains(ByokaiExportFormat.EXPORT_JSON_ENTRY))
            assertTrue(names.contains(ByokaiExportFormat.ATTACHMENTS_DIR + "aaa.jpg"))
            assertTrue(names.contains(ByokaiExportFormat.ATTACHMENTS_DIR + "bbb.jpg"))

            val target = AttachmentAwareFakeRepo()
            val targetStorage = FakeAttachmentStorage(newTempDir())
            val summary = (
                ConversationExportManager(target, targetStorage)
                    .importFromBytes(zipFile.readBytes()) as Result.Success
                ).data
            assertEquals(1, summary.importedConversations)
            assertEquals(2, summary.importedMessages)
            assertEquals(0, summary.skippedAttachments)
            assertTrue(summary.skippedConversations.isEmpty())

            val imported = target.conversations().first().single()
            assertEquals("Trip", imported.title)
            // Fresh conversation id — no collision with the source row.
            assertFalse(imported.id == conversation.id)

            val msgs = target.messages(imported.id).first()
            assertEquals(2, msgs.size)
            val withAttachments = msgs[0]
            assertEquals(2, withAttachments.attachments.size)
            val (restored1, restored2) = withAttachments.attachments
            // Metadata survives; identity is fresh.
            assertEquals("image/jpeg", restored1.mimeType)
            assertEquals(800, restored1.width)
            assertEquals(600, restored1.height)
            assertFalse(restored1.id == "att-1")
            assertFalse(restored1.fileName == "aaa.jpg")
            assertTrue(restored1.fileName.endsWith(".jpg"))
            assertFalse(restored2.id == "att-2")
            // Bytes survive bit-for-bit in the target storage.
            assertArrayEquals(img1, targetStorage.readBytes(restored1))
            assertArrayEquals(img2, targetStorage.readBytes(restored2))
            // The text-only message has no attachments.
            assertTrue(msgs[1].attachments.isEmpty())
            // Source storage is untouched by the import.
            assertArrayEquals(img1, sourceStorage.readBytes(
                ImageAttachment("att-1", "aaa.jpg", "image/jpeg", 800, 600),
            ))
        } finally {
            zipFile.delete()
        }
    }

    @Test
    fun `v1 json still imports with the same behavior`() = runTest {
        val v1 = """{"format":"byokai-export","version":1,"exportedAt":0,"appVersion":"0.4.0","conversations":[{"title":"Old","createdAt":1,"updatedAt":2,"messages":[{"role":"USER","content":"hi","createdAt":3,"isPartial":false}]}]}"""

        // importFromBytes dispatches non-ZIP input to the JSON path.
        val target = AttachmentAwareFakeRepo()
        val storage = FakeAttachmentStorage(newTempDir())
        val manager = ConversationExportManager(target, storage)
        val summary =
            (manager.importFromBytes(v1.toByteArray(Charsets.UTF_8)) as Result.Success).data
        assertEquals(1, summary.importedConversations)
        assertEquals(1, summary.importedMessages)
        assertEquals(0, summary.skippedAttachments)
        val msgs = target.messages(target.conversations().first().single().id).first()
        assertEquals(1, msgs.size)
        assertEquals("hi", msgs[0].content)
        assertTrue(msgs[0].attachments.isEmpty())

        // The dedicated JSON entry point behaves identically.
        val target2 = AttachmentAwareFakeRepo()
        val summary2 = (
            ConversationExportManager(target2, FakeAttachmentStorage(newTempDir()))
                .importFromJson(v1) as Result.Success
            ).data
        assertEquals(1, summary2.importedConversations)
    }

    @Test
    fun `missing and empty attachment entries are skipped with a note`() = runTest {
        val okBytes = fakeJpeg(7)
        val exportJson = """{"format":"byokai-export","version":2,"exportedAt":0,"appVersion":"0.10.0","conversations":[{"title":"Pics","createdAt":1,"updatedAt":2,"messages":[{"role":"USER","content":"see","createdAt":3,"isPartial":false,"attachments":[{"id":"a1","fileName":"ghost.jpg","mimeType":"image/jpeg","width":10,"height":10},{"id":"a2","fileName":"empty.jpg","mimeType":"image/jpeg","width":10,"height":10},{"id":"a3","fileName":"ok.jpg","mimeType":"image/jpeg","width":10,"height":10}]}]}]}"""
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            zip.putNextEntry(ZipEntry(ByokaiExportFormat.EXPORT_JSON_ENTRY))
            zip.write(exportJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // Zero-byte entry: present but corrupt.
            zip.putNextEntry(ZipEntry(ByokaiExportFormat.ATTACHMENTS_DIR + "empty.jpg"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ByokaiExportFormat.ATTACHMENTS_DIR + "ok.jpg"))
            zip.write(okBytes)
            zip.closeEntry()
            // ghost.jpg has no entry at all.
        }

        val target = AttachmentAwareFakeRepo()
        val storage = FakeAttachmentStorage(newTempDir())
        val summary = (
            ConversationExportManager(target, storage)
                .importFromBytes(buf.toByteArray()) as Result.Success
            ).data
        // The conversation and message still import; the two bad
        // attachments are counted, never fatal.
        assertEquals(1, summary.importedConversations)
        assertEquals(1, summary.importedMessages)
        assertEquals(2, summary.skippedAttachments)
        assertTrue(summary.skippedConversations.isEmpty())

        val msgs = target.messages(target.conversations().first().single().id).first()
        assertEquals(1, msgs.size)
        assertEquals(1, msgs[0].attachments.size)
        assertArrayEquals(okBytes, storage.readBytes(msgs[0].attachments[0]))
    }

    @Test
    fun `oversize zip is rejected before parsing`() = runTest {
        // Not even a ZIP: the size gate fires before magic sniffing.
        val big = ByteArray(ByokaiExportFormat.MAX_BYTES + 1)
        val manager = ConversationExportManager(
            AttachmentAwareFakeRepo(),
            FakeAttachmentStorage(newTempDir()),
        )
        val result = manager.importFromBytes(big)
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).cause.message!!.contains("50"))
    }

    @Test
    fun `zip magic sniffing distinguishes archives from json`() {
        assertTrue(
            ByokaiExportFormat.looksLikeZip(
                byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00),
            ),
        )
        assertFalse(ByokaiExportFormat.looksLikeZip("{}".toByteArray()))
        assertFalse(ByokaiExportFormat.looksLikeZip(ByteArray(0)))
        assertFalse(ByokaiExportFormat.looksLikeZip(byteArrayOf(0x50, 0x4B)))
    }

    @Test
    fun `export fails loudly when an attachment cannot be read`() = runTest {
        val source = AttachmentAwareFakeRepo()
        val storage = FakeAttachmentStorage(newTempDir())
        val conversation = (source.create("C") as Result.Success).data
        // Metadata references a file that was never written.
        source.appendMessage(
            conversation.id, ChatRole.USER, "x", false,
            listOf(ImageAttachment("a9", "missing.jpg", "image/jpeg", 1, 1)),
        )
        val result = ConversationExportManager(source, storage).exportToZip()
        // No half-written corrupt ZIP is returned.
        assertTrue(result is Result.Error)
    }

    @Test
    fun `failed conversation import rolls back copied attachment files`() = runTest {
        val source = AttachmentAwareFakeRepo()
        val sourceStorage = FakeAttachmentStorage(newTempDir())
        val conversation = (source.create("Doomed") as Result.Success).data
        val img = fakeJpeg(3)
        sourceStorage.seed("pic.jpg", img)
        source.appendMessage(
            conversation.id, ChatRole.USER, "x", false,
            listOf(ImageAttachment("a1", "pic.jpg", "image/jpeg", 10, 10)),
        )
        val zipFile =
            (ConversationExportManager(source, sourceStorage).exportToZip()
                as Result.Success).data
        try {
            val target = AttachmentAwareFakeRepo().apply { failAppends = true }
            val targetStorage = FakeAttachmentStorage(newTempDir())
            val summary = (
                ConversationExportManager(target, targetStorage)
                    .importFromBytes(zipFile.readBytes()) as Result.Success
                ).data
            assertEquals(0, summary.importedConversations)
            assertEquals(listOf("Doomed"), summary.skippedConversations)
            // The attachment file copied before the failure was deleted again.
            assertTrue(targetStorage.storedFileNames().isEmpty())
            assertTrue(target.conversations().first().isEmpty())
        } finally {
            zipFile.delete()
        }
    }
}
