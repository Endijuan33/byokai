package com.byokai.core.chat

import com.byokai.domain.model.ChatMessage

/**
 * Heuristic token ESTIMATE for chat context (Milestone 4D).
 *
 * This is NOT a provider token count: real tokenizers (BPE etc.) are
 * provider- and model-specific and cannot run on-device cheaply. The
 * estimate exists so the UI can show an honest "est." label and the
 * [ContextPolicy] can truncate history conservatively. Every
 * user-visible rendering MUST carry the "est." label — never present
 * this as an official count.
 *
 * Heuristic: ~4 characters per token for typical text, plus a small
 * per-message overhead for role markers. Errs on the side of
 * over-estimation so truncation stays conservative.
 *
 * Pure Kotlin, no Android dependencies — JVM-unit-tested.
 */
object TokenEstimator {

    /** Characters assumed per token. */
    const val CHARS_PER_TOKEN = 4

    /** Flat overhead per message for role/name framing tokens. */
    const val TOKENS_PER_MESSAGE_OVERHEAD = 4

    /** Estimated tokens for one piece of text. */
    fun estimateTokens(text: String): Int =
        (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Estimated tokens for a full request context. */
    fun estimateRequestTokens(
        messages: List<ChatMessage>,
        systemPrompt: String?,
    ): Int {
        var total = messages.size * TOKENS_PER_MESSAGE_OVERHEAD
        for (message in messages) {
            total += estimateTokens(message.content)
        }
        if (!systemPrompt.isNullOrBlank()) {
            total += estimateTokens(systemPrompt) + TOKENS_PER_MESSAGE_OVERHEAD
        }
        return total
    }
}
