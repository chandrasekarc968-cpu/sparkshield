package com.sparkshield.android.features

import com.sparkshield.android.protocol.TelemetryFrame

/**
 * Transforms incoming 29-byte TelemetryFrame into a 16-element normalized feature vector
 * and maintains an 8-frame sliding window yielding a (1, 1, 128) model tensor.
 *
 * Parity with python_core/signal_models.py:FeatureExtractor.
 */
class FeatureExtractor(
    private val window: FeatureWindow = FeatureWindow()
) {
    /**
     * Extracts 16 normalized feature values from a single TelemetryFrame.
     * All output values are bounded in [0.0, 1.0].
     *
     * @param frame The input validated TelemetryFrame.
     * @return FloatArray of size 16.
     */
    fun extractFrameFeatures(frame: TelemetryFrame): FloatArray {
        val vec = FloatArray(FeatureLayout.NUM_FEATURES_PER_FRAME)

        // 1. Linear normalized scalar fields
        vec[FeatureLayout.IDX_NORM_PEAK] = (frame.peakMv.toFloat() / Normalization.PEAK_MV_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_NORM_RISE] = (frame.riseTimeCode.toFloat() / Normalization.RISE_TIME_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_LOG_RISE] = Normalization.log1pNorm(frame.riseTimeCode.toFloat())
        vec[FeatureLayout.IDX_NORM_DECAY] = (frame.decayTimeUs.toFloat() / Normalization.DECAY_TIME_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_LOG_DECAY] = Normalization.log1pNorm(frame.decayTimeUs.toFloat())
        vec[FeatureLayout.IDX_NORM_OPTICAL] = (frame.opticalSensorMv.toFloat() / Normalization.OPTICAL_MV_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_OPTICAL_RAIL] = (frame.opticalSensorMv.toFloat() / Normalization.OPTICAL_RAIL_MV).coerceIn(0.0f, 1.0f)

        // 2. Normalized FFT bins & High-Frequency Energy Ratio
        var totalEnergy = 0.0f
        var hfEnergy = 0.0f
        val bins = frame.fftEnergyBins
        for (i in 0 until 8) {
            val normBin = (bins[i].toInt() and 0xFF) / Normalization.FFT_ENERGY_SCALE
            vec[FeatureLayout.IDX_FFT_BIN_START + i] = normBin
            totalEnergy += normBin
            if (i >= 4) {
                hfEnergy += normBin
            }
        }
        vec[FeatureLayout.IDX_HF_ENERGY_RATIO] = (hfEnergy / (totalEnergy + Normalization.EPSILON)).coerceIn(0.0f, 1.0f)

        return vec
    }

    /**
     * Updates sliding window with new frame features and returns flattened 128-float array.
     *
     * @param frame Newly arrived telemetry frame.
     * @return FloatArray of size 128, suitable for feeding into ONNX tensor shape (1, 1, 128).
     */
    fun update(frame: TelemetryFrame): FloatArray {
        val features = extractFrameFeatures(frame)
        window.push(features)
        return window.getFlattenedWindow()
    }

    /**
     * Resets sliding window to normal baseline.
     */
    fun reset() {
        window.reset()
    }
}
