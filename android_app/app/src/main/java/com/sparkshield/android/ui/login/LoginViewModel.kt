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

    fun signIn() {
        val currentState = _uiState.value
        
        // Basic validation
        val emailError = if (currentState.email.isBlank()) "Email cannot be empty" 
                         else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(currentState.email).matches()) "Invalid email format"
                         else null
        
        val passwordError = if (currentState.password.isBlank()) "Password cannot be empty" else null

        if (emailError != null || passwordError != null) {
            _uiState.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }

        // Simulate demo login
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loginErrorMessage = null) }
            
            // Artificial delay for premium feel
            delay(1500L)
            
            // Demo authentication: Accept anything for now as requested
            // In a real app, this would call an AuthRepository
            _uiState.update { it.copy(isLoading = false, isLoginSuccessful = true) }
        }
    }

    fun onLoginErrorDismissed() {
        _uiState.update { it.copy(loginErrorMessage = null) }
    }
}
