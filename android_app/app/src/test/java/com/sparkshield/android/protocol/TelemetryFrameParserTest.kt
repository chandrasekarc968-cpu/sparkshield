package com.sparkshield.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryFrameParserTest {

    @Before
    fun setup() {
        TelemetryFrameParser.resetInvalidFrameCount()
    }

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
        val packed = original.toByteArray()

        assertEquals("Packed frame must be exactly 29 bytes", 29, packed.size)

        val result = TelemetryFrameParser.parse(packed)
        assertTrue("Parser must succeed for valid packed frame", result is ProtocolResult.Success)

        val parsed = (result as ProtocolResult.Success).value
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
        assertEquals("Invalid frame count must remain 0 on success", 0L, TelemetryFrameParser.invalidFrameCount.get())
    }

    @Test
    fun testRejectNullInput() {
        val initialCount = TelemetryFrameParser.invalidFrameCount.get()
        val res = TelemetryFrameParser.parse(null)

        assertTrue("Null input must return ProtocolResult.Failure", res is ProtocolResult.Failure)
        val failure = res as ProtocolResult.Failure
        assertTrue("Error must be NullInput", failure.error is ProtocolError.NullInput)
        assertEquals("Invalid frame count must increment", initialCount + 1, TelemetryFrameParser.invalidFrameCount.get())
    }

    @Test
    fun testRejectMalformedLength() {
        val initialCount = TelemetryFrameParser.invalidFrameCount.get()
        val shortBuffer = ByteArray(28)
        val longBuffer = ByteArray(30)

        val res1 = TelemetryFrameParser.parse(shortBuffer)
        val res2 = TelemetryFrameParser.parse(longBuffer)

        assertTrue("Short buffer must fail", res1 is ProtocolResult.Failure)
        assertTrue("Long buffer must fail", res2 is ProtocolResult.Failure)
        assertEquals("Invalid frame count must increment by 2", initialCount + 2, TelemetryFrameParser.invalidFrameCount.get())
    }

    @Test
    fun testRejectMagicMismatch() {
        val initialCount = TelemetryFrameParser.invalidFrameCount.get()
        val frame = createSampleFrame()
        val packed = frame.toByteArray()

        // Corrupt magic from 0x5353 to 0x1234
        packed[0] = 0x12
        packed[1] = 0x34

        val result = TelemetryFrameParser.parse(packed)
        assertTrue("Magic mismatch must fail", result is ProtocolResult.Failure)
        val failure = result as ProtocolResult.Failure
        assertTrue("Error must be MagicMismatch", failure.error is ProtocolError.MagicMismatch)
        assertEquals(initialCount + 1, TelemetryFrameParser.invalidFrameCount.get())
    }

    @Test
    fun testRejectCorruptedCrc() {
        val initialCount = TelemetryFrameParser.invalidFrameCount.get()
        val frame = createSampleFrame()
        val packed = frame.toByteArray()

        // Corrupt a payload byte
        packed[12] = (packed[12].toInt() xor 0xFF).toByte()

        val result = TelemetryFrameParser.parse(packed)
        assertTrue("Corrupted payload must fail CRC check", result is ProtocolResult.Failure)
        val failure = result as ProtocolResult.Failure
        assertTrue("Error must be CrcMismatch", failure.error is ProtocolError.CrcMismatch)
        assertEquals(initialCount + 1, TelemetryFrameParser.invalidFrameCount.get())
    }

    @Test
    fun testRejectInvalidEventFlags() {
        val initialCount = TelemetryFrameParser.invalidFrameCount.get()
        val frame = createSampleFrame()

        // 1. All zero flags
        val packedZero = frame.toByteArray()
        packedZero[10] = 0x00.toByte()
        // Recompute CRC so CRC doesn't fail first
        val crcZero = Crc16Ccitt.calculate(packedZero, 0, 27)
        packedZero[27] = (crcZero shr 8).toByte()
        packedZero[28] = (crcZero and 0xFF).toByte()
        val resZero = TelemetryFrameParser.parse(packedZero)
        assertTrue("Zero flags must fail", resZero is ProtocolResult.Failure)
        assertTrue((resZero as ProtocolResult.Failure).error is ProtocolError.InvalidEventFlags)

        // 2. Undefined upper bits set (e.g. 0x10)
        val packedUpper = frame.toByteArray()
        packedUpper[10] = 0x18.toByte()
        val crcUpper = Crc16Ccitt.calculate(packedUpper, 0, 27)
        packedUpper[27] = (crcUpper shr 8).toByte()
        packedUpper[28] = (crcUpper and 0xFF).toByte()
        val resUpper = TelemetryFrameParser.parse(packedUpper)
        assertTrue("Upper flag bits must fail", resUpper is ProtocolResult.Failure)
        assertTrue((resUpper as ProtocolResult.Failure).error is ProtocolError.InvalidEventFlags)

        // 3. NORMAL (0x08) + EMP (0x01) conflict
        val packedConflict = frame.toByteArray()
        packedConflict[10] = 0x09.toByte()
        val crcConflict = Crc16Ccitt.calculate(packedConflict, 0, 27)
        packedConflict[27] = (crcConflict shr 8).toByte()
        packedConflict[28] = (crcConflict and 0xFF).toByte()
        val resConflict = TelemetryFrameParser.parse(packedConflict)
        assertTrue("NORMAL + EMP conflict must fail", resConflict is ProtocolResult.Failure)
        assertTrue((resConflict as ProtocolResult.Failure).error is ProtocolError.InvalidEventFlags)

        assertEquals(initialCount + 3, TelemetryFrameParser.invalidFrameCount.get())
    }

    @Test
    fun testSequenceTrackerProcessStatus() {
        val tracker = SequenceTracker()

        val s1 = tracker.process(100L)
        assertTrue("First packet must be Continuous", s1 is SequenceStatus.Continuous)
        assertTrue(s1.isContinuous)

        val s2 = tracker.process(101L)
        assertTrue("Monotonic sequence must be Continuous", s2 is SequenceStatus.Continuous)

        // Duplicate
        val sDup = tracker.process(101L)
        assertTrue("Duplicate sequence must be Duplicate", sDup is SequenceStatus.Duplicate)
        assertFalse(sDup.isContinuous)
        assertEquals(1L, tracker.totalDuplicates)

        // Gap / dropped: expected 102, received 105 -> dropped: 102, 103, 104 (3 packets)
        val sGap = tracker.process(105L)
        assertTrue("Jump in sequence must be Gap", sGap is SequenceStatus.Gap)
        assertFalse(sGap.isContinuous)
        val gap = sGap as SequenceStatus.Gap
        assertEquals(3L, gap.droppedCount)
        assertEquals(3L, tracker.totalDropped)

        // Out of order / old packet
        val sOld = tracker.process(90L)
        assertTrue("Old packet must be OutOfOrder", sOld is SequenceStatus.OutOfOrder)
        assertEquals(1L, tracker.totalOutOfOrder)
    }
}
