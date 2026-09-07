package com.sparkshield.android.service

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.InferenceResult

/**
 * Confidence-gated alert decision maker.
 *
 * Requirements:
 *   - Strictly alerts only when confidence >= 0.85 (85%).
 *   - Class NORMAL never triggers alerts.
 *   - Enforces a 3-second (3000 ms) cooldown per tamper class to prevent alert flooding.
 */
class AlertGate(
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    // Map storing the timestamp of the last triggered alert for each tamper class
    private val lastAlertTimestamps = mutableMapOf<ClassLabels, Long>()

    sealed interface AlertDecision {
        data class TriggerAlert(
            val tamperClass: ClassLabels,
            val confidence: Float,
            val timestampMs: Long
        ) : AlertDecision

        data class Suppressed(val reason: SuppressionReason) : AlertDecision
    }

    enum class SuppressionReason {
        NORMAL_CLASS,
        CONFIDENCE_BELOW_THRESHOLD,
        RATE_LIMITED_COOLDOWN
    }

    /**
     * Evaluates an inference result to decide whether an audible/haptic alert should be dispatched.
     *
     * @param result Edge neural network inference result.
     * @param currentTimeMs Current epoch milliseconds (allows deterministic unit testing).
     * @return [AlertDecision] either triggering an alert or documenting suppression.
     */
    @Synchronized
    fun evaluate(result: InferenceResult, currentTimeMs: Long = System.currentTimeMillis()): AlertDecision {
        // 1. Never alert on normal benign grid operations
        if (!result.isTamper) {
            return AlertDecision.Suppressed(SuppressionReason.NORMAL_CLASS)
        }

        // 2. Strictly gate confidence threshold (>= 0.85)
        if (result.confidence < confidenceThreshold) {
            return AlertDecision.Suppressed(SuppressionReason.CONFIDENCE_BELOW_THRESHOLD)
        }

        // 3. Check rate-limiting cooldown per tamper class
        val lastTimestamp = lastAlertTimestamps[result.predictedClass]
        if (lastTimestamp != null) {
            val elapsed = currentTimeMs - lastTimestamp
            if (elapsed < cooldownMs) {
                return AlertDecision.Suppressed(SuppressionReason.RATE_LIMITED_COOLDOWN)
            }
        }

        // Passed all gates: record timestamp and dispatch alert
        lastAlertTimestamps[result.predictedClass] = currentTimeMs
        return AlertDecision.TriggerAlert(
            tamperClass = result.predictedClass,
            confidence = result.confidence,
            timestampMs = currentTimeMs
        )
    }

    /**
     * Resets cooldown records.
     */
    @Synchronized
    fun reset() {
        lastAlertTimestamps.clear()
    }

    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD: Float = 0.85f
        const val DEFAULT_COOLDOWN_MS: Long = 3000L // 3 seconds cooldown
    }
}
