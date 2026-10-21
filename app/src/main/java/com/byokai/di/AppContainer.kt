package com.byokai.di

import android.content.Context
import com.byokai.data.local.datastore.SettingsDataStoreHolder
import com.byokai.data.repository.SettingsRepositoryImpl
import com.byokai.domain.repository.SettingsRepository

/**
 * Manual dependency graph (interim).
 *
 * Hilt is deferred until KSP supports Kotlin 2.4.x (see docs/ROADMAP.md),
 * so dependencies are wired here as lazy singletons. The Application owns
 * exactly one [AppContainer]; screens resolve dependencies from it via
 * `context.applicationContext as ByokaiApplication`.
 *
 * When Hilt returns, this class is replaced by Hilt modules — the
 * repository interfaces and their constructor signatures stay unchanged,
 * so call sites are unaffected.
 */
class AppContainer(context: Context) {

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(SettingsDataStoreHolder.get(context))
    }
}
