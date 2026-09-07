package com.sparkshield.android.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryWsMessageTest {

    @Test
    fun testJsonSerializationAllFieldsPresent() {
        val msg = TelemetryWsMessage(
            seqId = 1001L,
            timestampMs = 1725700000123L,
            eventFlags = 0x08, // NORMAL
            peakMv = 3250,
            riseTimeNs = 250L,
            decayTimeUs = 500,
            opticalMv = 300,
            fftBins = listOf(10, 20, 30, 40, 50, 40, 30, 20),
            classification = "NORMAL",
            confidence = 0.9850f,
            inferenceTimeUs = 450L,
            tamperDetected = false
        )

        val json = msg.toJson()

        assertTrue(json.contains("\"seqId\":1001"))
        assertTrue(json.contains("\"timestampMs\":1725700000123"))
        assertTrue(json.contains("\"eventFlags\":8"))
        assertTrue(json.contains("\"peakMv\":3250"))
        assertTrue(json.contains("\"riseTimeNs\":250"))
        assertTrue(json.contains("\"decayTimeUs\":500"))
        assertTrue(json.contains("\"opticalMv\":300"))
        assertTrue(json.contains("\"fftBins\":[10,20,30,40,50,40,30,20]"))
        assertTrue(json.contains("\"classification\":\"NORMAL\""))
        assertTrue(json.contains("\"confidence\":0.9850"))
        assertTrue(json.contains("\"inferenceTimeUs\":450"))
        assertTrue(json.contains("\"tamperDetected\":false"))
    }

    @Test
    fun testAllFourClassifications() {
        val classes = listOf("NORMAL", "EMP", "OPTICAL", "SURGE")

        for (cls in classes) {
            val isTamper = cls != "NORMAL"
            val msg = TelemetryWsMessage(
                seqId = 1L,
                timestampMs = 1000L,
                eventFlags = 1,
                peakMv = 5000,
                riseTimeNs = 20L,
                decayTimeUs = 10,
                opticalMv = 4000,
                fftBins = List(8) { 10 },
                classification = cls,
                confidence = 0.95f,
                inferenceTimeUs = 300L,
                tamperDetected = isTamper
            )

            val json = msg.toJson()
            assertTrue(json.contains("\"classification\":\"$cls\""))
            assertTrue(json.contains("\"tamperDetected\":$isTamper"))
        }
    }

    @Test
    fun testTamperDetectedConfidenceThreshold() {
        // At confidence 0.849, tamperDetected should be false
        val belowThreshold = TelemetryWsMessage(
            seqId = 2L,
            timestampMs = 1000L,
            eventFlags = 1,
            peakMv = 8000,
            riseTimeNs = 15L,
            decayTimeUs = 5,
            opticalMv = 100,
            fftBins = List(8) { 50 },
            classification = "EMP",
            confidence = 0.849f,
            inferenceTimeUs = 350L,
            tamperDetected = false
        )
        assertFalse(belowThreshold.tamperDetected)
        assertTrue(belowThreshold.toJson().contains("\"tamperDetected\":false"))

        // At confidence 0.850, tamperDetected should be true
        val atThreshold = TelemetryWsMessage(
            seqId = 3L,
            timestampMs = 1000L,
            eventFlags = 1,
            peakMv = 8000,
            riseTimeNs = 15L,
            decayTimeUs = 5,
            opticalMv = 100,
            fftBins = List(8) { 50 },
            classification = "EMP",
            confidence = 0.850f,
            inferenceTimeUs = 350L,
            tamperDetected = true
        )
        assertTrue(atThreshold.tamperDetected)
        assertTrue(atThreshold.toJson().contains("\"tamperDetected\":true"))
    }
}
