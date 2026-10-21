package com.byokai.domain.model

/**
 * One persisted conversation: a thread of [Message] rows forming the
 * chat history (Milestone 3E).
 *
 * The title starts as a default ("New conversation") and is replaced by
 * the first user message's leading text once the user sends something.
 * The list is ordered by [updatedAt] descending.
 *
 * @property id stable UUID, primary key in storage.
 * @property title user-visible title.
 * @property createdAt epoch millis when the conversation was created.
 * @property updatedAt epoch millis of the last message append or rename.
 * @property systemPrompt optional per-conversation system instruction,
 * sent to the provider through the adapter's system-prompt channel
 * (never mixed into user messages). Null when unset.
 */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String? = null,
)
