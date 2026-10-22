package com.byokai.ui.conversations

import com.byokai.core.common.Result
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.Conversation
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.Message
import com.byokai.domain.repository.ConversationRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory fake of [ConversationRepository] for ViewModel tests.
 *
 * Mirrors the real contract: conversations are ordered by `updatedAt`
 * descending, messages by `createdAt` ascending, [appendMessage] bumps
 * the conversation's `updatedAt`, and [delete] cascades to messages.
 * [failWrites] flips every suspending write to [Result.Error] to
 * simulate IO failures.
 *
 * Timestamps come from a monotonically increasing fake clock so
 * ordering assertions are deterministic regardless of real time.
 *
 * The real [com.byokai.data.repository.SqliteConversationRepository]
 * is Android-only and cannot be instantiated on the JVM.
 */
class FakeConversationRepository : ConversationRepository {

    private val conversations = mutableMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<Message>>()

    private val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()

    /** Flip to make every suspending write return [Result.Error]. */
    var failWrites: Boolean = false

    /** Every appended message, in append order, across conversations. */
    val appendedMessages = mutableListOf<Message>()

    var createCount: Int = 0
        private set
    var deleteCount: Int = 0
        private set
    val renameCalls = mutableListOf<Pair<String, String>>()

    private var clock = 1_000L
    private fun tick(): Long = ++clock

    override fun conversations(): Flow<List<Conversation>> = conversationsFlow

    override suspend fun get(id: String): Conversation? = conversations[id]

    override suspend fun create(title: String): Result<Conversation> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        createCount++
        val now = tick()
        val conversation = Conversation(UUID.randomUUID().toString(), title, now, now)
        conversations[conversation.id] = conversation
        emitConversations()
        return Result.Success(conversation)
    }

    override suspend fun delete(id: String): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        deleteCount++
        conversations.remove(id)
        messages.remove(id)
        messageFlows.remove(id)
        emitConversations()
        return Result.Success(Unit)
    }

    override suspend fun rename(id: String, title: String): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        renameCalls.add(id to title)
        conversations[id]?.let {
            conversations[id] = it.copy(title = title, updatedAt = tick())
            emitConversations()
        }
        return Result.Success(Unit)
    }

    override suspend fun setSystemPrompt(id: String, systemPrompt: String?): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        conversations[id]?.let {
            conversations[id] = it.copy(systemPrompt = systemPrompt, updatedAt = tick())
            emitConversations()
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
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = tick(),
            isPartial = isPartial,
            attachments = attachments,
        )
        messages.getOrPut(conversationId) { mutableListOf() }.add(message)
        appendedMessages.add(message)
        messageFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
            .update { it + message }
        conversations[conversationId]?.let {
            conversations[conversationId] = it.copy(updatedAt = message.createdAt)
            emitConversations()
        }
        return Result.Success(message)
    }

    override suspend fun markComplete(messageId: String): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        messages.values.forEach { list ->
            val index = list.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                val updated = list[index].copy(isPartial = false)
                list[index] = updated
                messageFlows[updated.conversationId]?.update { current ->
                    current.map { if (it.id == messageId) updated else it }
                }
            }
        }
        return Result.Success(Unit)
    }

    override suspend fun deleteMessagesFrom(
        conversationId: String,
        messageId: String,
    ): Result<Unit> {
        if (failWrites) return Result.Error(RuntimeException("simulated IO failure"))
        val list = messages[conversationId] ?: return Result.Success(Unit)
        val target = list.indexOfFirst { it.id == messageId }
        if (target < 0) return Result.Success(Unit)
        // Mirror the real contract: the target row and every row stored
        // after it (createdAt ordering == list order here) are removed.
        val removed = list.subList(target, list.size).toList()
        list.subList(target, list.size).clear()
        messageFlows[conversationId]?.update { current ->
            current.filter { kept -> removed.none { it.id == kept.id } }
        }
        conversations[conversationId]?.let {
            conversations[conversationId] = it.copy(updatedAt = tick())
            emitConversations()
        }
        return Result.Success(Unit)
    }

    override fun searchConversations(query: String): Flow<List<Conversation>> {
        if (query.isBlank()) return conversations()
        val q = query.lowercase()
        return conversationsFlow.map { all ->
            all.filter { conversation ->
                conversation.title.lowercase().contains(q) ||
                    messages[conversation.id].orEmpty()
                        .any { it.content.lowercase().contains(q) }
            }
        }
    }

    private fun emitConversations() {
        conversationsFlow.value =
            conversations.values.sortedByDescending { it.updatedAt }
    }
}
