package com.sparkshield.android.protocol

import java.util.Arrays

/**
 * SparkShield 29-Byte Binary Telemetry Protocol frame definition.
 *
 * Wire specification:
 *   Total Length: 29 bytes (packed, big-endian)
 *   Bytes 0-1:   magic (uint16, 0x5353, ASCII 'SS')
 *   Bytes 2-5:   sequence_id (uint32)
 *   Bytes 6-9:   timestamp_ms (uint32)
 *   Byte 10:     event_flags (uint8: bit0=EMP, bit1=OPTICAL, bit2=SURGE, bit3=NORMAL)
 *   Bytes 11-12: peak_mv (uint16)
 *   Bytes 13-14: rise_time_code (uint16, 10 ns/LSB)
 *   Bytes 15-16: decay_time_us (uint16, 1 µs/LSB)
 *   Bytes 17-18: optical_sensor_mv (uint16)
 *   Bytes 19-26: fft_energy_bins (uint8[8])
 *   Bytes 27-28: crc16 (uint16, CRC-16-CCITT over bytes 0..26)
 */
data class TelemetryFrame(
    val sequenceId: Long,
    val timestampMs: Long,
    val eventFlags: Int,
    val peakMv: Int,
    val riseTimeCode: Int,
    val decayTimeUs: Int,
    val opticalSensorMv: Int,
    val fftEnergyBins: ByteArray,
    val magic: Int = FRAME_MAGIC,
    var crc16: Int = 0
) {
    init {
        require(fftEnergyBins.size == 8) {
            "fftEnergyBins must be exactly 8 bytes, got ${fftEnergyBins.size}"
        }
    }

    /** Rise time in nanoseconds (10 ns per LSB). */
    val riseTimeNs: Long
        get() = riseTimeCode.toLong() * 10L

    /** Decay time in milliseconds (1 µs per LSB). */
    val decayTimeMs: Float
        get() = decayTimeUs / 1000.0f

    /** Peak voltage in Volts. */
    val peakV: Float
        get() = peakMv / 1000.0f

    /** Optical sensor voltage in Volts. */
    val opticalSensorV: Float
        get() = opticalSensorMv / 1000.0f

    val isEmp: Boolean
        get() = (eventFlags and FLAG_EMP) != 0

    val isOptical: Boolean
        get() = (eventFlags and FLAG_OPTICAL) != 0

    val isSurge: Boolean
        get() = (eventFlags and FLAG_SURGE) != 0

    val isNormal: Boolean
        get() = (eventFlags and FLAG_NORMAL) != 0

    /**
     * Serializes this frame to 29-byte big-endian packed wire format with CRC-16.
     */
    fun toByteArray(): ByteArray = TelemetryFrameParser.pack(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TelemetryFrame
        if (sequenceId != other.sequenceId) return false
        if (timestampMs != other.timestampMs) return false
        if (eventFlags != other.eventFlags) return false
        if (peakMv != other.peakMv) return false
        if (riseTimeCode != other.riseTimeCode) return false
        if (decayTimeUs != other.decayTimeUs) return false
        if (opticalSensorMv != other.opticalSensorMv) return false
        if (!Arrays.equals(fftEnergyBins, other.fftEnergyBins)) return false
        if (magic != other.magic) return false
        if (crc16 != other.crc16) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequenceId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + eventFlags
        result = 31 * result + peakMv
        result = 31 * result + riseTimeCode
        result = 31 * result + decayTimeUs
        result = 31 * result + opticalSensorMv
        result = 31 * result + Arrays.hashCode(fftEnergyBins)
        result = 31 * result + magic
        result = 31 * result + crc16
        return result
    }

    companion object {
        const val FRAME_MAGIC: Int = 0x5353
        const val FRAME_LENGTH: Int = 29
        const val PAYLOAD_LENGTH_FOR_CRC: Int = 27

        const val FLAG_EMP: Int = 1 shl 0      // 0x01
        const val FLAG_OPTICAL: Int = 1 shl 1  // 0x02
        const val FLAG_SURGE: Int = 1 shl 2    // 0x04
        const val FLAG_NORMAL: Int = 1 shl 3   // 0x08
    }
}
