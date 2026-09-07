"""Unit tests for SparkShield Mock WebSocket Publisher."""

import asyncio
import json
import pytest
import struct

from python_core.mock_ws_server import MockWebSocketServer, WS_GUID
from python_core.signal_models import SignalClass


def test_telemetry_payload_schema():
    """Validates that build_telemetry_payload produces all required 12 fields with correct types."""
    server = MockWebSocketServer(port=18765, rate_hz=10.0, seed=123)
    payload_str = server.build_telemetry_payload()
    data = json.loads(payload_str)

    expected_keys = [
        "seqId",
        "timestampMs",
        "eventFlags",
        "peakMv",
        "riseTimeNs",
        "decayTimeUs",
        "opticalMv",
        "fftBins",
        "classification",
        "confidence",
        "inferenceTimeUs",
        "tamperDetected",
    ]

    for key in expected_keys:
        assert key in data, f"Missing key '{key}' in telemetry JSON"

    assert isinstance(data["seqId"], int)
    assert isinstance(data["timestampMs"], int)
    assert isinstance(data["eventFlags"], int)
    assert isinstance(data["peakMv"], int)
    assert isinstance(data["riseTimeNs"], int)
    assert isinstance(data["decayTimeUs"], int)
    assert isinstance(data["opticalMv"], int)
    assert isinstance(data["fftBins"], list)
    assert len(data["fftBins"]) == 8
    assert isinstance(data["classification"], str)
    assert isinstance(data["confidence"], (float, int))
    assert isinstance(data["inferenceTimeUs"], int)
    assert isinstance(data["tamperDetected"], bool)


def test_ws_frame_encoding():
    """Tests RFC 6455 unmasked frame encoding across message lengths."""
    # Small payload (<= 125 bytes)
    short_msg = "hello sparkshield"
    short_frame = MockWebSocketServer.encode_ws_frame(short_msg)
    assert short_frame[0] == 0x81  # FIN + text opcode
    assert short_frame[1] == len(short_msg)
    assert short_frame[2:].decode("utf-8") == short_msg

    # Medium payload (126 <= len <= 65535)
    medium_msg = "A" * 300
    med_frame = MockWebSocketServer.encode_ws_frame(medium_msg)
    assert med_frame[0] == 0x81
    assert med_frame[1] == 126
    unpacked_len = struct.unpack("!H", med_frame[2:4])[0]
    assert unpacked_len == 300
    assert med_frame[4:].decode("utf-8") == medium_msg


def test_all_four_classifications_generation():
    """Verifies that tamper injection generates EMP, OPTICAL, and SURGE classifications."""
    server = MockWebSocketServer(port=18766, rate_hz=10.0, seed=42)

    # 1. Normal frame
    normal_data = json.loads(server.build_telemetry_payload())
    assert normal_data["classification"] == "NORMAL"
    assert normal_data["tamperDetected"] is False

    # 2. EMP injection
    server.trigger_tamper(SignalClass.EMP, burst_count=2)
    emp_data = json.loads(server.build_telemetry_payload())
    assert emp_data["classification"] == "EMP"
    assert emp_data["tamperDetected"] is True

    # 3. OPTICAL injection
    server.trigger_tamper(SignalClass.OPTICAL, burst_count=2)
    optical_data = json.loads(server.build_telemetry_payload())
    assert optical_data["classification"] == "OPTICAL"
    assert optical_data["tamperDetected"] is True

    # 4. SURGE injection
    server.trigger_tamper(SignalClass.SURGE, burst_count=2)
    surge_data = json.loads(server.build_telemetry_payload())
    assert surge_data["classification"] == "SURGE"
    assert surge_data["tamperDetected"] is True


def test_server_socket_lifecycle_and_client_reception():
    """Tests starting the server, connecting via TCP, performing handshake, and receiving frames."""
    async def _run():
        test_port = 18767
        server = MockWebSocketServer(host="127.0.0.1", port=test_port, rate_hz=20.0, seed=42)
        await server.start()

        try:
            reader, writer = await asyncio.open_connection("127.0.0.1", test_port)

            # Send RFC 6455 handshake
            dummy_key = "dGhlIHNhbXBsZSBub25jZQ=="
            handshake_request = (
                "GET / HTTP/1.1\r\n"
                "Host: 127.0.0.1\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {dummy_key}\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            )
            writer.write(handshake_request.encode("utf-8"))
            await writer.drain()

            # Read handshake response
            handshake_response = b""
            while b"\r\n\r\n" not in handshake_response:
                chunk = await asyncio.wait_for(reader.read(512), timeout=2.0)
                handshake_response += chunk

            assert b"101 Switching Protocols" in handshake_response
            assert b"Sec-WebSocket-Accept:" in handshake_response

            # Read at least 2 telemetry frames
            frames_received = 0
            for _ in range(2):
                header = await asyncio.wait_for(reader.read(2), timeout=2.0)
                assert len(header) == 2
                b0, b1 = header[0], header[1]
                assert b0 & 0x0F == 0x01  # Text frame opcode
                payload_len = b1 & 0x7F

                if payload_len == 126:
                    ext = await reader.read(2)
                    payload_len = struct.unpack("!H", ext)[0]

                payload_bytes = await asyncio.wait_for(reader.read(payload_len), timeout=2.0)
                frame_json = json.loads(payload_bytes.decode("utf-8"))
                assert "seqId" in frame_json
                assert "classification" in frame_json
                frames_received += 1

            assert frames_received == 2

            writer.close()
            await writer.wait_closed()
        finally:
            await server.stop()

    asyncio.run(_run())
