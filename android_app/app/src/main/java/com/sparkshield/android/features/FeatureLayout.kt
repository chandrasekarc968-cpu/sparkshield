package com.sparkshield.android.features

/**
 * Feature layout definitions matching python_core/signal_models.py:FeatureExtractor.
 *
 * Each 29-byte telemetry frame produces exactly 16 normalized float features in [0.0, 1.0]:
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
 * CRITICAL ARCHITECTURAL CONSTRAINT:
 * Ground-truth event flags are NEVER used as model input features.
 * The edge classifier relies solely on physical and spectral waveform metrics.
 *
 * Sliding Window:
 *   8 frames x 16 features = 128 float values.
 *   Flattened in chronological order: oldest frame features first (indices 0..15),
 *   newest frame features last (indices 112..127).
 *   Model input tensor shape: [1, 1, 128].
 */
object FeatureLayout {
    const val NUM_FEATURES_PER_FRAME: Int = 16
    const val WINDOW_FRAME_COUNT: Int = 8
    const val TOTAL_FEATURES: Int = NUM_FEATURES_PER_FRAME * WINDOW_FRAME_COUNT // 128

    // Feature Index Constants
    const val IDX_PEAK_MV: Int = 0
    const val IDX_RISE_TIME_CODE: Int = 1
    const val IDX_LOG_RISE_TIME: Int = 2
    const val IDX_DECAY_TIME_US: Int = 3
    const val IDX_LOG_DECAY_TIME: Int = 4
    const val IDX_OPTICAL_SENSOR_MV: Int = 5
    const val IDX_OPTICAL_RAIL_PROXIMITY: Int = 6
    const val IDX_HF_ENERGY_RATIO: Int = 7
    const val IDX_FFT_BIN_0: Int = 8
    const val IDX_FFT_BIN_1: Int = 9
    const val IDX_FFT_BIN_2: Int = 10
    const val IDX_FFT_BIN_3: Int = 11
    const val IDX_FFT_BIN_4: Int = 12
    const val IDX_FFT_BIN_5: Int = 13
    const val IDX_FFT_BIN_6: Int = 14
    const val IDX_FFT_BIN_7: Int = 15

    const val FFT_BIN_START_IDX: Int = 8
    const val FFT_BIN_COUNT: Int = 8

    // Aliases for compatibility
    const val IDX_NORM_PEAK: Int = IDX_PEAK_MV
    const val IDX_NORM_RISE: Int = IDX_RISE_TIME_CODE
    const val IDX_LOG_RISE: Int = IDX_LOG_RISE_TIME
    const val IDX_NORM_DECAY: Int = IDX_DECAY_TIME_US
    const val IDX_LOG_DECAY: Int = IDX_LOG_DECAY_TIME
    const val IDX_NORM_OPTICAL: Int = IDX_OPTICAL_SENSOR_MV
    const val IDX_OPTICAL_RAIL: Int = IDX_OPTICAL_RAIL_PROXIMITY
    const val IDX_FFT_BIN_START: Int = FFT_BIN_START_IDX
}
