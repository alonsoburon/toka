package com.toka.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toka.app.BuildConfig
import com.toka.app.R
import com.toka.app.data.api.decodeMagicInvite
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.theme.CardBg
import com.toka.app.ui.theme.personColors
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EntryMode { Join, Create, Token }

/**
 * Pantalla de entrada. Tres caminos:
 *  - Unirse con un código que trae servidor, hogar y código de invitación.
 *  - Crear un hogar desde cero.
 *  - Entrar con un token ya emitido (identidad persistente, como la del seed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinHouseholdScreen(
    viewModel: OnboardingViewModel,
    onLoggedIn: () -> Unit
) {
    val state by viewModel.createState.collectAsStateWithLifecycle()

    val storedServer by AppContainer.instance.tokenStore.serverUrl
        .collectAsStateWithLifecycle(initialValue = null)

    var mode by remember { mutableStateOf(EntryMode.Join) }
    var code by remember { mutableStateOf("") }
    var householdName by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    // Para Token/Crear hace falta saber a qué servidor apuntar; Unirse lo trae en el código.
    var server by remember(storedServer) { mutableStateOf(storedServer ?: BuildConfig.BASE_URL) }
    var selectedColor by remember { mutableIntStateOf(0) }
    var selectedEmoji by remember { mutableStateOf("🐱") }

    val canSubmit = when (mode) {
        EntryMode.Join -> code.isNotBlank() && userName.isNotBlank()
        EntryMode.Create -> householdName.isNotBlank() && userName.isNotBlank()
        EntryMode.Token -> token.isNotBlank()
    }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val colors = personColors()
    val context = LocalContext.current
    val invalidCodeMessage = stringResource(R.string.onboarding_invalid_code)

    val emojis = listOf(
        "🐱", "🐶", "🦊", "🐸", "🐼", "🐨", "🐰", "🐯", "🐮",
        "🐷", "🐵", "🐔", "🐙", "🦄", "🐳", "🐧", "🐝", "🐞",
        "🌻", "🌸", "🍀", "⭐", "🌈", "🔥", "💜", "💚", "🧡"
    )

    LaunchedEffect(state) {
        when (state) {
            is OnboardingUiState.Error -> {
                snackbarHostState.showSnackbar((state as OnboardingUiState.Error).message)
                viewModel.reset()
            }
            is OnboardingUiState.Success -> {
                // Apenas hay token, que baje el hogar para que el Dashboard lo muestre.
                AppContainer.instance.syncNow()
                onLoggedIn()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Text(text = "🏠", fontSize = 64.sp)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Toka",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Pink
            )

            Text(
                text = stringResource(
                    when (mode) {
                        EntryMode.Join -> R.string.onboarding_join_subtitle
                        EntryMode.Create -> R.string.onboarding_create_subtitle
                        EntryMode.Token -> R.string.onboarding_token_subtitle
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == EntryMode.Join,
                    onClick = { mode = EntryMode.Join },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    enabled = state !is OnboardingUiState.Loading
                ) { Text(stringResource(R.string.onboarding_join_tab)) }
                SegmentedButton(
                    selected = mode == EntryMode.Create,
                    onClick = { mode = EntryMode.Create },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    enabled = state !is OnboardingUiState.Loading
                ) { Text(stringResource(R.string.onboarding_create_tab)) }
                SegmentedButton(
                    selected = mode == EntryMode.Token,
                    onClick = { mode = EntryMode.Token },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    enabled = state !is OnboardingUiState.Loading
                ) { Text(stringResource(R.string.onboarding_token_tab)) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (mode != EntryMode.Join) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.onboarding_server_label)) },
                    supportingText = { Text(stringResource(R.string.onboarding_server_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is OnboardingUiState.Loading
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (mode) {
                EntryMode.Create -> OutlinedTextField(
                    value = householdName,
                    onValueChange = { householdName = it },
                    label = { Text(stringResource(R.string.onboarding_household_name)) },
                    placeholder = { Text(stringResource(R.string.onboarding_household_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is OnboardingUiState.Loading
                )

                EntryMode.Join -> OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.onboarding_code_label)) },
                    placeholder = { Text("eyJzZXJ2ZXIiOiJodHRwczovL...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    enabled = state !is OnboardingUiState.Loading
                )

                EntryMode.Token -> OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.onboarding_token_label)) },
                    supportingText = { Text(stringResource(R.string.onboarding_token_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is OnboardingUiState.Loading
                )
            }

            if (mode != EntryMode.Token) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text(stringResource(R.string.onboarding_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is OnboardingUiState.Loading
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.onboarding_color_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    colors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (index == selectedColor) {
                                        Modifier.border(3.dp, Color.White, CircleShape)
                                    } else {
                                        Modifier.border(3.dp, Color.Transparent, CircleShape)
                                    }
                                )
                                .clickable { selectedColor = index }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.onboarding_avatar_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(emojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedEmoji == emoji) Pink.copy(alpha = 0.2f)
                                    else CardBg
                                )
                                .clickable { selectedEmoji = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 26.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(colors[selectedColor]),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = selectedEmoji, fontSize = 24.sp)
                        }

                        Column {
                            Text(
                                text = userName.ifEmpty { stringResource(R.string.onboarding_name_placeholder) },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.onboarding_preview_caption),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    when (mode) {
                        EntryMode.Token -> scope.launch {
                            val target = server.trim()
                            withContext(Dispatchers.IO) {
                                if (target.isNotBlank()) AppContainer.instance.reconnect(target)
                            }
                            viewModel.loginWithToken(token)
                        }

                        EntryMode.Create -> scope.launch {
                            val target = server.trim()
                            withContext(Dispatchers.IO) {
                                if (target.isNotBlank()) AppContainer.instance.reconnect(target)
                            }
                            viewModel.createHousehold(
                                householdName = householdName,
                                name = userName,
                                color = personColorToHex(colors[selectedColor]),
                                emoji = selectedEmoji
                            )
                        }

                        EntryMode.Join -> {
                            val invite = decodeMagicInvite(code)
                            if (invite == null) {
                                scope.launch { snackbarHostState.showSnackbar(invalidCodeMessage) }
                                return@Button
                            }
                            val colorHex = personColorToHex(colors[selectedColor])
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        AppContainer.instance.reconnect(invite.server)
                                    }
                                    viewModel.joinHousehold(
                                        inviteCode = invite.code,
                                        name = userName,
                                        color = colorHex,
                                        emoji = selectedEmoji,
                                        householdName = invite.household
                                    )
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.onboarding_connect_error, e.message)
                                    )
                                }
                            }
                        }
                    }
                },
                enabled = canSubmit && state !is OnboardingUiState.Loading,
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (state is OnboardingUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        stringResource(
                            when (mode) {
                                EntryMode.Join -> R.string.onboarding_enter
                                EntryMode.Create -> R.string.onboarding_create
                                EntryMode.Token -> R.string.onboarding_token_enter
                            }
                        ),
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun personColorToHex(color: Color): String {
    val r = (color.red * 255).toInt().coerceIn(0, 255)
    val g = (color.green * 255).toInt().coerceIn(0, 255)
    val b = (color.blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}
