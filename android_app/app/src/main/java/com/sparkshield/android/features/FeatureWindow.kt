package com.sparkshield.android.features

import java.util.ArrayDeque

/**
 * Maintains a rolling FIFO sliding window of 8 frames x 16 features = 128 float values.
 *
 * Pre-filled with baseline normal features so the model input tensor is immediately valid
 * upon receipt of the first frame, matching Python's FeatureExtractor._init_baseline().
 */
class FeatureWindow(
    private val frameCapacity: Int = FeatureLayout.WINDOW_FRAME_COUNT,
    private val featuresPerFrame: Int = FeatureLayout.NUM_FEATURES_PER_FRAME
) {
    private val buffer: ArrayDeque<FloatArray> = ArrayDeque(frameCapacity)
    private var baselineVector: FloatArray = FloatArray(featuresPerFrame)

    init {
        // Compute baseline normal frame vector
        baselineVector = computeBaselineVector()
        reset()
    }

    private fun computeBaselineVector(): FloatArray {
        val vec = FloatArray(featuresPerFrame)
        vec[FeatureLayout.IDX_NORM_PEAK] = (3250.0f / Normalization.PEAK_MV_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_NORM_RISE] = (50000.0f / Normalization.RISE_TIME_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_LOG_RISE] = Normalization.log1pNorm(50000.0f)
        vec[FeatureLayout.IDX_NORM_DECAY] = (5000.0f / Normalization.DECAY_TIME_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_LOG_DECAY] = Normalization.log1pNorm(5000.0f)
        vec[FeatureLayout.IDX_NORM_OPTICAL] = (150.0f / Normalization.OPTICAL_MV_SCALE).coerceIn(0.0f, 1.0f)
        vec[FeatureLayout.IDX_OPTICAL_RAIL] = (150.0f / Normalization.OPTICAL_RAIL_MV).coerceIn(0.0f, 1.0f)

        val rawBins = byteArrayOf(220.toByte(), 35.toByte(), 12.toByte(), 4.toByte(), 2.toByte(), 1.toByte(), 0.toByte(), 0.toByte())
        var totalEnergy = 0.0f
        var hfEnergy = 0.0f
        for (i in 0 until 8) {
            val normVal = (rawBins[i].toInt() and 0xFF) / Normalization.FFT_ENERGY_SCALE
            vec[FeatureLayout.IDX_FFT_BIN_START + i] = normVal
            totalEnergy += normVal
            if (i >= 4) {
                hfEnergy += normVal
            }
        }
        vec[FeatureLayout.IDX_HF_ENERGY_RATIO] = hfEnergy / (totalEnergy + Normalization.EPSILON)
        return vec
    }

    /**
     * Push a new 16-element feature vector into the sliding window.
     * Evicts oldest if capacity is reached.
     */
    @Synchronized
    fun push(features: FloatArray) {
        require(features.size == featuresPerFrame) {
            "Expected $featuresPerFrame features, got ${features.size}"
        }
        if (buffer.size >= frameCapacity) {
            buffer.removeFirst()
        }
        buffer.addLast(features.copyOf())
    }

    /**
     * Returns flattened chronological 128-float array (oldest to newest).
     */
    @Synchronized
    fun getFlattenedWindow(): FloatArray {
        val out = FloatArray(frameCapacity * featuresPerFrame)
        var offset = 0
        for (vec in buffer) {
            System.arraycopy(vec, 0, out, offset, featuresPerFrame)
            offset += featuresPerFrame
        }
        return out
    }

    /**
     * Resets sliding window to normal baseline.
     */
    @Synchronized
    fun reset() {
        buffer.clear()
        for (i in 0 until frameCapacity) {
            buffer.addLast(baselineVector.copyOf())
        }
    }

    val size: Int
        @Synchronized get() = buffer.size
}
