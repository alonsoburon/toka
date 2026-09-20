package com.toka.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextMuted

private data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val screen: Screen
)

private val items = listOf(
    BottomNavItem("Tareas", Icons.Default.CheckCircle, Screen.Dashboard),
    BottomNavItem("Plantillas", Icons.Default.Repeat, Screen.Templates),
    BottomNavItem("Historial", Icons.Default.Schedule, Screen.History),
    BottomNavItem("Personas", Icons.Default.People, Screen.People),
    BottomNavItem("Ajustes", Icons.Default.Settings, Screen.Settings)
)

@Composable
fun TokaBottomBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        items.forEach { item ->
            val selected = currentRoute == item.screen.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.screen) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label) },
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = Pink,
                    selectedTextColor = Pink,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
