package com.byokai.data.export

import com.byokai.BuildConfig
import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.repository.AttachmentStorage
import com.byokai.domain.repository.ConversationRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Exports and imports conversation history (Milestone 4E, v2 ZIP in
 * Phase 9B).
 *
 * Two serializations share the [ByokaiExport] envelope:
 *
 * - **v1 JSON** ([exportToJson] / [importFromJson]): the bare envelope
 *   as a UTF-8 JSON document. Conversation text and metadata only —
 *   never credentials (the repository layer has no access to key
 *   material). Attachment *metadata* is included but the raw bytes
 *   are not; use the ZIP form for full fidelity.
 * - **v2 ZIP** ([exportToZip] / [importFromZip] / [importFromBytes]):
 *   `export.json` plus one `attachments/<fileName>` entry per image
 *   with the raw bytes read via [AttachmentStorage].
 *
 * Import validates the envelope, then re-creates each conversation
 * with FRESH UUIDs (no id collisions with existing rows) and
 * re-appends its messages in order. Timestamps are preserved from
 * the export; ids are not. Attachments are copied into
 * [AttachmentStorage] under fresh file names (`<uuid>.jpg`) and
 * remapped onto the re-created messages.
 *
 * All work runs on [Dispatchers.IO]. Import is all-or-nothing per
 * conversation: a conversation whose messages fail to append is
 * reported in the summary rather than half-imported, and any
 * attachment files already copied for it are deleted again. One
 * missing or corrupt attachment entry never fails the whole import —
 * it is counted in [ImportSummary.skippedAttachments].
 *
 * @param attachmentStorage backing store for image bytes. Required:
 * the v2 ZIP paths cannot function without it. (DI wiring note for
 * the coordinator: `AppContainer` must pass the real
 * `AttachmentStorage` here.)
 */
