package com.toka.app.ui.taskdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.api.TaskDTO
import com.toka.app.data.api.UpdateTemplateRequest
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.components.PersonChip
import com.toka.app.ui.components.ReminderTimesField
import com.toka.app.ui.theme.CardBg
import com.toka.app.ui.theme.CompleteGreen
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.SkipRed
import com.toka.app.ui.theme.SurfaceBg
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onNavigateBack: () -> Unit
) {
    var task by remember { mutableStateOf<TaskDTO?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCompleteDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var reminderTimes by remember { mutableStateOf<String?>(null) }
    var completeNotes by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<PersonDTO>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun loadTask() {
        scope.launch {
            isLoading = true
            AppContainer.instance.taskRepository.getTask(taskId)
                .onSuccess {
                    task = it
                    isLoading = false
                    it.templateId?.let { tid ->
                        AppContainer.instance.taskRepository.getTemplate(tid)
                            .onSuccess { tpl -> reminderTimes = tpl.reminderTimes }
                    }
                }
                .onFailure { error = it.message; isLoading = false }
        }
    }

    LaunchedEffect(taskId) {
        loadTask()
        people = AppContainer.instance.peopleRepository.getPeople().getOrDefault(emptyList())
    }

    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = task?.templateName ?: "Tarea",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Pink)
                }
            }
            task != null -> {
                val t = task!!
                val personColor = t.assignedToColor?.let { color ->
                    try { Color(android.graphics.Color.parseColor(color)) } catch (_: Exception) { Pink }
                } ?: Pink

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(personColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = t.assignedToEmoji ?: "👤",
                                fontSize = 32.sp
                            )
                        }

                        Column {
                            Text(
                                text = t.assignedToName ?: "Sin asignar",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = t.assignedToName?.let { "Asignado" } ?: "Por asignar",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = t.templateName ?: "Tarea",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Vencimiento",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted
                                )
                                Column(
                                    modifier = Modifier.clickable { showDatePicker = true },
                                    horizontalAlignment = Alignment.End
                                ) {
                                    t.dueAt?.let { dueStr ->
                                        val relativeText = try {
                                            val dueInstant = Instant.parse(dueStr)
                                            val dueDate = LocalDate.ofInstant(dueInstant, ZoneId.systemDefault())
                                            val today = LocalDate.now()
                                            val daysBetween = ChronoUnit.DAYS.between(today, dueDate)
                                            when {
                                                daysBetween == 0L -> "Hoy"
                                                daysBetween == 1L -> "Mañana"
                                                daysBetween == -1L -> "Ayer"
                                                daysBetween < 0 -> "Hace ${-daysBetween} días"
                                                else -> "En $daysBetween días"
                                            }
                                        } catch (_: Exception) { "" }

                                        val absoluteText = try {
                                            val dueInstant = Instant.parse(dueStr)
                                            val dueDate = LocalDate.ofInstant(dueInstant, ZoneId.systemDefault())
                                            val formatter = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", Locale("es"))
                                            dueDate.format(formatter)
                                        } catch (_: Exception) { dueStr }

                                        Text(
                                            text = relativeText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = absoluteText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    } ?: Text(
                                        text = "Sin fecha",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                    TextButton(onClick = { showDatePicker = true }) {
                                        Text("Cambiar fecha", color = Pink, fontSize = 12.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Asignado a",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted
                                )
                                if (t.assignedToName != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = t.assignedToEmoji ?: "👤",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = t.assignedToName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Sin asignar",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                }
                            }

                            if (t.notes != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Notas: ${t.notes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    if (t.templateId != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Recordatorios",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ReminderTimesField(
                                    value = reminderTimes,
                                    onValueChange = { newValue ->
                                        reminderTimes = newValue
                                        scope.launch {
                                            AppContainer.instance.taskRepository.updateTemplate(
                                                t.templateId,
                                                UpdateTemplateRequest(reminderTimes = newValue ?: "")
                                            ).onFailure { error = it.message }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (t.status == "pending" && people.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Asignar a",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(people, key = { it.id }) { person ->
                                        PersonChip(
                                            person = person,
                                            selected = t.assignedToId == person.id,
                                            onClick = {
                                                scope.launch {
                                                    AppContainer.instance.taskRepository.updateTask(
                                                        taskId,
                                                        assignedToId = person.id
                                                    )
                                                        .onSuccess { loadTask() }
                                                        .onFailure { error = it.message }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (t.status == "pending") {
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { showCompleteDialog = true },
                            enabled = !isSubmitting,
                            colors = ButtonDefaults.buttonColors(containerColor = CompleteGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text("✓ Completar", fontSize = 16.sp, color = Color.White)
                        }
                    }

                    if (t.status == "done") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = CompleteGreen.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "✓ Completada",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CompleteGreen
                                )
                                t.completedAt?.let { completed ->
                                    val date = try {
                                        val instant = Instant.parse(completed)
                                        val localDate = LocalDate.ofInstant(instant, ZoneId.systemDefault())
                                        val formatter = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", Locale("es"))
                                        localDate.format(formatter)
                                    } catch (_: Exception) { completed }
                                    Text(
                                        text = date,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                t.completedByName?.let { name ->
                                    Text(
                                        text = "Por $name",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Tarea no encontrada", color = TextMuted)
                }
            }
        }
    }

    if (showCompleteDialog) {
        AlertDialog(
            onDismissRequest = { showCompleteDialog = false },
            title = { Text("Completar tarea") },
            text = {
                Column {
                    Text("¿Quieres añadir alguna nota?")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = completeNotes,
                        onValueChange = { completeNotes = it },
                        label = { Text("Notas") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCompleteDialog = false
                        isSubmitting = true
                        scope.launch {
                            AppContainer.instance.taskRepository.completeTask(taskId, completeNotes.ifBlank { null })
                                .onSuccess {
                                    loadTask()
                                    isSubmitting = false
                                }
                                .onFailure {
                                    error = it.message
                                    isSubmitting = false
                                }
                        }
                    }
                ) {
                    Text("Completar", color = CompleteGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCompleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val newDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toString()
                        scope.launch {
                            AppContainer.instance.taskRepository.updateTask(
                                taskId,
                                dueAt = newDate
                            )
                                .onSuccess { loadTask() }
                                .onFailure { error = it.message }
                        }
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
