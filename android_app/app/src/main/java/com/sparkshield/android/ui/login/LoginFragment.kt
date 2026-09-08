package com.sparkshield.android.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.sparkshield.android.R
import com.sparkshield.android.ui.theme.SparkShieldTheme

class LoginFragment : Fragment() {

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                SparkShieldTheme {
                    LoginScreen(
                        viewModel = viewModel,
                        onLoginSuccess = {
                            findNavController().navigate(R.id.action_loginFragment_to_navigation_dashboard)
                        },
                        onShowMessage = { message ->
                            Snackbar.make(this, message, Snackbar.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}
