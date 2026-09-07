package com.sparkshield.android.protocol

import java.util.Arrays

/**
 * Result of parsing a raw byte array into a SparkShield TelemetryFrame.
 */
sealed interface ProtocolResult {
    data class Success(val frame: TelemetryFrame) : ProtocolResult

    data class Failure(val error: ProtocolError, val rawBytes: ByteArray) : ProtocolResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Failure
            if (error != other.error) return false
            if (!Arrays.equals(rawBytes, other.rawBytes)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = error.hashCode()
            result = 31 * result + Arrays.hashCode(rawBytes)
            return result
        }
    }
}

/**
 * Protocol error categories for SparkShield frame decoding.
 */
sealed class ProtocolError(val message: String) {
    data class MalformedLength(val expected: Int, val actual: Int) :
        ProtocolError("Invalid frame length: expected $expected bytes, got $actual")

    data class MagicMismatch(val expected: Int, val actual: Int) :
        ProtocolError("Magic mismatch: expected 0x${expected.toString(16).uppercase()}, got 0x${actual.toString(16).uppercase()}")

    data class CrcMismatch(val expected: Int, val actual: Int) :
        ProtocolError("CRC mismatch: expected 0x${expected.toString(16).uppercase()}, calculated 0x${actual.toString(16).uppercase()}")

    data class GeneralError(val details: String) :
        ProtocolError(details)
}
