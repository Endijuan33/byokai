package com.byokai.core.network

import com.byokai.domain.provider.ProviderError

/**
 * The adapter's decision for one failed attempt: whether to retry, and how
 * long to wait first.
 */
data class RetryDecision(val shouldRetry: Boolean, val delayMillis: Long)

/**
 * Decides which provider failures are worth retrying and how long to wait
 * between attempts.
 *
 * Retry policy:
 * - Retryable **only before any stream [com.byokai.domain.chat.ChatEvent.Delta]
 *   was emitted**: [ProviderError.Network], [ProviderError.Timeout],
 *   [ProviderError.Server] with a 5xx status, and [ProviderError.RateLimited].
 *   Retrying after partial output would duplicate text the caller already
 *   delivered, so once the stream has started every failure is terminal.
 * - Never retried: [ProviderError.Auth], [ProviderError.InvalidModel],
 *   [ProviderError.Parse], and any other 4xx — these fail deterministically
 *   and a retry would just burn quota or time.
 * - At most [MAX_RETRIES] retries after the initial attempt.
 *
 * Pure JVM: no Android APIs.
 */
object RetryPolicy {

    /** Maximum retries after the initial attempt. */
    const val MAX_RETRIES = 2

    /** Base of the exponential backoff: delay = 1000ms * 2^attempt. */
    private const val BASE_DELAY_MILLIS = 1000L

    /** Cap applied to the provider's Retry-After hint: 30 seconds. */
    private const val MAX_RETRY_AFTER_MILLIS = 30_000L

    /**
     * Whether the failed attempt number [attempt] (0-based: 0 is the
     * initial attempt) may be retried.
     *
     * @param streamStarted true once any stream Delta was emitted; when
     * true the answer is always false.
     */
    fun shouldRetry(attempt: Int, error: ProviderError, streamStarted: Boolean): Boolean {
        if (streamStarted) return false
        if (attempt < 0 || attempt >= MAX_RETRIES) return false
        return when (error) {
            is ProviderError.Network -> true
            ProviderError.Timeout -> true
            is ProviderError.Server -> error.statusCode in 500..599
            is ProviderError.RateLimited -> true
            else -> false
        }
    }

    /**
     * How long to wait before the next attempt: exponential backoff
     * (`1000ms * 2^attempt`) **plus** the provider's `Retry-After` hint
     * converted to milliseconds and capped at 30 seconds.
     */
    fun delayMillis(attempt: Int, retryAfterSeconds: Long?): Long {
        val backoff = BASE_DELAY_MILLIS * (1L shl attempt.coerceAtLeast(0))
        val retryAfter = (retryAfterSeconds ?: 0L).coerceAtLeast(0L) * 1000L
        return backoff + retryAfter.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    }

    /**
     * Convenience combining [shouldRetry] and [delayMillis]; the
     * `Retry-After` hint is taken from [ProviderError.RateLimited] when
     * the error carries one.
     */
    fun decide(attempt: Int, error: ProviderError, streamStarted: Boolean): RetryDecision =
        RetryDecision(
            shouldRetry = shouldRetry(attempt, error, streamStarted),
            delayMillis = delayMillis(
                attempt,
                (error as? ProviderError.RateLimited)?.retryAfterSeconds,
            ),
        )
}
