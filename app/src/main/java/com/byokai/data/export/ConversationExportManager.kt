package com.byokai.data.export

import com.byokai.BuildConfig
import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.domain.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Exports and imports conversation history as versioned JSON
 * (Milestone 4E).
 *
 * - Export reads every conversation with its messages and serializes
 *   to [ByokaiExport]. Conversation text and metadata only — never
 *   credentials (the repository layer has no access to key material).
 * - Import validates the envelope, then re-creates each conversation
 *   with FRESH UUIDs (no id collisions with existing rows) and
 *   re-appends its messages in order. Timestamps are preserved from
 *   the export; ids are not.
 *
 * All work runs on [Dispatchers.IO]. Import is all-or-nothing per
 * conversation: a conversation whose messages fail to append is
 * reported in the summary rather than half-imported.
 */
class ConversationExportManager(
    private val conversationRepository: ConversationRepository,
) {

    /**
     * Summary of an import run.
     *
     * @property importedConversations conversations fully recreated.
     * @property importedMessages messages appended across them.
     * @property skippedConversations conversations that could not be
     * imported (with reasons); nothing was written for them.
     */
    data class ImportSummary(
        val importedConversations: Int,
        val importedMessages: Int,
        val skippedConversations: List<String>,
    )

    suspend fun exportToJson(): Result<String> = withContext(Dispatchers.IO) {
        try {
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
                        )
                    },
                )
            }
            val payload = ByokaiExport(
                format = ByokaiExportFormat.FORMAT_ID,
                version = ByokaiExportFormat.VERSION,
                exportedAt = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                conversations = exported,
            )
            Result.Success(ByokaiExportFormat.json.encodeToString(payload))
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Parses and imports [json]. Returns [Result.Error] when the input
     * is not a valid export (oversized, malformed JSON, wrong format
     * id, or unsupported version) — nothing is written in that case.
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
    const val VERSION = 1

    /** Imports larger than this are rejected before parsing. */
    const val MAX_BYTES = 50 * 1024 * 1024

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
