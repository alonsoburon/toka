package com.toka.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.toka.app.ui.createtemplate.CreateTemplateScreen
import com.toka.app.ui.dashboard.DashboardScreen
import com.toka.app.ui.history.HistoryScreen
import com.toka.app.ui.people.PeopleScreen
import com.toka.app.ui.people.SettingsScreen
import com.toka.app.ui.taskdetail.TaskDetailScreen
import com.toka.app.ui.templates.TemplatesScreen

@Composable
fun TokaNavGraph(
    navController: NavHostController,
    deepLinkTaskId: String? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    // Si se abrió desde una notificación o el widget, saltar a la tarea.
    LaunchedEffect(deepLinkTaskId) {
        if (deepLinkTaskId != null) {
            navController.navigate(Screen.TaskDetail.withId(deepLinkTaskId))
            onDeepLinkConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToTask = { id -> navController.navigate(Screen.TaskDetail.withId(id)) },
                onNavigateToCreateTemplate = { navController.navigate(Screen.CreateTemplate.route) }
            )
        }
        composable(Screen.TaskDetail.route) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")
                ?: return@composable
            TaskDetailScreen(
                taskId = taskId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.CreateTemplate.route) {
            CreateTemplateScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Templates.route) {
            TemplatesScreen(
                onNavigateToCreateTemplate = { navController.navigate(Screen.CreateTemplate.route) }
            )
        }
        composable(Screen.History.route) {
            HistoryScreen()
        }
        composable(Screen.People.route) {
            PeopleScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}
