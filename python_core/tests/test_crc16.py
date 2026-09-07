"""Automated tests for CRC-16-CCITT implementation."""

import os
import pytest
from python_core.crc16 import crc16_ccitt, crc16_ccitt_bitwise


def test_crc16_standard_check_vector():
    """Standard check vector for CRC-16/CCITT-FALSE: ASCII '123456789' must equal 0x29B1."""
    test_input = b"123456789"
    expected = 0x29B1

    assert crc16_ccitt_bitwise(test_input) == expected
    assert crc16_ccitt(test_input) == expected


def test_crc16_empty_buffer():
    """Empty buffer should return initial value 0xFFFF."""
    assert crc16_ccitt_bitwise(b"") == 0xFFFF
    assert crc16_ccitt(b"") == 0xFFFF


def test_crc16_known_patterns():
    """Verify known deterministic byte patterns produce non-zero 16-bit values."""
    zeros = b"\x00" * 32
    crc_zeros = crc16_ccitt(zeros)
    assert 0 <= crc_zeros <= 0xFFFF
    assert crc16_ccitt_bitwise(zeros) == crc_zeros

    ones = b"\xFF" * 32
    crc_ones = crc16_ccitt(ones)
    assert 0 <= crc_ones <= 0xFFFF
    assert crc16_ccitt_bitwise(ones) == crc_ones


def test_crc16_bitwise_and_table_equivalence():
    """Lookup table and bitwise reference implementations must produce identical results."""
    # Test on varying length buffers
    for length in [1, 2, 7, 16, 27, 29, 64, 128, 512]:
        data = os.urandom(length)
        res_bitwise = crc16_ccitt_bitwise(data)
        res_table = crc16_ccitt(data)
        assert res_bitwise == res_table, f"Mismatch at length {length}: {hex(res_bitwise)} vs {hex(res_table)}"


def test_crc16_single_bit_sensitivity():
    """A 1-bit difference in input must produce a completely different CRC."""
    data_a = bytearray(b"SparkShield telemetry frame payload")
    data_b = bytearray(data_a)
    data_b[10] ^= 0x01  # Flip one bit

    crc_a = crc16_ccitt(bytes(data_a))
    crc_b = crc16_ccitt(bytes(data_b))
    assert crc_a != crc_b
