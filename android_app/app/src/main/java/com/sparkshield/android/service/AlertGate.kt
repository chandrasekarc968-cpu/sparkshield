package com.sparkshield.android.service

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.InferenceResult

/**
 * Confidence-gated alert decision maker.
 * Pure Kotlin with zero Android dependencies for frictionless JVM unit testing.
 *
 * Requirements:
 *   - Alert only when:
 *       EMP confidence >= 0.85
 *       OPTICAL confidence >= 0.85
 *       SURGE confidence >= 0.85
 *   - NORMAL must never trigger a tamper alert.
 *   - Do not repeat the same alert more often than once every 5 seconds (5000 ms).
 *   - Reset the gate after a valid NORMAL result.
 *   - Configurable threshold (default 0.85).
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
            val alertTitle: String,
            val alertMessage: String,
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
     * Evaluates an inference result to decide whether an alert should be dispatched.
     *
     * @param result Edge neural network inference result.
     * @param currentTimeMs Current epoch milliseconds (allows deterministic unit testing).
     * @return [AlertDecision] either triggering an alert or documenting suppression.
     */
    @Synchronized
    fun evaluate(result: InferenceResult, currentTimeMs: Long = System.currentTimeMillis()): AlertDecision {
        // 1. NORMAL never triggers an alert, and resets the gate per requirement
        if (!result.isTamper) {
            reset()
            return AlertDecision.Suppressed(SuppressionReason.NORMAL_CLASS)
        }

        // 2. Strictly gate confidence threshold (>= 0.85)
        if (result.confidence < confidenceThreshold) {
            return AlertDecision.Suppressed(SuppressionReason.CONFIDENCE_BELOW_THRESHOLD)
        }

        // 3. Do not repeat the same alert more often than once every 5 seconds (5000 ms)
        val lastTimestamp = lastAlertTimestamps[result.predictedClass]
        if (lastTimestamp != null) {
            val elapsed = currentTimeMs - lastTimestamp
            if (elapsed < cooldownMs) {
                return AlertDecision.Suppressed(SuppressionReason.RATE_LIMITED_COOLDOWN)
            }
        }

        // Passed all gates: record timestamp and trigger alert
        lastAlertTimestamps[result.predictedClass] = currentTimeMs

        val message = getAlertText(result.predictedClass)
        return AlertDecision.TriggerAlert(
            tamperClass = result.predictedClass,
            confidence = result.confidence,
            alertTitle = SIMULATION_ALERT_TITLE,
            alertMessage = message,
            timestampMs = currentTimeMs
        )
    }

    /**
     * Resets the gate cooldown tracking.
     */
    @Synchronized
    fun reset() {
        lastAlertTimestamps.clear()
    }

    companion object {
        const val DEFAULT_CONFIDENCE_THRESHOLD: Float = 0.85f
        const val DEFAULT_COOLDOWN_MS: Long = 5000L // 5 seconds rate-limiting

        const val SIMULATION_ALERT_TITLE = "SIMULATION ONLY: Tamper Alert"

        const val TEXT_EMP = "High-voltage EMP-like transient detected in simulation"
        const val TEXT_OPTICAL = "Optical saturation event detected in simulation"
        const val TEXT_SURGE = "Inductive surge event detected in simulation"

        fun getAlertText(tamperClass: ClassLabels): String {
            return when (tamperClass) {
                ClassLabels.EMP -> TEXT_EMP
                ClassLabels.OPTICAL -> TEXT_OPTICAL
                ClassLabels.SURGE -> TEXT_SURGE
                ClassLabels.NORMAL -> ""
            }
        }
    }
}
