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
    val connectFailedMessage = stringResource(R.string.settings_server_failed)
    val leaveFailedMessage = stringResource(R.string.settings_leave_failed)

    val emoji = userEmoji ?: "🐣"
    val name = userName ?: stringResource(R.string.common_you)
    val houseName = householdName ?: stringResource(R.string.settings_default_household)
    val code = inviteCode ?: "---"

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
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
                        Text(stringResource(R.string.settings_regen), color = Pink, fontSize = 12.sp)
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
                    Text(stringResource(R.string.settings_server_title), style = MaterialTheme.typography.labelLarge, color = Pink)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_server_url),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = serverUrl ?: stringResource(R.string.settings_server_none),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { showServerDialog = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.settings_change_server), color = Pink, fontSize = 12.sp)
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
                        isLeaving = true
                        scope.launch {
                            AppContainer.instance.authRepository.leaveHousehold()
                                .onSuccess {
                                    showLeaveConfirm = false
                                    onLogout()
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar(
                                        it.message ?: leaveFailedMessage
                                    )
                                }
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

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text(stringResource(R.string.settings_change_server)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_server_prompt))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newServerUrl,
                        onValueChange = { newServerUrl = it },
                        label = { Text(stringResource(R.string.settings_server_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://8-235-73-211.sslip.io") }
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
                                        snackbarHostState.showSnackbar(connectFailedMessage)
                                    }
                                isCheckingServer = false
                            }
                        }
                    }
                ) {
                    Text(
                        if (isCheckingServer) stringResource(R.string.settings_testing)
                        else stringResource(R.string.common_save),
                        color = Pink
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
