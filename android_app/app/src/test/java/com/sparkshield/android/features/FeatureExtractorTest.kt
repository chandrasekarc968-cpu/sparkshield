package com.sparkshield.android.features

import com.sparkshield.android.protocol.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

    private fun createTestFrame(
        seq: Long = 1L,
        peakMv: Int = 3250,
        riseTimeCode: Int = 50000,
        decayTimeUs: Int = 5000,
        opticalSensorMv: Int = 150
    ): TelemetryFrame {
        return TelemetryFrame(
            sequenceId = seq,
            timestampMs = seq * 100L,
            eventFlags = TelemetryFrame.FLAG_NORMAL,
            peakMv = peakMv,
            riseTimeCode = riseTimeCode,
            decayTimeUs = decayTimeUs,
            opticalSensorMv = opticalSensorMv,
            fftEnergyBins = byteArrayOf(220.toByte(), 35.toByte(), 12.toByte(), 4.toByte(), 2.toByte(), 1.toByte(), 0.toByte(), 0.toByte())
        )
    }

    @Test
    fun testExtractedFeaturesWithinNormalizedBounds() {
        val extractor = FeatureExtractor()
        val frame = createTestFrame()
        val features = extractor.extractFrameFeatures(frame)

        assertEquals("Frame feature vector must have exactly 16 elements", 16, features.size)

        for (i in features.indices) {
            val v = features[i]
            assertTrue("Feature at index $i ($v) must be >= 0.0", v >= 0.0f)
            assertTrue("Feature at index $i ($v) must be <= 1.0", v <= 1.0f)
        }
    }

    @Test
    fun testZeroPaddingBeforeEightFrames() {
        val window = FeatureWindow()
        val extractor = FeatureExtractor(window)

        assertFalse("Should NOT be ready for inference with 0 frames", extractor.isReadyForInference)
        assertEquals(0, extractor.validFrameCount)

        // Push 1 frame
        val f1 = createTestFrame(seq = 1L, peakMv = 30000)
        val tensor1 = extractor.update(f1)

        assertEquals("Tensor size must always be 128 floats", 128, tensor1.size)
        assertEquals(1, extractor.validFrameCount)
        assertFalse("Should NOT be ready for inference with only 1 frame", extractor.isReadyForInference)

        // Slots 0..6 (indices 0..111) must be zero-padded
        for (i in 0 until 112) {
            assertEquals("Index $i must be 0.0f (zero-padded older frame)", 0.0f, tensor1[i], 0.0f)
        }
        // Slot 7 (indices 112..127) must contain the first frame's features
        val expectedPeakNorm = 30000.0f / Normalization.PEAK_MV_SCALE
        assertEquals("Slot 7 must contain active frame features", expectedPeakNorm, tensor1[112 + FeatureLayout.IDX_PEAK_MV], 1e-4f)

        // Enable warmup mode
        extractor.isWarmupModeEnabled = true
        assertTrue("Inference must be allowed when warm-up mode is enabled", extractor.isReadyForInference)
        extractor.isWarmupModeEnabled = false
        assertFalse("Reverting warm-up mode must restore gating", extractor.isReadyForInference)

        // Push 7 more frames (total 8)
        for (i in 2..8) {
            extractor.update(createTestFrame(seq = i.toLong(), peakMv = 3250))
        }

        assertEquals(8, extractor.validFrameCount)
        assertTrue("Must be ready for inference once 8 frames are available", extractor.isReadyForInference)

        // Window must now be fully populated (no zero-padded older frame in slot 0)
        val fullTensor = window.getFlattenedWindow()
        assertEquals(128, fullTensor.size)
        // First frame pushed (seq 1, peak 30000) should now be in Slot 0 (indices 0..15)
        assertEquals(expectedPeakNorm, fullTensor[0 + FeatureLayout.IDX_PEAK_MV], 1e-4f)
    }

    @Test
    fun testChronologicalOrderingInWindow() {
        val window = FeatureWindow()
        val extractor = FeatureExtractor(window)

        // Push 8 frames with distinct peak voltages: 10000, 20000, ..., 80000 (clamped to 65535)
        for (i in 1..8) {
            val peak = i * 8000
            extractor.update(createTestFrame(seq = i.toLong(), peakMv = peak))
        }

        val tensor = window.getFlattenedWindow()

        // Oldest frame (i=1, peak 8000) must be in slot 0 (index 0)
        val oldestPeak = tensor[0 * 16 + FeatureLayout.IDX_PEAK_MV]
        assertEquals(8000.0f / Normalization.PEAK_MV_SCALE, oldestPeak, 1e-4f)

        // Newest frame (i=8, peak 64000) must be in slot 7 (index 112)
        val newestPeak = tensor[7 * 16 + FeatureLayout.IDX_PEAK_MV]
        assertEquals(64000.0f / Normalization.PEAK_MV_SCALE, newestPeak, 1e-4f)
    }

    @Test
    fun testLog1pNormalization() {
        val normZero = Normalization.log1pNorm(0.0f)
        val normMax = Normalization.log1pNorm(65535.0f)

        assertEquals("log1p(0) must be 0.0", 0.0f, normZero, 1e-5f)
        assertEquals("log1p(65535) must be 1.0", 1.0f, normMax, 1e-5f)
    }
}
