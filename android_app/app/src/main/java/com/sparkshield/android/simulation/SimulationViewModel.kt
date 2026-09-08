package com.sparkshield.android.simulation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sparkshield.android.service.SparkShieldMonitoringService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SimulationUiState(
    val mode: SimulationMode = SimulationMode.NORMAL,
    val parameters: SimulationParameters = NormalParameters(),
    val seed: Long = 42L,
    val result: SimulationResult? = null,
    val baselineResult: SimulationResult? = null,
    val comparison: SignalComparison? = null,
    val isReplaying: Boolean = false,
    val replayCursorIndex: Int = 0,
    val publishToDashboard: Boolean = false,
    val isFramePreviewExpanded: Boolean = false,
    val validationErrors: List<String> = emptyList(),
    val statusMessage: String? = null,
    val selectedPreset: String = "Clean baseline"
)

class SimulationViewModel(
    private val simulationEngine: SimulationEngine = DefaultSimulationEngine(
        inferenceEngine = SparkShieldMonitoringService.getInferenceEngine(),
        acceleratorName = SparkShieldMonitoringService.serviceState.value.accelerator
    )
) : ViewModel() {

    private val _uiState = MutableStateFlow(SimulationUiState())
    val uiState: StateFlow<SimulationUiState> = _uiState.asStateFlow()

    private var replayJob: Job? = null
    private var sequenceCounter: Long = 1L

    init {
        // Initialize baseline and initial signal
        val baseline = simulationEngine.generateSignal(
            mode = SimulationMode.NORMAL,
            params = NormalParameters(),
            seed = 42L,
            sequenceId = sequenceCounter++
        )
        _uiState.update {
            it.copy(
                baselineResult = baseline,
                result = baseline
            )
        }
    }

    fun setMode(mode: SimulationMode) {
        val defaultParams = when (mode) {
            SimulationMode.NORMAL -> NormalParameters()
            SimulationMode.EMP -> EmpParameters()
            SimulationMode.OPTICAL -> OpticalParameters()
            SimulationMode.SURGE -> SurgeParameters()
        }
        val defaultPreset = when (mode) {
            SimulationMode.NORMAL -> "Clean baseline"
            SimulationMode.EMP -> "Strong EMP"
            SimulationMode.OPTICAL -> "Optical rail saturation"
            SimulationMode.SURGE -> "Moderate grid surge"
        }
        _uiState.update {
            it.copy(
                mode = mode,
                parameters = defaultParams,
                selectedPreset = defaultPreset,
                validationErrors = defaultParams.validate()
            )
        }
        generateAndClassify()
    }

    fun updateParameters(parameters: SimulationParameters) {
        val errors = parameters.validate()
        _uiState.update {
            it.copy(
                parameters = parameters,
                validationErrors = errors
            )
        }
    }

    fun loadPreset(presetName: String) {
        val preset = SimulationPresets.allPresets.find { it.name == presetName } ?: return
        _uiState.update {
            it.copy(
                mode = preset.mode,
                parameters = preset.parameters,
                selectedPreset = preset.name,
                validationErrors = preset.parameters.validate()
            )
        }
        generateAndClassify()
    }

    fun resetToDefaults() {
        val mode = _uiState.value.mode
        val defaultParams = when (mode) {
            SimulationMode.NORMAL -> NormalParameters()
            SimulationMode.EMP -> EmpParameters()
            SimulationMode.OPTICAL -> OpticalParameters()
            SimulationMode.SURGE -> SurgeParameters()
        }
        _uiState.update {
            it.copy(
                parameters = defaultParams,
                seed = 42L,
                validationErrors = emptyList()
            )
        }
        generateAndClassify()
    }

    fun setSeed(seed: Long) {
        _uiState.update { it.copy(seed = seed) }
        generateAndClassify()
    }

    fun toggleFramePreview() {
        _uiState.update { it.copy(isFramePreviewExpanded = !it.isFramePreviewExpanded) }
    }

    fun setPublishToDashboard(enabled: Boolean) {
        _uiState.update { it.copy(publishToDashboard = enabled) }
    }

    fun generateSignal(): SimulationResult? {
        val currentState = _uiState.value
        val errors = currentState.parameters.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(validationErrors = errors) }
            return null
        }

        val result = simulationEngine.generateSignal(
            mode = currentState.mode,
            params = currentState.parameters,
            seed = currentState.seed,
            sequenceId = sequenceCounter++
        )

        val comparison = currentState.baselineResult?.let { baseline ->
            simulationEngine.compareSignals(baseline, result)
        }

        _uiState.update {
            it.copy(
                result = result,
                comparison = comparison,
                validationErrors = emptyList()
            )
        }

        // If publish to dashboard is enabled, broadcast via WebSocket publisher
        if (currentState.publishToDashboard) {
            publishTelemetry(result)
        }

        return result
    }

    fun generateAndClassify(): SimulationResult? {
        return generateSignal()
    }

    fun generate8FrameWindow(): List<SimulationResult> {
        val currentState = _uiState.value
        val results = mutableListOf<SimulationResult>()
        for (i in 0 until 8) {
            val res = simulationEngine.generateSignal(
                mode = currentState.mode,
                params = currentState.parameters,
                seed = currentState.seed + i,
                sequenceId = sequenceCounter++
            )
            results.add(res)
            if (currentState.publishToDashboard) {
                publishTelemetry(res)
            }
        }
        val last = results.lastOrNull()
        if (last != null) {
            val comparison = currentState.baselineResult?.let { baseline ->
                simulationEngine.compareSignals(baseline, last)
            }
            _uiState.update {
                it.copy(
                    result = last,
                    comparison = comparison
                )
            }
        }
        return results
    }

    fun startReplay() {
        val currentResult = _uiState.value.result ?: return
        stopReplay()

        replayJob = viewModelScope.launch {
            _uiState.update { it.copy(isReplaying = true, replayCursorIndex = 0) }
            val sampleCount = currentResult.voltageSamplesMv.size
            var cursor = 0
            while (isActive && cursor < sampleCount) {
                _uiState.update { it.copy(replayCursorIndex = cursor) }
                cursor = (cursor + 4) % sampleCount
                delay(40L) // 25 fps moving cursor
            }
            _uiState.update { it.copy(isReplaying = false, replayCursorIndex = 0) }
        }
    }

    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _uiState.update { it.copy(isReplaying = false, replayCursorIndex = 0) }
    }

    private fun publishTelemetry(result: SimulationResult) {
        val publisher = SparkShieldMonitoringService.getWebSocketPublisher()
        if (publisher != null) {
            val wsMessage = com.sparkshield.android.websocket.TelemetryWsMessage(
                seqId = result.sequenceId,
                timestampMs = result.timestampMs,
                eventFlags = result.eventFlags,
                peakMv = result.peakVoltageMv.toInt(),
                riseTimeNs = result.riseTimeNs,
                decayTimeUs = result.decayTimeUs.toInt(),
                opticalMv = result.opticalSensorMv.toInt(),
                fftBins = result.fftEnergyBins.toList(),
                classification = result.observedClass,
                confidence = result.confidence,
                inferenceTimeUs = result.inferenceLatencyUs,
                tamperDetected = result.tamperDetected
            )
            publisher.publish(wsMessage)
        }
    }

    fun copyHexFrame(context: Context): Boolean {
        val hex = _uiState.value.result?.hexFrame ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SparkShield 29-Byte Frame", hex)
        clipboard.setPrimaryClip(clip)
        return true
    }

    fun copyJsonTelemetry(context: Context): Boolean {
        val result = _uiState.value.result ?: return false
        val json = simulationEngine.exportJson(result)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SparkShield Telemetry JSON", json)
        clipboard.setPrimaryClip(clip)
        return true
    }

    fun exportJson(): String {
        val result = _uiState.value.result ?: return "{}"
        return simulationEngine.exportJson(result)
    }

    fun exportCsv(): String {
        val result = _uiState.value.result ?: return ""
        return simulationEngine.exportCsv(result)
    }

    override fun onCleared() {
        super.onCleared()
        stopReplay()
    }
}
