package com.toka.app.ui.navigation

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import com.toka.app.R
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextMuted

private data class BottomNavItem(
    @StringRes val label: Int,
    val icon: ImageVector,
    val screen: Screen
)

private val items = listOf(
    BottomNavItem(R.string.nav_tasks, Icons.Default.CheckCircle, Screen.Dashboard),
    BottomNavItem(R.string.nav_templates, Icons.Default.Repeat, Screen.Templates),
    BottomNavItem(R.string.nav_history, Icons.Default.Schedule, Screen.History),
    BottomNavItem(R.string.nav_people, Icons.Default.People, Screen.People),
    BottomNavItem(R.string.nav_settings, Icons.Default.Settings, Screen.Settings)
)

@Composable
fun TokaBottomBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        items.forEach { item ->
            val selected = currentRoute == item.screen.route
            val label = stringResource(item.label)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.screen) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = label
                    )
                },
                label = { Text(label) },
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
