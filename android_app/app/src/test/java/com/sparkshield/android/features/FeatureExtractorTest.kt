package com.sparkshield.android.features

import com.sparkshield.android.protocol.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

    private fun createTestFrame(
        peakMv: Int = 3250,
        riseTimeCode: Int = 50000,
        decayTimeUs: Int = 5000,
        opticalSensorMv: Int = 150
    ): TelemetryFrame {
        return TelemetryFrame(
            sequenceId = 1L,
            timestampMs = 100L,
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

        assertEquals("Frame feature vector must have 16 elements", 16, features.size)

        for (i in features.indices) {
            val v = features[i]
            assertTrue("Feature at index $i ($v) must be >= 0.0", v >= 0.0f)
            assertTrue("Feature at index $i ($v) must be <= 1.0", v <= 1.0f)
        }
    }

    @Test
    fun testWindowSlidingShapeAndPreFill() {
        val window = FeatureWindow()
        val flat = window.getFlattenedWindow()

        assertEquals("Initial window must be pre-filled with 128 floats", 128, flat.size)

        // All 128 entries must be within valid range
        for (i in flat.indices) {
            assertTrue("Pre-filled feature $i must be within [0, 1]", flat[i] in 0.0f..1.0f)
        }

        val extractor = FeatureExtractor(window)
        val frame = createTestFrame(peakMv = 60000)
        val updated = extractor.update(frame)

        assertEquals("Updated window must produce exactly 128 floats", 128, updated.size)
        // Latest pushed frame is at the end of the window (indices 112..127)
        val latestPeakNorm = updated[112 + FeatureLayout.IDX_NORM_PEAK]
        val expectedPeakNorm = 60000.0f / Normalization.PEAK_MV_SCALE
        assertEquals(expectedPeakNorm, latestPeakNorm, 1e-4f)
    }

    @Test
    fun testLog1pNormalization() {
        val normZero = Normalization.log1pNorm(0.0f)
        val normMax = Normalization.log1pNorm(65535.0f)

        assertEquals("log1p(0) must be 0.0", 0.0f, normZero, 1e-5f)
        assertEquals("log1p(65535) must be 1.0", 1.0f, normMax, 1e-5f)
    }
}
