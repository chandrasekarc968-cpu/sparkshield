package com.sparkshield.android.protocol

/**
 * Generic result representing either successful decoding [ProtocolResult.Success]
 * or a structured failure [ProtocolResult.Failure].
 *
 * @param T The type of payload contained on success.
 */
sealed interface ProtocolResult<out T> {
    data class Success<out T>(val value: T) : ProtocolResult<T> {
        // Alias for frame property to maintain backward compatibility
        val frame: T get() = value
    }

    data class Failure(val error: ProtocolError, val rawBytes: ByteArray? = null) : ProtocolResult<Nothing> {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Failure
            if (error != other.error) return false
            if (rawBytes != null) {
                if (other.rawBytes == null) return false
                if (!rawBytes.contentEquals(other.rawBytes)) return false
            } else if (other.rawBytes != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = error.hashCode()
            result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
            return result
        }
    }
}

/**
 * Structured protocol errors matching strict rejection criteria:
 * - null or incorrectly sized input
 * - invalid magic
 * - invalid CRC
 * - invalid event flag combinations
 * - out-of-range values
 * - malformed frames
 */
sealed class ProtocolError(val message: String) {
    object NullInput : ProtocolError("Input byte array cannot be null")

    data class MalformedLength(val expected: Int, val actual: Int) :
        ProtocolError("Invalid frame length: expected $expected bytes, got $actual")

    data class MagicMismatch(val expected: Int, val actual: Int) :
        ProtocolError("Magic mismatch: expected 0x${expected.toString(16).uppercase()}, got 0x${actual.toString(16).uppercase()}")

    data class CrcMismatch(val expected: Int, val actual: Int) :
        ProtocolError("CRC mismatch: expected 0x${expected.toString(16).uppercase()}, calculated 0x${actual.toString(16).uppercase()}")

    data class InvalidEventFlags(val flags: Int, val reason: String) :
        ProtocolError("Invalid event flags 0x${flags.toString(16).uppercase()}: $reason")

    data class OutOfRangeValue(val fieldName: String, val value: Number, val range: String) :
        ProtocolError("Out-of-range value in $fieldName: $value (allowed $range)")

    data class MalformedFrame(val details: String) :
        ProtocolError("Malformed frame: $details")
}
