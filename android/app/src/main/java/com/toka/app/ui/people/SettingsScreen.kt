package com.toka.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toka.app.R
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = AppContainer.instance
    val scope = rememberCoroutineScope()
    val uid = container.authRepository.current?.uid
    val household by container.householdRepository.household.collectAsState()
    val people by container.householdRepository.people.collectAsState()
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }
    var isRegenerating by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val leaveFailedMessage = stringResource(R.string.settings_leave_failed)

    val me = people.firstOrNull { it.id == uid }
    val emoji = me?.avatarEmoji ?: "🐣"
    val name = me?.name ?: stringResource(R.string.common_you)
    val houseName = household?.name?.ifBlank { null } ?: stringResource(R.string.settings_default_household)
    val code = household?.inviteCode?.ifBlank { null } ?: "---"

    Scaffold(
        containerColor = SurfaceBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg),
                windowInsets = WindowInsets(0, 0, 0, 0)
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
                        .background(parseHexColor(me?.color).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 40.sp)
                }
                Column {
                    Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(houseName, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    container.authRepository.current?.email?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_household_title), style = MaterialTheme.typography.labelLarge, color = Pink)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_name), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(houseName, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.settings_code), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text(code, color = Pink, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        enabled = !isRegenerating,
                        onClick = {
                            isRegenerating = true
                            scope.launch {
                                container.householdRepository.regenerateInvite()
                                    .onFailure { snackbarHostState.showSnackbar(it.message ?: leaveFailedMessage) }
                                isRegenerating = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.settings_regen), color = Pink, fontSize = 12.sp)
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
                Text(stringResource(R.string.settings_leave))
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { container.authRepository.signOut() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_logout), color = TextSecondary)
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.settings_leave_title)) },
            text = { Text(stringResource(R.string.settings_leave_message)) },
            confirmButton = {
                TextButton(
                    enabled = !isLeaving,
                    onClick = {
                        val user = container.authRepository.current ?: return@TextButton
                        isLeaving = true
                        scope.launch {
                            // Al salir, el puntero users/{uid} se borra y la sesión pasa a "sin hogar":
                            // la navegación vuelve sola a la pantalla de hogar.
                            container.householdRepository.leave(user)
                                .onSuccess { showLeaveConfirm = false }
                                .onFailure { snackbarHostState.showSnackbar(it.message ?: leaveFailedMessage) }
                            isLeaving = false
                        }
                    }
                ) { Text(stringResource(R.string.settings_leave_confirm), color = SkipRed) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
