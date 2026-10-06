package com.toka.app.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object HouseholdSetup : Screen("household-setup")
    object Dashboard : Screen("dashboard")
    object TaskDetail : Screen("task/{taskId}") {
        fun withId(id: String) = "task/$id"
    }
    object CreateTemplate : Screen("create-template")
    object Templates : Screen("templates")
    object History : Screen("history")
    object People : Screen("people")
    object Settings : Screen("settings")
}
