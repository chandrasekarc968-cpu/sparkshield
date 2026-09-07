"""SparkShield Mock WebSocket Publisher.

RFC 6455 WebSocket server using Python standard library (asyncio).
Generates deterministic synthetic smart-meter telemetry, performs edge
classification using the ONNX model, and broadcasts JSON telemetry frames
to all connected dashboard clients.

SIMULATION ONLY:
All sensor values, voltage spikes, and tamper events are purely software-simulated.
"""

import argparse
import asyncio
import base64
import hashlib
import json
import logging
import os
import struct
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import numpy as np

from python_core.frame_protocol import TelemetryFrame
from python_core.signal_models import SignalClass
from python_core.mock_stream import MockStreamer

logger = logging.getLogger("sparkshield.mock_ws")

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
CLASS_NAMES = ["NORMAL", "EMP", "OPTICAL", "SURGE"]


def _softmax(logits: np.ndarray) -> np.ndarray:
    """Numerically stable softmax."""
    shifted = logits - np.max(logits, axis=-1, keepdims=True)
    exp_vals = np.exp(shifted)
    return exp_vals / np.sum(exp_vals, axis=-1, keepdims=True)


class MockWebSocketServer:
    """Standalone RFC 6455 WebSocket publisher for SparkShield dashboard clients."""

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8765,
        rate_hz: float = 10.0,
        seed: int = 42,
        model_path: Optional[str] = None,
    ):
        self.host = host
        self.port = port
        self.rate_hz = max(0.1, float(rate_hz))
        self.interval_sec = 1.0 / self.rate_hz
        self.streamer = MockStreamer(rate_hz=self.rate_hz, seed=seed)
        self.clients: Set[asyncio.StreamWriter] = set()
        self.is_running = False
        self.server: Optional[asyncio.Server] = None
        self.streaming_task: Optional[asyncio.Task] = None

        # Try to load ONNX inference session if available
        self.onnx_session = None
        target_model = model_path or os.path.join("artifacts", "sparkshield.onnx")
        if not os.path.exists(target_model):
            # Fallback check inside android_app assets
            alt_path = os.path.join("android_app", "app", "src", "main", "assets", "sparkshield_1d_cnn.onnx")
            if os.path.exists(alt_path):
                target_model = alt_path

        if os.path.exists(target_model):
            try:
                import onnxruntime as ort
                self.onnx_session = ort.InferenceSession(
                    target_model,
                    providers=["CPUExecutionProvider"]
                )
                logger.info("Loaded ONNX model for realistic inference: %s", target_model)
            except Exception as e:
                logger.warning("Could not initialize ONNX runtime (%s). Using algorithmic inference.", e)

    def trigger_tamper(self, signal_class: SignalClass, burst_count: int = 8):
        """Injects simulated tamper burst."""
        self.streamer.trigger_tamper(signal_class, burst_count=burst_count)

    def _perform_inference(self, feature_tensor: np.ndarray, frame: TelemetryFrame) -> Tuple[str, int, float, int]:
        """Runs ONNX model inference or fallback heuristic."""
        start_ns = time.perf_counter_ns()

        if self.onnx_session is not None and self.streamer.total_frames_sent >= 8:
            try:
                inputs = {self.onnx_session.get_inputs()[0].name: feature_tensor.astype(np.float32)}
                logits = self.onnx_session.run(None, inputs)[0]
                probs = _softmax(logits[0])
                class_idx = int(np.argmax(probs))
                confidence = float(probs[class_idx])
                latency_us = max(1, (time.perf_counter_ns() - start_ns) // 1000)
                label = CLASS_NAMES[class_idx]
                return label, class_idx, confidence, latency_us
            except Exception as e:
                logger.debug("Inference failed, falling back: %s", e)

        # Fallback heuristic based on frame event flags
        latency_us = max(1, (time.perf_counter_ns() - start_ns) // 1000)
        if frame.is_emp:
            return "EMP", 1, 0.965, latency_us
        elif frame.is_optical:
            return "OPTICAL", 2, 0.982, latency_us
        elif frame.is_surge:
            return "SURGE", 3, 0.941, latency_us
        else:
            return "NORMAL", 0, 0.992, latency_us

    def build_telemetry_payload(self) -> str:
        """Generates next deterministic frame, runs inference, and returns JSON."""
        frame, _, _, feature_tensor = self.streamer.next_frame()
        label, class_idx, confidence, latency_us = self._perform_inference(feature_tensor, frame)

        is_tamper = (class_idx != 0 and confidence >= 0.85)

        data = {
            "seqId": int(frame.sequence_id),
            "timestampMs": int(frame.timestamp_ms),
            "eventFlags": int(frame.event_flags),
            "peakMv": int(frame.peak_mv),
            "riseTimeNs": int(frame.rise_time_ns),
            "decayTimeUs": int(frame.decay_time_us),
            "opticalMv": int(frame.optical_sensor_mv),
            "fftBins": [int(b) for b in frame.fft_energy_bins],
            "classification": label,
            "confidence": round(confidence, 4),
            "inferenceTimeUs": int(latency_us),
            "tamperDetected": bool(is_tamper),
        }
        return json.dumps(data)

    @staticmethod
    def encode_ws_frame(message: str) -> bytes:
        """Encodes UTF-8 text message into an RFC 6455 unmasked WebSocket text frame."""
        payload = message.encode("utf-8")
        length = len(payload)

        if length <= 125:
            header = struct.pack("!BB", 0x81, length)
        elif length <= 65535:
            header = struct.pack("!BBH", 0x81, 126, length)
        else:
            header = struct.pack("!BBQ", 0x81, 127, length)

        return header + payload

    async def _handle_handshake(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> bool:
        """Performs RFC 6455 WebSocket opening handshake."""
        sec_key = None
        while True:
            line_bytes = await reader.readline()
            if not line_bytes:
                return False
            line = line_bytes.decode("utf-8", errors="ignore").strip()
            if not line:
                break
            if line.lower().startswith("sec-websocket-key:"):
                sec_key = line.split(":", 1)[1].strip()

        if not sec_key:
            return False

        accept_raw = hashlib.sha1((sec_key + WS_GUID).encode("utf-8")).digest()
        accept_str = base64.b64encode(accept_raw).decode("utf-8")

        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_str}\r\n"
            "\r\n"
        )
        writer.write(response.encode("utf-8"))
        await writer.drain()
        return True

    async def _client_handler(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        """Handles client connection lifecycle."""
        peer = writer.get_extra_info("peername")
        try:
            success = await self._handle_handshake(reader, writer)
            if not success:
                writer.close()
                await writer.wait_closed()
                return

            self.clients.add(writer)
            logger.info("Dashboard client connected: %s (Total clients: %d)", peer, len(self.clients))

            # Keep reading frames until disconnect (Ping/Pong/Close)
            while self.is_running:
                header = await reader.read(2)
                if len(header) < 2:
                    break

                b0, b1 = header[0], header[1]
                opcode = b0 & 0x0F
                is_masked = bool(b1 & 0x80)
                payload_len = b1 & 0x7F

                if payload_len == 126:
                    ext = await reader.read(2)
                    payload_len = struct.unpack("!H", ext)[0]
                elif payload_len == 127:
                    ext = await reader.read(8)
                    payload_len = struct.unpack("!Q", ext)[0]

                if is_masked:
                    mask = await reader.read(4)

                # Consume payload bytes
                if payload_len > 0:
                    _ = await reader.read(payload_len)

                if opcode == 0x8:  # Close
                    break
                elif opcode == 0x9:  # Ping -> reply Pong
                    writer.write(b"\x8A\x00")
                    await writer.drain()

        except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError):
            pass
        except Exception as e:
            logger.debug("Client handler error: %s", e)
        finally:
            self.clients.discard(writer)
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass
            logger.info("Dashboard client disconnected: %s (Remaining: %d)", peer, len(self.clients))

    async def _streaming_loop(self):
        """Asynchronously emits telemetry frames to all connected dashboard clients."""
        logger.info("Telemetry streaming loop started at %.1f Hz (interval: %.1f ms)", self.rate_hz, self.interval_sec * 1000)
        while self.is_running:
            start_t = time.perf_counter()

            if self.clients:
                payload = self.build_telemetry_payload()
                frame_bytes = self.encode_ws_frame(payload)

                disconnected = []
                for client in list(self.clients):
                    try:
                        client.write(frame_bytes)
                        await client.drain()
                    except Exception:
                        disconnected.append(client)

                for dead_client in disconnected:
                    self.clients.discard(dead_client)
                    try:
                        dead_client.close()
                    except Exception:
                        pass
            else:
                # Still advance internal generator state even if no clients connected
                _ = self.streamer.next_frame()

            elapsed = time.perf_counter() - start_t
            sleep_time = max(0.0, self.interval_sec - elapsed)
            await asyncio.sleep(sleep_time)

    async def start(self):
        """Starts the WebSocket server and background streamer."""
        self.is_running = True
        self.server = await asyncio.start_server(self._client_handler, self.host, self.port)
        logger.info("SparkShield Mock WebSocket Publisher listening on ws://%s:%d", self.host, self.port)
        self.streaming_task = asyncio.create_task(self._streaming_loop())

    async def stop(self):
        """Cleanly stops server, closes client sockets, and cancels background tasks."""
        self.is_running = False
        if self.streaming_task:
            self.streaming_task.cancel()
            try:
                await self.streaming_task
            except asyncio.CancelledError:
                pass

        for client in list(self.clients):
            try:
                client.close()
                await client.wait_closed()
            except Exception:
                pass
        self.clients.clear()

        if self.server:
            self.server.close()
            await self.server.wait_closed()
            self.server = None

        logger.info("Mock WebSocket Publisher stopped cleanly.")


def main():
    """Command-line entry point."""
    parser = argparse.ArgumentParser(description="SparkShield Mock WebSocket Publisher")
    parser.add_argument("--host", default="127.0.0.1", help="Host address (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8765, help="WebSocket port (default: 8765)")
    parser.add_argument("--rate", type=float, default=10.0, help="Frames per second (default: 10.0)")
    parser.add_argument("--seed", type=int, default=42, help="Deterministic random seed")
    parser.add_argument("--tamper", choices=["emp", "optical", "surge"], help="Inject initial tamper event")
    parser.add_argument("--demo-sequence", action="store_true", default=True, help="Cycle NORMAL-EMP-OPTICAL-SURGE (default: True)")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    )

    server = MockWebSocketServer(host=args.host, port=args.port, rate_hz=args.rate, seed=args.seed)

    if args.tamper:
        mapping = {
            "emp": SignalClass.EMP,
            "optical": SignalClass.OPTICAL,
            "surge": SignalClass.SURGE,
        }
        server.trigger_tamper(mapping[args.tamper], burst_count=8)

    async def runner():
        await server.start()
        print(f"\n==================================================================")
        print(f"  SparkShield Mock WebSocket Telemetry Publisher Active")
        print(f"  Listening on: ws://{args.host}:{args.port}")
        print(f"  Rate: {args.rate} Hz | Seed: {args.seed}")
        print(f"  Connect dashboard at http://localhost:3000?ws=ws://{args.host}:{args.port}")
        print(f"  Press Ctrl+C to terminate.")
        print(f"==================================================================\n")
        try:
            while True:
                await asyncio.sleep(1.0)
        except (KeyboardInterrupt, asyncio.CancelledError):
            print("\nShutting down server...")
        finally:
            await server.stop()

    try:
        asyncio.run(runner())
    except KeyboardInterrupt:
        print("Done.")


if __name__ == "__main__":
    main()
