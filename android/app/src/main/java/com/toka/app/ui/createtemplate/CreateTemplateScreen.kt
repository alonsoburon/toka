package com.toka.app.ui.createtemplate

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toka.app.R
import com.toka.app.data.api.CreateTemplateRequest
import com.toka.app.ui.components.ReminderTimesField
import com.toka.app.ui.components.normalizeReminders
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.theme.CardBg
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.SurfaceBg
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.TextSecondary
import com.toka.app.ui.theme.parseHexColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    onNavigateBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var reminderInput by remember { mutableStateOf("") }
    var isRecurring by remember { mutableStateOf(false) }
    var selectedDays by remember { mutableIntStateOf(7) }
    var customDaysInput by remember { mutableStateOf("") }
    var showCustomDialog by remember { mutableStateOf(false) }
    var selectedPersonId by remember { mutableStateOf<Long?>(null) }

    var people by remember { mutableStateOf<List<PersonDTO>>(emptyList()) }
    var isLoadingPeople by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val createError = stringResource(R.string.create_error)

    val dayOptions = listOf(1, 3, 7, 14, 30, 90)

    LaunchedEffect(Unit) {
        AppContainer.instance.peopleRepository.getPeople()
            .onSuccess { people = it; isLoadingPeople = false }
            .onFailure { isLoadingPeople = false }
    }

    val selectedPerson = people.find { it.id == selectedPersonId }

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.create_title),
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.create_name_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.create_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.create_description_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text(stringResource(R.string.create_description_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.create_reminders_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            ReminderTimesField(
                value = reminderInput.ifBlank { null },
                onValueChange = { reminderInput = it ?: "" }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.create_type_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isRecurring,
                    onClick = { isRecurring = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.create_once))
                }
                SegmentedButton(
                    selected = isRecurring,
                    onClick = { isRecurring = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.create_recurring))
                }
            }

            if (isRecurring) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.create_every_days),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dayOptions) { days ->
                        FilterChip(
                            selected = selectedDays == days && customDaysInput.isBlank(),
                            onClick = {
                                selectedDays = days
                                customDaysInput = ""
                            },
                            label = { Text("$days") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Pink.copy(alpha = 0.15f),
                                selectedLabelColor = Pink
                            )
                        )
                    }
                    item {
                        FilterChip(
                            selected = customDaysInput.isNotBlank(),
                            onClick = { showCustomDialog = true },
                            label = {
                                Text(
                                    if (customDaysInput.isNotBlank()) customDaysInput
                                    else stringResource(R.string.create_custom)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Pink.copy(alpha = 0.15f),
                                selectedLabelColor = Pink
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.create_assign_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoadingPeople) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Pink,
                    strokeWidth = 2.dp
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (selectedPersonId == null) Pink.copy(alpha = 0.15f)
                                    else CardBg
                                )
                                .clickable { selectedPersonId = null }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.common_anyone),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selectedPersonId == null) Pink else TextSecondary
                            )
                        }
                    }
                    items(people) { person ->
                        val isSelected = selectedPersonId == person.id
                        val personColor = parseHexColor(person.color)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isSelected) personColor.copy(alpha = 0.15f)
                                    else CardBg
                                )
                                .clickable { selectedPersonId = person.id }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(person.avatarEmoji, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = person.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) personColor else TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.create_preview_label),
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedPerson != null) parseHexColor(selectedPerson.color)
                                    else TextMuted
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = selectedPerson?.avatarEmoji ?: "👤",
                                fontSize = 20.sp
                            )
                        }
                        Column {
                            Text(
                                text = name.ifEmpty { stringResource(R.string.create_preview_name) },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (name.isNotBlank()) TextPrimary else TextMuted
                            )
                            Text(
                                text = if (isRecurring) {
                                    val days = if (customDaysInput.isNotBlank()) {
                                        customDaysInput.toIntOrNull() ?: selectedDays
                                    } else selectedDays
                                    pluralStringResource(R.plurals.create_preview_recurring, days, days)
                                } else {
                                    stringResource(R.string.create_once)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    isSubmitting = true
                    val recurringDays = if (isRecurring) {
                        if (customDaysInput.isNotBlank()) customDaysInput.toIntOrNull() ?: selectedDays
                        else selectedDays
                    } else null

                    scope.launch {
                        AppContainer.instance.taskRepository.createTemplate(
                            CreateTemplateRequest(
                                name = name,
                                description = description.ifBlank { null },
                                recurrenceDays = recurringDays,
                                preferredAssigneeId = selectedPersonId,
                                reminderTimes = normalizeReminders(reminderInput)
                            )
                        )
                            .onSuccess { onNavigateBack() }
                            .onFailure {
                                isSubmitting = false
                                snackbarHostState.showSnackbar(it.message ?: createError)
                            }
                    }
                },
                enabled = name.isNotBlank() && !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.create_button), fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showCustomDialog) {
        var dialogInput by remember { mutableStateOf(customDaysInput) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text(stringResource(R.string.create_custom_title)) },
            text = {
                OutlinedTextField(
                    value = dialogInput,
                    onValueChange = { dialogInput = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.create_custom_days_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = dialogInput.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            customDaysInput = dialogInput
                            selectedDays = parsed
                            showCustomDialog = false
                        }
                    },
                    enabled = dialogInput.toIntOrNull()?.let { it > 0 } == true,
                    colors = ButtonDefaults.buttonColors(containerColor = Pink)
                ) {
                    Text(stringResource(R.string.common_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
