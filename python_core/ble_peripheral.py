"""SparkShield Telemetry Transport Layer and BLE GATT Peripheral Integration.

Provides:
  - TelemetryTransport: Abstract base class for frame communication.
  - MockLoopbackTransport: In-memory async queue transport with simulated channel impairments.
  - BlePeripheralTransport: Backward-compatible BLE GATT peripheral abstraction.
  - BumbleBlePeripheral: Production Google Bumble BLE peripheral implementing GATT Service
    1A860001-C7E2-432A-8C2A-8B6C7741E001, Telemetry Characteristic 1A860002-C7E2-432A-8C2A-8B6C7741E001,
    MTU 247, and 29-byte frame notifications.

SIMULATION ONLY:
All telemetry, transients, and sensor readings represent software-simulated waveforms.
"""

import abc
import asyncio
import logging
import random
from typing import Callable, List, Optional

from python_core.frame_protocol import FRAME_LENGTH

logger = logging.getLogger("sparkshield.transport")

# SparkShield Production 128-bit UUIDs for Bluetooth Low Energy GATT
SPARKSHIELD_SERVICE_UUID = "1A860001-C7E2-432A-8C2A-8B6C7741E001"
TELEMETRY_CHAR_UUID = "1A860002-C7E2-432A-8C2A-8B6C7741E001"
CONTROL_CHAR_UUID = "1A860003-C7E2-432A-8C2A-8B6C7741E001"
DEFAULT_DEVICE_NAME = "SparkShield-Core"


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
    """In-memory loopback transport for local simulation, testing, and IPC."""

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

        if self.packet_loss_ratio > 0.0 and random.random() < self.packet_loss_ratio:
            self.dropped_count += 1
            return True

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
        device_name: str = DEFAULT_DEVICE_NAME,
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
        self._is_advertising = True
        logger.info("BlePeripheralTransport advertising as '%s' with Service UUID %s", self.device_name, self.service_uuid)
        return True

    async def stop(self) -> None:
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
        if not self._is_advertising:
            return False
        if len(frame_bytes) != FRAME_LENGTH:
            raise ValueError(f"Invalid frame size: {len(frame_bytes)}")

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


class BumbleBlePeripheral:
    """Production Google Bumble BLE GATT Peripheral for SparkShield-Core.

    Advertises service 1A860001-C7E2-432A-8C2A-8B6C7741E001 and characteristic
    1A860002-C7E2-432A-8C2A-8B6C7741E001 with READ & NOTIFY permissions.
    """

    def __init__(
        self,
        device_name: str = DEFAULT_DEVICE_NAME,
        service_uuid: str = SPARKSHIELD_SERVICE_UUID,
        char_uuid: str = TELEMETRY_CHAR_UUID,
        address: str = "F0:F1:F2:F3:F4:F5",
        controller=None,
        transport=None,
    ):
        self.device_name = device_name
        self.service_uuid = service_uuid
        self.char_uuid = char_uuid
        self.address = address
        self.controller = controller
        self.transport = transport
        self.device = None
        self.telemetry_char = None
        self.service = None
        self.is_running = False
        self._notification_subscribers = 0

    def initialize_gatt(self):
        """Builds Bumble Device, Service, and Characteristic objects."""
        from bumble.device import Device
        from bumble.gatt import Characteristic, Service

        self.telemetry_char = Characteristic(
            uuid=self.char_uuid,
            properties=Characteristic.Properties.READ | Characteristic.Properties.NOTIFY,
            permissions=Characteristic.Permissions.READABLE,
            value=b"\x00" * FRAME_LENGTH,
        )

        self.service = Service(self.service_uuid, [self.telemetry_char])

        if self.transport is not None:
            self.device = Device.with_hci(
                name=self.device_name,
                address=self.address,
                hci_source=self.transport.source,
                hci_sink=self.transport.sink,
            )
        elif self.controller is not None:
            self.device = Device.with_hci(
                name=self.device_name,
                address=self.address,
                hci_source=self.controller,
                hci_sink=self.controller,
            )
        else:
            self.device = Device(name=self.device_name, address=self.address)

        self.device.add_service(self.service)

    async def start(self):
        """Powers on device and initiates BLE advertising with Service UUID."""
        if self.device is None:
            self.initialize_gatt()

        from bumble.core import AdvertisingData, UUID

        await self.device.power_on()

        # Advertise device name and 128-bit SparkShield Service UUID
        adv_data = AdvertisingData([
            (AdvertisingData.COMPLETE_LOCAL_NAME, bytes(self.device_name, "utf-8")),
            (AdvertisingData.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS, bytes(UUID(self.service_uuid))),
        ])
        await self.device.start_advertising(advertising_data=bytes(adv_data), auto_restart=True)
        self.is_running = True
        logger.info("BumbleBlePeripheral active and advertising as '%s' (UUID: %s)", self.device_name, self.service_uuid)

    async def notify_frame(self, frame_bytes: bytes):
        """Transmits 29-byte notification to subscribed BLE centrals."""
        if not self.is_running or self.device is None or self.telemetry_char is None:
            return

        if len(frame_bytes) != FRAME_LENGTH:
            raise ValueError(f"Frame must be {FRAME_LENGTH} bytes, got {len(frame_bytes)}")

        await self.device.notify_subscribers(self.telemetry_char, frame_bytes)

    async def stop(self):
        """Stops advertising and powers off."""
        self.is_running = False
        if self.device is not None:
            try:
                await self.device.stop_advertising()
            except Exception:
                pass
            try:
                await self.device.power_off()
            except Exception:
                pass
        logger.info("BumbleBlePeripheral stopped cleanly")
