package com.sparkshield.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Big-endian binary parser and serializer for the SparkShield 29-byte telemetry frame.
 *
 * Wire format (big-endian):
 *   Offset 00: uint16 magic (0x5353)
 *   Offset 02: uint32 sequence_id
 *   Offset 06: uint32 timestamp_ms
 *   Offset 10: uint8  event_flags (bit 0=EMP, bit 1=OPTICAL, bit 2=SURGE, bit 3=NORMAL)
 *   Offset 11: uint16 peak_mv
 *   Offset 13: uint16 rise_time_code (10 ns/LSB)
 *   Offset 15: uint16 decay_time_us (1 µs/LSB)
 *   Offset 17: uint16 optical_sensor_mv
 *   Offset 19: uint8[8] fft_energy_bins
 *   Offset 27: uint16 crc16 (CRC-16-CCITT over offsets 0..26)
 */
object TelemetryFrameParser {

    /**
     * Counter tracking the total number of malformed, corrupted, or rejected frames.
     * Guaranteed to never crash callers upon receipt of bad frames.
     */
    val invalidFrameCount = AtomicLong(0L)

    /**
     * Parse and strictly validate a 29-byte binary buffer.
     *
     * Rejection criteria:
     *   - null or incorrectly sized input
     *   - invalid magic (must be 0x5353)
     *   - invalid CRC (CRC-16-CCITT over bytes 0..26)
     *   - invalid event flag combinations (unknown bits, conflicting flags)
     *   - out-of-range values
     *   - malformed frames
     *
     * @param bytes Raw byte array to decode.
     * @return [ProtocolResult.Success] containing validated [TelemetryFrame], or [ProtocolResult.Failure].
     */
    fun parse(bytes: ByteArray?): ProtocolResult<TelemetryFrame> {
        if (bytes == null) {
            invalidFrameCount.incrementAndGet()
            return ProtocolResult.Failure(ProtocolError.NullInput, null)
        }

        if (bytes.size != TelemetryFrame.FRAME_LENGTH) {
            invalidFrameCount.incrementAndGet()
            return ProtocolResult.Failure(
                ProtocolError.MalformedLength(TelemetryFrame.FRAME_LENGTH, bytes.size),
                bytes
            )
        }

        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            // 1. Validate magic word (0x5353)
            val magic = buffer.short.toInt() and 0xFFFF
            if (magic != TelemetryFrame.FRAME_MAGIC) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.MagicMismatch(TelemetryFrame.FRAME_MAGIC, magic),
                    bytes
                )
            }

            // 2. Validate CRC-16-CCITT before trusting payload fields
            val expectedCrc = ((bytes[27].toInt() and 0xFF) shl 8) or (bytes[28].toInt() and 0xFF)
            val calculatedCrc = Crc16Ccitt.calculate(bytes, 0, TelemetryFrame.PAYLOAD_LENGTH_FOR_CRC)
            if (calculatedCrc != expectedCrc) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.CrcMismatch(expectedCrc, calculatedCrc),
                    bytes
                )
            }

            // 3. Unsigned-safe decoding
            val sequenceId = buffer.int.toLong() and 0xFFFFFFFFL
            val timestampMs = buffer.int.toLong() and 0xFFFFFFFFL
            val eventFlags = buffer.get().toInt() and 0xFF

            // 4. Validate event flag combinations
            // Only bits 0..3 are defined (mask 0x0F)
            if ((eventFlags and 0xF0) != 0) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.InvalidEventFlags(eventFlags, "Undefined upper flag bits set"),
                    bytes
                )
            }
            if (eventFlags == 0) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.InvalidEventFlags(eventFlags, "No event flag bits asserted"),
                    bytes
                )
            }
            // NORMAL (bit 3) cannot be asserted simultaneously with tamper flags (bits 0..2)
            val isNormalSet = (eventFlags and TelemetryFrame.FLAG_NORMAL) != 0
            val isTamperSet = (eventFlags and (TelemetryFrame.FLAG_EMP or TelemetryFrame.FLAG_OPTICAL or TelemetryFrame.FLAG_SURGE)) != 0
            if (isNormalSet && isTamperSet) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.InvalidEventFlags(eventFlags, "NORMAL flag asserted simultaneously with tamper flags"),
                    bytes
                )
            }
            // Exactly one primary class flag should be set
            if (Integer.bitCount(eventFlags) > 1) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.InvalidEventFlags(eventFlags, "Multiple conflicting class flags asserted"),
                    bytes
                )
            }

            // 5. Unsigned 16-bit values
            val peakMv = buffer.short.toInt() and 0xFFFF
            val riseTimeCode = buffer.short.toInt() and 0xFFFF
            val decayTimeUs = buffer.short.toInt() and 0xFFFF
            val opticalSensorMv = buffer.short.toInt() and 0xFFFF

            // 6. Range checks
            if (peakMv !in 0..65535) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.OutOfRangeValue("peak_mv", peakMv, "0..65535"),
                    bytes
                )
            }
            if (opticalSensorMv !in 0..65535) {
                invalidFrameCount.incrementAndGet()
                return ProtocolResult.Failure(
                    ProtocolError.OutOfRangeValue("optical_sensor_mv", opticalSensorMv, "0..65535"),
                    bytes
                )
            }

            val fftEnergyBins = ByteArray(8)
            buffer.get(fftEnergyBins)

            val frame = TelemetryFrame(
                sequenceId = sequenceId,
                timestampMs = timestampMs,
                eventFlags = eventFlags,
                peakMv = peakMv,
                riseTimeCode = riseTimeCode,
                decayTimeUs = decayTimeUs,
                opticalSensorMv = opticalSensorMv,
                fftEnergyBins = fftEnergyBins,
                magic = magic,
                crc16 = expectedCrc
            )

            ProtocolResult.Success(frame)
        } catch (t: Throwable) {
            invalidFrameCount.incrementAndGet()
            ProtocolResult.Failure(
                ProtocolError.MalformedFrame(t.message ?: "Failed to unpack binary frame"),
                bytes
            )
        }
    }

    /**
     * Serializes a TelemetryFrame into the exact 29-byte big-endian wire format,
     * computing and appending CRC-16-CCITT over bytes 0..26.
     *
     * @param frame The TelemetryFrame to pack.
     * @return 29-byte packed ByteArray.
     */
    fun pack(frame: TelemetryFrame): ByteArray {
        val bytes = ByteArray(TelemetryFrame.FRAME_LENGTH)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        buffer.putShort(TelemetryFrame.FRAME_MAGIC.toShort())
        buffer.putInt((frame.sequenceId and 0xFFFFFFFFL).toInt())
        buffer.putInt((frame.timestampMs and 0xFFFFFFFFL).toInt())
        buffer.put((frame.eventFlags and 0xFF).toByte())
        buffer.putShort((frame.peakMv and 0xFFFF).toShort())
        buffer.putShort((frame.riseTimeCode and 0xFFFF).toShort())
        buffer.putShort((frame.decayTimeUs and 0xFFFF).toShort())
        buffer.putShort((frame.opticalSensorMv and 0xFFFF).toShort())
        buffer.put(frame.fftEnergyBins, 0, 8)

        // Compute CRC-16-CCITT over first 27 bytes
        val calculatedCrc = Crc16Ccitt.calculate(bytes, 0, TelemetryFrame.PAYLOAD_LENGTH_FOR_CRC)
        frame.crc16 = calculatedCrc
        buffer.putShort((calculatedCrc and 0xFFFF).toShort())

        return bytes
    }

    /**
     * Resets the invalid frame counter to zero.
     */
    fun resetInvalidFrameCount() {
        invalidFrameCount.set(0L)
    }
}
