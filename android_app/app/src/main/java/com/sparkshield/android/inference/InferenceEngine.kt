package com.sparkshield.android.inference

/**
 * Clean decoupled abstraction for on-device edge ML model execution.
 *
 * Requirements:
 *   - load(): Result<Unit>
 *   - infer(input: FloatArray): Result<InferenceResult>
 *   - close()
 */
interface InferenceEngine {

    /**
     * Loads the model and validates input/output tensor shapes.
     * Fails with a structured error if the model artifact is missing or invalid.
     */
    suspend fun load(): Result<Unit>

    /**
     * Executes inference on the 128-element normalized feature array (shape [1, 1, 128]).
     * Returns structured Result with InferenceResult or failure.
     */
    suspend fun infer(input: FloatArray): Result<InferenceResult>

    /**
     * Releases ONNX sessions and native runtime resources.
     */
    fun close()
}
