package com.sparkshield.android.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuOnnxInferenceEngineTest {

    @Test
    fun testClassMapping() {
        assertEquals("NORMAL must map to index 0", 0, ClassLabels.NORMAL.id)
        assertEquals("EMP must map to index 1", 1, ClassLabels.EMP.id)
        assertEquals("OPTICAL must map to index 2", 2, ClassLabels.OPTICAL.id)
        assertEquals("SURGE must map to index 3", 3, ClassLabels.SURGE.id)

        assertEquals(ClassLabels.NORMAL, ClassLabels.fromId(0))
        assertEquals(ClassLabels.EMP, ClassLabels.fromId(1))
        assertEquals(ClassLabels.OPTICAL, ClassLabels.fromId(2))
        assertEquals(ClassLabels.SURGE, ClassLabels.fromId(3))
    }

    @Test
    fun testSoftmaxNumericalStability() {
        // Test standard logits
        val standardLogits = floatArrayOf(2.0f, 1.0f, 0.1f, -1.0f)
        val standardProbs = CpuOnnxInferenceEngine.stableSoftmax(standardLogits)

        assertEquals(4, standardProbs.size)
        var sum = 0.0f
        for (p in standardProbs) {
            assertTrue("Probability must be >= 0", p >= 0.0f)
            assertTrue("Probability must be <= 1", p <= 1.0f)
            sum += p
        }
        assertEquals("Probabilities must sum to 1.0", 1.0f, sum, 1e-5f)

        // Test extreme large logits that would overflow plain exp(1000) -> NaN/Inf
        val extremeLogits = floatArrayOf(1000.0f, 999.0f, 500.0f, 0.0f)
        val extremeProbs = CpuOnnxInferenceEngine.stableSoftmax(extremeLogits)

        for (p in extremeProbs) {
            assertFalse("Must not produce NaN", p.isNaN())
            assertFalse("Must not produce Inf", p.isInfinite())
        }
        assertEquals("Highest logit must receive highest probability", 1000.0f, extremeLogits[0], 0.0f)
        assertTrue(extremeProbs[0] > 0.5f)
        var extremeSum = 0.0f
        for (p in extremeProbs) extremeSum += p
        assertEquals(1.0f, extremeSum, 1e-4f)

        // Test extreme negative logits
        val negativeLogits = floatArrayOf(-500.0f, -600.0f, -700.0f, -800.0f)
        val negativeProbs = CpuOnnxInferenceEngine.stableSoftmax(negativeLogits)
        for (p in negativeProbs) {
            assertFalse(p.isNaN())
            assertFalse(p.isInfinite())
        }
    }

    @Test
    fun testExpectedInputAndOutputShapes() {
        val expectedInput = CpuOnnxInferenceEngine.EXPECTED_INPUT_SHAPE
        val expectedOutput = CpuOnnxInferenceEngine.EXPECTED_OUTPUT_SHAPE

        assertEquals(1L, expectedInput[0])
        assertEquals(1L, expectedInput[1])
        assertEquals(128L, expectedInput[2])

        assertEquals(1L, expectedOutput[0])
        assertEquals(4L, expectedOutput[1])
    }
}
