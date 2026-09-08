package com.sparkshield.android.ui.login

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkshield.android.ui.components.*
import com.sparkshield.android.ui.theme.Midnight
import com.sparkshield.android.ui.theme.SecondaryText

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(uiState.isLoginSuccessful) {
        if (uiState.isLoginSuccessful) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(uiState.loginErrorMessage) {
        uiState.loginErrorMessage?.let {
            onShowMessage(it)
            viewModel.onLoginErrorDismissed()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Midnight)
    ) {
        val maxWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val maxHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1F6FEB).copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(x = maxWidthPx * 0.5f, y = -maxHeightPx * 0.2f),
                        radius = maxHeightPx * 1.5f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 48.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand Area
            BrandingSection()

            Spacer(modifier = Modifier.height(48.dp))

            // Welcome Section
            WelcomeSection()

            Spacer(modifier = Modifier.height(32.dp))

            // Input Fields
            PremiumTextField(
                value = uiState.email,
                onValueChange = viewModel::onEmailChanged,
                label = "Email address",
                leadingIcon = Icons.Default.Email,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                error = uiState.emailError
            )

            Spacer(modifier = Modifier.height(16.dp))

            PremiumTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChanged,
                label = "Password",
                leadingIcon = Icons.Default.Lock,
                visualTransformation = if (uiState.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = viewModel::onPasswordVisibilityToggle) {
                        Icon(
                            imageVector = if (uiState.passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (uiState.passwordVisible) "Hide password" else "Show password",
                            tint = SecondaryText
                        )
                    }
                },
                error = uiState.passwordError
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Remember & Forgot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.rememberDevice,
                        onCheckedChange = viewModel::onRememberDeviceChanged,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = SecondaryText
                        )
                    )
                    Text(
                        text = "Remember this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText
                    )
                }
                TextButton(onClick = { onShowMessage("Password recovery is not configured yet.") }) {
                    Text(
                        text = "Forgot password?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Action
            AuthenticationButton(
                text = "Sign In",
                onClick = viewModel::signIn,
                isLoading = uiState.isLoading
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                Text(
                    text = "OR CONTINUE WITH",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = SecondaryText
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Alternative Auth
            Row(modifier = Modifier.fillMaxWidth()) {
                SecondaryAuthButton(
                    text = "Google",
                    onClick = { onShowMessage("Google Sign-In is not configured yet.") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                SecondaryAuthButton(
                    text = "Passkey",
                    onClick = { onShowMessage("Passkey authentication is not configured yet.") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(48.dp))

            // Footer
            SecurityFooter()
        }
    }
}

@Composable
private fun BrandingSection() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(1000)) + scaleIn(initialScale = 0.8f, animationSpec = tween(1000))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SparkShieldLogo(size = 100.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "SparkShield",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Intelligent Protection for Energy Infrastructure",
                style = MaterialTheme.typography.bodyMedium,
                color = SecondaryText,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WelcomeSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sign in to access your intelligent protection system.",
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryText
        )
    }
}

@Composable
private fun SecurityFooter() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(0.6f)
    ) {
        Icon(
            Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = EmeraldGreen
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Protected by SparkShield Edge AI",
            style = MaterialTheme.typography.labelMedium,
            color = SecondaryText
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Secure • Local Intelligence • Privacy Aware",
        style = androidx.compose.ui.text.TextStyle(
            fontSize = 10.sp,
            color = SecondaryText,
            textAlign = TextAlign.Center
        )
    )
}

private val EmeraldGreen = Color(0xFF238636)
