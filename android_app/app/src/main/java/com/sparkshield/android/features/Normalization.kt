package com.sparkshield.android.features

import kotlin.math.ln

/**
 * Normalization constants and mathematical scaling parameters matching
 * python_core/signal_models.py and models/split_metadata.json.
 */
object Normalization {
    const val PEAK_MV_SCALE: Float = 65535.0f
    const val RISE_TIME_SCALE: Float = 65535.0f
    const val DECAY_TIME_SCALE: Float = 65535.0f
    const val OPTICAL_MV_SCALE: Float = 65535.0f
    const val OPTICAL_RAIL_MV: Float = 5000.0f
    const val FFT_ENERGY_SCALE: Float = 255.0f
    const val EPSILON: Float = 1e-5f

    // Precomputed natural logarithm of (1.0 + 65535.0) = ln(65536.0) ~ 11.090355f
    val LN_65536: Float = ln(65536.0f)

    /**
     * Compute log1p normalization: ln(1 + x) / ln(1 + 65535).
     */
    fun log1pNorm(value: Float): Float {
        val clamped = value.coerceIn(0.0f, 65535.0f)
        return ln(1.0f + clamped) / LN_65536
    }
}
