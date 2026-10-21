package com.byokai.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Chat completion entry point for the UI.
 *
 * Resolves the provider config and its stored key, then delegates to
 * the matching [com.byokai.domain.provider.AiProvider] adapter. The
 * returned [Flow] is cold and emits [ChatEvent]s in stream order; it
 * never throws — adapter failures surface as [ChatEvent.Error].
 */
interface ChatRepository {
    /**
     * Streams a completion for [request].
     *
     * @return cold flow of [ChatEvent.Delta] chunks followed by exactly
     * one terminal [ChatEvent.Done] or [ChatEvent.Error].
     */
    fun streamChat(request: ChatRequest): Flow<ChatEvent>
}
