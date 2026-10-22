package com.byokai.domain.repository

import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.Conversation
import com.byokai.domain.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * Persisted conversation history (Milestone 3E).
 *
 * Conversations are the unit of history the user browses; messages are
 * appended per chat turn. All writes are suspending and return [Result]
 * so IO failures are handled by callers, never swallowed. Reads are cold
 * [Flow]s that re-emit after every write.
 *
 * The real implementation ([com.byokai.data.repository.SqliteConversationRepository])
 * is Android-only (hand-rolled SQLite, no Room); it cannot be
 * instantiated on the JVM, so ViewModel tests use an in-memory fake.
 */
interface ConversationRepository {

    /** All conversations, ordered by `updatedAt` descending. */
    fun conversations(): Flow<List<Conversation>>

    /** The conversation with [id], or null when it does not exist. */
    suspend fun get(id: String): Conversation?

    /**
     * Creates a conversation with a fresh UUID id and [title].
     * `createdAt` and `updatedAt` are the creation instant.
     */
    suspend fun create(title: String): Result<Conversation>

    /**
     * Deletes the conversation; its messages are deleted with it
     * (foreign-key cascade).
     */
    suspend fun delete(id: String): Result<Unit>

    /** Replaces the conversation title. */
    suspend fun rename(id: String, title: String): Result<Unit>

    /**
     * Sets (or clears, when null) the conversation's system prompt.
     * Stored as nullable TEXT; sent to the provider via the adapter's
     * system-prompt channel, never as a user message.
     */
    suspend fun setSystemPrompt(id: String, systemPrompt: String?): Result<Unit>

    /** Messages of one conversation, ordered by `createdAt` ascending. */
    fun messages(conversationId: String): Flow<List<Message>>

    /**
     * Appends a message and bumps the conversation's `updatedAt` in the
     * same transaction, so the list ordering never observes a message
     * without its recency bump.
     */
    suspend fun appendMessage(
        conversationId: String,
        role: ChatRole,
        content: String,
        isPartial: Boolean,
    ): Result<Message>

    /**
     * Deletes the message with [messageId] and every message stored
     * after it in [conversationId], in a single transaction, and bumps
     * the conversation's `updatedAt`. Ordering follows insertion order
     * (the same order [messages] returns). A no-op when [messageId]
     * does not exist.
     *
     * Used by edit-and-resend (truncate from the edited user message)
     * and regenerate (truncate from the last assistant message).
     */
    suspend fun deleteMessagesFrom(
        conversationId: String,
        messageId: String,
    ): Result<Unit>

    /**
     * Conversations whose title or any message content contains [query]
     * (case-insensitive substring match), ordered by `updatedAt`
     * descending. A blank [query] behaves like [conversations].
     */
    fun searchConversations(query: String): Flow<List<Conversation>>

    /**
     * Marks a message complete (`is_partial = 0`).
     *
     * Reserved for flows that persist a partial row first and complete it
     * later; the current chat flow appends terminal rows directly, so this
     * is unused by [com.byokai.ui.chat.ChatViewModel] today.
     */
    suspend fun markComplete(messageId: String): Result<Unit>
}
