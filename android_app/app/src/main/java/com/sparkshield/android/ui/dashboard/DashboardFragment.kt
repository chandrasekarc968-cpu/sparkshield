package com.sparkshield.android.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.sparkshield.android.R
import com.sparkshield.android.databinding.FragmentDashboardBinding
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.MonitoringViewModel
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitoringViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnToggleMonitor.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isServiceRunning) {
                viewModel.stopMonitoring(requireContext())
            } else {
                viewModel.startMonitoring(requireContext())
            }
        }

        binding.btnOpenSimulationLab.setOnClickListener {
            findNavController().navigate(R.id.navigation_simulation)
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
            // Protection Status
            if (state.isServiceRunning) {
                if (state.classIndex != ClassLabels.NORMAL.id && state.confidence >= 0.85f) {
                    tvProtectionStatus.text = "TAMPER DETECTED"
                    tvProtectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_emp))
                    tvProtectionSubtext.text = "Immediate attention required!"
                    tvRiskLevel.text = "CRITICAL"
                    tvRiskLevel.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_emp))
                } else {
                    tvProtectionStatus.text = "SYSTEM PROTECTED"
                    tvProtectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_normal))
                    tvProtectionSubtext.text = "Real-time edge monitoring active"
                    tvRiskLevel.text = "LOW"
                    tvRiskLevel.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_normal))
                }
                btnToggleMonitor.text = "Stop Monitoring"
                btnToggleMonitor.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_border))
            } else {
                tvProtectionStatus.text = "SYSTEM IDLE"
                tvProtectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                tvProtectionSubtext.text = "Monitoring service is not running"
                tvRiskLevel.text = "N/A"
                tvRiskLevel.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                btnToggleMonitor.text = "Start Monitoring"
                btnToggleMonitor.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.color_normal))
            }

            // Meter Info
            tvConnectionStatus.text = if (state.isConnected) "CONNECTED" else "DISCONNECTED"
            tvConnectionStatus.setTextColor(
                if (state.isConnected) ContextCompat.getColor(requireContext(), R.color.accent_blue)
                else ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )

            // Risk Score
            val riskScore = if (state.classIndex == ClassLabels.NORMAL.id) state.probabilities[state.classIndex] else state.confidence
            tvRiskScore.text = "Confidence: ${"%.2f".format(state.confidence)}"

            // Latest AI Detection
            tvClassification.text = state.predictedClass
            val classColor = when (state.classIndex) {
                ClassLabels.NORMAL.id -> ContextCompat.getColor(requireContext(), R.color.color_normal)
                ClassLabels.EMP.id -> ContextCompat.getColor(requireContext(), R.color.color_emp)
                ClassLabels.OPTICAL.id -> ContextCompat.getColor(requireContext(), R.color.color_optical)
                ClassLabels.SURGE.id -> ContextCompat.getColor(requireContext(), R.color.color_surge)
                else -> ContextCompat.getColor(requireContext(), R.color.color_normal)
            }
            tvClassification.setTextColor(classColor)
            val confPercent = (state.confidence * 100).toInt()
            tvConfidence.text = "$confPercent%"
            pbConfidence.progress = confPercent

            // Quick Metrics
            tvPeakAmplitude.text = "${(state.peakVoltageV * 1000).toInt()} mV"
            tvOpticalLevel.text = "${(state.opticalSensorV * 1000).toInt()} mV"
            tvRise.text = "${state.riseTimeNs} ns"
            tvDecay.text = "${"%.2f".format(state.decayTimeMs)} ms"
            tvSequenceId.text = "SEQ #${state.currentSequenceId}"

            // Parity Diagnostics
            tvFrameCounters.text = "Valid: ${state.validFrames} | Dropped: ${state.droppedFrames} | Alerts: ${state.tamperAlerts} | DB: ${state.persistedTamperEvents} alerts, ${state.persistedSnapshots} snapshots"
            tvInferenceLatency.text = "Inference Latency: ${state.inferenceLatencyUs} µs (${"%.2f".format(state.latencyMs)} ms) | Accelerator: ${state.accelerator}"
            tvProtocolError.text = "Protocol Error: ${state.latestProtocolError ?: "None"}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
