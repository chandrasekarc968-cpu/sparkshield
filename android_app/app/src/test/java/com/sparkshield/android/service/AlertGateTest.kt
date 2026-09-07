package com.sparkshield.android.service

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.InferenceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertGateTest {

    private fun createResult(
        targetClass: ClassLabels,
        confidence: Float
    ): InferenceResult {
        return InferenceResult(
            predictedClass = targetClass,
            confidence = confidence,
            probabilities = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            latencyMs = 0.5f
        )
    }

    @Test
    fun testNormalClassNeverAlerts() {
        val gate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 3000L)
        val normalResult = createResult(ClassLabels.NORMAL, 0.99f)

        val decision = gate.evaluate(normalResult, currentTimeMs = 1000L)
        assertTrue("NORMAL class must be suppressed", decision is AlertGate.AlertDecision.Suppressed)
        val suppressed = decision as AlertGate.AlertDecision.Suppressed
        assertEquals(AlertGate.SuppressionReason.NORMAL_CLASS, suppressed.reason)
    }

    @Test
    fun testConfidenceGatedThreshold() {
        val gate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 3000L)

        // Below threshold (0.84 < 0.85) -> Suppressed
        val lowConfResult = createResult(ClassLabels.EMP, 0.84f)
        val decisionLow = gate.evaluate(lowConfResult, currentTimeMs = 1000L)
        assertTrue("Below 0.85 must be suppressed", decisionLow is AlertGate.AlertDecision.Suppressed)
        val suppressedLow = decisionLow as AlertGate.AlertDecision.Suppressed
        assertEquals(AlertGate.SuppressionReason.CONFIDENCE_BELOW_THRESHOLD, suppressedLow.reason)

        // At or above threshold (0.85 >= 0.85) -> Trigger alert
        val highConfResult = createResult(ClassLabels.EMP, 0.85f)
        val decisionHigh = gate.evaluate(highConfResult, currentTimeMs = 1000L)
        assertTrue("At or above 0.85 must trigger alert", decisionHigh is AlertGate.AlertDecision.TriggerAlert)
        val alert = decisionHigh as AlertGate.AlertDecision.TriggerAlert
        assertEquals(ClassLabels.EMP, alert.tamperClass)
        assertEquals(0.85f, alert.confidence)
    }

    @Test
    fun testRateLimitingCooldownPerClass() {
        val gate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 3000L)
        val empResult = createResult(ClassLabels.EMP, 0.95f)

        // 1. Initial alert at t = 1000ms -> Triggered
        val d1 = gate.evaluate(empResult, currentTimeMs = 1000L)
        assertTrue("First alert must trigger", d1 is AlertGate.AlertDecision.TriggerAlert)

        // 2. Second alert at t = 2500ms (1500ms later < 3000ms cooldown) -> Suppressed
        val d2 = gate.evaluate(empResult, currentTimeMs = 2500L)
        assertTrue("Second alert within cooldown must be suppressed", d2 is AlertGate.AlertDecision.Suppressed)
        assertEquals(
            AlertGate.SuppressionReason.RATE_LIMITED_COOLDOWN,
            (d2 as AlertGate.AlertDecision.Suppressed).reason
        )

        // 3. Different tamper class (OPTICAL) at t = 2500ms -> Triggered (per-class cooldown)
        val opticalResult = createResult(ClassLabels.OPTICAL, 0.92f)
        val dOptical = gate.evaluate(opticalResult, currentTimeMs = 2500L)
        assertTrue("Different class must trigger independently", dOptical is AlertGate.AlertDecision.TriggerAlert)

        // 4. Third EMP alert at t = 4001ms (3001ms elapsed >= 3000ms) -> Triggered
        val d3 = gate.evaluate(empResult, currentTimeMs = 4001L)
        assertTrue("Alert after cooldown must trigger", d3 is AlertGate.AlertDecision.TriggerAlert)
    }
}
