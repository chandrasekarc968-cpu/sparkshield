"""Unit tests for SparkShield Bumble BLE Peripheral Integration."""

import asyncio
import pytest

from bumble.controller import Controller
from bumble.device import Device, Peer
from bumble.link import LocalLink

from python_core.ble_peripheral import (
    BumbleBlePeripheral,
    DEFAULT_DEVICE_NAME,
    SPARKSHIELD_SERVICE_UUID,
    TELEMETRY_CHAR_UUID,
)
from python_core.frame_protocol import TelemetryFrame, pack_frame
from python_core.signal_models import SignalClass, SignalGenerator


def test_ble_constants():
    """Validates SparkShield production BLE UUID constants."""
    assert SPARKSHIELD_SERVICE_UUID == "1A860001-C7E2-432A-8C2A-8B6C7741E001"
    assert TELEMETRY_CHAR_UUID == "1A860002-C7E2-432A-8C2A-8B6C7741E001"
    assert DEFAULT_DEVICE_NAME == "SparkShield-Core"


def test_bumble_peripheral_lifecycle_and_virtual_connection():
    """Tests end-to-end virtual BLE connection, MTU negotiation, and 29-byte notification."""
    async def _run():
        link = LocalLink()
        c_periph = Controller("c_periph", link=link)
        c_central = Controller("c_central", link=link)

        peripheral = BumbleBlePeripheral(
            device_name="SparkShield-Core",
            service_uuid=SPARKSHIELD_SERVICE_UUID,
            char_uuid=TELEMETRY_CHAR_UUID,
            address="F0:F1:F2:F3:F4:F5",
            controller=c_periph,
        )

        central = Device.with_hci("Android-Central", "F0:F1:F2:F3:F4:F6", c_central, c_central)

        await peripheral.start()
        await central.power_on()

        # Connect central to peripheral
        connection = await central.connect("F0:F1:F2:F3:F4:F5")
        assert connection is not None
        peer = Peer(connection)

        # 1. Negotiate MTU 247
        negotiated_mtu = await peer.request_mtu(247)
        assert negotiated_mtu >= 247

        # 2. Discover service & telemetry characteristic
        services = await peer.discover_services()
        service_uuids = [str(s.uuid).upper() for s in services]
        assert any("1A860001" in u for u in service_uuids)

        chars = await peer.discover_characteristics()
        target_chars = [c for c in chars if "1A860002" in str(c.uuid).upper()]
        assert len(target_chars) == 1
        telemetry_char = target_chars[0]

        # 3. Subscribe to notifications
        received_frames = []
        await peer.subscribe(telemetry_char, lambda data: received_frames.append(data))

        # 4. Generate valid 29-byte frame
        gen = SignalGenerator(seed=42)
        frame, _ = gen.generate(SignalClass.NORMAL)
        packed = pack_frame(frame)
        assert len(packed) == 29

        # 5. Transmit notification
        await peripheral.notify_frame(packed)
        await asyncio.sleep(0.05)

        assert len(received_frames) == 1
        assert len(received_frames[0]) == 29
        assert received_frames[0] == packed

        # Clean teardown
        await connection.disconnect()
        await central.power_off()
        await peripheral.stop()

    asyncio.run(_run())
