package com.byokai.data.export

import kotlinx.serialization.Serializable

/**
 * Versioned export format for conversation portability (Milestone 4E,
 * extended in Phase 9B).
 *
 * Two serializations share this envelope:
 *
 * - **v1 JSON** — the bare envelope as a `.json` document
 *   (`ConversationExportManager.exportToJson`). Carries conversation
 *   text and metadata only.
 * - **v2 ZIP** — a `.zip` archive (`ConversationExportManager.exportToZip`)
 *   containing `export.json` (this envelope, `"version": 2`, messages
 *   with their `attachments` arrays) plus one
 *   `attachments/<fileName>` entry per image with the raw file bytes.
 *   The ZIP exists so image bytes travel with the export without
 *   base64-bloating the JSON (~33% overhead) and without silently
 *   dropping attachments.
 *
 * Documented in `docs/EXPORT.md`. Importers MUST reject unknown
 * `format` values and `version`s newer than they understand.
 *
 * Privacy: this format carries conversation text, metadata, and (v2)
 * image attachments ONLY. It never contains API keys, credentials,
 * provider configs, or keystore material — there is no code path that
 * puts them here, and [ByokaiExportTest] asserts their absence.
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
    /**
     * Image attachments (Phase 9B). Empty for v1 exports and for
     * text-only messages. The raw bytes live in the v2 ZIP's
     * `attachments/` entries; a bare-JSON export carries this
     * metadata but no bytes.
     */
    val attachments: List<ExportedAttachment> = emptyList(),
)

/**
 * One image attachment as exported (Phase 9B).
 *
 * Mirrors [com.byokai.domain.model.ImageAttachment]; `id` and
 * `fileName` are the SOURCE values. Import mints fresh ids and fresh
 * file names (`<uuid>.jpg`), so these are never reused as-is.
 */
@Serializable
data class ExportedAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
)
