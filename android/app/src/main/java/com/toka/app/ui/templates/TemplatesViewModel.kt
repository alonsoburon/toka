package com.toka.app.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.api.TemplateDTO
import com.toka.app.data.api.UpdateTemplateRequest
import com.toka.app.data.repository.PeopleRepository
import com.toka.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TemplatesUiState(
    val templates: List<TemplateDTO> = emptyList(),
    val people: List<PersonDTO> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class TemplatesViewModel(
    private val taskRepository: TaskRepository,
    private val peopleRepository: PeopleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TemplatesUiState())
    val uiState: StateFlow<TemplatesUiState> = _uiState.asStateFlow()

    init {
        // Room es la fuente de verdad: la lista se redibuja sola cuando entra un sync.
        viewModelScope.launch {
            combine(taskRepository.templates, peopleRepository.people) { templates, people ->
                templates to people
            }.collectLatest { (templates, people) ->
                _uiState.update {
                    it.copy(templates = templates, people = people, isLoading = false, error = null)
                }
            }
        }
    }

    fun update(id: Long, request: UpdateTemplateRequest) {
        viewModelScope.launch {
            taskRepository.updateTemplate(id, request).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "No se pudo actualizar") }
            }
        }
    }

    fun setRecurrence(id: Long, recurrenceDays: Int?) {
        viewModelScope.launch {
            taskRepository.setTemplateRecurrence(id, recurrenceDays).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "No se pudo actualizar") }
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            taskRepository.deleteTemplate(id).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "No se pudo eliminar") }
            }
        }
    }
}
