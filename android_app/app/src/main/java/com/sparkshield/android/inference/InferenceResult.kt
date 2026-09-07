package com.sparkshield.android.inference

import java.util.Arrays

/**
 * Output of edge neural network inference over a (1, 1, 128) feature window.
 *
 * Requirements:
 *   - label: String ("NORMAL", "EMP", "OPTICAL", "SURGE")
 *   - classIndex: Int (0, 1, 2, 3)
 *   - probabilities: FloatArray (size 4)
 *   - confidence: Float (0.0 .. 1.0)
 *   - inferenceTimeUs: Long (duration in microseconds)
 */
data class InferenceResult(
    val label: String,
    val classIndex: Int,
    val probabilities: FloatArray,
    val confidence: Float,
    val inferenceTimeUs: Long
) {
    val isTamper: Boolean
        get() = classIndex != ClassLabels.NORMAL.id

    val latencyMs: Float
        get() = inferenceTimeUs / 1000.0f

    val predictedClass: ClassLabels
        get() = ClassLabels.fromId(classIndex)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InferenceResult
        if (label != other.label) return false
        if (classIndex != other.classIndex) return false
        if (confidence != other.confidence) return false
        if (inferenceTimeUs != other.inferenceTimeUs) return false
        if (!Arrays.equals(probabilities, other.probabilities)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + classIndex
        result = 31 * result + Arrays.hashCode(probabilities)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + inferenceTimeUs.hashCode()
        return result
    }

    override fun toString(): String {
        return "InferenceResult(label=$label, classIndex=$classIndex, confidence=${"%.3f".format(confidence)}, " +
                "time=${inferenceTimeUs}µs, probs=[${probabilities.joinToString { "%.3f".format(it) }}])"
    }
}
