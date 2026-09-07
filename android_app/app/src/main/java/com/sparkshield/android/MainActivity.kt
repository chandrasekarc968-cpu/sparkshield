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
 * Diagnostic Activity for SparkShield smart-meter edge monitoring.
 *
 * Provides real-time visibility into:
 *   - Service status
 *   - Model load status & errors
 *   - Classification & confidence
 *   - Inference duration (microseconds & milliseconds)
 *   - Frame counts: valid, invalid, dropped, inferred, alerts
 *   - Latest protocol parsing error
 *   - Simulated tamper injection controls
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MonitoringViewModel by viewModels()

    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvFrameCounters: TextView
    private lateinit var tvLatestProtocolError: TextView
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
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvFrameCounters = findViewById(R.id.tvFrameCounters)
        tvLatestProtocolError = findViewById(R.id.tvLatestProtocolError)
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
            viewModel.injectTamperBurst(this, ClassLabels.EMP, burstCount = 8)
        }

        btnInjectOptical.setOnClickListener {
            viewModel.injectTamperBurst(this, ClassLabels.OPTICAL, burstCount = 8)
        }

        btnInjectSurge.setOnClickListener {
            viewModel.injectTamperBurst(this, ClassLabels.SURGE, burstCount = 8)
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
        // Connection & Service Status
        if (state.isConnected) {
            tvConnectionStatus.text = getString(R.string.status_connected)
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.color_normal))
        } else {
            tvConnectionStatus.text = if (state.isServiceRunning) "Running (Idle)" else getString(R.string.status_disconnected)
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Model Load Status & Accelerator Indicator
        if (state.isModelLoaded) {
            val statusDetail = if (state.fallbackReason != null) {
                "Accelerator: ${state.accelerator} (Fallback: ${state.qnnInitStatus}) | Model: Ready"
            } else {
                "Accelerator: ${state.accelerator} | Model: Ready"
            }
            tvModelStatus.text = statusDetail
            tvModelStatus.setTextColor(ContextCompat.getColor(this, R.color.color_normal))
        } else if (state.modelLoadError != null) {
            tvModelStatus.text = "Model Error: ${state.modelLoadError}"
            tvModelStatus.setTextColor(ContextCompat.getColor(this, R.color.color_emp))
        } else {
            tvModelStatus.text = "Model: Initializing..."
            tvModelStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Frame & Persistence Counters
        tvFrameCounters.text = "Valid: ${state.validFrames} | Dropped: ${state.droppedFrames} | Alerts: ${state.tamperAlerts} | DB Persisted: ${state.persistedTamperEvents} alerts, ${state.persistedSnapshots} snapshots"

        // Latest Protocol Error
        tvLatestProtocolError.text = "Latest Protocol Error: ${state.latestProtocolError ?: "None"}"

        // Classification & Confidence
        tvClassification.text = state.predictedClass
        val classColor = when (state.classIndex) {
            ClassLabels.NORMAL.id -> ContextCompat.getColor(this, R.color.color_normal)
            ClassLabels.EMP.id -> ContextCompat.getColor(this, R.color.color_emp)
            ClassLabels.OPTICAL.id -> ContextCompat.getColor(this, R.color.color_optical)
            ClassLabels.SURGE.id -> ContextCompat.getColor(this, R.color.color_surge)
            else -> ContextCompat.getColor(this, R.color.color_normal)
        }
        tvClassification.setTextColor(classColor)

        val confPercent = (state.confidence * 100).toInt()
        tvConfidence.text = "Confidence: $confPercent%"
        pbConfidence.progress = confPercent

        // Latency
        tvInferenceLatency.text = "Inference Duration: ${state.inferenceLatencyUs} µs (${"%.2f".format(state.latencyMs)} ms)"

        // Telemetry Metrics
        tvPeakVoltage.text = "${(state.peakVoltageV * 1000).toInt()} mV"
        tvOpticalSensor.text = "${(state.opticalSensorV * 1000).toInt()} mV"
        tvSequence.text = "#${state.currentSequenceId}"

        // Buttons
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
