package com.sparkshield.android.inference

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [QnnHtpInferenceEngine] verifying:
 *   - Input tensor element count [1, 1, 128] validation.
 *   - Transparent automatic fallback to CPU engine on non-Snapdragon / host test environments.
 *   - Correct class label decoding and non-negative inference execution latency.
 *   - Idempotent resource cleanup on close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QnnHtpInferenceEngineTest {

    private class MockCpuEngine : InferenceEngine {
        var isLoaded = false
        var isClosed = false

        override suspend fun load(): Result<Unit> {
            isLoaded = true
            return Result.success(Unit)
        }

        override suspend fun infer(input: FloatArray): Result<InferenceResult> {
            val probs = floatArrayOf(0.95f, 0.02f, 0.02f, 0.01f)
            return Result.success(
                InferenceResult(
                    label = "NORMAL",
                    classIndex = 0,
                    probabilities = probs,
                    confidence = 0.95f,
                    inferenceTimeUs = 1200L
                )
            )
        }

        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun testInitializationWithFallbackWhenNativeUnavailable() = runTest {
        val mockCpu = MockCpuEngine()
        val engine = QnnHtpInferenceEngine(
            context = null,
            cpuFallbackEngine = mockCpu,
            forceCpuFallback = true
        )

        val loadResult = engine.load()
        assertTrue(loadResult.isSuccess)
        assertTrue(mockCpu.isLoaded)
        assertEquals("CPU (ONNX)", engine.activeAccelerator)
        assertFalse(engine.isUsingHtp)

        engine.close()
        assertTrue(mockCpu.isClosed)
    }

    @Test
    fun testInputDimensionValidation() = runTest {
        val mockCpu = MockCpuEngine()
        val engine = QnnHtpInferenceEngine(
            context = null,
            cpuFallbackEngine = mockCpu,
            forceCpuFallback = true
        )

        engine.load()

        // Pass 64 floats instead of 128
        val badInput = FloatArray(64)
        val result = engine.infer(badInput)

        // Invalid dimension should fail validation in QnnHtpInferenceEngine
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        engine.close()
    }

    @Test
    fun testFallbackInferenceExecution() = runTest {
        val mockCpu = MockCpuEngine()
        val engine = QnnHtpInferenceEngine(
            context = null,
            cpuFallbackEngine = mockCpu,
            forceCpuFallback = true
        )

        engine.load()

        val validFeatureTensor = FloatArray(128) { 0.05f }
        val inferResult = engine.infer(validFeatureTensor)

        assertTrue(inferResult.isSuccess)
        val res = inferResult.getOrThrow()
        assertEquals("NORMAL", res.label)
        assertEquals(0, res.classIndex)
        assertEquals(0.95f, res.confidence, 0.001f)
        assertEquals(4, res.probabilities.size)
        assertTrue(res.inferenceTimeUs > 0)

        engine.close()
    }

    @Test
    fun testIdempotentClose() = runTest {
        val mockCpu = MockCpuEngine()
        val engine = QnnHtpInferenceEngine(
            context = null,
            cpuFallbackEngine = mockCpu,
            forceCpuFallback = true
        )

        engine.load()
        engine.close()
        engine.close() // Safe repeated close
        assertTrue(mockCpu.isClosed)
        assertEquals("CLOSED", engine.qnnInitStatus)
    }

    @Test
    fun testFallbackReasonAndDiagnostics() = runTest {
        val mockCpu = MockCpuEngine()
        val engine = QnnHtpInferenceEngine(
            context = null,
            cpuFallbackEngine = mockCpu,
            forceCpuFallback = true
        )

        engine.load()
        assertEquals("FALLBACK_CPU", engine.qnnInitStatus)
        assertNotNull(engine.fallbackReason)
        assertTrue(engine.fallbackReason!!.contains("explicitly requested"))

        val input = FloatArray(128)
        engine.infer(input)
        assertEquals(1L, engine.fallbackExecutionCount)
        assertEquals(0L, engine.htpExecutionCount)
        assertEquals(0L, engine.errorCount)

        engine.close()
    }

    @Test
    fun testRepeatedLoadCycles() = runTest {
        val mockCpu = MockCpuEngine()
        val engine = QnnHtpInferenceEngine(
            context = null,
            cpuFallbackEngine = mockCpu,
            forceCpuFallback = true
        )

        val load1 = engine.load()
        assertTrue(load1.isSuccess)
        val load2 = engine.load() // Idempotent load
        assertTrue(load2.isSuccess)

        engine.close()
    }
}
