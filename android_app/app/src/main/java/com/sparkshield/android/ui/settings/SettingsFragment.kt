package com.sparkshield.android.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sparkshield.android.databinding.FragmentSettingsBinding
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.MonitoringViewModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitoringViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnInjectEmp.setOnClickListener {
            viewModel.injectTamperBurst(requireContext(), ClassLabels.EMP)
        }
        binding.btnInjectOptical.setOnClickListener {
            viewModel.injectTamperBurst(requireContext(), ClassLabels.OPTICAL)
        }
        binding.btnInjectSurge.setOnClickListener {
            viewModel.injectTamperBurst(requireContext(), ClassLabels.SURGE)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: MonitoringState) {
        with(binding) {
            tvEngineInfo.text = state.accelerator
            tvDataSource.text = if (state.providerMode == "MOCK") "Simulated Stream (Mock)" else "BLE Peripheral (${state.providerMode})"
            
            btnInjectEmp.isEnabled = state.isServiceRunning
            btnInjectOptical.isEnabled = state.isServiceRunning
            btnInjectSurge.isEnabled = state.isServiceRunning
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
