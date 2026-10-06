package com.toka.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toka.app.R
import com.toka.app.data.di.AppContainer
import com.toka.app.data.repository.HouseholdRepository
import com.toka.app.ui.theme.CardBg
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.personColors
import com.toka.app.ui.tokaViewModel

private enum class SetupMode { Join, Create }

/** Después de entrar con Google: crear un hogar o unirse a uno con el código de invitación. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdSetupScreen() {
    val viewModel = tokaViewModel {
        HouseholdSetupViewModel(AppContainer.instance.authRepository, AppContainer.instance.householdRepository)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(SetupMode.Join) }
    var code by remember { mutableStateOf("") }
    var householdName by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf(viewModel.suggestedName) }
    var selectedColor by remember { mutableIntStateOf(0) }
    var selectedEmoji by remember { mutableStateOf("🐱") }

    val colors = personColors()
    val snackbarHostState = remember { SnackbarHostState() }
    val busy = state.loading

    val emojis = listOf(
        "🐱", "🐶", "🦊", "🐸", "🐼", "🐨", "🐰", "🐯", "🐮",
        "🐷", "🐵", "🐔", "🐙", "🦄", "🐳", "🐧", "🐝", "🐞",
        "🌻", "🌸", "🍀", "⭐", "🌈", "🔥", "💜", "💚", "🧡"
    )

    val canSubmit = userName.isNotBlank() && when (mode) {
        SetupMode.Join -> HouseholdRepository.normalizeCode(code).length == 6
        SetupMode.Create -> householdName.isNotBlank()
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
            Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp)
        )
            Text(
                "Toka",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Pink
            )
            Text(
                stringResource(
                    if (mode == SetupMode.Join) R.string.onboarding_join_subtitle else R.string.onboarding_create_subtitle
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(24.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == SetupMode.Join,
                    onClick = { mode = SetupMode.Join },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !busy
                ) { Text(stringResource(R.string.onboarding_join_tab)) }
                SegmentedButton(
                    selected = mode == SetupMode.Create,
                    onClick = { mode = SetupMode.Create },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !busy
                ) { Text(stringResource(R.string.onboarding_create_tab)) }
            }

            Spacer(Modifier.height(16.dp))

            if (mode == SetupMode.Create) {
                OutlinedTextField(
                    value = householdName,
                    onValueChange = { householdName = it.take(60) },
                    label = { Text(stringResource(R.string.onboarding_household_name)) },
                    placeholder = { Text(stringResource(R.string.onboarding_household_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = HouseholdRepository.normalizeCode(it) },
                    label = { Text(stringResource(R.string.onboarding_code_label)) },
                    placeholder = { Text(stringResource(R.string.household_code_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it.take(40) },
                label = { Text(stringResource(R.string.onboarding_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.onboarding_color_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(3.dp, if (index == selectedColor) Color.White else Color.Transparent, CircleShape)
                            .clickable { selectedColor = index }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.onboarding_avatar_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(emojis) { emoji ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (selectedEmoji == emoji) Pink.copy(alpha = 0.2f) else CardBg)
                            .clickable { selectedEmoji = emoji },
                        contentAlignment = Alignment.Center
                    ) { Text(emoji, fontSize = 26.sp) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(colors[selectedColor]),
                        contentAlignment = Alignment.Center
                    ) { Text(selectedEmoji, fontSize = 26.sp) }
                    Column {
                        Text(
                            userName.ifEmpty { stringResource(R.string.onboarding_name_placeholder) },
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            stringResource(R.string.onboarding_preview_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    val color = colorToHex(colors[selectedColor])
                    if (mode == SetupMode.Create) viewModel.create(householdName, userName, color, selectedEmoji)
                    else viewModel.join(code, userName, color, selectedEmoji)
                },
                enabled = canSubmit && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(if (mode == SetupMode.Create) R.string.onboarding_create else R.string.onboarding_enter),
                        fontSize = 16.sp
                    )
                }
            }

            TextButton(onClick = viewModel::signOut, enabled = !busy) {
                Text(stringResource(R.string.household_sign_out), color = TextMuted)
            }
        }
    }
}

private fun colorToHex(color: Color): String {
    val r = (color.red * 255).toInt().coerceIn(0, 255)
    val g = (color.green * 255).toInt().coerceIn(0, 255)
    val b = (color.blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}
