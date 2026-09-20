package com.toka.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val userName by AppContainer.instance.tokenStore.userName.collectAsState(initial = null)
    val userEmoji by AppContainer.instance.tokenStore.userEmoji.collectAsState(initial = null)
    val userColor by AppContainer.instance.tokenStore.userColor.collectAsState(initial = null)
    val householdName by AppContainer.instance.tokenStore.householdName.collectAsState(initial = null)
    val inviteCode by AppContainer.instance.tokenStore.inviteCode.collectAsState(initial = null)
    val serverUrl by AppContainer.instance.tokenStore.serverUrl.collectAsState(initial = null)
    var showServerDialog by remember { mutableStateOf(false) }
    var newServerUrl by remember { mutableStateOf("") }
    var isCheckingServer by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val emoji = userEmoji ?: "🐣"
    val name = userName ?: "Tú"
    val houseName = householdName ?: "Mi hogar"
    val code = inviteCode ?: "---"

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(userColor).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 40.sp)
                }
                Column {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(houseName, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
            }

            Spacer(Modifier.height(28.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tu hogar", style = MaterialTheme.typography.labelLarge, color = Pink)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Nombre", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(houseName, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Código", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(code, color = Pink, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                val hid = AppContainer.instance.tokenStore.getHouseholdId() ?: return@launch
                                AppContainer.instance.peopleRepository.regenerateInvite(hid)
                                    .onSuccess { newCode ->
                                        AppContainer.instance.tokenStore.saveInviteCode(newCode)
                                    }
                                    .onFailure {
                                        // silently fail, user will see old code
                                    }
                            }
                        }
                    ) {
                        Text("Generar nuevo código", color = Pink, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🔗 Servidor", style = MaterialTheme.typography.labelLarge, color = Pink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "URL actual",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = serverUrl ?: "No configurada",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { showServerDialog = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Cambiar servidor", color = Pink, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = { showLeaveConfirm = true },
                enabled = !isLeaving,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SkipRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Salir del hogar")
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = {
                    scope.launch {
                        AppContainer.instance.authRepository.logout()
                        onLogout()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cerrar sesión (solo este teléfono)", color = TextSecondary)
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Salir del hogar") },
            text = {
                Text("Se eliminará tu persona del hogar y tus tareas quedarán sin asignar. Necesita conexión.")
            },
            confirmButton = {
                TextButton(
                    enabled = !isLeaving,
                    onClick = {
                        isLeaving = true
                        scope.launch {
                            AppContainer.instance.authRepository.leaveHousehold()
                                .onSuccess {
                                    showLeaveConfirm = false
                                    onLogout()
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar(
                                        it.message ?: "No se pudo salir del hogar"
                                    )
                                }
                            isLeaving = false
                        }
                    }
                ) { Text("Salir", color = SkipRed) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("Cambiar servidor") },
            text = {
                Column {
                    Text("Ingresa la nueva URL del servidor:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newServerUrl,
                        onValueChange = { newServerUrl = it },
                        label = { Text("URL del servidor") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://192.168.100.8:3000") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newServerUrl.isNotBlank() && !isCheckingServer,
                    onClick = {
                        if (newServerUrl.isNotBlank()) {
                            isCheckingServer = true
                            scope.launch {
                                AppContainer.instance.checkServer(newServerUrl)
                                    .onSuccess {
                                        AppContainer.instance.reconnect(newServerUrl)
                                        showServerDialog = false
                                        AppContainer.instance.authRepository.logout()
                                        onLogout()
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar(
                                            "No se pudo conectar: revisa la URL y que el server esté arriba"
                                        )
                                    }
                                isCheckingServer = false
                            }
                        }
                    }
                ) { Text(if (isCheckingServer) "Probando…" else "Guardar", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text("Cancelar") }
            }
        )
    }
}
