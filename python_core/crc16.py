"""CRC-16-CCITT implementation for SparkShield frame protocol.

Specification:
  - Name: CRC-16/CCITT-FALSE (also known as CRC-16/AUTOSAR)
  - Polynomial: 0x1021 (x^16 + x^12 + x^5 + 1)
  - Initial Value: 0xFFFF
  - RefIn: False (MSB-first)
  - RefOut: False (MSB-first)
  - XorOut: 0x0000
  - Standard check value for ASCII b"123456789": 0x29B1

This module provides both a reference bitwise algorithm and a fast 256-entry
lookup-table implementation for embedded and real-time edge processing.
"""

from typing import Union

# Precompute 256-entry lookup table for fast processing
_CRC_TABLE = []
for byte_val in range(256):
    curr = byte_val << 8
    for _ in range(8):
        if curr & 0x8000:
            curr = ((curr << 1) ^ 0x1021) & 0xFFFF
        else:
            curr = (curr << 1) & 0xFFFF
    _CRC_TABLE.append(curr)


def crc16_ccitt_bitwise(data: Union[bytes, bytearray, memoryview], init: int = 0xFFFF) -> int:
    """Calculate CRC-16-CCITT using bitwise shift-and-xor reference algorithm.

    Args:
        data: Buffer of bytes to compute CRC over.
        init: Initial shift register value (default 0xFFFF).

    Returns:
        16-bit unsigned integer CRC result.
    """
    crc = init & 0xFFFF
    for byte in data:
        crc ^= (byte << 8) & 0xFFFF
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def crc16_ccitt(data: Union[bytes, bytearray, memoryview], init: int = 0xFFFF) -> int:
    """Calculate CRC-16-CCITT using high-performance 256-entry lookup table.

    Args:
        data: Buffer of bytes to compute CRC over.
        init: Initial shift register value (default 0xFFFF).

    Returns:
        16-bit unsigned integer CRC result.
    """
    crc = init & 0xFFFF
    for byte in data:
        tbl_idx = ((crc >> 8) ^ byte) & 0xFF
        crc = (_CRC_TABLE[tbl_idx] ^ (crc << 8)) & 0xFFFF
    return crc
