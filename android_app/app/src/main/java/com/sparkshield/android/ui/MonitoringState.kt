package com.sparkshield.android.ui

import com.sparkshield.android.inference.ClassLabels
import java.util.Arrays

/**
 * Historical record of a confidence-gated tamper detection event.
 */
data class TamperEvent(
    val timestampMs: Long,
    val tamperClass: ClassLabels,
    val confidence: Float,
    val message: String
)

/**
 * Immutable state representation for the SparkShield real-time UI.
 */
data class MonitoringState(
    val isServiceRunning: Boolean = false,
    val isConnected: Boolean = false,
    val isModelLoaded: Boolean = false,
    val modelLoadError: String? = null,
    val currentSequenceId: Long = 0L,
    val timestampMs: Long = 0L,
    val peakVoltageV: Float = 0.0f,
    val opticalSensorV: Float = 0.0f,
    val riseTimeNs: Long = 0L,
    val decayTimeMs: Float = 0.0f,
    val predictedClass: String = "NORMAL",
    val classIndex: Int = 0,
    val confidence: Float = 1.0f,
    val probabilities: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
    val inferenceLatencyUs: Long = 0L,
    val receivedFrames: Long = 0L,
    val validFrames: Long = 0L,
    val invalidFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val inferredFrames: Long = 0L,
    val tamperAlerts: Long = 0L,
    val latestProtocolError: String? = null,
    val tamperAlertLog: List<TamperEvent> = emptyList(),
    val providerMode: String = "AUTO",
    val providerDetails: String = "Initializing...",
    val bleRssi: Int? = null,
    val persistedTamperEvents: Long = 0L,
    val persistedSnapshots: Long = 0L,
    val accelerator: String = "CPU (ONNX)",
    val qnnInitStatus: String = "UNINITIALIZED",
    val fallbackReason: String? = null,
    val contextHash: String? = null
) {
    val latencyMs: Float
        get() = inferenceLatencyUs / 1000.0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MonitoringState
        if (isServiceRunning != other.isServiceRunning) return false
        if (isConnected != other.isConnected) return false
        if (isModelLoaded != other.isModelLoaded) return false
        if (modelLoadError != other.modelLoadError) return false
        if (currentSequenceId != other.currentSequenceId) return false
        if (timestampMs != other.timestampMs) return false
        if (peakVoltageV != other.peakVoltageV) return false
        if (opticalSensorV != other.opticalSensorV) return false
        if (riseTimeNs != other.riseTimeNs) return false
        if (decayTimeMs != other.decayTimeMs) return false
        if (predictedClass != other.predictedClass) return false
        if (classIndex != other.classIndex) return false
        if (confidence != other.confidence) return false
        if (!Arrays.equals(probabilities, other.probabilities)) return false
        if (inferenceLatencyUs != other.inferenceLatencyUs) return false
        if (receivedFrames != other.receivedFrames) return false
        if (validFrames != other.validFrames) return false
        if (invalidFrames != other.invalidFrames) return false
        if (droppedFrames != other.droppedFrames) return false
        if (inferredFrames != other.inferredFrames) return false
        if (tamperAlerts != other.tamperAlerts) return false
        if (latestProtocolError != other.latestProtocolError) return false
        if (tamperAlertLog != other.tamperAlertLog) return false
        if (persistedTamperEvents != other.persistedTamperEvents) return false
        if (persistedSnapshots != other.persistedSnapshots) return false
        if (accelerator != other.accelerator) return false
        if (qnnInitStatus != other.qnnInitStatus) return false
        if (fallbackReason != other.fallbackReason) return false
        if (contextHash != other.contextHash) return false
        return true
    }

    override fun hashCode(): Int {
        var result = isServiceRunning.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isModelLoaded.hashCode()
        result = 31 * result + (modelLoadError?.hashCode() ?: 0)
        result = 31 * result + currentSequenceId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + peakVoltageV.hashCode()
        result = 31 * result + opticalSensorV.hashCode()
        result = 31 * result + riseTimeNs.hashCode()
        result = 31 * result + decayTimeMs.hashCode()
        result = 31 * result + predictedClass.hashCode()
        result = 31 * result + classIndex
        result = 31 * result + confidence.hashCode()
        result = 31 * result + Arrays.hashCode(probabilities)
        result = 31 * result + inferenceLatencyUs.hashCode()
        result = 31 * result + receivedFrames.hashCode()
        result = 31 * result + validFrames.hashCode()
        result = 31 * result + invalidFrames.hashCode()
        result = 31 * result + droppedFrames.hashCode()
        result = 31 * result + inferredFrames.hashCode()
        result = 31 * result + tamperAlerts.hashCode()
        result = 31 * result + (latestProtocolError?.hashCode() ?: 0)
        result = 31 * result + tamperAlertLog.hashCode()
        result = 31 * result + persistedTamperEvents.hashCode()
        result = 31 * result + persistedSnapshots.hashCode()
        result = 31 * result + accelerator.hashCode()
        result = 31 * result + qnnInitStatus.hashCode()
        result = 31 * result + (fallbackReason?.hashCode() ?: 0)
        result = 31 * result + (contextHash?.hashCode() ?: 0)
        return result
    }
}
