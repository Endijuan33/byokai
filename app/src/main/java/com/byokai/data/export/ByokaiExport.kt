package com.byokai.data.export

import kotlinx.serialization.Serializable

/**
 * Versioned export format for conversation portability (Milestone 4E).
 *
 * Documented in `docs/EXPORT.md`. The format envelope is:
 * `{"format":"byokai-export","version":1,...}` — importers MUST reject
 * unknown `format` values and `version`s newer than they understand.
 *
 * Privacy: this format carries conversation text and metadata ONLY.
 * It never contains API keys, credentials, provider configs, or
 * keystore material — there is no code path that puts them here, and
 * [ByokaiExportTest] asserts their absence.
 */
@Serializable
data class ByokaiExport(
    /** Must be [ByokaiExportFormat.FORMAT_ID]. */
    val format: String,
    /** Must be <= [ByokaiExportFormat.VERSION]. */
    val version: Int,
    /** Epoch millis when the export was produced. */
    val exportedAt: Long,
    /** App version that produced the export (informational). */
    val appVersion: String,
    val conversations: List<ExportedConversation>,
)

@Serializable
data class ExportedConversation(
    val title: String,
    val systemPrompt: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ExportedMessage>,
)

@Serializable
data class ExportedMessage(
    /** "USER", "ASSISTANT", or "SYSTEM" (ChatRole names). */
    val role: String,
    val content: String,
    val createdAt: Long,
    val isPartial: Boolean = false,
)
