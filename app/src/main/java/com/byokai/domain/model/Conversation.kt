package com.byokai.domain.model

/**
 * One chat conversation (a thread of messages).
 *
 * @property id stable identifier.
 * @property title user-visible title.
 * @property createdAt epoch millis when the conversation started.
 * @property updatedAt epoch millis of the last message or rename.
 */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
