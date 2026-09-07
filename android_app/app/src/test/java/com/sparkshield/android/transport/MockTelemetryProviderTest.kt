package com.sparkshield.android.transport

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.TelemetryFrame
import com.sparkshield.android.protocol.TelemetryFrameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTelemetryProviderTest {

    @Test
    fun testDefaultDemoSequencePattern() {
        val provider = MockTelemetryProvider(
            intervalMs = 100L,
            seed = 42L,
            useDefaultDemoSequence = true
        )

        // 1. 20 frames of NORMAL
        for (i in 0 until 20) {
            val f = provider.generateNextFrame()
            assertTrue("Frame $i must be NORMAL", f.isNormal)
        }

        // 2. 8 frames of EMP
        for (i in 0 until 8) {
            val f = provider.generateNextFrame()
            assertTrue("Frame ${20 + i} must be EMP", f.isEmp)
            assertTrue("EMP peak voltage must be high", f.peakMv in 20000..65535)
            assertTrue("EMP rise time code must be ultrafast (1..3)", f.riseTimeCode in 1..3)
            assertTrue("EMP decay time must be short (1..15 us)", f.decayTimeUs in 1..15)
        }

        // 3. 20 frames of NORMAL
        for (i in 0 until 20) {
            val f = provider.generateNextFrame()
            assertTrue("Frame ${28 + i} must be NORMAL", f.isNormal)
        }

        // 4. 8 frames of OPTICAL
        for (i in 0 until 8) {
            val f = provider.generateNextFrame()
            assertTrue("Frame ${48 + i} must be OPTICAL", f.isOptical)
            assertTrue("Optical sensor must be saturated high", f.opticalSensorMv in 3200..5000)
        }

        // 5. 20 frames of NORMAL
        for (i in 0 until 20) {
            val f = provider.generateNextFrame()
            assertTrue("Frame ${56 + i} must be NORMAL", f.isNormal)
        }

        // 6. 8 frames of SURGE
        for (i in 0 until 8) {
            val f = provider.generateNextFrame()
            assertTrue("Frame ${76 + i} must be SURGE", f.isSurge)
            assertTrue("Surge peak must be medium-high", f.peakMv in 6000..25000)
        }

        // 7. Loop repeats: next frame must be NORMAL
        val repeated = provider.generateNextFrame()
        assertTrue("Sequence must repeat back to NORMAL", repeated.isNormal)
    }

    @Test
    fun testValidWireFormatAndCrcOnAllGeneratedFrames() {
        val provider = MockTelemetryProvider(intervalMs = 100L, seed = 100L)

        for (i in 0 until 50) {
            val frameBytes = provider.generateNextFrameBytes()
            assertEquals("Packed frame must be 29 bytes", 29, frameBytes.size)

            val parsedResult = TelemetryFrameParser.parse(frameBytes)
            assertTrue("Every generated frame must pass parsing & CRC validation", parsedResult is ProtocolResult.Success)
        }
    }

    @Test
    fun testMalformedFrameInjectionAndRecovery() {
        val provider = MockTelemetryProvider(intervalMs = 100L, seed = 555L)

        // Generate normal valid frame
        val validBytes = provider.generateNextFrameBytes()
        assertTrue(TelemetryFrameParser.parse(validBytes) is ProtocolResult.Success)

        // Inject malformed frame
        provider.nextFrameMalformed = true
        val malformedBytes = provider.generateNextFrameBytes()
        val failResult = TelemetryFrameParser.parse(malformedBytes)
        assertTrue("Injected malformed frame must be rejected", failResult is ProtocolResult.Failure)

        // Subsequent frame must automatically recover to valid data
        val recoveredBytes = provider.generateNextFrameBytes()
        val recoveredResult = TelemetryFrameParser.parse(recoveredBytes)
        assertTrue("Subsequent frame must recover cleanly", recoveredResult is ProtocolResult.Success)
    }

    @Test
    fun testDeterministicSeedReproduction() {
        val provider1 = MockTelemetryProvider(seed = 9999L)
        val provider2 = MockTelemetryProvider(seed = 9999L)

        for (i in 0 until 10) {
            val f1 = provider1.generateNextFrame()
            val f2 = provider2.generateNextFrame()

            assertEquals(f1.sequenceId, f2.sequenceId)
            assertEquals(f1.peakMv, f2.peakMv)
            assertEquals(f1.riseTimeCode, f2.riseTimeCode)
            assertEquals(f1.decayTimeUs, f2.decayTimeUs)
            assertEquals(f1.opticalSensorMv, f2.opticalSensorMv)
        }
    }
}
