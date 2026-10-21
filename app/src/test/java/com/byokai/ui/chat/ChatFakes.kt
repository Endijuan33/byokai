package com.byokai.ui.chat

import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.chat.ChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scriptable fake of [ChatRepository] for ViewModel tests.
 *
 * Emits [events] in order per call and records the last request.
 * Set [hangForever] to suspend without emitting, simulating a stream
 * that only ends via cancellation (used for stop/guard tests).
 */
class FakeChatRepository(
    var events: List<ChatEvent> = emptyList(),
    var hangForever: Boolean = false,
) : ChatRepository {

    var lastRequest: ChatRequest? = null
        private set
    var callCount: Int = 0
        private set

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        lastRequest = request
        callCount++
        if (hangForever) {
            kotlinx.coroutines.awaitCancellation()
        }
        events.forEach { emit(it) }
    }
}
