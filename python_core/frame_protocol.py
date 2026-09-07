"""SparkShield 29-Byte Binary Telemetry Protocol.

Specification:
  Total Length: 29 bytes (packed, big-endian)
  Bytes 0-1:   magic (uint16, 0x5353, ASCII 'SS')
  Bytes 2-5:   sequence_id (uint32)
  Bytes 6-9:   timestamp_ms (uint32)
  Byte 10:     event_flags (uint8: bit0=EMP, bit1=OPTICAL, bit2=SURGE, bit3=NORMAL)
  Bytes 11-12: peak_mv (uint16)
  Bytes 13-14: rise_time_code (uint16, 10 ns/LSB)
  Bytes 15-16: decay_time_us (uint16, 1 µs/LSB)
  Bytes 17-18: optical_sensor_mv (uint16)
  Bytes 19-26: fft_energy_bins (uint8[8])
  Bytes 27-28: crc16 (uint16, CRC-16-CCITT over bytes 0..26)
"""

import struct
from dataclasses import dataclass, field
from typing import List, Optional, Tuple, Union

from python_core.crc16 import crc16_ccitt

FRAME_MAGIC = 0x5353  # ASCII 'S', 'S'
FRAME_LENGTH = 29
PAYLOAD_LENGTH_FOR_CRC = 27  # Bytes 0 through 26 inclusive

# Format string: big-endian uint16, uint32, uint32, uint8, uint16, uint16, uint16, uint16, 8s, uint16
FRAME_STRUCT_FORMAT = ">HIIBHHHH8sH"
FRAME_PAYLOAD_STRUCT_FORMAT = ">HIIBHHHH8s"

# Event flag bitmasks
FLAG_EMP: int = 1 << 0      # Bit 0 = 0x01
FLAG_OPTICAL: int = 1 << 1  # Bit 1 = 0x02
FLAG_SURGE: int = 1 << 2    # Bit 2 = 0x04
FLAG_NORMAL: int = 1 << 3   # Bit 3 = 0x08


class ProtocolError(Exception):
    """Base exception for SparkShield protocol errors."""
    pass


class MalformedFrameError(ProtocolError):
    """Raised when frame byte length or structure is invalid."""
    pass


class MagicMismatchError(ProtocolError):
    """Raised when frame magic word does not equal 0x5353."""
    pass


class CrcMismatchError(ProtocolError):
    """Raised when calculated CRC does not match frame CRC."""
    pass


@dataclass
class TelemetryFrame:
    """Represents a validated SparkShield telemetry frame."""
    sequence_id: int
    timestamp_ms: int
    event_flags: int
    peak_mv: int
    rise_time_code: int
    decay_time_us: int
    optical_sensor_mv: int
    fft_energy_bins: bytes
    magic: int = FRAME_MAGIC
    crc16: int = 0

    def __post_init__(self):
        # Ensure fft_energy_bins is exactly 8 bytes
        if isinstance(self.fft_energy_bins, (list, bytearray)):
            self.fft_energy_bins = bytes(self.fft_energy_bins)
        if len(self.fft_energy_bins) != 8:
            raise ValueError(f"fft_energy_bins must be exactly 8 bytes, got {len(self.fft_energy_bins)}")

    @property
    def rise_time_ns(self) -> int:
        """Rise time in nanoseconds (10 ns per LSB)."""
        return self.rise_time_code * 10

    @property
    def decay_time_ms(self) -> float:
        """Decay time in milliseconds (1 µs per LSB)."""
        return self.decay_time_us / 1000.0

    @property
    def peak_v(self) -> float:
        """Peak voltage in Volts."""
        return self.peak_mv / 1000.0

    @property
    def optical_sensor_v(self) -> float:
        """Optical sensor voltage in Volts."""
        return self.optical_sensor_mv / 1000.0

    @property
    def is_emp(self) -> bool:
        return bool(self.event_flags & FLAG_EMP)

    @property
    def is_optical(self) -> bool:
        return bool(self.event_flags & FLAG_OPTICAL)

    @property
    def is_surge(self) -> bool:
        return bool(self.event_flags & FLAG_SURGE)

    @property
    def is_normal(self) -> bool:
        return bool(self.event_flags & FLAG_NORMAL)


def _clamp_uint(val: int, bits: int) -> int:
    """Safely clamp integer to unsigned integer with specified bit width."""
    max_val = (1 << bits) - 1
    return max(0, min(int(val), max_val))


