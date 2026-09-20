package com.toka.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.api.TaskDTO
import com.toka.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Última tarea resuelta, para ofrecer "Deshacer" mientras el snackbar está visible. */
data class CompletedAction(val taskId: Long, val name: String)

data class DashboardUiState(
    val tasks: List<TaskDTO> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastAction: CompletedAction? = null
)

class DashboardViewModel(
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Room es la fuente de verdad: la lista se redibuja sola cuando el SyncEngine
        // baja cambios (o cuando una escritura local aplica al instante).
        viewModelScope.launch {
            taskRepository.pendingTasks.collect { tasks ->
                _uiState.update { it.copy(tasks = tasks, isLoading = false, error = null) }
            }
        }
    }

    /** Baja cambios del servidor; el Flow se encarga de repintar. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            taskRepository.refresh()
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Error de red")
                    }
                    return@launch
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun completeTask(taskId: Long) = resolve(taskId, "done")

    fun skipTask(taskId: Long) = resolve(taskId, "skipped")

    private fun resolve(taskId: Long, status: String) {
        val name = _uiState.value.tasks.firstOrNull { it.id == taskId }?.templateName ?: "Tarea"
        viewModelScope.launch {
            val result = if (status == "done") {
                taskRepository.completeTask(taskId)
            } else {
                taskRepository.skipTask(taskId)
            }
            result
                .onSuccess {
                    _uiState.update { it.copy(lastAction = CompletedAction(taskId, name)) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "No se pudo actualizar") }
                }
        }
    }

    fun undo() {
        val action = _uiState.value.lastAction ?: return
        viewModelScope.launch {
            taskRepository.undoTask(action.taskId)
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "No se pudo deshacer") }
                }
            _uiState.update { it.copy(lastAction = null) }
        }
    }

    fun consumeLastAction() {
        _uiState.update { it.copy(lastAction = null) }
    }
}
