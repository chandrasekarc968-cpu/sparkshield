package com.sparkshield.android.features

import java.util.ArrayDeque

/**
 * Maintains a rolling FIFO sliding window of 8 frames x 16 features = 128 float values.
 *
 * Zero-Padding Behavior & Inference Gating:
 * -----------------------------------------
 * Before the window accumulates 8 valid frames:
 *   - Older frames in the window are zero-padded (all 16 features set to 0.0f).
 *   - For example, if N < 8 frames have arrived, the first (8 - N) frame slots are 0.0f,
 *     and the remaining N slots contain the received frames in chronological order.
 *   - Inference is strictly NOT executed until at least 8 valid frames are available
 *     ([isReadyForInference] returns false), unless [isWarmupModeEnabled] is explicitly set to true.
 *
 * Chronological Flattening:
 *   - Oldest frame features first (indices 0..15)
 *   - Newest frame features last (indices 112..127)
 *   - Shape: FloatArray(128) representing tensor shape [1, 1, 128].
 */
class FeatureWindow(
    private val frameCapacity: Int = FeatureLayout.WINDOW_FRAME_COUNT,
    private val featuresPerFrame: Int = FeatureLayout.NUM_FEATURES_PER_FRAME
) {
    private val buffer: ArrayDeque<FloatArray> = ArrayDeque(frameCapacity)

    /** Number of valid frames pushed since initialization or reset. */
    var validFrameCount: Int = 0
        private set

    /**
     * Warm-up mode flag. When enabled, allows executing inference before the window
     * contains 8 valid frames. Defaults to false.
     */
    var isWarmupModeEnabled: Boolean = false

    /**
     * Indicates whether the window has sufficient frames (at least 8 valid frames,
     * or warm-up mode is enabled) to safely run model inference.
     */
    val isReadyForInference: Boolean
        @Synchronized get() = (validFrameCount >= frameCapacity) || isWarmupModeEnabled

    init {
        reset()
    }

    /**
     * Push a new 16-element feature vector into the sliding window.
     * Evicts the oldest frame once capacity (8 frames) is reached.
     *
     * @param features Normalized FloatArray of size 16.
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
        validFrameCount++
    }

    /**
     * Returns flattened chronological 128-float array (oldest to newest).
     *
     * If fewer than 8 frames have arrived, older frame slots are zero-padded (0.0f),
     * and the active frames are placed at the end of the window (newest at the very end).
     *
     * @return FloatArray of size 128 (flattened [1, 1, 128]).
     */
    @Synchronized
    fun getFlattenedWindow(): FloatArray {
        val totalSize = frameCapacity * featuresPerFrame
        val out = FloatArray(totalSize)

        // If buffer has fewer than frameCapacity elements, zero-pad older slots
        val padFrames = frameCapacity - buffer.size
        var offset = padFrames * featuresPerFrame

        for (vec in buffer) {
            System.arraycopy(vec, 0, out, offset, featuresPerFrame)
            offset += featuresPerFrame
        }
        return out
    }

    /**
     * Resets sliding window state, clearing buffer and resetting valid frame counter.
     */
    @Synchronized
    fun reset() {
        buffer.clear()
        validFrameCount = 0
    }

    val size: Int
        @Synchronized get() = buffer.size
}
