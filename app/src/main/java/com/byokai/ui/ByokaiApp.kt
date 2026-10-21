package com.byokai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.byokai.ui.home.HomeScreen
import com.byokai.ui.settings.SettingsScreen

/** Navigation route identifiers. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/**
 * Root navigation graph for BYOKAI.
 *
 * Phase 2 destinations: "home" (conversation list) and "settings" (fully
 * functional). Chat and provider routes arrive in Phase 3
 * (see docs/ROADMAP.md).
 */
@Composable
fun ByokaiApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onSettingsClick = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }
    }
}
