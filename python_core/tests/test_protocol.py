"""Automated tests for SparkShield 29-byte frame protocol."""

import struct
import pytest
from python_core.frame_protocol import (
    FRAME_LENGTH,
    FRAME_MAGIC,
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
    TelemetryFrame,
    pack_frame,
    unpack_frame,
    validate_frame,
    SequenceTracker,
    MalformedFrameError,
    MagicMismatchError,
    CrcMismatchError,
)
from python_core.crc16 import crc16_ccitt


@pytest.fixture
def sample_frame():
    return TelemetryFrame(
        sequence_id=12345,
        timestamp_ms=987654321,
        event_flags=FLAG_NORMAL,
        peak_mv=3250,
        rise_time_code=5000,
        decay_time_us=8500,
        optical_sensor_mv=140,
        fft_energy_bins=bytes([210, 40, 15, 5, 2, 1, 0, 0]),
    )


def test_frame_length_is_strictly_29_bytes(sample_frame):
    """Packed frame must strictly be 29 bytes."""
    packed = pack_frame(sample_frame)
    assert len(packed) == 29
    assert FRAME_LENGTH == 29


def test_big_endian_field_encoding():
    """Verify multi-byte fields are strictly encoded in big-endian order."""
    frame = TelemetryFrame(
        sequence_id=0x12345678,
        timestamp_ms=0xAABBCCDD,
        event_flags=FLAG_EMP,
        peak_mv=0x1122,
        rise_time_code=0x3344,
        decay_time_us=0x5566,
        optical_sensor_mv=0x7788,
        fft_energy_bins=bytes([1, 2, 3, 4, 5, 6, 7, 8]),
    )
    packed = pack_frame(frame)

    # Magic: Bytes 0-1 == 0x53, 0x53
    assert packed[0] == 0x53
    assert packed[1] == 0x53

    # Sequence ID: Bytes 2-5 == 0x12, 0x34, 0x56, 0x78
    assert packed[2:6] == bytes([0x12, 0x34, 0x56, 0x78])

    # Timestamp: Bytes 6-9 == 0xAA, 0xBB, 0xCC, 0xDD
    assert packed[6:10] == bytes([0xAA, 0xBB, 0xCC, 0xDD])

    # Event Flags: Byte 10 == 0x01 (FLAG_EMP)
    assert packed[10] == 0x01

    # Peak mV: Bytes 11-12 == 0x11, 0x22
    assert packed[11:13] == bytes([0x11, 0x22])

    # Rise time: Bytes 13-14 == 0x33, 0x44
    assert packed[13:15] == bytes([0x33, 0x44])

    # Decay time: Bytes 15-16 == 0x55, 0x66
    assert packed[15:17] == bytes([0x55, 0x66])

    # Optical mV: Bytes 17-18 == 0x77, 0x88
    assert packed[17:19] == bytes([0x77, 0x88])

    # FFT bins: Bytes 19-26
    assert packed[19:27] == bytes([1, 2, 3, 4, 5, 6, 7, 8])

    # CRC: Bytes 27-28 big-endian uint16
    expected_crc = crc16_ccitt(packed[0:27])
    packed_crc = struct.unpack(">H", packed[27:29])[0]
    assert packed_crc == expected_crc


def test_round_trip_pack_unpack(sample_frame):
    """Packed frame must unpack back to identical values."""
    packed = pack_frame(sample_frame)
    unpacked = unpack_frame(packed)

    assert unpacked.magic == FRAME_MAGIC
    assert unpacked.sequence_id == sample_frame.sequence_id
    assert unpacked.timestamp_ms == sample_frame.timestamp_ms
    assert unpacked.event_flags == sample_frame.event_flags
    assert unpacked.peak_mv == sample_frame.peak_mv
    assert unpacked.rise_time_code == sample_frame.rise_time_code
    assert unpacked.decay_time_us == sample_frame.decay_time_us
    assert unpacked.optical_sensor_mv == sample_frame.optical_sensor_mv
    assert unpacked.fft_energy_bins == sample_frame.fft_energy_bins
    assert unpacked.crc16 == sample_frame.crc16

    # Verify physical conversion helpers
    assert unpacked.rise_time_ns == sample_frame.rise_time_code * 10
    assert unpacked.decay_time_ms == sample_frame.decay_time_us / 1000.0
    assert unpacked.peak_v == sample_frame.peak_mv / 1000.0
    assert unpacked.optical_sensor_v == sample_frame.optical_sensor_mv / 1000.0


def test_validate_frame_success(sample_frame):
    """Validation succeeds on valid frame."""
    packed = pack_frame(sample_frame)
    is_valid, err = validate_frame(packed)
    assert is_valid is True
    assert err is None


