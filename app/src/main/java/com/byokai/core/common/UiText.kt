package com.byokai.core.common

import android.content.Context
import androidx.annotation.StringRes

/**
 * UI-facing text: either a localizable string resource or a raw string.
 *
 * ViewModels emit [UiText] and never hold a [Context]; screens resolve it
 * with [asString]. This keeps user-visible copy localizable and testable
 * without Android instrumentation.
 */
sealed interface UiText {
    data class Res(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    fun asString(context: Context): String = when (this) {
        is Res -> context.getString(resId, *args.toTypedArray())
        is Raw -> value
    }
}
