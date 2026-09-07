package com.sparkshield.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.MonitoringViewModel
import kotlinx.coroutines.launch

/**
 * Main dashboard activity for SparkShield smart-meter tamper detection.
 * Provides real-time telemetry metrics, classification outputs, and test injection controls.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MonitoringViewModel by viewModels()

    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvClassification: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var pbConfidence: ProgressBar
    private lateinit var tvInferenceLatency: TextView
    private lateinit var tvPeakVoltage: TextView
    private lateinit var tvOpticalSensor: TextView
    private lateinit var tvSequence: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnInjectEmp: Button
    private lateinit var btnInjectOptical: Button
    private lateinit var btnInjectSurge: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkNotificationPermission()
        bindViews()
        setupListeners()
        observeState()
    }

    private fun bindViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvClassification = findViewById(R.id.tvClassification)
        tvConfidence = findViewById(R.id.tvConfidence)
        pbConfidence = findViewById(R.id.pbConfidence)
        tvInferenceLatency = findViewById(R.id.tvInferenceLatency)
        tvPeakVoltage = findViewById(R.id.tvPeakVoltage)
        tvOpticalSensor = findViewById(R.id.tvOpticalSensor)
        tvSequence = findViewById(R.id.tvSequence)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnInjectEmp = findViewById(R.id.btnInjectEmp)
        btnInjectOptical = findViewById(R.id.btnInjectOptical)
        btnInjectSurge = findViewById(R.id.btnInjectSurge)
    }

    private fun setupListeners() {
        btnStartService.setOnClickListener {
            viewModel.startMonitoring(this)
        }

        btnStopService.setOnClickListener {
            viewModel.stopMonitoring(this)
        }

        btnInjectEmp.setOnClickListener {
            viewModel.injectTamperBurst(this, ClassLabels.EMP, burstCount = 5)
        }

        btnInjectOptical.setOnClickListener {
            viewModel.injectTamperBurst(this, ClassLabels.OPTICAL, burstCount = 5)
        }

        btnInjectSurge.setOnClickListener {
            viewModel.injectTamperBurst(this, ClassLabels.SURGE, burstCount = 5)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: MonitoringState) {
        if (state.isConnected) {
            tvConnectionStatus.text = getString(R.string.status_connected)
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_normal))
        } else {
            tvConnectionStatus.text = getString(R.string.status_disconnected)
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        tvClassification.text = state.predictedClass.name
        val classColor = when (state.predictedClass) {
            ClassLabels.NORMAL -> ContextCompat.getColor(this, R.color.color_normal)
            ClassLabels.EMP -> ContextCompat.getColor(this, R.color.color_emp)
            ClassLabels.OPTICAL -> ContextCompat.getColor(this, R.color.color_optical)
            ClassLabels.SURGE -> ContextCompat.getColor(this, R.color.color_surge)
        }
        tvClassification.setTextColor(classColor)

        val confPercent = (state.confidence * 100).toInt()
        tvConfidence.text = getString(R.string.metric_confidence, confPercent)
        pbConfidence.progress = confPercent

        tvInferenceLatency.text = getString(R.string.metric_latency, state.latencyMs)
        tvPeakVoltage.text = "${(state.peakVoltageV * 1000).toInt()} mV"
        tvOpticalSensor.text = "${(state.opticalSensorV * 1000).toInt()} mV"
        tvSequence.text = "#${state.currentSequenceId}"

        btnStartService.isEnabled = !state.isServiceRunning
        btnStopService.isEnabled = state.isServiceRunning
        btnInjectEmp.isEnabled = state.isServiceRunning
        btnInjectOptical.isEnabled = state.isServiceRunning
        btnInjectSurge.isEnabled = state.isServiceRunning
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_NOTIFICATIONS = 101
    }
}
