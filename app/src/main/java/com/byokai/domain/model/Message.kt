package com.byokai.domain.model

/**
 * One persisted message inside a [Conversation].
 *
 * @property id stable identifier.
 * @property conversationId the owning conversation.
 * @property role who produced the message.
 * @property content the message text.
 * @property createdAt epoch millis when the message was stored.
 * @property isPartial true while a streamed response is still arriving;
 * false once the final text is known.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val isPartial: Boolean,
)
