package com.byokai.core.common

/**
 * Explicit result of an operation that can fail.
 *
 * Used at repository boundaries so IO and mapping failures are handled by
 * callers — never swallowed. `Loading` covers operations whose result is
 * not yet known; most repository calls return only [Success] or [Error].
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val cause: Throwable) : Result<Nothing>
    data object Loading : Result<Nothing>
}
