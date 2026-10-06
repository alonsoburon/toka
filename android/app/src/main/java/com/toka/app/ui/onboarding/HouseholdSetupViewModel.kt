package com.toka.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.repository.AuthRepository
import com.toka.app.data.repository.HouseholdRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HouseholdSetupUiState(val loading: Boolean = false, val error: String? = null)

/** Crear un hogar o unirse a uno. Al terminar, la sesión pasa sola a "con hogar" (users/{uid}). */
class HouseholdSetupViewModel(
    private val auth: AuthRepository,
    private val household: HouseholdRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HouseholdSetupUiState())
    val state: StateFlow<HouseholdSetupUiState> = _state.asStateFlow()

    val suggestedName: String
        get() = auth.current?.displayName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            ?: auth.current?.email?.substringBefore('@')
            ?: ""

    fun create(householdName: String, name: String, color: String, emoji: String) = run {
        household.create(it, householdName, name, color, emoji)
    }

    fun join(code: String, name: String, color: String, emoji: String) = run {
        household.join(it, code, name, color, emoji)
    }

    fun signOut() = auth.signOut()

    private fun run(block: suspend (com.google.firebase.auth.FirebaseUser) -> Result<String>) {
        val user = auth.current ?: return
        viewModelScope.launch {
            _state.value = HouseholdSetupUiState(loading = true)
            block(user)
                .onSuccess { _state.value = HouseholdSetupUiState() }
                .onFailure { _state.value = HouseholdSetupUiState(error = it.message ?: "Error desconocido") }
        }
    }
}
