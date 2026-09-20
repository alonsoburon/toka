package com.toka.app.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.TokenStore
import com.toka.app.data.api.PersonDTO
import com.toka.app.data.repository.PeopleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PeopleUiState(
    val people: List<PersonDTO> = emptyList(),
    val inviteCode: String = "",
    val myPersonId: Long? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

class PeopleViewModel(
    private val peopleRepository: PeopleRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    init {
        // Room es la fuente de verdad: la lista se redibuja sola cuando entra un sync.
        viewModelScope.launch {
            peopleRepository.people.collect { people ->
                _uiState.update { it.copy(people = people, isLoading = false) }
            }
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    inviteCode = tokenStore.inviteCode.first() ?: "",
                    myPersonId = tokenStore.getPersonId()
                )
            }
        }
    }

    fun addPerson(name: String, color: String, emoji: String) {
        viewModelScope.launch {
            val hid = tokenStore.getHouseholdId()
            if (hid == null) {
                _uiState.update { it.copy(error = "No hay hogar activo") }
                return@launch
            }
            peopleRepository.addPerson(hid, name, color, emoji)
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "Error al añadir persona") }
                }
        }
    }

    fun deletePerson(id: Long) {
        viewModelScope.launch {
            peopleRepository.deletePerson(id)
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "Error al eliminar persona") }
                }
        }
    }

    fun regenerateInvite(onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            val hid = tokenStore.getHouseholdId()
            if (hid == null) {
                _uiState.update { it.copy(error = "No hay hogar activo") }
                return@launch
            }
            peopleRepository.regenerateInvite(hid)
                .onSuccess { newCode ->
                    tokenStore.saveInviteCode(newCode)
                    _uiState.update { it.copy(inviteCode = newCode) }
                    onSuccess(newCode)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "Error al regenerar código") }
                }
        }
    }
}
