package com.byokai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.byokai.ui.chat.ChatScreen
import com.byokai.ui.home.HomeScreen
import com.byokai.ui.providers.ProvidersScreen
import com.byokai.ui.settings.SettingsScreen

/** Navigation route identifiers. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val CHAT = "chat"
}

/**
 * Root navigation graph for BYOKAI.
 *
 * Phase 2 destinations: "home" (conversation list) and "settings".
 * Phase 3 adds "providers" (BYOK provider setup) and "chat" (streaming
 * chat over the user's own keys).
 */
@Composable
fun ByokaiApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onProvidersClick = { navController.navigate(Routes.PROVIDERS) },
                onChatClick = { navController.navigate(Routes.CHAT) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.PROVIDERS) {
            ProvidersScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onBackClick = { navController.popBackStack() },
                onProvidersClick = { navController.navigate(Routes.PROVIDERS) },
            )
        }
    }
}
