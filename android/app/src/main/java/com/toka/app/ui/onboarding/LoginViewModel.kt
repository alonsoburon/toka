package com.toka.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toka.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(val loading: Boolean = false, val error: String? = null)

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun starting() {
        _state.value = LoginUiState(loading = true)
    }

    fun signIn(idToken: String) = viewModelScope.launch {
        runCatching { auth.signInWithGoogle(idToken) }
            .onSuccess { _state.value = LoginUiState() }
            .onFailure { _state.value = LoginUiState(error = it.message ?: "Error al iniciar sesión") }
    }

    /** Solo debug con emuladores: entra como una cuenta falsa del emulador de Auth. */
    fun signInDev(email: String, name: String) = viewModelScope.launch {
        _state.value = LoginUiState(loading = true)
        runCatching { auth.signInDev(email, name) }
            .onSuccess { _state.value = LoginUiState() }
            .onFailure { _state.value = LoginUiState(error = "Login dev falló (¿corre scripts/dev.sh?): ${it.message}") }
    }

    fun credentialFailed(message: String?) {
        _state.value = LoginUiState(error = message)
    }
}