class ConversationExportManager(
    private val conversationRepository: ConversationRepository,
    private val attachmentStorage: AttachmentStorage,
) {

    /**
     * Summary of an import run.
     *
     * @property importedConversations conversations fully recreated.
     * @property importedMessages messages appended across them.
     * @property skippedConversations conversations that could not be
     * imported (with reasons); nothing was written for them.
     * @property skippedAttachments individual attachments that could
     * not be restored (missing or corrupt ZIP entry, or a storage
     * write failure); their messages were still imported without
     * them.
     */
    data class ImportSummary(
        val importedConversations: Int,
        val importedMessages: Int,
        val skippedConversations: List<String>,
        val skippedAttachments: Int = 0,
    )

    suspend fun exportToJson(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.Success(ByokaiExportFormat.json.encodeToString(buildPayload()))
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Builds the v2 ZIP: `export.json` (envelope version 2, messages
     * with attachment metadata) plus `attachments/<fileName>` raw
     * bytes for every image, read via [AttachmentStorage].
     *
     * Fails LOUDLY when any attachment's bytes cannot be read — a ZIP
     * whose `export.json` references entries that are not there would
     * be corrupt by construction, so there is no silent skip on the
     * export path. The returned temp file is the caller's to delete
     * after handing it to the Storage Access Framework.
     */
    suspend fun exportToZip(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload()
            val tmp = File.createTempFile("byokai-export-", ".zip")
            try {
                ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(ByokaiExportFormat.EXPORT_JSON_ENTRY))
                    zip.write(
                        ByokaiExportFormat.json.encodeToString(payload)
                            .toByteArray(Charsets.UTF_8),
                    )
                    zip.closeEntry()
                    for (conversation in payload.conversations) {
                        for (message in conversation.messages) {
                            for (attachment in message.attachments) {
                                // readBytes resolves the file by fileName; the
                                // exported metadata carries the same fields.
                                val bytes = attachmentStorage.readBytes(
                                    attachment.toImageAttachment(),
                                )
                                zip.putNextEntry(
                                    ZipEntry(
                                        ByokaiExportFormat.ATTACHMENTS_DIR +
                                            attachment.fileName,
                                    ),
                                )
                                zip.write(bytes)
                                zip.closeEntry()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            Result.Success(tmp)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Parses and imports [json]. Returns [Result.Error] when the input
     * is not a valid export (oversized, malformed JSON, wrong format
     * id, or unsupported version) — nothing is written in that case.
     *
     * Behavior is unchanged from v1: messages are re-created without
     * attachments. Attachment metadata that a v2 JSON envelope may
     * carry is ignored here — the bytes only exist in the ZIP form
     * (see [importFromZip]).
     */
    suspend fun importFromJson(json: String): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            val payload = when (val parsed = ByokaiExportFormat.parse(json)) {
                is Result.Success -> parsed.data
                is Result.Error -> return@withContext Result.Error(parsed.cause)
                is Result.Loading -> return@withContext Result.Error(
                    IllegalStateException("unexpected Loading"),
                )
            }
            var conversations = 0
            var messages = 0
            val skipped = mutableListOf<String>()
            for (exported in payload.conversations) {
                val title = exported.title.ifBlank { "Imported conversation" }
                when (val created = conversationRepository.create(title)) {
                    is Result.Success -> {
                        val id = created.data.id
                        var ok = true
                        if (!exported.systemPrompt.isNullOrBlank()) {
                            if (conversationRepository.setSystemPrompt(
                                    id,
                                    exported.systemPrompt,
                                ) is Result.Error
                            ) {
                                ok = false
                            }
                        }
                        var appended = 0
                        if (ok) {
                            for (message in exported.messages) {
                                val role = runCatching { ChatRole.valueOf(message.role) }
                                    .getOrDefault(ChatRole.USER)
                                when (
                                    conversationRepository.appendMessage(
                                        id,
                                        role,
                                        message.content,
                                        message.isPartial,
                                    )
                                ) {
                                    is Result.Success -> appended++
                                    is Result.Error -> {
                                        ok = false
                                        break
                                    }
                                    is Result.Loading -> Unit
                                }
                            }
                        }
                        if (ok) {
                            conversations++
                            messages += appended
                        } else {
                            // Roll back the half-imported conversation.
                            conversationRepository.delete(id)
                            skipped.add(title)
                        }
                    }
                    is Result.Error -> skipped.add(title)
                    is Result.Loading -> Unit
                }
            }
            Result.Success(ImportSummary(conversations, messages, skipped))
        }

    /**
     * Imports from raw [bytes], dispatching on content: a ZIP archive
     * (detected by the `PK\x03\x04` magic) goes through [importFromZip],
     * anything else through [importFromJson].
     *
     * The [ByokaiExportFormat.MAX_BYTES] cap is enforced on the raw
     * bytes BEFORE sniffing or parsing.
     */
    suspend fun importFromBytes(bytes: ByteArray): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            if (bytes.size > ByokaiExportFormat.MAX_BYTES) {
                return@withContext Result.Error(
                    IllegalArgumentException(
                        "Export is larger than " +
                            "${ByokaiExportFormat.MAX_BYTES / 1024 / 1024} MB",
                    ),
                )
            }
            if (ByokaiExportFormat.looksLikeZip(bytes)) {
                importZip(bytes)
            } else {
                importFromJson(bytes.toString(Charsets.UTF_8))
            }
        }

    /**
     * Imports a v2 ZIP archive (see [exportToZip]).
     *
     * Validates the envelope, re-creates conversations with fresh
     * UUIDs, copies each attachment into [AttachmentStorage] under a
     * fresh file name (`<uuid>.jpg`), and remaps the message
     * attachments onto the re-created messages. A missing or corrupt
     * attachment entry increments [ImportSummary.skippedAttachments]
     * without failing the import.
     */
    suspend fun importFromZip(bytes: ByteArray): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            if (bytes.size > ByokaiExportFormat.MAX_BYTES) {
                return@withContext Result.Error(
                    IllegalArgumentException(
                        "Export is larger than " +
                            "${ByokaiExportFormat.MAX_BYTES / 1024 / 1024} MB",
                    ),
                )
            }
            importZip(bytes)
        }

    private suspend fun importZip(bytes: ByteArray): Result<ImportSummary> {
        val entries = try {
            readZipEntries(bytes)
        } catch (e: Exception) {
            return Result.Error(
                IllegalArgumentException("Not a valid BYOKAI export file", e),
            )
        }
        val exportJson = entries[ByokaiExportFormat.EXPORT_JSON_ENTRY]
            ?: return Result.Error(
                IllegalArgumentException(
                    "Not a valid BYOKAI export file: missing export.json",
                ),
            )
        val payload = when (
            val parsed = ByokaiExportFormat.parse(exportJson.toString(Charsets.UTF_8))
        ) {
            is Result.Success -> parsed.data
            is Result.Error -> return Result.Error(parsed.cause)
            is Result.Loading -> return Result.Error(
                IllegalStateException("unexpected Loading"),
            )
        }

        var conversations = 0
        var messages = 0
        var skippedAttachments = 0
        val skipped = mutableListOf<String>()
        for (exported in payload.conversations) {
            val title = exported.title.ifBlank { "Imported conversation" }
            when (val created = conversationRepository.create(title)) {
                is Result.Success -> {
                    val id = created.data.id
                    var ok = true
                    // Attachment files copied for this conversation; deleted
                    // again when the conversation is rolled back.
                    val copied = mutableListOf<ImageAttachment>()
                    if (!exported.systemPrompt.isNullOrBlank()) {
                        if (conversationRepository.setSystemPrompt(
                                id,
                                exported.systemPrompt,
                            ) is Result.Error
                        ) {
                            ok = false
                        }
                    }
                    var appended = 0
                    if (ok) {
                        for (message in exported.messages) {
                            val remapped = mutableListOf<ImageAttachment>()
                            for (attachment in message.attachments) {
                                val data = entries[
                                    ByokaiExportFormat.ATTACHMENTS_DIR +
                                        attachment.fileName,
                                ]
                                // Missing entry (no match — the entry-name
                                // sanitizer already excluded traversal
                                // attempts) or zero bytes: skip with a note,
                                // never fail the import.
                                if (data == null || data.isEmpty()) {
                                    skippedAttachments++
                                    continue
                                }
                                val freshId = UUID.randomUUID().toString()
                                val fresh = ImageAttachment(
                                    id = freshId,
                                    fileName = "$freshId.jpg",
                                    mimeType = attachment.mimeType,
                                    width = attachment.width,
                                    height = attachment.height,
                                )
                                val stored = runCatching {
                                    attachmentStorage.fileFor(fresh.fileName)
                                        .also { it.parentFile?.mkdirs() }
                                        .writeBytes(data)
                                }.isSuccess
                                if (stored) {
                                    remapped.add(fresh)
                                    copied.add(fresh)
                                } else {
                                    skippedAttachments++
                                }
                            }
                            val role = runCatching { ChatRole.valueOf(message.role) }
                                .getOrDefault(ChatRole.USER)
                            when (
                                conversationRepository.appendMessage(
                                    conversationId = id,
                                    role = role,
                                    content = message.content,
                                    isPartial = message.isPartial,
                                    attachments = remapped,
                                )
                            ) {
                                is Result.Success -> appended++
                                is Result.Error -> {
                                    ok = false
                                    break
                                }
                                is Result.Loading -> Unit
                            }
                        }
                    }
                    if (ok) {
                        conversations++
                        messages += appended
                    } else {
                        // Roll back: delete copied attachment files
                        // (best effort), then the half-imported
                        // conversation.
                        for (attachment in copied) {
                            runCatching { attachmentStorage.delete(attachment.fileName) }
                        }
                        conversationRepository.delete(id)
                        skipped.add(title)
                    }
                }
                is Result.Error -> skipped.add(title)
                is Result.Loading -> Unit
            }
        }
        return Result.Success(
            ImportSummary(conversations, messages, skipped, skippedAttachments),
        )
    }

    /**
     * Reads every conversation with its messages (including attachment
     * metadata) into the shared [ByokaiExport] payload used by both
     * the JSON and the ZIP export.
     */
    private suspend fun buildPayload(): ByokaiExport {
        val conversations = conversationRepository.conversations().first()
        val exported = conversations.map { conversation ->
            val messages = conversationRepository.messages(conversation.id).first()
            ExportedConversation(
                title = conversation.title,
                systemPrompt = conversation.systemPrompt,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
                messages = messages.map { message ->
                    ExportedMessage(
                        role = message.role.name,
                        content = message.content,
                        createdAt = message.createdAt,
                        isPartial = message.isPartial,
                        attachments = message.attachments.map { attachment ->
                            ExportedAttachment(
                                id = attachment.id,
                                fileName = attachment.fileName,
                                mimeType = attachment.mimeType,
                                width = attachment.width,
                                height = attachment.height,
                            )
                        },
                    )
                },
            )
        }
        return ByokaiExport(
            format = ByokaiExportFormat.FORMAT_ID,
            version = ByokaiExportFormat.VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            conversations = exported,
        )
    }

    /**
     * Reads a ZIP archive into entry-name -> bytes. Only `export.json`
     * and `attachments/*` entries with safe names are kept; anything
     * else (including path-traversal attempts like `../`) is ignored.
     * The TOTAL uncompressed size is capped at [ByokaiExportFormat.MAX_BYTES]
     * — enforced incrementally while reading, so a highly compressible
     * archive cannot OOM the importer.
     */
    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var totalUncompressed = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isSafeEntryName(entry.name)) {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read <= 0) break
                        totalUncompressed += read
                        if (totalUncompressed > ByokaiExportFormat.MAX_BYTES) {
                            throw IllegalArgumentException(
                                "Export is larger than " +
                                    "${ByokaiExportFormat.MAX_BYTES / 1024 / 1024} MB " +
                                    "uncompressed",
                            )
                        }
                        buf.write(chunk, 0, read)
                    }
                    out[entry.name] = buf.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\")) return false
        // Reject empty segments, "." and ".." — no traversal, no absolute paths.
        if (name.split("/").any { it.isEmpty() || it == "." || it == ".." }) return false
        return name == ByokaiExportFormat.EXPORT_JSON_ENTRY ||
            name.startsWith(ByokaiExportFormat.ATTACHMENTS_DIR)
    }

    /**
     * Rebuilds the domain attachment from exported metadata so
     * [AttachmentStorage.readBytes] can resolve the source file by
     * `fileName` on the export path.
     */
    private fun ExportedAttachment.toImageAttachment(): ImageAttachment =
        ImageAttachment(
            id = id,
            fileName = fileName,
            mimeType = mimeType,
            width = width,
            height = height,
        )
}

