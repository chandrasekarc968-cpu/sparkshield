"""SparkShield Production Bumble BLE GATT Peripheral Service.

Streams 29-byte smart-meter telemetry frames over Bluetooth Low Energy GATT
using Google Bumble.

SIMULATION ONLY:
All sensor values, voltage spikes, and tamper events are purely software-simulated.
No physical electrical meters, high-voltage equipment, EMP device, or laser hardware is used.
"""

import argparse
import asyncio
import logging
import signal
import sys
import time
from typing import Optional

from python_core.ble_peripheral import (
    BumbleBlePeripheral,
    DEFAULT_DEVICE_NAME,
    SPARKSHIELD_SERVICE_UUID,
    TELEMETRY_CHAR_UUID,
)
from python_core.mock_stream import MockStreamer
from python_core.signal_models import SignalClass

logger = logging.getLogger("sparkshield.ble_service")


async def run_bumble_server(
    rate_hz: float = 10.0,
    name: str = DEFAULT_DEVICE_NAME,
    mode: str = "virtual",
    transport_spec: Optional[str] = None,
    seed: int = 42,
):
    """Runs Bumble BLE peripheral and streams 29-byte telemetry frames.

    Modes:
    - "virtual": Uses Bumble LocalLink virtual bus (for deterministic software-only testing).
    - "hardware": Uses physical Bluetooth HCI adapter or transport spec (e.g. usb:0, serial:COM3).
    """
    transport = None
    controller = None

    if mode == "virtual" and (transport_spec is None or transport_spec == "virtual"):
        from bumble.controller import Controller
        from bumble.link import LocalLink

        logger.info("Initializing Bumble LocalLink virtual HCI bus (software-only simulation mode)")
        link = LocalLink()
        controller = Controller("c_peripheral", link=link)
    else:
        # Hardware HCI transport requested
        effective_spec = transport_spec or "usb:0"
        logger.info("Attempting to open physical Bluetooth HCI transport: '%s'...", effective_spec)

        try:
            import bumble.transport

            transport = await bumble.transport.open_transport(effective_spec)
            logger.info("Successfully opened physical HCI transport '%s'", effective_spec)
        except Exception as exc:
            print("\n" + "=" * 70, file=sys.stderr)
            print(f"[ERROR] Failed to open Bluetooth HCI transport '{effective_spec}':", file=sys.stderr)
            print(f"        {exc}", file=sys.stderr)
            print("-" * 70, file=sys.stderr)
            print("To run with physical Bluetooth hardware:", file=sys.stderr)
            print("  - Connect a compatible Bluetooth USB dongle: --transport usb:0", file=sys.stderr)
            print("  - Or attach a serial HCI adapter:          --transport serial:COM3", file=sys.stderr)
            print("  - Or connect via HCI TCP socket:           --transport tcp-client:127.0.0.1:1234", file=sys.stderr)
            print("\nTo run deterministic software-only simulation without hardware:", file=sys.stderr)
            print("  python -m python_core.bumble_service --mode virtual", file=sys.stderr)
            print("=" * 70 + "\n", file=sys.stderr)
            sys.exit(1)

    peripheral = BumbleBlePeripheral(
        device_name=name,
        service_uuid=SPARKSHIELD_SERVICE_UUID,
        char_uuid=TELEMETRY_CHAR_UUID,
        controller=controller,
        transport=transport,
    )

    streamer = MockStreamer(rate_hz=rate_hz, seed=seed)

    await peripheral.start()
    print("==================================================================")
    print(f"  SparkShield Bumble BLE Peripheral Active")
    print(f"  Mode:           {'VIRTUAL (LocalLink)' if controller else f'HARDWARE ({transport_spec})'}")
    print(f"  Device Name:    {name}")
    print(f"  Service UUID:   {SPARKSHIELD_SERVICE_UUID}")
    print(f"  Char UUID:      {TELEMETRY_CHAR_UUID} (READ | NOTIFY)")
    print(f"  CCCD:           00002902-0000-1000-8000-00805f9b34fb (enabled)")
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
        if transport is not None:
            try:
                await transport.close()
            except Exception:
                pass
        print("Bumble peripheral stopped cleanly.")


def main():
    parser = argparse.ArgumentParser(description="SparkShield Bumble BLE Peripheral Service")
    parser.add_argument("--rate", type=float, default=10.0, help="Stream rate in Hz (default: 10.0)")
    parser.add_argument("--name", default=DEFAULT_DEVICE_NAME, help="Advertised device name (default: SparkShield-Core)")
    parser.add_argument("--mode", choices=["virtual", "hardware"], default="virtual", help="Execution mode (default: virtual)")
    parser.add_argument("--transport", default=None, help="HCI transport spec (e.g., virtual, usb:0, serial:COM3)")
    parser.add_argument("--seed", type=int, default=42, help="Deterministic seed for synthetic stream (default: 42)")
    args = parser.parse_args()

    # If explicit hardware transport is supplied, promote mode to hardware
    mode = args.mode
    if args.transport and args.transport != "virtual":
        mode = "hardware"

    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s: %(message)s")

    try:
        asyncio.run(run_bumble_server(
            rate_hz=args.rate,
            name=args.name,
            mode=mode,
            transport_spec=args.transport,
            seed=args.seed,
        ))
    except KeyboardInterrupt:
        print("Done.")


if __name__ == "__main__":
    main()

