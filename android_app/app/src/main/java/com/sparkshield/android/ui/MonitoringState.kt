package com.sparkshield.android.ui

import com.sparkshield.android.inference.ClassLabels
import java.util.Arrays

/**
 * Historical record of a confidence-gated tamper detection event.
 */
data class TamperEvent(
    val timestampMs: Long,
    val tamperClass: ClassLabels,
    val confidence: Float
)

/**
 * Immutable state representation for the SparkShield real-time UI.
 */
data class MonitoringState(
    val isServiceRunning: Boolean = false,
    val isConnected: Boolean = false,
    val currentSequenceId: Long = 0L,
    val timestampMs: Long = 0L,
    val peakVoltageV: Float = 0.0f,
    val opticalSensorV: Float = 0.0f,
    val riseTimeNs: Long = 0L,
    val decayTimeMs: Float = 0.0f,
    val predictedClass: ClassLabels = ClassLabels.NORMAL,
    val confidence: Float = 1.0f,
    val probabilities: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
    val latencyMs: Float = 0.0f,
    val framesReceived: Long = 0L,
    val framesDropped: Long = 0L,
    val lastTamperClass: ClassLabels? = null,
    val lastTamperTimestamp: Long = 0L,
    val lastTamperConfidence: Float = 0.0f,
    val tamperAlertLog: List<TamperEvent> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MonitoringState
        if (isServiceRunning != other.isServiceRunning) return false
        if (isConnected != other.isConnected) return false
        if (currentSequenceId != other.currentSequenceId) return false
        if (timestampMs != other.timestampMs) return false
        if (peakVoltageV != other.peakVoltageV) return false
        if (opticalSensorV != other.opticalSensorV) return false
        if (riseTimeNs != other.riseTimeNs) return false
        if (decayTimeMs != other.decayTimeMs) return false
        if (predictedClass != other.predictedClass) return false
        if (confidence != other.confidence) return false
        if (!Arrays.equals(probabilities, other.probabilities)) return false
        if (latencyMs != other.latencyMs) return false
        if (framesReceived != other.framesReceived) return false
        if (framesDropped != other.framesDropped) return false
        if (lastTamperClass != other.lastTamperClass) return false
        if (lastTamperTimestamp != other.lastTamperTimestamp) return false
        if (lastTamperConfidence != other.lastTamperConfidence) return false
        if (tamperAlertLog != other.tamperAlertLog) return false
        return true
    }

    override fun hashCode(): Int {
        var result = isServiceRunning.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + currentSequenceId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + peakVoltageV.hashCode()
        result = 31 * result + opticalSensorV.hashCode()
        result = 31 * result + riseTimeNs.hashCode()
        result = 31 * result + decayTimeMs.hashCode()
        result = 31 * result + predictedClass.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + Arrays.hashCode(probabilities)
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + framesReceived.hashCode()
        result = 31 * result + framesDropped.hashCode()
        result = 31 * result + (lastTamperClass?.hashCode() ?: 0)
        result = 31 * result + lastTamperTimestamp.hashCode()
        result = 31 * result + lastTamperConfidence.hashCode()
        result = 31 * result + tamperAlertLog.hashCode()
        return result
    }
}
