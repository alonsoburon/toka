package com.toka.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.api.TaskDTO
import com.toka.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Cuántas tareas completó una persona en la ventana elegida. */
data class PersonStat(val name: String, val emoji: String, val count: Int)

data class HistoryUiState(
    val groupedTasks: Map<String, List<TaskDTO>> = emptyMap(),
    val stats: List<PersonStat> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class HistoryViewModel(
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    var days: Int = 30
        private set

    private var allHistory: List<TaskDTO> = emptyList()

    init {
        // Room es la fuente de verdad: el historial se reagrupa solo cuando entra un sync.
        viewModelScope.launch {
            taskRepository.history.collect { tasks ->
                allHistory = tasks
                _uiState.update {
                    it.copy(
                        groupedTasks = group(tasks, days),
                        stats = statsFor(tasks, days),
                        isLoading = false,
                        error = null
                    )
                }
            }
        }
    }

    fun setDays(value: Int) {
        days = value
        regroup()
    }

    fun loadHistory() {
        regroup()
    }

    private fun regroup() {
        _uiState.update {
            it.copy(
                groupedTasks = group(allHistory, days),
                stats = statsFor(allHistory, days),
                isLoading = false
            )
        }
    }

    private fun group(tasks: List<TaskDTO>, days: Int): Map<String, List<TaskDTO>> {
        val cutoff = LocalDate.now().minusDays(days.toLong())
        return tasks
            .filter { it.completedAt != null }
            .mapNotNull { task -> parseDate(task.completedAt!!)?.let { it to task } }
            .filter { (date, _) -> !date.isBefore(cutoff) }
            .groupBy { (date, _) -> formatGroupKey(date) }
            .mapValues { (_, pairs) -> pairs.map { it.second } }
    }

    /** Reparto entre personas: solo las completadas, ordenadas de más a menos. */
    private fun statsFor(tasks: List<TaskDTO>, days: Int): List<PersonStat> {
        val cutoff = LocalDate.now().minusDays(days.toLong())
        return tasks
            .filter { it.status == "done" && it.completedAt != null }
            .filter { task -> parseDate(task.completedAt!!)?.let { !it.isBefore(cutoff) } ?: false }
            .groupBy { it.completedByName ?: "Sin asignar" }
            .map { (name, group) ->
                PersonStat(
                    name = name,
                    emoji = group.firstOrNull()?.completedByEmoji ?: "👤",
                    count = group.size
                )
            }
            .sortedByDescending { it.count }
    }

    private fun parseDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value.substringBefore("T"))
        } catch (_: Exception) {
            null
        }

    private fun formatGroupKey(date: LocalDate): String {
        val today = LocalDate.now()
        val daysDiff = ChronoUnit.DAYS.between(date, today)

        return when {
            daysDiff == 0L -> "Hoy"
            daysDiff == 1L -> "Ayer"
            daysDiff in 2L..6L -> {
                val dayName = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es"))
                val capitalized = dayName.replaceFirstChar { it.uppercase() }
                "$capitalized ${date.dayOfMonth}"
            }
            else -> {
                val monthName = date.month.getDisplayName(TextStyle.FULL, Locale("es"))
                val capitalized = monthName.replaceFirstChar { it.uppercase() }
                "$capitalized ${date.year}"
            }
        }
    }
}
