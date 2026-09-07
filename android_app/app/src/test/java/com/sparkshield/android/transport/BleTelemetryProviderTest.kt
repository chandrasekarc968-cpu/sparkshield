package com.sparkshield.android.transport

import com.sparkshield.android.protocol.Crc16Ccitt
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.TelemetryFrameParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BleTelemetryProviderTest {

    @Test
    fun testInitialStateAndTransitions() {
        val provider = BleTelemetryProvider(context = null)
        assertTrue(
            "Initial state must be Disconnected",
            provider.connectionState.value is BleConnectionState.Disconnected
        )

        // State transition progression
        provider.setConnectionStateForTesting(BleConnectionState.Scanning)
        assertEquals(BleConnectionState.Scanning, provider.connectionState.value)

        provider.setConnectionStateForTesting(BleConnectionState.Connecting)
        assertEquals(BleConnectionState.Connecting, provider.connectionState.value)

        provider.setConnectionStateForTesting(BleConnectionState.Connected)
        assertEquals(BleConnectionState.Connected, provider.connectionState.value)

        provider.setConnectionStateForTesting(BleConnectionState.ServicesDiscovered)
        assertEquals(BleConnectionState.ServicesDiscovered, provider.connectionState.value)

        provider.setConnectionStateForTesting(BleConnectionState.NotificationsEnabled)
        assertEquals(BleConnectionState.NotificationsEnabled, provider.connectionState.value)

        provider.setConnectionStateForTesting(BleConnectionState.Streaming)
        assertEquals(BleConnectionState.Streaming, provider.connectionState.value)
    }

    @Test
    fun testNotificationSetupAndUuids() {
        assertEquals(
            "SparkShield Service UUID must match protocol spec",
            UUID.fromString("1A860001-C7E2-432A-8C2A-8B6C7741E001"),
            BleTelemetryProvider.SERVICE_UUID
        )
        assertEquals(
            "SparkShield Telemetry Characteristic UUID must match protocol spec",
            UUID.fromString("1A860002-C7E2-432A-8C2A-8B6C7741E001"),
            BleTelemetryProvider.CHARACTERISTIC_UUID
        )
        assertEquals(
            "CCCD Descriptor UUID must be standard 0x2902",
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BleTelemetryProvider.CCCD_UUID
        )
        assertEquals("SparkShield-Core", BleTelemetryProvider.TARGET_DEVICE_NAME)
    }

    @Test
    fun testPermissionDeniedBehavior() = runBlocking {
        val provider = BleTelemetryProvider(context = null)
        assertFalse(provider.hasRequiredPermissions())

        val missing = provider.getMissingPermissions()
        assertTrue("Must report missing BLUETOOTH_SCAN", missing.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue("Must report missing BLUETOOTH_CONNECT", missing.contains("android.permission.BLUETOOTH_CONNECT"))

        // Starting without permissions should transition to PermissionDenied
        provider.start()
        assertTrue(
            "State must transition to PermissionDenied",
            provider.connectionState.value is BleConnectionState.PermissionDenied
        )
    }

    @Test
    fun testReconnectBackoffBoundedExponential() {
        val provider = BleTelemetryProvider(context = null)

        // 1s * 2^min(attempt, 4), capped at 15000ms
        assertEquals(1000L, provider.calculateBackoffDelayMs(0))
        assertEquals(2000L, provider.calculateBackoffDelayMs(1))
        assertEquals(4000L, provider.calculateBackoffDelayMs(2))
        assertEquals(8000L, provider.calculateBackoffDelayMs(3))
        assertEquals(15000L, provider.calculateBackoffDelayMs(4)) // 16000 capped to 15000
        assertEquals(15000L, provider.calculateBackoffDelayMs(5))
        assertEquals(15000L, provider.calculateBackoffDelayMs(10))
    }

    @Test
    fun testValidFrameProcessingAndChannelDelivery() {
        val provider = BleTelemetryProvider(context = null)
        val mock = MockTelemetryProvider(seed = 1234L)
        val validBytes = mock.generateNextFrameBytes()
        assertEquals(29, validBytes.size)

        provider.handleIncomingFrame(validBytes)

        val received = provider.frameChannel.tryReceive().getOrNull()
        assertNotNull("Valid frame must be accepted into bounded channel", received)
        assertEquals(29, received!!.size)
        assertTrue(provider.lastFrameTimeMs.value > 0L)
    }

    @Test
    fun testMalformedFrameRejection_WrongLength() {
        val provider = BleTelemetryProvider(context = null)
        val shortBytes = ByteArray(20) { 0x01 }

        provider.handleIncomingFrame(shortBytes)

        val received = provider.frameChannel.tryReceive().getOrNull()
        assertNull("Short frame must be rejected and discarded", received)
    }

    @Test
    fun testMalformedFrameRejection_BadMagic() {
        val provider = BleTelemetryProvider(context = null)
        val mock = MockTelemetryProvider(seed = 42L)
        val frameBytes = mock.generateNextFrameBytes()

        // Corrupt magic from 0x5353 to 0x1234
        frameBytes[0] = 0x12
        frameBytes[1] = 0x34

        provider.handleIncomingFrame(frameBytes)

        val received = provider.frameChannel.tryReceive().getOrNull()
        assertNull("Frame with invalid magic must be rejected", received)
    }

    @Test
    fun testMalformedFrameRejection_CrcFailure() {
        val provider = BleTelemetryProvider(context = null)
        val mock = MockTelemetryProvider(seed = 99L)
        val frameBytes = mock.generateNextFrameBytes()

        // Corrupt CRC-16 (bytes 27-28)
        frameBytes[27] = (frameBytes[27].toInt() xor 0xFF).toByte()

        provider.handleIncomingFrame(frameBytes)

        val received = provider.frameChannel.tryReceive().getOrNull()
        assertNull("Frame with CRC mismatch must be rejected", received)
    }

    @Test
    fun testDuplicateAndGapSequenceTracking() {
        val provider = BleTelemetryProvider(context = null)

        fun createRawFrame(seqId: Long): ByteArray {
            val buf = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(0x5353.toShort())
            buf.putInt(seqId.toInt())
            buf.putInt(1000)
            buf.put(0x08.toByte()) // NORMAL
            buf.putShort(3250.toShort())
            buf.putShort(500.toShort())
            buf.putShort(50.toShort())
            buf.putShort(150.toShort())
            buf.put(ByteArray(8) { 10 })
            val crc = Crc16Ccitt.compute(buf.array(), 0, 27)
            buf.putShort(crc.toShort())
            return buf.array()
        }

        // Frame 1
        provider.handleIncomingFrame(createRawFrame(1L))
        assertEquals(0L, provider.droppedFrameCount.value)

        // Duplicate Frame 1 (duplicate should not increase dropped frame count)
        provider.handleIncomingFrame(createRawFrame(1L))
        assertEquals(0L, provider.droppedFrameCount.value)

        // Gap: Frame 5 (missed 2, 3, 4 -> 3 dropped frames)
        provider.handleIncomingFrame(createRawFrame(5L))
        assertEquals(3L, provider.droppedFrameCount.value)

        // Frame 6 (sequential, no drops)
        provider.handleIncomingFrame(createRawFrame(6L))
        assertEquals(3L, provider.droppedFrameCount.value)
    }

    @Test
    fun testSequenceRolloverAtUint32Boundary() {
        val provider = BleTelemetryProvider(context = null)

        fun createRawFrame(seqId: Long): ByteArray {
            val buf = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(0x5353.toShort())
            buf.putInt(seqId.toInt())
            buf.putInt(1000)
            buf.put(0x08.toByte()) // NORMAL
            buf.putShort(3250.toShort())
            buf.putShort(500.toShort())
            buf.putShort(50.toShort())
            buf.putShort(150.toShort())
            buf.put(ByteArray(8) { 10 })
            val crc = Crc16Ccitt.compute(buf.array(), 0, 27)
            buf.putShort(crc.toShort())
            return buf.array()
        }

        // 1. Initial frame at max uint32 (4294967295L)
        provider.handleIncomingFrame(createRawFrame(0xFFFFFFFFL))
        assertEquals(0L, provider.droppedFrameCount.value)

        // 2. Rollover to 0L: must be continuous with 0 dropped frames
        provider.handleIncomingFrame(createRawFrame(0L))
        assertEquals(0L, provider.droppedFrameCount.value)

        // 3. Rollover from 0xFFFFFFFEL to 2L (missed 0xFFFFFFFFL, 0L, 1L -> 3 dropped)
        val providerWithGap = BleTelemetryProvider(context = null)
        providerWithGap.handleIncomingFrame(createRawFrame(0xFFFFFFFEL))
        assertEquals(0L, providerWithGap.droppedFrameCount.value)

        providerWithGap.handleIncomingFrame(createRawFrame(2L))
        assertEquals(3L, providerWithGap.droppedFrameCount.value)
    }

    @Test
    fun testInvalidFlagsNeverEnqueued() {
        val provider = BleTelemetryProvider(context = null)

        // Create frame with conflict flags (NORMAL 0x08 | EMP 0x01 = 0x09)
        val buf = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x5353.toShort())
        buf.putInt(100)
        buf.putInt(1000)
        buf.put(0x09.toByte()) // Invalid conflict flags
        buf.putShort(3250.toShort())
        buf.putShort(500.toShort())
        buf.putShort(50.toShort())
        buf.putShort(150.toShort())
        buf.put(ByteArray(8) { 10 })
        val crc = Crc16Ccitt.compute(buf.array(), 0, 27)
        buf.putShort(crc.toShort())

        provider.handleIncomingFrame(buf.array())

        val received = provider.frameChannel.tryReceive().getOrNull()
        assertNull("Invalid frame must never reach channel or inference", received)
    }

    @Test
    fun testCleanShutdown() = runBlocking {
        val provider = BleTelemetryProvider(context = null)
        provider.setConnectionStateForTesting(BleConnectionState.Streaming)

        provider.stop()

        assertTrue(
            "State after stop must be Disconnected",
            provider.connectionState.value is BleConnectionState.Disconnected
        )
        val disconnected = provider.connectionState.value as BleConnectionState.Disconnected
        assertEquals("Manually stopped", disconnected.reason)
    }
}
