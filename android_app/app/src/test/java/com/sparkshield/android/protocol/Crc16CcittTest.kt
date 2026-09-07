package com.sparkshield.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class Crc16CcittTest {

    @Test
    fun testStandardCheckVector() {
        // Standard check sequence: ASCII "123456789" -> 0x29B1 (10673)
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        val crcTable = Crc16Ccitt.compute(data)
        val crcBitwise = Crc16Ccitt.computeBitwise(data)

        assertEquals("Table CRC must equal 0x29B1", 0x29B1, crcTable)
        assertEquals("Bitwise CRC must equal 0x29B1", 0x29B1, crcBitwise)
    }

    @Test
    fun testLookupTableMatchesBitwiseReference() {
        val rng = Random(12345)
        for (trial in 0 until 50) {
            val length = rng.nextInt(64) + 1
            val buffer = ByteArray(length)
            rng.nextBytes(buffer)

            val tableResult = Crc16Ccitt.compute(buffer)
            val bitwiseResult = Crc16Ccitt.computeBitwise(buffer)

            assertEquals("Trial $trial mismatch", bitwiseResult, tableResult)
        }
    }

    @Test
    fun testSubarrayOffsetAndLength() {
        val full = "PRE_123456789_POST".toByteArray(Charsets.US_ASCII)
        val offset = 4
        val length = 9
        val crc = Crc16Ccitt.compute(full, offset, length)
        assertEquals(0x29B1, crc)
    }
}
