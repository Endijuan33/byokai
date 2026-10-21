package com.byokai.core.ui

import android.content.Context
import android.content.res.Configuration
import com.byokai.domain.model.AppLanguage
import java.util.Locale

/**
 * Wraps a [Context] with the given [AppLanguage] locale so resources
 * resolve in the user's chosen language.
 */
object LocaleHelper {

    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = language.toLocale()
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
