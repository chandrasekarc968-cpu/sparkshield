package com.sparkshield.android.features

import com.sparkshield.android.protocol.TelemetryFrame

/**
 * Transforms incoming 29-byte TelemetryFrame into a 16-element normalized feature vector
 * and maintains an 8-frame sliding window yielding a (1, 1, 128) model tensor.
 *
 * Parity with python_core/signal_models.py:FeatureExtractor.
 *
 * Feature Layout (16 normalized float features per frame):
 *   0  = peak_mv / 65535.0
 *   1  = rise_time_code / 65535.0
 *   2  = log1p(rise_time_code) / log1p(65535.0)
 *   3  = decay_time_us / 65535.0
 *   4  = log1p(decay_time_us) / log1p(65535.0)
 *   5  = optical_sensor_mv / 65535.0
 *   6  = min(optical_sensor_mv / 5000.0, 1.0)
 *   7  = sum(fft_bins[4..7]) / (sum(fft_bins[0..7]) + 1e-5)
 *   8  = fft_bins[0] / 255.0
 *   9  = fft_bins[1] / 255.0
 *   10 = fft_bins[2] / 255.0
 *   11 = fft_bins[3] / 255.0
 *   12 = fft_bins[4] / 255.0
 *   13 = fft_bins[5] / 255.0
 *   14 = fft_bins[6] / 255.0
 *   15 = fft_bins[7] / 255.0
 *
 * Note: Event flags are strictly excluded from the feature vector to prevent shortcut learning.
 */
class FeatureExtractor(
    private val window: FeatureWindow = FeatureWindow()
) {
    /**
     * Whether at least 8 valid frames have been accumulated (or warm-up mode is enabled)
     * so inference can be safely executed.
     */
    val isReadyForInference: Boolean
        get() = window.isReadyForInference

    /**
     * Configurable warm-up mode flag. When true, allows running inference even before
     * 8 valid frames have arrived. Defaults to false.
     */
    var isWarmupModeEnabled: Boolean
        get() = window.isWarmupModeEnabled
        set(value) {
            window.isWarmupModeEnabled = value
        }

    /** Number of valid frames received in the sliding window. */
    val validFrameCount: Int
        get() = window.validFrameCount

    /**
     * Extracts 16 normalized feature values from a single TelemetryFrame.
     * All output values are bounded in [0.0, 1.0].
     *
     * @param frame The input validated TelemetryFrame.
     * @return FloatArray of size 16.
     */
    fun extractFrameFeatures(frame: TelemetryFrame): FloatArray {
        val vec = FloatArray(FeatureLayout.NUM_FEATURES_PER_FRAME)

        // 0. Linear peak voltage: peak_mv / 65535.0
        vec[FeatureLayout.IDX_PEAK_MV] = (frame.peakMv.toFloat() / Normalization.PEAK_MV_SCALE).coerceIn(0.0f, 1.0f)

        // 1. Linear rise time: rise_time_code / 65535.0
        vec[FeatureLayout.IDX_RISE_TIME_CODE] = (frame.riseTimeCode.toFloat() / Normalization.RISE_TIME_SCALE).coerceIn(0.0f, 1.0f)

        // 2. Log rise time: log1p(rise_time_code) / log1p(65535.0)
        vec[FeatureLayout.IDX_LOG_RISE_TIME] = Normalization.log1pNorm(frame.riseTimeCode.toFloat())

        // 3. Linear decay time: decay_time_us / 65535.0
        vec[FeatureLayout.IDX_DECAY_TIME_US] = (frame.decayTimeUs.toFloat() / Normalization.DECAY_TIME_SCALE).coerceIn(0.0f, 1.0f)

        // 4. Log decay time: log1p(decay_time_us) / log1p(65535.0)
        vec[FeatureLayout.IDX_LOG_DECAY_TIME] = Normalization.log1pNorm(frame.decayTimeUs.toFloat())

        // 5. Linear optical sensor: optical_sensor_mv / 65535.0
        vec[FeatureLayout.IDX_OPTICAL_SENSOR_MV] = (frame.opticalSensorMv.toFloat() / Normalization.OPTICAL_MV_SCALE).coerceIn(0.0f, 1.0f)

        // 6. Optical rail proximity: min(optical_sensor_mv / 5000.0, 1.0)
        vec[FeatureLayout.IDX_OPTICAL_RAIL_PROXIMITY] = (frame.opticalSensorMv.toFloat() / Normalization.OPTICAL_RAIL_MV).coerceIn(0.0f, 1.0f)

        // 7..15. FFT energy bins & High-frequency energy ratio:
        // 7: sum(bins[4..7]) / (sum(bins[0..7]) + 1e-5)
        // 8..15: bins[0..7] / 255.0
        var totalEnergy = 0.0f
        var hfEnergy = 0.0f
        val bins = frame.fftEnergyBins
        for (i in 0 until 8) {
            val normBin = (bins[i].toInt() and 0xFF) / Normalization.FFT_ENERGY_SCALE
            vec[FeatureLayout.FFT_BIN_START_IDX + i] = normBin
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
     * @return FloatArray of size 128 (flattened chronological [1, 1, 128]).
     */
    fun update(frame: TelemetryFrame): FloatArray {
        val features = extractFrameFeatures(frame)
        window.push(features)
        return window.getFlattenedWindow()
    }

    /**
     * Resets sliding window state.
     */
    fun reset() {
        window.reset()
    }
}
