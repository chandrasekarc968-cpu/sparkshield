package com.sparkshield.android.simulation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.sparkshield.android.ui.theme.SparkShieldTheme

/**
 * Fragment hosting the SparkShield Simulation Lab interactive Compose screen.
 */
class SimulationLabFragment : Fragment() {

    private val viewModel: SimulationViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                SparkShieldTheme {
                    SimulationLabScreen(viewModel = viewModel)
                }
            }
        }
    }
}
