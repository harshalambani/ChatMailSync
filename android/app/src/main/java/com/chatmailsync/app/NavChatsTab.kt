package com.chatmailsync.app

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** KT-13 step 4 (D240 = A, D241 = P1). The Chats tab's routes: the chat list and one chat's detail screen.
 * Route bodies moved out of MainActivity.kt unchanged apart from `a.` in front of the actions, launchers
 * and callable references that live in NavActions. State comes through `s`, whose members read as before. */
internal fun NavGraphBuilder.chatsTabRoutes(
    navController: NavHostController,
    s: NavScreenState,
    a: NavActions,
) = with(s) {
    composable("chats") {
        // Same "re-read on entry" reasoning as Home: the Me row
        // lives here too, and a background sync may have learned
        // a name while Chats sat on the back stack.
        LaunchedEffect(Unit) { a.refreshSelfSender() }
        val chatsMeDisplay = selfSenderDisplay(selfSender.selfSenderSource, selfSender.selfSenderName)
        ChatsListScreen(
            onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
            // The empty state offers the import directly rather than
            // sending the user to Home to find it -- same launcher
            // Home's own [Import] button uses, so a file picked from
            // either place lands in the same inbox.
            onImportChat = { a.pickFile.launch(arrayOf("*/*")) },
            meLabel = chatsMeDisplay.label,
            meColor = chatsMeDisplay.color,
            meDescription = selfSenderContentDescription(selfSender.selfSenderSource, selfSender.selfSenderName),
            onMeClick = { navController.navigate("me") },
        )
    }
    composable("chat/{chatId}") { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: ""
        ChatDetailScreen(
            chatId = chatId,
            onBack = { navController.popBackStack() },
            onDeleted = { navController.popBackStack() },
            onSyncThisChat = { a.startRealSync(chatFilter = chatId) },
            syncInProgress = anySyncRunning,
            selfSenderSource = selfSender.selfSenderSource,
            selfSenderName = selfSender.selfSenderName,
            onOpenMe = { navController.navigate("me") },
        )
    }
}
