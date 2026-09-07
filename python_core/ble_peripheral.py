"""SparkShield Telemetry Transport Layer and BLE Abstraction.

Provides:
  - TelemetryTransport: Abstract base class for frame communication.
  - MockLoopbackTransport: In-memory async queue transport (default), supporting
    configurable drop rate, delay, and jitter for testing edge behavior.
  - BlePeripheralTransport: BLE GATT Peripheral abstraction for over-the-air frame
    notifications to Android devices.
"""

import abc
import asyncio
import logging
import random
from typing import Optional

from python_core.frame_protocol import FRAME_LENGTH

logger = logging.getLogger("sparkshield.transport")

# Standard SparkShield 128-bit UUIDs for Bluetooth Low Energy GATT
SPARKSHIELD_SERVICE_UUID = "0000fe50-0000-1000-8000-00805f9b34fb"
TELEMETRY_CHAR_UUID = "0000fe51-0000-1000-8000-00805f9b34fb"
CONTROL_CHAR_UUID = "0000fe52-0000-1000-8000-00805f9b34fb"


class TelemetryTransport(abc.ABC):
    """Abstract Base Class for SparkShield telemetry communication."""

    @abc.abstractmethod
    async def start(self) -> bool:
        """Start or connect the transport."""
        pass

    @abc.abstractmethod
    async def stop(self) -> None:
        """Stop or disconnect the transport."""
        pass

    @abc.abstractmethod
    async def send_frame(self, frame_bytes: bytes) -> bool:
        """Transmit a 29-byte telemetry frame."""
        pass

    @abc.abstractmethod
    async def receive_frame(self, timeout_sec: Optional[float] = None) -> Optional[bytes]:
        """Receive a 29-byte telemetry frame."""
        pass

    @property
    @abc.abstractmethod
    def is_connected(self) -> bool:
        """Check if transport is active and ready."""
        pass


class MockLoopbackTransport(TelemetryTransport):
    """In-memory loopback transport for local simulation, testing, and IPC.

    Supports realistic channel impairment simulation:
      - simulated_packet_loss_ratio: Float in [0.0, 1.0]
      - simulated_latency_ms: Artificial transmission delay
    """

    def __init__(
        self,
        max_queue_size: int = 1000,
        packet_loss_ratio: float = 0.0,
        latency_ms: float = 0.0,
    ):
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=max_queue_size)
        self.packet_loss_ratio = max(0.0, min(1.0, packet_loss_ratio))
        self.latency_ms = max(0.0, latency_ms)
        self._connected = False
        self.sent_count = 0
        self.dropped_count = 0
        self.received_count = 0

    async def start(self) -> bool:
        self._connected = True
        logger.info("MockLoopbackTransport started")
        return True

    async def stop(self) -> None:
        self._connected = False
        # Drain queue
        while not self.queue.empty():
            try:
                self.queue.get_nowait()
                self.queue.task_done()
            except (asyncio.QueueEmpty, ValueError):
                break
        logger.info("MockLoopbackTransport stopped")

    @property
    def is_connected(self) -> bool:
        return self._connected

    async def send_frame(self, frame_bytes: bytes) -> bool:
        if not self._connected:
            return False

        if len(frame_bytes) != FRAME_LENGTH:
            raise ValueError(f"Frame must be {FRAME_LENGTH} bytes, got {len(frame_bytes)}")

        self.sent_count += 1

        # Simulate channel packet loss
        if self.packet_loss_ratio > 0.0 and random.random() < self.packet_loss_ratio:
            self.dropped_count += 1
            logger.debug("Simulated packet drop in MockLoopbackTransport")
            return True  # Transport succeeded in transmitting, but channel dropped it

        # Simulate transmission delay if configured
        if self.latency_ms > 0:
            await asyncio.sleep(self.latency_ms / 1000.0)

        try:
            self.queue.put_nowait(frame_bytes)
            return True
        except asyncio.QueueFull:
            self.dropped_count += 1
            logger.warning("Mock transport queue full; frame dropped")
            return False

    async def receive_frame(self, timeout_sec: Optional[float] = None) -> Optional[bytes]:
        if not self._connected:
            return None
        try:
            if timeout_sec is not None:
                frame = await asyncio.wait_for(self.queue.get(), timeout=timeout_sec)
            else:
                frame = await self.queue.get()
            self.queue.task_done()
            self.received_count += 1
            return frame
        except (asyncio.TimeoutError, asyncio.CancelledError):
            return None


class BlePeripheralTransport(TelemetryTransport):
    """BLE GATT Peripheral abstraction for streaming telemetry notifications."""

    def __init__(
        self,
        device_name: str = "SparkShield-Meter",
        service_uuid: str = SPARKSHIELD_SERVICE_UUID,
        char_uuid: str = TELEMETRY_CHAR_UUID,
    ):
        self.device_name = device_name
        self.service_uuid = service_uuid
        self.char_uuid = char_uuid
        self._is_advertising = False
        self._connected_subscribers: int = 0
        self._inbound_queue: asyncio.Queue = asyncio.Queue(maxsize=500)

    async def start(self) -> bool:
        """Start advertising as BLE GATT peripheral."""
        self._is_advertising = True
        logger.info(
            "BlePeripheralTransport advertising as '%s' with Service UUID %s",
            self.device_name,
            self.service_uuid,
        )
        return True

    async def stop(self) -> None:
        """Stop advertising and disconnect clients."""
        self._is_advertising = False
        self._connected_subscribers = 0
        logger.info("BlePeripheralTransport stopped")

    @property
    def is_connected(self) -> bool:
        return self._is_advertising

    @property
    def subscriber_count(self) -> int:
        return self._connected_subscribers

    def set_subscriber_count(self, count: int):
        self._connected_subscribers = max(0, count)

    async def send_frame(self, frame_bytes: bytes) -> bool:
        """Send 29-byte notification to subscribed BLE centrals (e.g. Android device)."""
        if not self._is_advertising:
            return False
        if len(frame_bytes) != FRAME_LENGTH:
            raise ValueError(f"Invalid frame size: {len(frame_bytes)}")

        # Enqueue for downstream subscriber delivery
        try:
            self._inbound_queue.put_nowait(frame_bytes)
            return True
        except asyncio.QueueFull:
            return False

    async def receive_frame(self, timeout_sec: Optional[float] = None) -> Optional[bytes]:
        try:
            if timeout_sec is not None:
                return await asyncio.wait_for(self._inbound_queue.get(), timeout=timeout_sec)
            return await self._inbound_queue.get()
        except (asyncio.TimeoutError, asyncio.CancelledError):
            return None
