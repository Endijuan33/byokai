package com.byokai.core.chat

import com.byokai.domain.model.ChatMessage

/**
 * Request-side context truncation (Milestone 4D).
 *
 * Decides which messages to SEND for one request. Persisted history is
 * NEVER modified here — truncation applies only to the in-memory
 * request list built in
 * [com.byokai.ui.chat.ChatViewModel.startStream]. Deleting history is
 * a separate, explicit user action.
 *
 * Strategy (deterministic):
 * - The system prompt is always kept in full (it is small and
 *   load-bearing for behavior).
 * - Newest messages are kept first; older ones are dropped until the
 *   estimated total fits in `contextWindow − outputReserve`.
 * - At least the newest message is always kept, even when it alone
 *   exceeds the budget — sending nothing is never useful.
 * - When `contextWindow` is null (unknown — the current case for all
 *   adapters, whose [com.byokai.domain.model.ModelInfo.contextWindow]
 *   is unset), a conservative fallback keeps the last
 *   [DEFAULT_MAX_MESSAGES] messages capped at roughly
 *   [DEFAULT_MAX_CHARS] characters of estimated text.
 *
 * LLM-based summarization of dropped history is deliberately deferred
 * (see docs/ROADMAP.md): it costs provider calls, can hallucinate, and
 * needs its own UX. Simple truncation is predictable and honest.
 *
 * Pure Kotlin, no Android dependencies — JVM-unit-tested.
 */
object ContextPolicy {

    /**
     * Tokens reserved for the model's reply; never spent on history.
     */
    const val OUTPUT_RESERVE_TOKENS = 2_048

    /**
     * Fallback message cap when the model's context window is unknown.
     */
    const val DEFAULT_MAX_MESSAGES = 50

    /**
     * Fallback character cap (estimated ~32k tokens / 4 chars) when the
     * model's context window is unknown.
     */
    const val DEFAULT_MAX_CHARS = 128_000

    /**
     * Result of planning one request's context.
     *
     * @property messages the messages to send, oldest-first (a suffix
     * of the input, order preserved).
     * @property droppedCount how many of the oldest input messages were
     * left out (0 when everything fits).
     * @property estimatedTokens the [TokenEstimator] estimate for the
     * returned messages plus [systemPrompt] (labeled "est." in UI).
     */
    data class Plan(
        val messages: List<ChatMessage>,
        val droppedCount: Int,
        val estimatedTokens: Int,
    )

    /**
     * Plans the request context for [messages] (oldest-first, full
     * persisted history) and [systemPrompt].
     *
     * @param contextWindow the model's context window in tokens, or
     * null when unknown (fallback caps apply).
     */
    fun plan(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        contextWindow: Int?,
    ): Plan {
        if (messages.isEmpty()) {
            return Plan(
                messages = emptyList(),
                droppedCount = 0,
                estimatedTokens = TokenEstimator.estimateRequestTokens(
                    emptyList(),
                    systemPrompt,
                ),
            )
        }
        val kept = if (contextWindow != null) {
            keepWithinBudget(messages, systemPrompt, contextWindow)
        } else {
            keepWithinFallback(messages)
        }
        return Plan(
            messages = kept,
            droppedCount = messages.size - kept.size,
            estimatedTokens = TokenEstimator.estimateRequestTokens(kept, systemPrompt),
        )
    }

    private fun keepWithinBudget(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        contextWindow: Int,
    ): List<ChatMessage> {
        val systemTokens = if (systemPrompt.isNullOrBlank()) {
            0
        } else {
            TokenEstimator.estimateTokens(systemPrompt) +
                TokenEstimator.TOKENS_PER_MESSAGE_OVERHEAD
        }
        val budget = contextWindow - OUTPUT_RESERVE_TOKENS - systemTokens
        // Degenerate budget (tiny/unknown window): still send the
        // newest message rather than nothing.
        if (budget <= 0) return listOf(messages.last())

        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        for (message in messages.asReversed()) {
            val cost = TokenEstimator.estimateTokens(message.content) +
                TokenEstimator.TOKENS_PER_MESSAGE_OVERHEAD
            if (used + cost > budget && kept.isNotEmpty()) break
            kept.addFirst(message)
            used += cost
        }
        return kept.toList()
    }

    private fun keepWithinFallback(messages: List<ChatMessage>): List<ChatMessage> {
        val tail = messages.takeLast(DEFAULT_MAX_MESSAGES)
        // Character cap, newest-first.
        val kept = ArrayDeque<ChatMessage>()
        var chars = 0
        for (message in tail.asReversed()) {
            if (chars + message.content.length > DEFAULT_MAX_CHARS && kept.isNotEmpty()) break
            kept.addFirst(message)
            chars += message.content.length
        }
        return kept.toList()
    }
}
