package com.toka.app.ui.navigation

sealed class Screen(val route: String) {
    object Join : Screen("onboarding/join")
    object Dashboard : Screen("dashboard")
    object TaskDetail : Screen("task/{taskId}") {
        fun withId(id: Long) = "task/$id"
    }
    object CreateTemplate : Screen("create-template")
    object Templates : Screen("templates")
    object History : Screen("history")
    object People : Screen("people")
    object Settings : Screen("settings")
}
