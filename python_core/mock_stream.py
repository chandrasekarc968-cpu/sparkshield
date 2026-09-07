"""SparkShield Mock Telemetry Streamer.

Provides continuous, deterministic synthetic telemetry frame generation with
support for scheduled or interactive tamper signal injection (NORMAL, EMP, OPTICAL, SURGE).
Can stream over TCP, async queues, or local transports with accurate timing.
"""

import argparse
import asyncio
import logging
import sys
import time
from typing import AsyncGenerator, Callable, List, Optional, Tuple

import numpy as np

from python_core.ble_peripheral import MockLoopbackTransport, TelemetryTransport
from python_core.frame_protocol import (
    FRAME_LENGTH,
    TelemetryFrame,
    pack_frame,
    unpack_frame,
    validate_frame,
)
from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator

logger = logging.getLogger("sparkshield.stream")


class MockStreamer:
    """Streams packed 29-byte telemetry frames at target frequencies with deterministic scenarios."""

    def __init__(
        self,
        rate_hz: float = 10.0,
        seed: int = 42,
        transport: Optional[TelemetryTransport] = None,
    ):
        self.rate_hz = max(0.1, float(rate_hz))
        self.interval_sec = 1.0 / self.rate_hz
        self.generator = SignalGenerator(seed=seed)
        self.extractor = FeatureExtractor()
        self.transport = transport or MockLoopbackTransport()
        self.is_running = False
        self.active_class = SignalClass.NORMAL
        self.tamper_burst_remaining = 0
        self.total_frames_sent = 0

    def trigger_tamper(self, signal_class: SignalClass, burst_count: int = 5):
        """Injects a tamper event burst for `burst_count` consecutive frames."""
        self.active_class = signal_class
        self.tamper_burst_remaining = max(1, burst_count)
        logger.warning(
            "Tamper injected: %s for %d frames", signal_class.name, self.tamper_burst_remaining
        )

    def next_frame(self) -> Tuple[TelemetryFrame, bytes, np.ndarray, np.ndarray]:
        """Generates the next deterministic frame, packed bytes, waveform, and (1, 1, 128) feature tensor.

        Returns:
            Tuple of (TelemetryFrame, 29_byte_packed, waveform_128, feature_tensor_1_1_128).
        """
        # Check if tamper burst is active
        if self.tamper_burst_remaining > 0:
            current_class = self.active_class
            self.tamper_burst_remaining -= 1
            if self.tamper_burst_remaining == 0:
                self.active_class = SignalClass.NORMAL
        else:
            current_class = SignalClass.NORMAL

        # Generate frame and waveform
        frame, waveform = self.generator.generate(current_class)

        # Pack into 29 bytes with CRC
        packed_bytes = pack_frame(frame)

        # Update sliding window feature extractor -> (1, 1, 128)
        feature_tensor = self.extractor.update(frame)

        self.total_frames_sent += 1
        return frame, packed_bytes, waveform, feature_tensor

    async def stream_generator(
        self,
        max_frames: Optional[int] = None,
    ) -> AsyncGenerator[Tuple[TelemetryFrame, bytes, np.ndarray, np.ndarray], None]:
        """Async generator yielding telemetry frames at configured rate."""
        self.is_running = True
        count = 0
        try:
            while self.is_running and (max_frames is None or count < max_frames):
                start_time = time.perf_counter()

                frame, packed, wave, tensor = self.next_frame()
                await self.transport.send_frame(packed)

                yield frame, packed, wave, tensor
                count += 1

                elapsed = time.perf_counter() - start_time
                sleep_time = max(0.0, self.interval_sec - elapsed)
                await asyncio.sleep(sleep_time)
        finally:
            self.is_running = False

    async def run(
        self,
        duration_sec: Optional[float] = None,
        frame_callback: Optional[Callable[[TelemetryFrame, bytes], None]] = None,
    ):
        """Starts streaming loop."""
        await self.transport.start()
        start_ts = time.time()
        try:
            async for frame, packed, _, _ in self.stream_generator():
                if frame_callback:
                    frame_callback(frame, packed)
                if duration_sec and (time.time() - start_ts) >= duration_sec:
                    break
        finally:
            await self.transport.stop()


async def run_tcp_server(host: str = "127.0.0.1", port: int = 9002, rate_hz: float = 10.0):
    """Serves continuous 29-byte telemetry frames over raw TCP socket."""
    streamer = MockStreamer(rate_hz=rate_hz)
    logger.info("Starting SparkShield TCP Telemetry Server on %s:%d (Rate: %.1f Hz)", host, port, rate_hz)

    async def handle_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        addr = writer.get_extra_info("peername")
        logger.info("Client connected from %s", addr)
        try:
            while True:
                start_t = time.perf_counter()
                frame, packed, _, _ = streamer.next_frame()
                writer.write(packed)
                await writer.drain()

                elapsed = time.perf_counter() - start_t
                await asyncio.sleep(max(0.0, streamer.interval_sec - elapsed))
        except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError):
            logger.info("Client disconnected from %s", addr)
        finally:
            writer.close()
            await writer.wait_closed()

    server = await asyncio.start_server(handle_client, host, port)
    async with server:
        await server.serve_forever()


def main():
    """Command-line entry point for running mock stream."""
    parser = argparse.ArgumentParser(description="SparkShield Mock Telemetry Streamer")
    parser.add_argument("--rate", type=float, default=10.0, help="Frames per second (default: 10)")
    parser.add_argument("--count", type=int, default=20, help="Number of frames to emit (default: 20)")
    parser.add_argument("--tamper", choices=["emp", "optical", "surge"], help="Tamper event to inject")
    parser.add_argument("--tcp", action="store_true", help="Start TCP server on 127.0.0.1:9002")
    parser.add_argument("--port", type=int, default=9002, help="TCP port (default: 9002)")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="[%(asctime)s] [%(levelname)s] %(name)s: %(message)s",
    )

    if args.tcp:
        try:
            asyncio.run(run_tcp_server(port=args.port, rate_hz=args.rate))
        except KeyboardInterrupt:
            print("\nShutting down TCP server.")
        return

    streamer = MockStreamer(rate_hz=args.rate)
    if args.tamper:
        t_class = {
            "emp": SignalClass.EMP,
            "optical": SignalClass.OPTICAL,
            "surge": SignalClass.SURGE,
        }[args.tamper]
        # Schedule tamper after 5 frames
        for _ in range(5):
            f, raw, _, _ = streamer.next_frame()
            print(f"Frame #{f.sequence_id}: CLASS=NORMAL, Peak={f.peak_mv}mV, CRC=0x{f.crc16:04X}, raw={raw.hex()[:16]}...")
        streamer.trigger_tamper(t_class, burst_count=5)

    for _ in range(args.count):
        f, raw, _, tensor = streamer.next_frame()
        flag_str = "NORMAL"
        if f.is_emp:
            flag_str = "EMP"
        elif f.is_optical:
            flag_str = "OPTICAL"
        elif f.is_surge:
            flag_str = "SURGE"
        print(
            f"Seq={f.sequence_id:04d} | Class={flag_str:<7} | Peak={f.peak_mv:5d}mV | "
            f"Rise={f.rise_time_code:5d} ({f.rise_time_ns:6d}ns) | "
            f"Decay={f.decay_time_us:5d}us | Opt={f.optical_sensor_mv:4d}mV | "
            f"CRC=0x{f.crc16:04X} | TensorShape={tensor.shape}"
        )


if __name__ == "__main__":
    main()
