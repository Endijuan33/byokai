package com.byokai.domain.model

/**
 * One persisted message inside a [Conversation] (Milestone 3E).
 *
 * Mirrors [ChatMessage] with storage metadata. Only [ChatRole.USER] and
 * [ChatRole.ASSISTANT] rows are written by the chat flow.
 *
 * @property id stable UUID, primary key in storage.
 * @property conversationId the owning [Conversation.id].
 * @property role who produced the message.
 * @property content the message text, exactly as sent or streamed.
 * @property createdAt epoch millis when the message was stored.
 * @property isPartial true when the assistant turn was interrupted (error
 * or user stop) before its terminal stream event; the kept text is real
 * stream output, never fabricated.
 * @property attachments image attachments stored with this message
 * (Phase 9B). Populated for user turns sent with photos; empty for
 * text-only turns and for rows written before schema v3.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val isPartial: Boolean,
    val attachments: List<ImageAttachment> = emptyList(),
)
