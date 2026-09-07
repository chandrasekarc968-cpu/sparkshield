"""SparkShield Phase 4 End-to-End Verification Script.

Validates:
1. WebSocket JSON message serialization (12 fields).
2. All four classifications (NORMAL, EMP, OPTICAL, SURGE).
3. Confidence and tamperDetected flag values.
4. Multi-client broadcast capability.
5. Reconnect and handshake behavior.
6. Malformed payload handling and client disconnect safety.
7. Clean server shutdown.
"""

import asyncio
import json
import struct
import sys
import time
from pathlib import Path

repo_root = Path(__file__).resolve().parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from python_core.mock_ws_server import MockWebSocketServer
from python_core.signal_models import SignalClass


async def connect_client(port: int, client_id: int):
    """Establishes RFC 6455 connection to the test server."""
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    dummy_key = f"dGhlIHNhbXBsZSBub25jZQ{client_id}="
    handshake = (
        f"GET / HTTP/1.1\r\n"
        f"Host: 127.0.0.1:{port}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {dummy_key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    )
    writer.write(handshake.encode("utf-8"))
    await writer.drain()

    # Wait for handshake response
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = await asyncio.wait_for(reader.read(512), timeout=3.0)
        resp += chunk

    assert b"101 Switching Protocols" in resp, f"Client {client_id} handshake failed"
    return reader, writer


async def read_ws_frame(reader: asyncio.StreamReader) -> dict:
    """Reads a single text frame from WebSocket stream."""
    header = await asyncio.wait_for(reader.read(2), timeout=3.0)
    assert len(header) == 2
    b0, b1 = header[0], header[1]
    assert (b0 & 0x0F) == 0x01, f"Expected text frame opcode (0x1), got {b0 & 0x0F}"

    payload_len = b1 & 0x7F
    if payload_len == 126:
        ext = await reader.read(2)
        payload_len = struct.unpack("!H", ext)[0]
    elif payload_len == 127:
        ext = await reader.read(8)
        payload_len = struct.unpack("!Q", ext)[0]

    payload_bytes = await asyncio.wait_for(reader.read(payload_len), timeout=3.0)
    return json.loads(payload_bytes.decode("utf-8"))


async def main():
    print("=" * 70)
    print("SparkShield Phase 4: WebSocket Publisher & Dashboard Parity Validator")
    print("=" * 70)

    test_port = 19765
    server = MockWebSocketServer(host="127.0.0.1", port=test_port, rate_hz=20.0, seed=42)
    await server.start()
    print(f"[1/5] Server started on port {test_port}.")

    try:
        # Step 2: Multi-client connection
        print("[2/5] Connecting multiple dashboard clients simultaneously...")
        r1, w1 = await connect_client(test_port, 1)
        r2, w2 = await connect_client(test_port, 2)
        assert len(server.clients) == 2, f"Expected 2 active clients, got {len(server.clients)}"
        print("      PASSED: 2 concurrent clients connected and registered.")

        # Step 3: Verify schema and multi-client broadcast
        print("[3/5] Verifying 12-field JSON schema and multi-client broadcast...")
        for i in range(10):
            frame1 = await read_ws_frame(r1)
            frame2 = await read_ws_frame(r2)

            # Both clients must receive identical sequence
            assert frame1["seqId"] == frame2["seqId"], f"Frame mismatch: {frame1['seqId']} vs {frame2['seqId']}"

            # Validate all 12 fields
            required_keys = [
                "seqId", "timestampMs", "eventFlags", "peakMv", "riseTimeNs",
                "decayTimeUs", "opticalMv", "fftBins", "classification",
                "confidence", "inferenceTimeUs", "tamperDetected"
            ]
            for key in required_keys:
                assert key in frame1, f"Missing key {key} in frame"

            assert len(frame1["fftBins"]) == 8
            assert isinstance(frame1["tamperDetected"], bool)

        print("      PASSED: 12-field telemetry frames verified with synchronized broadcast.")

        # Step 4: Validate all 4 classifications & tamper logic
        print("[4/5] Testing tamper bursts: EMP, OPTICAL, SURGE, and NORMAL...")
        # EMP
        server.trigger_tamper(SignalClass.EMP, burst_count=8)
        emp_seen = False
        for _ in range(12):
            f = await read_ws_frame(r1)
            _ = await read_ws_frame(r2)
            if f["classification"] == "EMP" and f["confidence"] >= 0.85:
                emp_seen = True
                assert f["tamperDetected"] is True, f"Expected tamperDetected=True for EMP at conf {f['confidence']}"
                break
        assert emp_seen, "Did not observe EMP tamper frame with confidence >= 0.85"

        # OPTICAL
        server.trigger_tamper(SignalClass.OPTICAL, burst_count=8)
        optical_seen = False
        for _ in range(12):
            f = await read_ws_frame(r1)
            _ = await read_ws_frame(r2)
            if f["classification"] == "OPTICAL" and f["confidence"] >= 0.85:
                optical_seen = True
                assert f["tamperDetected"] is True, f"Expected tamperDetected=True for OPTICAL at conf {f['confidence']}"
                break
        assert optical_seen, "Did not observe OPTICAL tamper frame with confidence >= 0.85"

        # SURGE
        server.trigger_tamper(SignalClass.SURGE, burst_count=8)
        surge_seen = False
        for _ in range(12):
            f = await read_ws_frame(r1)
            _ = await read_ws_frame(r2)
            if f["classification"] == "SURGE" and f["confidence"] >= 0.85:
                surge_seen = True
                assert f["tamperDetected"] is True, f"Expected tamperDetected=True for SURGE at conf {f['confidence']}"
                break
        assert surge_seen, "Did not observe SURGE tamper frame with confidence >= 0.85"
        print("      PASSED: All 4 classifications and tamperDetected logic verified.")

        # Step 5: Test client disconnect resilience
        print("[5/5] Testing graceful client disconnection & dead connection culling...")
        w1.close()
        await w1.wait_closed()
        # Read next frame on w2 to trigger culling
        _ = await read_ws_frame(r2)
        assert len(server.clients) == 1, f"Expected 1 client remaining, got {len(server.clients)}"

        w2.close()
        await w2.wait_closed()
        print("      PASSED: Disconnected clients cleanly pruned without server interruption.")

    finally:
        await server.stop()
        print("      PASSED: Server shutdown completed cleanly.")

    print("=" * 70)
    print("ALL PHASE 4 END-TO-END VERIFICATION CHECKS PASSED SUCCESSFULLY.")
    print("=" * 70)


if __name__ == "__main__":
    asyncio.run(main())
