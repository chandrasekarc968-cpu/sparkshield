package com.sparkshield.android.features

/**
 * Feature layout definitions matching python_core/signal_models.py:FeatureExtractor.
 *
 * 16 features per telemetry frame:
 *   [0] Linear peak voltage: peak_mv / 65535.0
 *   [1] Linear rise time: rise_time_code / 65535.0
 *   [2] Log rise time: ln(1 + rise_time_code) / ln(1 + 65535)
 *   [3] Linear decay time: decay_time_us / 65535.0
 *   [4] Log decay time: ln(1 + decay_time_us) / ln(1 + 65535)
 *   [5] Linear optical sensor: optical_sensor_mv / 65535.0
 *   [6] Optical saturation proximity: min(optical_sensor_mv / 5000.0, 1.0)
 *   [7] High-frequency RF energy ratio: sum(bins[4..7]) / (sum(bins[0..7]) + 1e-5)
 *   [8..15] Normalized FFT energy bins: bins[0..7] / 255.0
 */
object FeatureLayout {
    const val NUM_FEATURES_PER_FRAME = 16
    const val WINDOW_FRAME_COUNT = 8
    const val TOTAL_FEATURES = NUM_FEATURES_PER_FRAME * WINDOW_FRAME_COUNT // 128

    const val IDX_NORM_PEAK = 0
    const val IDX_NORM_RISE = 1
    const val IDX_LOG_RISE = 2
    const val IDX_NORM_DECAY = 3
    const val IDX_LOG_DECAY = 4
    const val IDX_NORM_OPTICAL = 5
    const val IDX_OPTICAL_RAIL = 6
    const val IDX_HF_ENERGY_RATIO = 7
    const val IDX_FFT_BIN_START = 8
    const val FFT_BIN_COUNT = 8
}
