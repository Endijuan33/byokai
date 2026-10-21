package com.byokai.domain.chat

import com.byokai.domain.provider.ProviderError

/**
 * One event in a streaming chat completion.
 *
 * A stream emits zero or more [Delta] text chunks, then exactly one
 * terminal event: [Done] on success or [Error] on failure. Consumers
 * accumulate [Delta.text] until the terminal event arrives.
 */
sealed interface ChatEvent {
    /** A chunk of generated text; append to the running response. */
    data class Delta(val text: String) : ChatEvent

    /** The completed response; [fullText] is the entire generated text. */
    data class Done(val fullText: String) : ChatEvent

    /** The stream failed; no [Done] follows. */
    data class Error(val error: ProviderError) : ChatEvent
}
