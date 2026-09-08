package com.sparkshield.android.ui.detection

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
import com.sparkshield.android.R
import com.sparkshield.android.databinding.FragmentDetectionBinding
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.MonitoringViewModel
import kotlinx.coroutines.launch

class DetectionFragment : Fragment() {

    private var _binding: FragmentDetectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitoringViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectionBinding.inflate(inflater, container, false)
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
            // Engine Info
            tvEngineName.text = state.accelerator
            tvEngineDetails.text = "Status: ${state.qnnInitStatus} | Context: ${state.contextHash?.take(8) ?: "N/A"}"
            
            // Classification
            tvClassification.text = state.predictedClass
            val classColor = when (state.classIndex) {
                ClassLabels.NORMAL.id -> ContextCompat.getColor(requireContext(), R.color.color_normal)
                ClassLabels.EMP.id -> ContextCompat.getColor(requireContext(), R.color.color_emp)
                ClassLabels.OPTICAL.id -> ContextCompat.getColor(requireContext(), R.color.color_optical)
                ClassLabels.SURGE.id -> ContextCompat.getColor(requireContext(), R.color.color_surge)
                else -> ContextCompat.getColor(requireContext(), R.color.color_normal)
            }
            tvClassification.setTextColor(classColor)

            tvConfidence.text = "${(state.confidence * 100).toInt()}%"
            tvLatency.text = "${state.inferenceLatencyUs} µs"

            // Probabilities
            val p = state.probabilities
            if (p.size >= 4) {
                tvProbNormal.text = "%.3f".format(p[0])
                pbProbNormal.progress = (p[0] * 100).toInt()

                tvProbEmp.text = "%.3f".format(p[1])
                pbProbEmp.progress = (p[1] * 100).toInt()

                tvProbOptical.text = "%.3f".format(p[2])
                pbProbOptical.progress = (p[2] * 100).toInt()

                tvProbSurge.text = "%.3f".format(p[3])
                pbProbSurge.progress = (p[3] * 100).toInt()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