def test_malformed_frame_length_rejection(sample_frame):
    """Frame with length != 29 must be rejected."""
    packed = pack_frame(sample_frame)

    # Truncated frame
    short_frame = packed[:28]
    is_valid, err = validate_frame(short_frame)
    assert is_valid is False
    assert "length" in err.lower()
    with pytest.raises(MalformedFrameError):
        unpack_frame(short_frame)

    # Extended frame
    long_frame = packed + b"\x00"
    is_valid, err = validate_frame(long_frame)
    assert is_valid is False
    with pytest.raises(MalformedFrameError):
        unpack_frame(long_frame)


def test_bad_magic_rejection(sample_frame):
    """Frame with corrupted magic number must be rejected."""
    packed = bytearray(pack_frame(sample_frame))
    # Mutate magic from 0x5353 ('SS') to 0x5858 ('XX')
    packed[0] = 0x58
    packed[1] = 0x58

    is_valid, err = validate_frame(bytes(packed))
    assert is_valid is False
    assert "magic" in err.lower()

    with pytest.raises(MagicMismatchError):
        unpack_frame(bytes(packed))


def test_bad_crc_rejection(sample_frame):
    """Frame with payload bit flips triggering CRC mismatch must be rejected."""
    packed = bytearray(pack_frame(sample_frame))
    # Flip a bit in the peak_mv field (byte 11)
    packed[11] ^= 0x01

    is_valid, err = validate_frame(bytes(packed))
    assert is_valid is False
    assert "crc mismatch" in err.lower()

    with pytest.raises(CrcMismatchError):
        unpack_frame(bytes(packed))


def test_sequence_tracker_continuity():
    """SequenceTracker tracks monotonic sequences and detects dropped or duplicate packets."""
    tracker = SequenceTracker()

    # Normal continuity: 0, 1, 2, 3
    for seq in range(4):
        ok, dropped = tracker.process_sequence(seq)
        assert ok is True
        assert dropped == 0
    assert tracker.total_received == 4
    assert tracker.total_dropped == 0

    # Gap: skip seq 4, 5, 6 -> arrive at 7
    ok, dropped = tracker.process_sequence(7)
    assert ok is False
    assert dropped == 3  # 4, 5, 6 dropped
    assert tracker.total_dropped == 3

    # Duplicate: packet 7 arrives again
    ok, dropped = tracker.process_sequence(7)
    assert ok is False
    assert dropped == 0
    assert tracker.total_duplicates == 1

    # Normal continuation after gap: 8
    ok, dropped = tracker.process_sequence(8)
    assert ok is True
    assert dropped == 0


def test_sequence_tracker_wraparound():
    """SequenceTracker correctly handles 32-bit unsigned integer wraparound."""
    tracker = SequenceTracker(initial_sequence=0xFFFFFFFE)
    # Next sequence: 0xFFFFFFFF
    ok, dropped = tracker.process_sequence(0xFFFFFFFF)
    assert ok is True
    assert dropped == 0

    # Wraparound to 0
    ok, dropped = tracker.process_sequence(0)
    assert ok is True
    assert dropped == 0

    # Wraparound gap: from 0 jump to 3 (dropped 1, 2)
    ok, dropped = tracker.process_sequence(3)
    assert ok is False
    assert dropped == 2
    assert tracker.total_dropped == 2


def test_sequence_rollover_uint32_boundary_gap():
    """SequenceTracker jumping directly across uint32 boundary with gap."""
    tracker = SequenceTracker(initial_sequence=0xFFFFFFFE)
    # Jump from 0xFFFFFFFE to 2 (missed 0xFFFFFFFF, 0, 1 -> 3 dropped)
    ok, dropped = tracker.process_sequence(2)
    assert ok is False
    assert dropped == 3
    assert tracker.total_dropped == 3


def test_invalid_event_flags_rejection(sample_frame):
    """Frames with reserved upper bits or conflict flags must fail validation."""
    from python_core.frame_protocol import FLAG_EMP, FLAG_NORMAL, FLAG_OPTICAL, FLAG_SURGE

    # Conflict: NORMAL and EMP both set
    sample_frame.event_flags = FLAG_NORMAL | FLAG_EMP
    packed = pack_frame(sample_frame)
    is_valid, err = validate_frame(packed)
    assert is_valid is False
    assert "flag" in err.lower() or "invalid" in err.lower()

    # Reserved upper bits set
    sample_frame.event_flags = 0x10 | FLAG_NORMAL
    packed = pack_frame(sample_frame)
    is_valid, err = validate_frame(packed)
    assert is_valid is False

