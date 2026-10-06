package com.toka.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.toka.app.data.repository.Session
import com.toka.app.ui.navigation.Screen
import com.toka.app.ui.onboarding.HouseholdSetupScreen
import com.toka.app.ui.onboarding.LoginScreen
import com.toka.app.ui.navigation.TokaBottomBar
import com.toka.app.ui.navigation.TokaNavGraph
import com.toka.app.ui.theme.TokaTheme

class MainActivity : ComponentActivity() {

    /** Tarea a abrir si se entró tocando una notificación de recordatorio. */
    private val pendingTaskId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge explícito: en Android 15 (targetSdk 35) ya es obligatorio, y así
        // los iconos de las barras se ajustan solos al modo claro/oscuro.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingTaskId.value = intent
            .getStringExtra(com.toka.app.notifications.Notifications.EXTRA_TASK_ID)
            ?.takeIf { it.isNotBlank() }
        setContent {
            TokaTheme {
                val container = com.toka.app.data.di.AppContainer.instance
                val session by container.sessionRepository.session.collectAsState()

                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // La sesión (Auth + users/{uid}) decide qué se ve. Cada rama es una pantalla completa: al
                // cerrar sesión (a mano, o porque otra cuenta se salió del hogar) el árbol se reconstruye
                // desde cero, sin navegación que sincronizar.
                when (val current = session) {
                    Session.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    Session.SignedOut -> LoginScreen()
                    is Session.NoHousehold -> HouseholdSetupScreen()
                    is Session.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Text(current.message, textAlign = TextAlign.Center)
                            TextButton(onClick = { container.authRepository.signOut() }) {
                                Text(stringResource(R.string.household_sign_out))
                            }
                        }
                    }
                    is Session.InHousehold -> {
                        val navController = rememberNavController()
                        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                        val showBottomBar = currentRoute in listOf(
                            Screen.Dashboard.route,
                            Screen.Templates.route,
                            Screen.History.route,
                            Screen.People.route,
                            Screen.Settings.route
                        )

                        Scaffold(
                            bottomBar = {
                                if (showBottomBar) {
                                    TokaBottomBar(
                                        currentRoute = currentRoute,
                                        onNavigate = { screen ->
                                            navController.navigate(screen.route) {
                                                popUpTo(Screen.Dashboard.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    )
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                TokaNavGraph(
                                    navController = navController,
                                    deepLinkTaskId = pendingTaskId.value,
                                    onDeepLinkConsumed = { pendingTaskId.value = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTaskId.value = intent
            .getStringExtra(com.toka.app.notifications.Notifications.EXTRA_TASK_ID)
            ?.takeIf { it.isNotBlank() }
    }
}
