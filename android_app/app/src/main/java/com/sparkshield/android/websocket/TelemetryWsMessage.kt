package com.sparkshield.android.websocket

import java.util.Locale

/**
 * Data model for real-time WebSocket telemetry and inference messages.
 *
 * All multi-client telemetry frames broadcast over WebSocket are formatted
 * according to this contract.
 *
 * SIMULATION ONLY:
 * All sensor metrics, peak voltages, and tamper flags represent synthetic software signals.
 */
data class TelemetryWsMessage(
    val seqId: Long,
    val timestampMs: Long,
    val eventFlags: Int,
    val peakMv: Int,
    val riseTimeNs: Long,
    val decayTimeUs: Int,
    val opticalMv: Int,
    val fftBins: List<Int>,
    val classification: String,
    val confidence: Float,
    val inferenceTimeUs: Long,
    val tamperDetected: Boolean
) {
    /**
     * Serializes this message into a compact, deterministic JSON string
     * without reflection overhead, guaranteed to execute reliably on JVM and Android.
     */
    fun toJson(): String {
        val fftJson = fftBins.joinToString(separator = ",", prefix = "[", postfix = "]")
        val formattedConfidence = String.format(Locale.US, "%.4f", confidence)
        val safeClassification = classification.replace("\"", "\\\"")

        return buildString {
            append('{')
            append("\"seqId\":").append(seqId).append(',')
            append("\"timestampMs\":").append(timestampMs).append(',')
            append("\"eventFlags\":").append(eventFlags).append(',')
            append("\"peakMv\":").append(peakMv).append(',')
            append("\"riseTimeNs\":").append(riseTimeNs).append(',')
            append("\"decayTimeUs\":").append(decayTimeUs).append(',')
            append("\"opticalMv\":").append(opticalMv).append(',')
            append("\"fftBins\":").append(fftJson).append(',')
            append("\"classification\":\"").append(safeClassification).append("\",")
            append("\"confidence\":").append(formattedConfidence).append(',')
            append("\"inferenceTimeUs\":").append(inferenceTimeUs).append(',')
            append("\"tamperDetected\":").append(tamperDetected)
            append('}')
        }
    }
}
