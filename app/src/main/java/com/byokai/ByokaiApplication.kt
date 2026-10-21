package com.byokai

import android.app.Application
import com.byokai.di.AppContainer

/**
 * Application entry point for BYOKAI.
 *
 * Owns the [AppContainer] dependency graph (manual DI until Hilt returns —
 * see docs/ARCHITECTURE.md).
 */
class ByokaiApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
