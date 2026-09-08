package com.sparkshield.android.ui.monitor

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sparkshield.android.R
import com.sparkshield.android.databinding.FragmentMonitorBinding
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.MonitoringViewModel
import kotlinx.coroutines.launch

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitoringViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeState()
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
            // BLE Connection Status
            val dotColor = if (state.isConnected) {
                ContextCompat.getColor(requireContext(), R.color.color_normal)
            } else if (state.isServiceRunning) {
                ContextCompat.getColor(requireContext(), R.color.color_optical)
            } else {
                ContextCompat.getColor(requireContext(), R.color.card_border)
            }
            
            val bg = vConnectionDot.background as? GradientDrawable ?: GradientDrawable().apply {
                shape = GradientDrawable.OVAL
            }
            bg.setColor(dotColor)
            vConnectionDot.background = bg

            tvBleStatus.text = if (state.isConnected) "BLE: CONNECTED" else "BLE: DISCONNECTED"
            tvRssi.text = if (state.bleRssi != null) "RSSI: ${state.bleRssi} dBm" else "RSSI: - dBm"
            tvSequenceId.text = "#${state.currentSequenceId}"

            // EMP Metrics
            tvPeakAmplitude.text = "${(state.peakVoltageV * 1000).toInt()} mV"
            tvRiseTime.text = "${state.riseTimeNs} ns"
            tvDecayTime.text = "${"%.2f".format(state.decayTimeMs)} ms"

            // Optical Metrics
            tvOpticalLevel.text = "${(state.opticalSensorV * 1000).toInt()} mV"
            if (state.classIndex == ClassLabels.OPTICAL.id && state.confidence >= 0.85f) {
                tvOpticalStatus.text = "BLINDING DETECTED"
                tvOpticalStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_emp))
            } else {
                tvOpticalStatus.text = "NORMAL"
                tvOpticalStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_normal))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
