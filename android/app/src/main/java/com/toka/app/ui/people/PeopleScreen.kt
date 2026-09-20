package com.toka.app.ui.people

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toka.app.R
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.api.encodeMagicInvite
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.components.LoadingShimmer
import com.toka.app.ui.theme.CardBg
import com.toka.app.ui.theme.personColors
import com.toka.app.ui.theme.Pink
import kotlinx.coroutines.launch
import com.toka.app.ui.theme.SkipRed
import com.toka.app.ui.theme.SurfaceBg
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.TextSecondary
import com.toka.app.ui.theme.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen() {
    val viewModel = remember { PeopleViewModel(AppContainer.instance.peopleRepository, AppContainer.instance.tokenStore) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val serverUrl by AppContainer.instance.tokenStore.serverUrl.collectAsState(initial = null)
    val householdName by AppContainer.instance.tokenStore.householdName.collectAsState(initial = null)

    val encodedInvite = encodeMagicInvite(
        server = serverUrl ?: "",
        code = uiState.inviteCode,
        household = householdName ?: ""
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.people_copied)
    val regenDoneMessage = stringResource(R.string.people_regen_done)
    val shareText = stringResource(R.string.people_share_text, encodedInvite)

    var showAddDialog by remember { mutableStateOf(false) }
    var personToEdit by remember { mutableStateOf<PersonDTO?>(null) }
    var showRegenConfirm by remember { mutableStateOf(false) }
    var personToDelete by remember { mutableStateOf<PersonDTO?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.people_title),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.people_add_profile),
                            tint = Pink
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            uiState.isLoading -> {
                LoadingShimmer()
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Pink.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = stringResource(R.string.people_invite_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = uiState.inviteCode.ifEmpty { stringResource(R.string.people_invite_empty) },
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        letterSpacing = 3.sp
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("inviteCode", encodedInvite))
                                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                                    }) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = stringResource(R.string.people_copy_code),
                                            tint = TextSecondary
                                        )
                                    }
                                    IconButton(onClick = {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                            type = "text/plain"
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                sendIntent,
                                                context.getString(R.string.people_share_chooser)
                                            )
                                        )
                                    }) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = stringResource(R.string.common_share),
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }

                            TextButton(onClick = { showRegenConfirm = true }) {
                                Text(stringResource(R.string.people_regen_button), color = Pink, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    items(uiState.people, key = { it.id }) { person ->
                        val personColor = parseHexColor(person.color)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { personToEdit = person }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(personColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = person.avatarEmoji, fontSize = 26.sp)
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = person.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(personColor)
                                )

                                if (person.id != uiState.myPersonId) {
                                    IconButton(onClick = { personToDelete = person }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.common_delete),
                                            tint = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val editing = personToEdit
    if (showAddDialog || editing != null) {
        PersonFormDialog(
            existing = editing,
            onDismiss = {
                showAddDialog = false
                personToEdit = null
            },
            onConfirm = { name, color, emoji ->
                if (editing != null) {
                    viewModel.updatePerson(editing.id, name, color, emoji)
                } else {
                    viewModel.addPerson(name, color, emoji)
                }
                showAddDialog = false
                personToEdit = null
            }
        )
    }

    if (showRegenConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenConfirm = false },
            title = { Text(stringResource(R.string.people_regen_title)) },
            text = { Text(stringResource(R.string.people_regen_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRegenConfirm = false
                    viewModel.regenerateInvite {
                        scope.launch { snackbarHostState.showSnackbar(regenDoneMessage) }
                    }
                }) { Text(stringResource(R.string.common_generate), color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    personToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { personToDelete = null },
            title = { Text(stringResource(R.string.people_delete_title)) },
            text = { Text(stringResource(R.string.people_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePerson(target.id)
                    personToDelete = null
                }) { Text(stringResource(R.string.common_delete), color = SkipRed) }
            },
            dismissButton = {
                TextButton(onClick = { personToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun PersonFormDialog(
    existing: PersonDTO?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String, emoji: String) -> Unit
) {
    val colors = personColors()
    val emojis = listOf("🐱", "🐶", "🦊", "🐸", "🐼", "🐨")

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var selectedColor by remember(existing) {
        mutableIntStateOf(
            existing?.color
                ?.let { hex -> colors.indexOfFirst { colorToHex(it).equals(hex, ignoreCase = true) } }
                ?.takeIf { it >= 0 } ?: 0
        )
    }
    var selectedEmoji by remember(existing) { mutableStateOf(existing?.avatarEmoji ?: "🐱") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (existing == null) R.string.people_add_profile else R.string.people_edit_title
                ),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                if (existing == null) {
                    Text(
                        text = stringResource(R.string.people_add_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.people_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.people_color_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (index == selectedColor) Modifier.border(3.dp, Color.White, CircleShape)
                                    else Modifier.border(3.dp, Color.Transparent, CircleShape)
                                )
                                .clickable { selectedColor = index }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.people_avatar_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    emojis.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedEmoji == emoji) Pink.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .clickable { selectedEmoji = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 26.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, colorToHex(colors[selectedColor]), selectedEmoji) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Pink)
            ) {
                Text(stringResource(if (existing == null) R.string.common_add else R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

private fun colorToHex(color: Color): String = "#%02X%02X%02X".format(
    (color.red * 255).toInt().coerceIn(0, 255),
    (color.green * 255).toInt().coerceIn(0, 255),
    (color.blue * 255).toInt().coerceIn(0, 255)
)
