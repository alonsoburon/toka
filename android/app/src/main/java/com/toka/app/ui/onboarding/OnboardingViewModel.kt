package com.toka.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class OnboardingUiState {
    object Idle : OnboardingUiState()
    object Loading : OnboardingUiState()
    data class Error(val message: String) : OnboardingUiState()
    data class Success(val token: String) : OnboardingUiState()
}

class OnboardingViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _createState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val createState: StateFlow<OnboardingUiState> = _createState.asStateFlow()

    fun joinHousehold(
        inviteCode: String,
        name: String,
        color: String,
        emoji: String,
        householdName: String
    ) {
        viewModelScope.launch {
            _createState.value = OnboardingUiState.Loading
            authRepository.joinHousehold(inviteCode, name, color, emoji, householdName)
                .onSuccess { _createState.value = OnboardingUiState.Success(it.token) }
                .onFailure {
                    _createState.value = OnboardingUiState.Error(it.message ?: "Error desconocido")
                }
        }
    }

    fun createHousehold(
        householdName: String,
        name: String,
        color: String,
        emoji: String
    ) {
        viewModelScope.launch {
            _createState.value = OnboardingUiState.Loading
            authRepository.createHousehold(householdName, name, color, emoji)
                .onSuccess { _createState.value = OnboardingUiState.Success(it.token) }
                .onFailure {
                    _createState.value = OnboardingUiState.Error(it.message ?: "Error desconocido")
                }
        }
    }

    fun reset() {
        _createState.value = OnboardingUiState.Idle
    }
}
