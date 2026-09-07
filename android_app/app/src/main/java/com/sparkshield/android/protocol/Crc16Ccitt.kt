package com.sparkshield.android.protocol

/**
 * CRC-16-CCITT (CRC-16/CCITT-FALSE, CRC-16/AUTOSAR) implementation.
 *
 * Exact parity with python_core/crc16.py:
 *   - Polynomial: 0x1021 (x^16 + x^12 + x^5 + 1)
 *   - Initial Value: 0xFFFF
 *   - RefIn: False (MSB-first)
 *   - RefOut: False (MSB-first)
 *   - XorOut: 0x0000
 *   - Check value for ASCII "123456789": 0x29B1 (10673)
 */
object Crc16Ccitt {

    const val POLYNOMIAL: Int = 0x1021
    const val INITIAL_VALUE: Int = 0xFFFF
    const val CHECK_VALUE: Int = 0x29B1

    // Precomputed 256-entry lookup table
    private val TABLE = IntArray(256) { byteVal ->
        var curr = byteVal shl 8
        for (i in 0 until 8) {
            curr = if ((curr and 0x8000) != 0) {
                ((curr shl 1) xor POLYNOMIAL) and 0xFFFF
            } else {
                (curr shl 1) and 0xFFFF
            }
        }
        curr
    }

    /**
     * Compute CRC-16-CCITT over a byte array using the 256-entry lookup table.
     *
     * @param bytes Byte array over which to compute CRC.
     * @param offset Starting offset.
     * @param length Number of bytes to include.
     * @return 16-bit unsigned integer CRC result (0..65535).
     */
    fun calculate(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var crc = INITIAL_VALUE and 0xFFFF
        val end = offset + length
        for (i in offset until end) {
            val byteVal = bytes[i].toInt() and 0xFF
            val tableIndex = ((crc ushr 8) xor byteVal) and 0xFF
            crc = (TABLE[tableIndex] xor (crc shl 8)) and 0xFFFF
        }
        return crc
    }

    /**
     * Alias for calculate(bytes, offset, length) for backward-compatibility.
     */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size, init: Int = INITIAL_VALUE): Int {
        var crc = init and 0xFFFF
        val end = offset + length
        for (i in offset until end) {
            val byteVal = data[i].toInt() and 0xFF
            val tableIndex = ((crc ushr 8) xor byteVal) and 0xFF
            crc = (TABLE[tableIndex] xor (crc shl 8)) and 0xFFFF
        }
        return crc
    }

    /**
     * Reference bitwise algorithm for validation and unit testing parity.
     */
    fun computeBitwise(data: ByteArray, offset: Int = 0, length: Int = data.size, init: Int = INITIAL_VALUE): Int {
        var crc = init and 0xFFFF
        val end = offset + length
        for (i in offset until end) {
            val byteVal = data[i].toInt() and 0xFF
            crc = crc xor ((byteVal shl 8) and 0xFFFF)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor POLYNOMIAL) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }
}