def pack_frame(frame: TelemetryFrame) -> bytes:
    """Packs a TelemetryFrame into the wire-format 29-byte packed big-endian buffer.

    Calculates and appends CRC-16-CCITT over bytes 0 through 26.

    Args:
        frame: TelemetryFrame to pack.

    Returns:
        29-byte binary buffer.
    """
    # Safe boundary clamping to prevent struct.pack overflow
    seq_clamped = _clamp_uint(frame.sequence_id, 32)
    ts_clamped = _clamp_uint(frame.timestamp_ms, 32)
    flags_clamped = _clamp_uint(frame.event_flags, 8)
    peak_clamped = _clamp_uint(frame.peak_mv, 16)
    rise_clamped = _clamp_uint(frame.rise_time_code, 16)
    decay_clamped = _clamp_uint(frame.decay_time_us, 16)
    optical_clamped = _clamp_uint(frame.optical_sensor_mv, 16)

    # Pack the first 27 bytes (payload over which CRC is calculated)
    payload_27 = struct.pack(
        FRAME_PAYLOAD_STRUCT_FORMAT,
        FRAME_MAGIC,
        seq_clamped,
        ts_clamped,
        flags_clamped,
        peak_clamped,
        rise_clamped,
        decay_clamped,
        optical_clamped,
        frame.fft_energy_bins,
    )

    # Compute CRC-16-CCITT over the 27 bytes
    calculated_crc = crc16_ccitt(payload_27)
    frame.crc16 = calculated_crc

    # Pack complete 29 bytes
    return payload_27 + struct.pack(">H", calculated_crc)


def validate_frame(data: bytes) -> Tuple[bool, Optional[str]]:
    """Validates raw frame length, magic word, and CRC.

    Args:
        data: Raw buffer to validate.

    Returns:
        Tuple of (is_valid, error_message_if_any).
    """
    if len(data) != FRAME_LENGTH:
        return False, f"Invalid frame length: expected {FRAME_LENGTH} bytes, got {len(data)}"

    magic = struct.unpack(">H", data[0:2])[0]
    if magic != FRAME_MAGIC:
        return False, f"Magic mismatch: expected 0x{FRAME_MAGIC:04X}, got 0x{magic:04X}"

    expected_crc = struct.unpack(">H", data[27:29])[0]
    calculated_crc = crc16_ccitt(data[0:PAYLOAD_LENGTH_FOR_CRC])
    if calculated_crc != expected_crc:
        return False, f"CRC mismatch: expected 0x{expected_crc:04X}, calculated 0x{calculated_crc:04X}"

    return True, None


def unpack_frame(data: bytes, strict: bool = True) -> TelemetryFrame:
    """Unpacks a 29-byte buffer into a TelemetryFrame.

    Args:
        data: 29-byte buffer.
        strict: If True, raises exceptions on length, magic, or CRC failure.

    Returns:
        Deserialized TelemetryFrame.

    Raises:
        MalformedFrameError: If length != 29.
        MagicMismatchError: If magic != 0x5353.
        CrcMismatchError: If CRC does not match.
    """
    if len(data) != FRAME_LENGTH:
        raise MalformedFrameError(
            f"Invalid frame length: expected {FRAME_LENGTH} bytes, got {len(data)}"
        )

    magic, seq_id, ts_ms, flags, peak_mv, rise_code, decay_us, opt_mv, bins, crc_val = struct.unpack(
        FRAME_STRUCT_FORMAT, data
    )

    if strict:
        if magic != FRAME_MAGIC:
            raise MagicMismatchError(
                f"Invalid magic: expected 0x{FRAME_MAGIC:04X}, got 0x{magic:04X}"
            )
        calc_crc = crc16_ccitt(data[0:PAYLOAD_LENGTH_FOR_CRC])
        if calc_crc != crc_val:
            raise CrcMismatchError(
                f"CRC mismatch: frame CRC 0x{crc_val:04X} != calculated CRC 0x{calc_crc:04X}"
            )

    return TelemetryFrame(
        sequence_id=seq_id,
        timestamp_ms=ts_ms,
        event_flags=flags,
        peak_mv=peak_mv,
        rise_time_code=rise_code,
        decay_time_us=decay_us,
        optical_sensor_mv=opt_mv,
        fft_energy_bins=bins,
        magic=magic,
        crc16=crc_val,
    )


class SequenceTracker:
    """Tracks sequence continuity, detecting dropped frames and duplicates."""

    def __init__(self, initial_sequence: Optional[int] = None):
        self.last_sequence: Optional[int] = initial_sequence
        self.total_received: int = 0
        self.total_dropped: int = 0
        self.total_duplicates: int = 0
        self.total_out_of_order: int = 0

    def process_sequence(self, sequence_id: int) -> Tuple[bool, int]:
        """Updates tracker with a newly received sequence_id.

        Handles 32-bit integer wrap-around.

        Returns:
            Tuple of (is_continuous, dropped_count).
        """
        self.total_received += 1

        if self.last_sequence is None:
            self.last_sequence = sequence_id
            return True, 0

        expected = (self.last_sequence + 1) & 0xFFFFFFFF

        if sequence_id == expected:
            self.last_sequence = sequence_id
            return True, 0

        if sequence_id == self.last_sequence:
            self.total_duplicates += 1
            return False, 0

        # Calculate difference accounting for 32-bit unsigned wrap-around
        diff = (sequence_id - self.last_sequence) & 0xFFFFFFFF

        # If sequence is within forward window of 2^31 - 1
        if diff < 0x80000000:
            dropped = diff - 1
            self.total_dropped += dropped
            self.last_sequence = sequence_id
            return False, dropped
        else:
            # Out of order or old duplicate
            self.total_out_of_order += 1
            return False, 0

    def reset(self):
        """Reset tracking state."""
        self.last_sequence = None
        self.total_received = 0
        self.total_dropped = 0
        self.total_duplicates = 0
        self.total_out_of_order = 0