/**
 * Format constants and parsing/validation for [ByokaiExport].
 *
 * Pure logic (no Android dependencies beyond BuildConfig-free
 * constants) — JVM-unit-tested.
 */
object ByokaiExportFormat {

    const val FORMAT_ID = "byokai-export"

    /** Current format version. Importers accept <= this. */
    const val VERSION = 2

    /** Imports larger than this are rejected before parsing. */
    const val MAX_BYTES = 50 * 1024 * 1024

    /** The envelope document inside a v2 ZIP archive. */
    const val EXPORT_JSON_ENTRY = "export.json"

    /** Directory prefix for raw attachment bytes inside a v2 ZIP. */
    const val ATTACHMENTS_DIR = "attachments/"

    /** ZIP local-file-header magic: 'P','K',0x03,0x04. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    val json: Json = Json {
        prettyPrint = true
        // Unknown future fields are ignored so older app versions can
        // still read newer exports' known subset... (reversed: newer
        // fields are ignored by older readers; version gate below is
        // the real compatibility check).
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * True when [bytes] start with the ZIP local-file-header magic.
     * Used by [ConversationExportManager.importFromBytes] to dispatch
     * between the v2 ZIP and the JSON form.
     */
    fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < ZIP_MAGIC.size) return false
        return ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }
    }

    /**
     * Validates [json] and parses it. Fails when the input exceeds
     * [MAX_BYTES], is not valid JSON, has the wrong `format` id, or
     * declares a `version` newer than [VERSION].
     */
    fun parse(json: String): Result<ByokaiExport> {
        if (json.toByteArray().size > MAX_BYTES) {
            return Result.Error(
                IllegalArgumentException(
                    "Export is larger than ${MAX_BYTES / 1024 / 1024} MB",
                ),
            )
        }
        val payload = try {
            this.json.decodeFromString<ByokaiExport>(json)
        } catch (e: SerializationException) {
            // SerializationException extends IllegalArgumentException;
            // one catch covers malformed JSON and type mismatches.
            return Result.Error(IllegalArgumentException("Not a valid BYOKAI export file", e))
        }
        if (payload.format != FORMAT_ID) {
            return Result.Error(
                IllegalArgumentException("Not a BYOKAI export (format=${payload.format})"),
            )
        }
        if (payload.version > VERSION) {
            return Result.Error(
                IllegalArgumentException(
                    "Export version ${payload.version} is newer than supported ($VERSION)",
                ),
            )
        }
        return Result.Success(payload)
    }
}
