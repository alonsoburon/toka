package com.toka.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.createtemplate.CreateTemplateScreen
import com.toka.app.ui.dashboard.DashboardScreen
import com.toka.app.ui.history.HistoryScreen
import com.toka.app.ui.onboarding.JoinHouseholdScreen
import com.toka.app.ui.onboarding.OnboardingViewModel
import com.toka.app.ui.people.PeopleScreen
import com.toka.app.ui.people.SettingsScreen
import com.toka.app.ui.taskdetail.TaskDetailScreen
import com.toka.app.ui.templates.TemplatesScreen

@Composable
fun TokaNavGraph(
    navController: NavHostController,
    isLoggedIn: Boolean,
    onLoggedIn: () -> Unit,
    onLogout: () -> Unit,
    deepLinkTaskId: Long? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val startDestination = if (isLoggedIn) Screen.Dashboard.route else Screen.Join.route
    val onboardingViewModel = remember { OnboardingViewModel(AppContainer.instance.authRepository) }

    // Si se abrió desde una notificación, saltar a la tarea. Se espera a estar logueado
    // por si el aviso se toca con la sesión cerrada.
    LaunchedEffect(deepLinkTaskId, isLoggedIn) {
        if (deepLinkTaskId != null && isLoggedIn) {
            navController.navigate(Screen.TaskDetail.withId(deepLinkTaskId))
            onDeepLinkConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Join.route) {
            JoinHouseholdScreen(
                viewModel = onboardingViewModel,
                onLoggedIn = {
                    onLoggedIn()
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToTask = { id -> navController.navigate(Screen.TaskDetail.withId(id)) },
                onNavigateToCreateTemplate = { navController.navigate(Screen.CreateTemplate.route) }
            )
        }
        composable(Screen.TaskDetail.route) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")?.toLongOrNull()
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
            SettingsScreen(onLogout = {
                onLogout()
                navController.navigate(Screen.Join.route) {
                    popUpTo(0) { inclusive = true }
                }
            })
        }
    }
}
