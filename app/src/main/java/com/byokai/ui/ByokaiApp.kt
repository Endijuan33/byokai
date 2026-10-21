package com.byokai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.byokai.ui.chat.ChatScreen
import com.byokai.ui.conversations.ConversationsScreen
import com.byokai.ui.home.HomeScreen
import com.byokai.ui.prompts.PromptsScreen
import com.byokai.ui.providers.ProvidersScreen
import com.byokai.ui.settings.SettingsScreen

/** Navigation route identifiers. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val CHAT = "chat"
    const val CONVERSATIONS = "conversations"
    const val PROMPTS = "prompts"

    /**
     * Chat route: bare `"chat"` starts a new conversation,
     * `"chat?conversationId=<id>"` opens an existing one. Ids are UUIDs,
     * so no argument encoding is needed.
     */
    fun chat(conversationId: String? = null): String =
        if (conversationId == null) CHAT else "$CHAT?conversationId=$conversationId"
}

/**
 * Root navigation graph for BYOKAI.
 *
 * Phase 2 destinations: "home" (conversation list) and "settings".
 * Phase 3 adds "providers" (BYOK provider setup) and "chat" (streaming
 * chat over the user's own keys). Milestone 3E adds "conversations"
 * (persisted history); "chat" takes an optional `conversationId`.
 */
@Composable
fun ByokaiApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onProvidersClick = { navController.navigate(Routes.PROVIDERS) },
                onChatClick = { navController.navigate(Routes.CONVERSATIONS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onPromptsClick = { navController.navigate(Routes.PROMPTS) },
            )
        }
        composable(Routes.PROMPTS) {
            PromptsScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.PROVIDERS) {
            ProvidersScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.CONVERSATIONS) {
            ConversationsScreen(
                onBackClick = { navController.popBackStack() },
                onOpenConversation = { id ->
                    navController.navigate(Routes.chat(id))
                },
            )
        }
        composable(
            route = "chat?conversationId={conversationId}",
            arguments = listOf(
                navArgument("conversationId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            ChatScreen(
                conversationId = backStackEntry.arguments?.getString("conversationId"),
                onBackClick = { navController.popBackStack() },
                onProvidersClick = { navController.navigate(Routes.PROVIDERS) },
            )
        }
    }
}
