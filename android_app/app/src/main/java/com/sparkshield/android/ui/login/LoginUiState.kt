package com.sparkshield.android.ui.login

/**
 * Immutable state representation for the Login screen.
 */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val rememberDevice: Boolean = false,
    val isLoading: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val isLoginSuccessful: Boolean = false,
    val loginErrorMessage: String? = null
)
