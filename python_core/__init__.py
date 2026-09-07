"""SparkShield Python Core Virtual Meter Package."""

from python_core.crc16 import crc16_ccitt, crc16_ccitt_bitwise
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
    ProtocolError,
    MalformedFrameError,
    MagicMismatchError,
    CrcMismatchError,
)
from python_core.signal_models import (
    SignalClass,
    SignalGenerator,
    FeatureExtractor,
)
from python_core.ble_peripheral import (
    TelemetryTransport,
    MockLoopbackTransport,
    BlePeripheralTransport,
)
from python_core.mock_stream import MockStreamer

__all__ = [
    "crc16_ccitt",
    "crc16_ccitt_bitwise",
    "FRAME_LENGTH",
    "FRAME_MAGIC",
    "FLAG_EMP",
    "FLAG_NORMAL",
    "FLAG_OPTICAL",
    "FLAG_SURGE",
    "TelemetryFrame",
    "pack_frame",
    "unpack_frame",
    "validate_frame",
    "SequenceTracker",
    "ProtocolError",
    "MalformedFrameError",
    "MagicMismatchError",
    "CrcMismatchError",
    "SignalClass",
    "SignalGenerator",
    "FeatureExtractor",
    "TelemetryTransport",
    "MockLoopbackTransport",
    "BlePeripheralTransport",
    "MockStreamer",
]
