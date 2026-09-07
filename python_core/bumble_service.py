"""SparkShield Production Bumble BLE GATT Peripheral Service.

Streams 29-byte smart-meter telemetry frames over Bluetooth Low Energy GATT
using Google Bumble.

SIMULATION ONLY:
All sensor values, voltage spikes, and tamper events are purely software-simulated.
"""

import argparse
import asyncio
import logging
import signal
import sys
import time

from python_core.ble_peripheral import (
    BumbleBlePeripheral,
    DEFAULT_DEVICE_NAME,
    SPARKSHIELD_SERVICE_UUID,
    TELEMETRY_CHAR_UUID,
)
from python_core.mock_stream import MockStreamer
from python_core.signal_models import SignalClass

logger = logging.getLogger("sparkshield.ble_service")


async def run_bumble_server(rate_hz: float = 10.0, name: str = DEFAULT_DEVICE_NAME, transport_str: str = "virtual"):
    """Runs Bumble BLE peripheral and streams 29-byte telemetry frames."""
    from bumble.controller import Controller
    from bumble.link import LocalLink

    link = LocalLink()
    controller = Controller("c_peripheral", link=link)

    peripheral = BumbleBlePeripheral(
        device_name=name,
        service_uuid=SPARKSHIELD_SERVICE_UUID,
        char_uuid=TELEMETRY_CHAR_UUID,
        controller=controller,
    )

    streamer = MockStreamer(rate_hz=rate_hz, seed=42)

    await peripheral.start()
    print("==================================================================")
    print(f"  SparkShield Bumble BLE Peripheral Active")
    print(f"  Device Name:    {name}")
    print(f"  Service UUID:   {SPARKSHIELD_SERVICE_UUID}")
    print(f"  Char UUID:      {TELEMETRY_CHAR_UUID}")
    print(f"  Stream Rate:    {rate_hz} Hz ({1000/rate_hz:.1f} ms interval)")
    print(f"  Frame Protocol: 29-byte Big-Endian with CRC-16-CCITT")
    print(f"  Press Ctrl+C to terminate.")
    print("==================================================================")

    interval = 1.0 / rate_hz
    try:
        while peripheral.is_running:
            start_t = time.perf_counter()
            frame, packed_bytes, _, _ = streamer.next_frame()

            await peripheral.notify_frame(packed_bytes)

            elapsed = time.perf_counter() - start_t
            sleep_time = max(0.0, interval - elapsed)
            await asyncio.sleep(sleep_time)
    except (asyncio.CancelledError, KeyboardInterrupt):
        print("\nStopping BLE peripheral...")
    finally:
        await peripheral.stop()
        print("Bumble peripheral stopped cleanly.")


def main():
    parser = argparse.ArgumentParser(description="SparkShield Bumble BLE Peripheral Service")
    parser.add_argument("--rate", type=float, default=10.0, help="Stream rate in Hz (default: 10.0)")
    parser.add_argument("--name", default=DEFAULT_DEVICE_NAME, help="Advertised device name (default: SparkShield-Core)")
    parser.add_argument("--transport", default="virtual", help="HCI Transport spec (default: virtual)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s: %(message)s")

    try:
        asyncio.run(run_bumble_server(rate_hz=args.rate, name=args.name, transport_str=args.transport))
    except KeyboardInterrupt:
        print("Done.")


if __name__ == "__main__":
    main()
