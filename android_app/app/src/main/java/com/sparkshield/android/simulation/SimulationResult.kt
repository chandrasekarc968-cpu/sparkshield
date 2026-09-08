package com.sparkshield.android.simulation

import com.sparkshield.android.inference.ClassLabels
import java.util.Arrays

data class SimulationResult(
    val mode: SimulationMode,
    val expectedClass: String,
    val observedClass: String,
    val probabilities: FloatArray,
    val confidence: Float,
    val tamperDetected: Boolean,
    val alertThreshold: Float = 0.85f,
    val inferenceLatencyUs: Long,
    val inferenceEngine: String,
    val explanation: String,
    val matchStatus: String,

    // Waveform points
    val timePointsUs: FloatArray,
    val voltageSamplesMv: FloatArray,
    val fftEnergyBins: IntArray,
    val sampleRateKhz: Float,

    // Signature metrics
    val peakVoltageMv: Float,
    val riseTimeNs: Long,
    val decayTimeUs: Long,
    val opticalSensorMv: Float,
    val hfEnergyRatio: Float,

    // 29-byte wire frame preview
    val hexFrame: String,
    val frameLength: Int,
    val sequenceId: Long,
    val timestampMs: Long,
    val crc16: Int,
    val crcValid: Boolean,
    val eventFlags: Int,

    val safetyNotice: String = "SOFTWARE SIMULATION ONLY - Does not control physical meters or attack hardware.",
    val validationErrors: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimulationResult
        if (mode != other.mode) return false
        if (hexFrame != other.hexFrame) return false
        if (!Arrays.equals(probabilities, other.probabilities)) return false
        if (!Arrays.equals(timePointsUs, other.timePointsUs)) return false
        if (!Arrays.equals(voltageSamplesMv, other.voltageSamplesMv)) return false
        if (!Arrays.equals(fftEnergyBins, other.fftEnergyBins)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + hexFrame.hashCode()
        result = 31 * result + Arrays.hashCode(probabilities)
        result = 31 * result + Arrays.hashCode(voltageSamplesMv)
        return result
    }
}

data class SignalComparison(
    val baselineClass: String,
    val attackClass: String,
    val matchStatus: String,
    val deltaPeakMv: Float,
    val deltaRiseNs: Long,
    val deltaDecayUs: Long,
    val deltaOpticalMv: Float,
    val deltaHfEnergyRatio: Float,
    val spectralShift: String
)
