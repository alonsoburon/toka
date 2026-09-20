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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    var showAddDialog by remember { mutableStateOf(false) }
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
                        text = "Personas",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Añadir persona",
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
                                        text = "Código de invitación",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = uiState.inviteCode.ifEmpty { "---" },
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
                                        scope.launch { snackbarHostState.showSnackbar("Código copiado") }
                                    }) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copiar código",
                                            tint = TextSecondary
                                        )
                                    }
                                    IconButton(onClick = {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "Únete a mi hogar en Toka: $encodedInvite")
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Compartir código"))
                                    }) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Compartir",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }

                            TextButton(onClick = { showRegenConfirm = true }) {
                                Text("🔄 Nuevo código", color = Pink, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    items(uiState.people, key = { it.id }) { person ->
                        val personColor = parseHexColor(person.color)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
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
                                            contentDescription = "Eliminar",
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

    if (showAddDialog) {
        AddPersonDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, color, emoji ->
                viewModel.addPerson(name, color, emoji)
                showAddDialog = false
            }
        )
    }

    if (showRegenConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenConfirm = false },
            title = { Text("Generar nuevo código") },
            text = { Text("El código actual dejará de funcionar para quien no se haya unido todavía.") },
            confirmButton = {
                TextButton(onClick = {
                    showRegenConfirm = false
                    viewModel.regenerateInvite {
                        scope.launch { snackbarHostState.showSnackbar("Nuevo código generado") }
                    }
                }) { Text("Generar", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenConfirm = false }) { Text("Cancelar") }
            }
        )
    }

    personToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { personToDelete = null },
            title = { Text("Eliminar persona") },
            text = {
                Text("¿Eliminar a ${target.name}? Sus tareas quedan sin asignar.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePerson(target.id)
                    personToDelete = null
                }) { Text("Eliminar", color = SkipRed) }
            },
            dismissButton = {
                TextButton(onClick = { personToDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun AddPersonDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, color: String, emoji: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableIntStateOf(0) }
    var selectedEmoji by remember { mutableStateOf("🐱") }

    val colors = personColors()
    val emojis = listOf("🐱", "🐶", "🦊", "🐸", "🐼", "🐨")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Añadir persona",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Color",
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
                    text = "Avatar",
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
                onClick = {
                    val colorHex = "#%02X%02X%02X".format(
                        (colors[selectedColor].red * 255).toInt().coerceIn(0, 255),
                        (colors[selectedColor].green * 255).toInt().coerceIn(0, 255),
                        (colors[selectedColor].blue * 255).toInt().coerceIn(0, 255)
                    )
                    onAdd(name, colorHex, selectedEmoji)
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Pink)
            ) {
                Text("Añadir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
