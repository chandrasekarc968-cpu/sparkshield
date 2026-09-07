package com.sparkshield.android.service

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.InferenceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertGateTest {

    private fun createResult(
        targetClass: ClassLabels,
        confidence: Float
    ): InferenceResult {
        return InferenceResult(
            label = targetClass.name,
            classIndex = targetClass.id,
            probabilities = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            confidence = confidence,
            inferenceTimeUs = 500L
        )
    }

    @Test
    fun testNormalClassNeverAlerts() {
        val gate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 5000L)
        val normalResult = createResult(ClassLabels.NORMAL, 0.99f)

        val decision = gate.evaluate(normalResult, currentTimeMs = 1000L)
        assertTrue("NORMAL class must be suppressed", decision is AlertGate.AlertDecision.Suppressed)
        val suppressed = decision as AlertGate.AlertDecision.Suppressed
        assertEquals(AlertGate.SuppressionReason.NORMAL_CLASS, suppressed.reason)
    }

    @Test
    fun testConfidenceThresholdBoundary_0849_0850_0851() {
        val gate = AlertGate(confidenceThreshold = 0.850f, cooldownMs = 5000L)

        // 1. Boundary: 0.849 -> Suppressed
        val res849 = createResult(ClassLabels.EMP, 0.849f)
        val decision849 = gate.evaluate(res849, currentTimeMs = 1000L)
        assertTrue("0.849 must be suppressed", decision849 is AlertGate.AlertDecision.Suppressed)
        assertEquals(
            AlertGate.SuppressionReason.CONFIDENCE_BELOW_THRESHOLD,
            (decision849 as AlertGate.AlertDecision.Suppressed).reason
        )

        // 2. Boundary: 0.850 -> Triggered
        val res850 = createResult(ClassLabels.EMP, 0.850f)
        val decision850 = gate.evaluate(res850, currentTimeMs = 1000L)
        assertTrue("0.850 must trigger alert", decision850 is AlertGate.AlertDecision.TriggerAlert)
        val alert850 = decision850 as AlertGate.AlertDecision.TriggerAlert
        assertEquals(ClassLabels.EMP, alert850.tamperClass)
        assertEquals(0.850f, alert850.confidence, 1e-5f)
        assertEquals(AlertGate.TEXT_EMP, alert850.alertMessage)

        // Reset for next test
        gate.reset()

        // 3. Boundary: 0.851 -> Triggered
        val res851 = createResult(ClassLabels.EMP, 0.851f)
        val decision851 = gate.evaluate(res851, currentTimeMs = 1000L)
        assertTrue("0.851 must trigger alert", decision851 is AlertGate.AlertDecision.TriggerAlert)
        val alert851 = decision851 as AlertGate.AlertDecision.TriggerAlert
        assertEquals(ClassLabels.EMP, alert851.tamperClass)
        assertEquals(0.851f, alert851.confidence, 1e-5f)
    }

    @Test
    fun testRepeatedAlertSuppressionWithin5Seconds() {
        val gate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 5000L)
        val empResult = createResult(ClassLabels.EMP, 0.95f)

        // 1. First alert at t = 1000 ms -> Triggered
        val d1 = gate.evaluate(empResult, currentTimeMs = 1000L)
        assertTrue("First alert at t=1000 must trigger", d1 is AlertGate.AlertDecision.TriggerAlert)

        // 2. Second alert at t = 3000 ms (2000 ms elapsed < 5000 ms) -> Suppressed
        val d2 = gate.evaluate(empResult, currentTimeMs = 3000L)
        assertTrue("Second alert at t=3000 must be rate-limited", d2 is AlertGate.AlertDecision.Suppressed)
        assertEquals(
            AlertGate.SuppressionReason.RATE_LIMITED_COOLDOWN,
            (d2 as AlertGate.AlertDecision.Suppressed).reason
        )

        // 3. Third alert at t = 5999 ms (4999 ms elapsed < 5000 ms) -> Suppressed
        val d3 = gate.evaluate(empResult, currentTimeMs = 5999L)
        assertTrue("Alert before 5s must be suppressed", d3 is AlertGate.AlertDecision.Suppressed)

        // 4. Fourth alert at t = 6001 ms (5001 ms elapsed > 5000 ms) -> Triggered
        val d4 = gate.evaluate(empResult, currentTimeMs = 6001L)
        assertTrue("Alert after 5s cooldown must trigger", d4 is AlertGate.AlertDecision.TriggerAlert)
    }

    @Test
    fun testGateResetOnNormalResult() {
        val gate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 5000L)
        val empResult = createResult(ClassLabels.EMP, 0.95f)
        val normalResult = createResult(ClassLabels.NORMAL, 0.99f)

        // 1. Alert at t = 1000 ms
        val d1 = gate.evaluate(empResult, currentTimeMs = 1000L)
        assertTrue("Initial alert must trigger", d1 is AlertGate.AlertDecision.TriggerAlert)

        // 2. Valid NORMAL frame arrives at t = 2000 ms -> Resets the gate
        val dNormal = gate.evaluate(normalResult, currentTimeMs = 2000L)
        assertTrue(dNormal is AlertGate.AlertDecision.Suppressed)

        // 3. New EMP alert at t = 2500 ms (within 5s of original, but gate was reset by NORMAL!) -> Triggered!
        val d2 = gate.evaluate(empResult, currentTimeMs = 2500L)
        assertTrue("Alert after NORMAL reset must trigger immediately", d2 is AlertGate.AlertDecision.TriggerAlert)
    }

    @Test
    fun testAllThreeTamperClassesHaveExpectedAlertTexts() {
        assertEquals("High-voltage EMP-like transient detected in simulation", AlertGate.getAlertText(ClassLabels.EMP))
        assertEquals("Optical saturation event detected in simulation", AlertGate.getAlertText(ClassLabels.OPTICAL))
        assertEquals("Inductive surge event detected in simulation", AlertGate.getAlertText(ClassLabels.SURGE))
    }
}
