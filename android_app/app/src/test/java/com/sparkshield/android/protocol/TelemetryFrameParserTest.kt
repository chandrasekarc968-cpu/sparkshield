package com.sparkshield.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFrameParserTest {

    private fun createSampleFrame(): TelemetryFrame {
        return TelemetryFrame(
            sequenceId = 42L,
            timestampMs = 4200L,
            eventFlags = TelemetryFrame.FLAG_EMP,
            peakMv = 45000,
            riseTimeCode = 2,
            decayTimeUs = 8,
            opticalSensorMv = 160,
            fftEnergyBins = byteArrayOf(120.toByte(), 150.toByte(), 180.toByte(), 210.toByte(), 230.toByte(), 200.toByte(), 180.toByte(), 150.toByte())
        )
    }

    @Test
    fun testPackAndParseRoundtrip() {
        val original = createSampleFrame()
        val packed = TelemetryFrameParser.pack(original)

        assertEquals("Packed frame must be exactly 29 bytes", 29, packed.size)

        val result = TelemetryFrameParser.parse(packed)
        assertTrue("Parser must succeed for valid packed frame", result is ProtocolResult.Success)

        val parsed = (result as ProtocolResult.Success).frame
        assertEquals(original.sequenceId, parsed.sequenceId)
        assertEquals(original.timestampMs, parsed.timestampMs)
        assertEquals(original.eventFlags, parsed.eventFlags)
        assertEquals(original.peakMv, parsed.peakMv)
        assertEquals(original.riseTimeCode, parsed.riseTimeCode)
        assertEquals(original.decayTimeUs, parsed.decayTimeUs)
        assertEquals(original.opticalSensorMv, parsed.opticalSensorMv)
        assertEquals(original.crc16, parsed.crc16)
        assertTrue("isEmp flag must be true", parsed.isEmp)
        assertFalse("isNormal flag must be false", parsed.isNormal)
    }

    @Test
    fun testRejectMalformedLength() {
        val shortBuffer = ByteArray(28)
        val longBuffer = ByteArray(30)

        val res1 = TelemetryFrameParser.parse(shortBuffer)
        val res2 = TelemetryFrameParser.parse(longBuffer)

        assertTrue("Short buffer must fail", res1 is ProtocolResult.Failure)
        assertTrue("Long buffer must fail", res2 is ProtocolResult.Failure)
    }

    @Test
    fun testRejectMagicMismatch() {
        val frame = createSampleFrame()
        val packed = TelemetryFrameParser.pack(frame)

        // Corrupt magic from 0x5353 ('SS') to 0x1234
        packed[0] = 0x12
        packed[1] = 0x34

        val result = TelemetryFrameParser.parse(packed)
        assertTrue("Magic mismatch must fail", result is ProtocolResult.Failure)
        val failure = result as ProtocolResult.Failure
        assertTrue("Error must be MagicMismatch", failure.error is ProtocolError.MagicMismatch)
    }

    @Test
    fun testRejectCorruptedCrc() {
        val frame = createSampleFrame()
        val packed = TelemetryFrameParser.pack(frame)

        // Corrupt payload byte
        packed[12] = (packed[12].toInt() xor 0xFF).toByte()

        val result = TelemetryFrameParser.parse(packed)
        assertTrue("Corrupted payload must fail CRC check", result is ProtocolResult.Failure)
        val failure = result as ProtocolResult.Failure
        assertTrue("Error must be CrcMismatch", failure.error is ProtocolError.CrcMismatch)
    }

    @Test
    fun testSequenceTrackerContinuityAndDrops() {
        val tracker = SequenceTracker()

        val r1 = tracker.processSequence(100L)
        assertTrue(r1.isContinuous)
        assertEquals(0L, r1.droppedCount)

        val r2 = tracker.processSequence(101L)
        assertTrue(r2.isContinuous)
        assertEquals(0L, r2.droppedCount)

        // Duplicate
        val rDup = tracker.processSequence(101L)
        assertFalse(rDup.isContinuous)
        assertEquals(1L, tracker.totalDuplicates)

        // Drop 3 packets: expected 102, received 105 -> dropped: 102, 103, 104 (3)
        val rDrop = tracker.processSequence(105L)
        assertFalse(rDrop.isContinuous)
        assertEquals(3L, rDrop.droppedCount)
        assertEquals(3L, tracker.totalDropped)
    }
}
