package com.sparkshield.android.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChanged(email: String) {
        _uiState.update { it.copy(email = email, emailError = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, passwordError = null) }
    }

    fun onPasswordVisibilityToggle() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onRememberDeviceChanged(remember: Boolean) {
        _uiState.update { it.copy(rememberDevice = remember) }
    }

    fun enterDemoMode() {
        _uiState.update { it.copy(isLoading = false, isLoginSuccessful = true) }
    }

    fun signIn() {
        val currentState = _uiState.value
        
        // Basic validation if credentials provided, or allow direct demo login
        if (currentState.email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(currentState.email).matches()) {
            _uiState.update { it.copy(emailError = "Invalid email format") }
            return
        }

        _uiState.update { it.copy(isLoading = false, isLoginSuccessful = true) }
    }

    fun onLoginErrorDismissed() {
        _uiState.update { it.copy(loginErrorMessage = null) }
    }
}
