package com.sparkshield.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Big-endian binary parser and serializer for the SparkShield 29-byte telemetry frame.
 *
 * Wire format (big-endian):
 *   Offset 00: uint16 magic (0x5353)
 *   Offset 02: uint32 sequence_id
 *   Offset 06: uint32 timestamp_ms
 *   Offset 10: uint8  event_flags
 *   Offset 11: uint16 peak_mv
 *   Offset 13: uint16 rise_time_code (10 ns/LSB)
 *   Offset 15: uint16 decay_time_us (1 µs/LSB)
 *   Offset 17: uint16 optical_sensor_mv
 *   Offset 19: uint8[8] fft_energy_bins
 *   Offset 27: uint16 crc16 (CRC-16-CCITT over offsets 0..26)
 */
object TelemetryFrameParser {

    /**
     * Parse and strictly validate a 29-byte binary buffer.
     *
     * @param data Byte array containing the frame.
     * @return [ProtocolResult.Success] on valid frame, [ProtocolResult.Failure] otherwise.
     */
    fun parse(data: ByteArray): ProtocolResult {
        if (data.size != TelemetryFrame.FRAME_LENGTH) {
            return ProtocolResult.Failure(
                ProtocolError.MalformedLength(TelemetryFrame.FRAME_LENGTH, data.size),
                data
            )
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Read magic
        val magic = buffer.short.toInt() and 0xFFFF
        if (magic != TelemetryFrame.FRAME_MAGIC) {
            return ProtocolResult.Failure(
                ProtocolError.MagicMismatch(TelemetryFrame.FRAME_MAGIC, magic),
                data
            )
        }

        val sequenceId = buffer.int.toLong() and 0xFFFFFFFFL
        val timestampMs = buffer.int.toLong() and 0xFFFFFFFFL
        val eventFlags = buffer.get().toInt() and 0xFF
        val peakMv = buffer.short.toInt() and 0xFFFF
        val riseTimeCode = buffer.short.toInt() and 0xFFFF
        val decayTimeUs = buffer.short.toInt() and 0xFFFF
        val opticalSensorMv = buffer.short.toInt() and 0xFFFF

        val fftEnergyBins = ByteArray(8)
        buffer.get(fftEnergyBins)

        val expectedCrc = buffer.short.toInt() and 0xFFFF
        val calculatedCrc = Crc16Ccitt.compute(data, 0, TelemetryFrame.PAYLOAD_LENGTH_FOR_CRC)

        if (calculatedCrc != expectedCrc) {
            return ProtocolResult.Failure(
                ProtocolError.CrcMismatch(expectedCrc, calculatedCrc),
                data
            )
        }

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

        return ProtocolResult.Success(frame)
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
        val calculatedCrc = Crc16Ccitt.compute(bytes, 0, TelemetryFrame.PAYLOAD_LENGTH_FOR_CRC)
        frame.crc16 = calculatedCrc
        buffer.putShort((calculatedCrc and 0xFFFF).toShort())

        return bytes
    }
}
