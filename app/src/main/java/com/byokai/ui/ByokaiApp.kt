package com.byokai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.byokai.ui.home.HomeScreen

/** Navigation route identifiers. */
object Routes {
    const val HOME = "home"
}

/**
 * Root navigation graph for BYOKAI.
 *
 * Phase 1 exposes a single "home" destination. Chat, provider settings, and
 * model selection routes are added in Phase 2/3 (see docs/ROADMAP.md).
 */
@Composable
fun ByokaiApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen()
        }
    }
}
