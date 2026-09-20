package com.toka.app.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toka.app.R
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.api.TemplateDTO
import com.toka.app.data.api.UpdateTemplateRequest
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.components.EmptyState
import com.toka.app.ui.components.LoadingShimmer
import com.toka.app.ui.components.PersonChip
import com.toka.app.ui.components.ReminderTimesField
import com.toka.app.ui.theme.CardBg
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.SkipRed
import com.toka.app.ui.theme.SurfaceBg
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onNavigateToCreateTemplate: () -> Unit
) {
    val viewModel = remember {
        TemplatesViewModel(
            AppContainer.instance.taskRepository,
            AppContainer.instance.peopleRepository
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<TemplateDTO?>(null) }
    var toDelete by remember { mutableStateOf<TemplateDTO?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = SurfaceBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.templates_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreateTemplate,
                containerColor = Pink,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tasks_create_template))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingShimmer()
            uiState.templates.isEmpty() -> EmptyState(
                icon = "🔁",
                title = stringResource(R.string.templates_empty_title),
                subtitle = stringResource(R.string.templates_empty_subtitle)
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.templates, key = { it.id }) { template ->
                    TemplateRow(
                        template = template,
                        people = uiState.people,
                        onClick = { editing = template },
                        onDelete = { toDelete = template }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    editing?.let { template ->
        EditTemplateDialog(
            template = template,
            people = uiState.people,
            onDismiss = { editing = null },
            onSave = { request, days ->
                viewModel.update(template.id, request)
                if (days != template.recurrenceDays) {
                    viewModel.setRecurrence(template.id, days)
                }
                editing = null
            }
        )
    }

    toDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.templates_delete_title)) },
            text = { Text(stringResource(R.string.templates_delete_message, template.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(template.id)
                    toDelete = null
                }) { Text(stringResource(R.string.common_delete), color = SkipRed) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun TemplateRow(
    template: TemplateDTO,
    people: List<PersonDTO>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val assignee = people.firstOrNull { it.id == template.preferredAssigneeId }
    val recurrence = template.recurrenceDays
        ?.let { pluralStringResource(R.plurals.templates_recurrence_every, it, it) }
        ?: stringResource(R.string.templates_once)
    val reminders = template.reminderTimes?.takeIf { it.isNotBlank() }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recurrence,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = assignee?.let { "${it.avatarEmoji} ${it.name}" }
                            ?: "👤 ${stringResource(R.string.common_anyone)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
                if (reminders != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.templates_reminders, reminders),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = TextMuted
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTemplateDialog(
    template: TemplateDTO,
    people: List<PersonDTO>,
    onDismiss: () -> Unit,
    onSave: (UpdateTemplateRequest, Int?) -> Unit
) {
    var name by remember { mutableStateOf(template.name) }
    var description by remember { mutableStateOf(template.description ?: "") }
    var selectedPersonId by remember { mutableStateOf(template.preferredAssigneeId) }
    var reminder by remember {
        mutableStateOf(template.reminderTimes?.takeIf { it.isNotBlank() })
    }

    // La recurrencia va por su propia operación (permite volver a "una sola vez"),
    // así que se guarda aparte del resto de los campos.
    var makeRecurring by remember { mutableStateOf(template.recurrenceDays != null) }
    var daysText by remember {
        mutableStateOf(template.recurrenceDays?.toString() ?: "7")
    }
    val days = if (makeRecurring) daysText.toIntOrNull()?.takeIf { it > 0 } else null
    val validRecurrence = !makeRecurring || days != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.templates_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.templates_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.templates_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )

                Spacer(Modifier.height(12.dp))

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !makeRecurring,
                        onClick = { makeRecurring = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(stringResource(R.string.create_once)) }
                    SegmentedButton(
                        selected = makeRecurring,
                        onClick = { makeRecurring = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.create_recurring)) }
                }
                Spacer(Modifier.height(8.dp))

                if (makeRecurring) {
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.create_every_days)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    text = stringResource(R.string.create_assign_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(people, key = { it.id }) { person ->
                        PersonChip(
                            person = person,
                            selected = selectedPersonId == person.id,
                            onClick = { selectedPersonId = person.id }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.reminders_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(Modifier.height(6.dp))
                ReminderTimesField(
                    value = reminder,
                    onValueChange = { reminder = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        UpdateTemplateRequest(
                            name = name.ifBlank { null },
                            description = description,
                            preferredAssigneeId = selectedPersonId,
                            reminderTimes = reminder ?: ""
                        ),
                        days
                    )
                },
                enabled = name.isNotBlank() && validRecurrence,
                colors = ButtonDefaults.buttonColors(containerColor = Pink)
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
