package com.sparkshield.android.inference

import android.content.Context

/**
 * Clean decoupled abstraction for on-device ML model execution.
 * Allows switching between CPU ONNX Runtime, mock test engines,
 * and future Qualcomm QNN / Hexagon HTP accelerators without altering service logic.
 */
interface InferenceEngine {

    /**
     * Initialize engine and load model resources.
     *
     * @param context Application context used to access asset files.
     * @return True if initialized successfully.
     */
    suspend fun initialize(context: Context): Boolean

    /**
     * Execute inference over a 128-element normalized feature array (1, 1, 128).
     *
     * @param featureTensor Normalized input tensor.
     * @return [InferenceResult] with predicted class, confidence, softmax probabilities, and latency.
     */
    suspend fun predict(featureTensor: FloatArray): InferenceResult

    /**
     * Flag indicating whether the engine is ready to accept prediction requests.
     */
    val isReady: Boolean

    /**
     * Release model sessions, memory buffers, and native runtime handles.
     */
    fun close()
}
