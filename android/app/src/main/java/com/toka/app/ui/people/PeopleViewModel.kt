package com.toka.app.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.model.PersonDTO
import com.toka.app.data.repository.AuthRepository
import com.toka.app.data.repository.HouseholdRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PeopleUiState(
    val people: List<PersonDTO> = emptyList(),
    val inviteCode: String = "",
    val myPersonId: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

class PeopleViewModel(
    private val household: HouseholdRepository,
    private val auth: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeopleUiState(myPersonId = auth.current?.uid))
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    init {
        // Firestore es la fuente de verdad: la lista se redibuja sola cuando cambia el hogar.
        viewModelScope.launch {
            household.people.collect { people ->
                _uiState.update { it.copy(people = people, isLoading = false) }
            }
        }
        viewModelScope.launch {
            household.household.collect { h ->
                _uiState.update { it.copy(inviteCode = h?.inviteCode ?: "") }
            }
        }
    }

    fun updateMyProfile(name: String, color: String, emoji: String) {
        val uid = auth.current?.uid ?: return
        household.updateMyProfile(uid, name, color, emoji)
    }

    fun regenerateInvite(onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            household.regenerateInvite()
                .onSuccess { newCode -> onSuccess(newCode) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Error al regenerar código") } }
        }
    }
}
