package com.byokai

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byokai.core.ui.LocaleHelper
import com.byokai.data.local.datastore.SettingsDataStoreHolder
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import com.byokai.ui.ByokaiApp
import com.byokai.ui.theme.ByokaiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-activity host for the BYOKAI Compose UI.
 *
 * The persisted language is applied in [attachBaseContext] by wrapping the
 * base context. DataStore is asynchronous by design, so reading it
 * synchronously here is a deliberate, bounded tradeoff: a 2-second timeout
 * with a default fallback, documented in docs/ARCHITECTURE.md.
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = runBlocking {
            withTimeoutOrNull(STARTUP_LOCALE_TIMEOUT_MS) {
                SettingsDataStoreHolder.get(newBase).language.first()
            } ?: AppLanguage.ENGLISH
        }
        super.attachBaseContext(LocaleHelper.wrap(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepository =
            (application as ByokaiApplication).appContainer.settingsRepository
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by settingsRepository.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            ByokaiTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                ByokaiApp()
            }
        }
    }

    companion object {
        private const val STARTUP_LOCALE_TIMEOUT_MS = 2000L
    }
}
