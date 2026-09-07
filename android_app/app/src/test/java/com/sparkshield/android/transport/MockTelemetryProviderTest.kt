package com.sparkshield.android.transport

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.protocol.Crc16Ccitt
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.TelemetryFrame
import com.sparkshield.android.protocol.TelemetryFrameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTelemetryProviderTest {

    @Test
    fun testDefaultFramesAreNormalAndValid() {
        val provider = MockTelemetryProvider(rateHz = 10.0f, seed = 42L)

        for (i in 0 until 10) {
            val frame = provider.generateNextFrame()
            assertEquals("Sequence must increment", i.toLong(), frame.sequenceId)
            assertTrue("Default frame must be NORMAL", frame.isNormal)
            assertFalse("Must not be EMP", frame.isEmp)
            assertFalse("Must not be OPTICAL", frame.isOptical)
            assertFalse("Must not be SURGE", frame.isSurge)

            // Test wire serialization and CRC validation
            val packed = TelemetryFrameParser.pack(frame)
            assertEquals(29, packed.size)

            val parsed = TelemetryFrameParser.parse(packed)
            assertTrue(parsed is ProtocolResult.Success)
        }
    }

    @Test
    fun testTamperBurstInjectionLifecycle() {
        val provider = MockTelemetryProvider(rateHz = 10.0f, seed = 123L)

        // First 3 frames normal
        for (i in 0 until 3) {
            assertTrue(provider.generateNextFrame().isNormal)
        }

        // Trigger EMP burst of 3 frames
        provider.triggerTamper(ClassLabels.EMP, burstCount = 3)

        // Next 3 frames must be EMP
        for (i in 0 until 3) {
            val f = provider.generateNextFrame()
            assertTrue("Frame must be EMP during burst", f.isEmp)
            assertFalse("Frame must not be NORMAL during burst", f.isNormal)
            assertTrue("EMP peak voltage must be high", f.peakMv >= 20000)
            assertTrue("EMP rise time code must be ultrafast (1..3)", f.riseTimeCode in 1..3)
            assertTrue("EMP decay time must be short (1..15 us)", f.decayTimeUs in 1..15)
        }

        // Fourth frame must revert back to NORMAL
        val revertedFrame = provider.generateNextFrame()
        assertTrue("Frame must revert to NORMAL after burst", revertedFrame.isNormal)
    }

    @Test
    fun testOpticalTamperProperties() {
        val provider = MockTelemetryProvider(rateHz = 10.0f, seed = 777L)
        provider.triggerTamper(ClassLabels.OPTICAL, burstCount = 1)
        val f = provider.generateNextFrame()

        assertTrue(f.isOptical)
        assertTrue("Optical sensor mV must be saturated high", f.opticalSensorMv >= 3200)
    }

    @Test
    fun testSurgeTamperProperties() {
        val provider = MockTelemetryProvider(rateHz = 10.0f, seed = 888L)
        provider.triggerTamper(ClassLabels.SURGE, burstCount = 1)
        val f = provider.generateNextFrame()

        assertTrue(f.isSurge)
        assertTrue("Surge peak must be medium-high (>= 6000 mV)", f.peakMv >= 6000)
    }
}
