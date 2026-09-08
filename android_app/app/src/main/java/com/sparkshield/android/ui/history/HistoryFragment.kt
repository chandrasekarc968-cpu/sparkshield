package com.sparkshield.android.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sparkshield.android.databinding.FragmentHistoryBinding
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.MonitoringViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitoringViewModel by viewModels()
    private val historyAdapter = HistoryAdapter()
    private var historyJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFilters()
        loadHistory(null)
    }

    private fun setupRecyclerView() {
        binding.rvHistory.adapter = historyAdapter
    }

    private fun setupFilters() {
        binding.chipGroupFilters.setOnCheckedChangeListener { _, checkedId ->
            val className = when (checkedId) {
                binding.chipEmp.id -> ClassLabels.EMP.name
                binding.chipOptical.id -> ClassLabels.OPTICAL.name
                binding.chipSurge.id -> ClassLabels.SURGE.name
                else -> null
            }
            loadHistory(className)
        }
    }

    private fun loadHistory(className: String?) {
        historyJob?.cancel()
        historyJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getTamperEvents(className).collect { events ->
                    historyAdapter.submitList(events)
                    binding.tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
