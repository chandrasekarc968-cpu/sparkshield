package com.sparkshield.android.inference

import java.util.Arrays

/**
 * Output of edge neural network inference over a (1, 1, 128) feature window.
 */
data class InferenceResult(
    val predictedClass: ClassLabels,
    val confidence: Float,
    val probabilities: FloatArray,
    val latencyMs: Float
) {
    val isTamper: Boolean
        get() = predictedClass.isTamper

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InferenceResult
        if (predictedClass != other.predictedClass) return false
        if (confidence != other.confidence) return false
        if (!Arrays.equals(probabilities, other.probabilities)) return false
        if (latencyMs != other.latencyMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = predictedClass.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + Arrays.hashCode(probabilities)
        result = 31 * result + latencyMs.hashCode()
        return result
    }

    override fun toString(): String {
        return "InferenceResult(class=$predictedClass, confidence=${"%.3f".format(confidence)}, " +
                "latency=${"%.2f".format(latencyMs)}ms, probs=[${probabilities.joinToString { "%.3f".format(it) }}])"
    }
}
