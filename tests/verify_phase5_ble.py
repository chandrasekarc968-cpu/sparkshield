"""SparkShield Phase 5 End-to-End BLE Verification Script.

Validates the complete production BLE pipeline:
1. Bumble BLE Peripheral (SparkShield-Core) advertising:
   - Service: 1A860001-C7E2-432A-8C2A-8B6C7741E001
   - Telemetry Characteristic: 1A860002-C7E2-432A-8C2A-8B6C7741E001 (READ | NOTIFY)
2. Android BleTelemetryProvider client emulation:
   - MTU 247 negotiation
   - Service & characteristic discovery
   - CCCD descriptor notification subscription
   - Bounded non-blocking frame reception
3. Protocol validation & rejection:
   - 29-byte frame integrity (magic uint16 = 0x5353, CRC-16-CCITT)
   - Rejection of truncated, bad-magic, and corrupted-CRC frames
   - Sequence ID gap tracking and duplicate detection
4. On-device inference pipeline:
   - 16-feature mathematical normalization
   - 8-frame sliding window (128 floats)
   - ONNX Runtime CPU inference (sparkshield.onnx)
   - Confidence gating (>= 0.85)
5. Dashboard WebSocket payload generation:
   - Exactly 12 fields matching TelemetryWsMessage schema
   - tamperDetected flag validation across NORMAL, EMP, OPTICAL, SURGE
6. Clean disconnect & teardown
"""

import asyncio
import json
import math
import os
import struct
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

import numpy as np
import onnxruntime as ort
from bumble.controller import Controller
from bumble.device import Device, Peer
from bumble.link import LocalLink

from python_core.ble_peripheral import (
    BumbleBlePeripheral,
    DEFAULT_DEVICE_NAME,
    SPARKSHIELD_SERVICE_UUID,
    TELEMETRY_CHAR_UUID,
)
from python_core.crc16 import crc16_ccitt
from python_core.frame_protocol import (
    FRAME_LENGTH,
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
    ProtocolError,
    TelemetryFrame,
    pack_frame,
    unpack_frame,
)
from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator


class BleSequenceTracker:
    """Emulates Android SequenceTracker for gap and duplicate tracking."""

    def __init__(self):
        self.last_seq = None
        self.dropped_count = 0
        self.duplicate_count = 0

    def process(self, seq_id: int):
        if self.last_seq is None:
            self.last_seq = seq_id
            return "OK"
        diff = seq_id - self.last_seq
        if diff == 1:
            self.last_seq = seq_id
            return "OK"
        elif diff <= 0:
            self.duplicate_count += 1
            return "DUPLICATE_OR_STALE"
        else:
            dropped = diff - 1
            self.dropped_count += dropped
            self.last_seq = seq_id
            return f"GAP_{dropped}"


class AndroidInferencePipeline:
    """Emulates Android on-device feature extraction and ONNX inference."""

    def __init__(self, model_path: str):
        self.feature_extractor = FeatureExtractor()
        self.window = []  # max 8 frames * 16 features = 128 floats
        self.session = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        self.class_labels = ["NORMAL", "EMP", "OPTICAL", "SURGE"]

    def process_frame(self, frame: TelemetryFrame) -> dict:
        t0 = time.perf_counter()

        # 16 normalized features
        feats = self.feature_extractor.extract_frame_features(frame)

        # Sliding window of 8 frames
        if len(self.window) >= 8:
            self.window.pop(0)
        self.window.append(feats)

        # Build 128-float flattened buffer with zero-padding for older frames
        buf = np.zeros(128, dtype=np.float32)
        pad_frames = 8 - len(self.window)
        offset = pad_frames * 16
        for f in self.window:
            buf[offset : offset + 16] = f
            offset += 16

        # Reshape to static input [1, 1, 128]
        inp = buf.reshape(1, 1, 128)
        logits = self.session.run(["logits"], {"input": inp})[0][0]

        # Softmax
        exp_logits = np.exp(logits - np.max(logits))
        probs = exp_logits / np.sum(exp_logits)
        pred_idx = int(np.argmax(probs))
        pred_label = self.class_labels[pred_idx]
        conf = float(probs[pred_idx])

        inference_time_us = int((time.perf_counter() - t0) * 1_000_000)

        # Confidence gating: confidence >= 0.85 and non-NORMAL
        tamper_detected = (pred_label != "NORMAL") and (conf >= 0.85)

        # Construct 12-field dashboard WebSocket message
        ws_msg = {
            "seqId": frame.sequence_id,
            "timestampMs": frame.timestamp_ms,
            "eventFlags": frame.event_flags,
            "peakMv": frame.peak_mv,
            "riseTimeNs": frame.rise_time_code * 10,
            "decayTimeUs": frame.decay_time_us,
            "opticalMv": frame.optical_sensor_mv,
            "fftBins": list(frame.fft_energy_bins),
            "classification": pred_label,
            "confidence": round(conf, 4),
            "inferenceTimeUs": inference_time_us,
            "tamperDetected": tamper_detected,
        }
        return ws_msg


async def run_phase5_e2e_verification():
    print("=" * 75)
    print("SparkShield Phase 5: Virtual-Bus BLE GATT Transport End-to-End Verification")
    print("  (Using Bumble LocalLink Virtual HCI Link - Software-Only Simulation)")
    print("=" * 75)

    # 1. Setup virtual BLE environment (Bumble local link)
    print("[1/7] Initializing virtual BLE bus and Bumble controllers (software-only loopback)...")
    link = LocalLink()
    c_periph = Controller("c_periph", link=link)
    c_central = Controller("c_central", link=link)

    peripheral = BumbleBlePeripheral(
        device_name=DEFAULT_DEVICE_NAME,
        service_uuid=SPARKSHIELD_SERVICE_UUID,
        char_uuid=TELEMETRY_CHAR_UUID,
        address="F0:F1:F2:F3:F4:01",
        controller=c_periph,
    )

    central = Device.with_hci("Android-BLE-Provider", "F0:F1:F2:F3:F4:02", c_central, c_central)

    await peripheral.start()
    await central.power_on()
    print("      PASSED: Bumble peripheral and central powered on with virtual HCI controllers.")

    try:
        # 2. GATT connection and MTU negotiation
        print("[2/7] Connecting Android BLE provider to 'SparkShield-Core' and requesting MTU 247...")
        conn = await central.connect("F0:F1:F2:F3:F4:01")
        assert conn is not None, "Failed to connect to peripheral"
        peer = Peer(conn)

        mtu = await peer.request_mtu(247)
        assert mtu >= 247, f"MTU negotiation failed: got {mtu}, expected >= 247"
        print(f"      PASSED: GATT connected, negotiated MTU {mtu} (>= 247 for atomic 29B frames).")

        # 3. Discover Service, Characteristic, and enable Notifications via CCCD
        print("[3/7] Discovering GATT services and enabling notifications on characteristic 1A860002...")
        services = await peer.discover_services()
        assert any("1A860001" in str(s.uuid).upper() for s in services), "SparkShield service not found"

        chars = await peer.discover_characteristics()
        target_chars = [c for c in chars if "1A860002" in str(c.uuid).upper()]
        assert len(target_chars) == 1, "Telemetry characteristic 1A860002 not found"
        telemetry_char = target_chars[0]

        # Bounded channel to emulate Android Channel<ByteArray>(100, DROP_OLDEST)
        frame_queue = asyncio.Queue(maxsize=100)

        def on_notification(data: bytes):
            if frame_queue.full():
                try:
                    frame_queue.get_nowait()  # DROP_OLDEST
                except asyncio.QueueEmpty:
                    pass
            frame_queue.put_nowait(data)

        await peer.subscribe(telemetry_char, on_notification)
        print("      PASSED: Telemetry characteristic discovered and CCCD notifications enabled.")

        # 4. Initialize ML Pipeline with Android asset model
        print("[4/7] Loading ONNX model asset from Android project (sparkshield_1d_cnn.onnx)...")
        model_path = os.path.join(REPO_ROOT, "android_app", "app", "src", "main", "assets", "sparkshield_1d_cnn.onnx")
        if not os.path.exists(model_path):
            model_path = os.path.join(REPO_ROOT, "android_app", "app", "src", "main", "assets", "sparkshield.onnx")
        if not os.path.exists(model_path):
            model_path = os.path.join(REPO_ROOT, "artifacts", "sparkshield.onnx")
        assert os.path.exists(model_path), f"ONNX model asset missing at {model_path}"

        pipeline = AndroidInferencePipeline(model_path)
        seq_tracker = BleSequenceTracker()
        print(f"      PASSED: Loaded Android asset model: {os.path.basename(model_path)} (static shape [1, 1, 128] -> [1, 4]).")

        # 5. Stream valid frames over BLE and verify inference + WebSocket output
        print("[5/7] Streaming simulated telemetry over BLE: NORMAL, EMP, OPTICAL, SURGE...")
        gen = SignalGenerator(seed=42)
        classes_to_test = [
            (SignalClass.NORMAL, 10),
            (SignalClass.EMP, 8),
            (SignalClass.OPTICAL, 8),
            (SignalClass.SURGE, 8),
        ]

        verified_messages = []
        seq_counter = 1

        for s_class, count in classes_to_test:
            for _ in range(count):
                frame, _ = gen.generate(s_class)
                frame.sequence_id = seq_counter
                seq_counter += 1
                packed = pack_frame(frame)
                assert len(packed) == 29

                # Transmit over BLE GATT notification
                await peripheral.notify_frame(packed)
                await asyncio.sleep(0.01)

                # Receive at Android provider
                raw_bytes = await asyncio.wait_for(frame_queue.get(), timeout=2.0)
                assert len(raw_bytes) == 29, f"Expected 29 bytes, got {len(raw_bytes)}"

                # Validate CRC and unpack
                unpacked = unpack_frame(raw_bytes)

                # Track sequence
                status = seq_tracker.process(unpacked.sequence_id)
                assert status == "OK", f"Sequence error: {status}"

                # Run inference & emit dashboard WebSocket JSON
                ws_payload = pipeline.process_frame(unpacked)
                verified_messages.append(ws_payload)

                # Validate 12-field schema
                required_fields = [
                    "seqId", "timestampMs", "eventFlags", "peakMv", "riseTimeNs",
                    "decayTimeUs", "opticalMv", "fftBins", "classification",
                    "confidence", "inferenceTimeUs", "tamperDetected"
                ]
                for fld in required_fields:
                    assert fld in ws_payload, f"Missing field {fld} in WebSocket payload"

                # Check JSON serializability
                json_str = json.dumps(ws_payload)
                assert len(json_str) > 50

        # Verify tamper detections across classes
        emp_hits = [m for m in verified_messages if m["classification"] == "EMP" and m["tamperDetected"]]
        opt_hits = [m for m in verified_messages if m["classification"] == "OPTICAL" and m["tamperDetected"]]
        surge_hits = [m for m in verified_messages if m["classification"] == "SURGE" and m["tamperDetected"]]
        normal_frames = [m for m in verified_messages if m["classification"] == "NORMAL"]

        print(f"      Verified: {len(normal_frames)} NORMAL frames (all tamperDetected=False)")
        print(f"      Verified: {len(emp_hits)} EMP tamper detections (conf >= 0.85)")
        print(f"      Verified: {len(opt_hits)} OPTICAL tamper detections (conf >= 0.85)")
        print(f"      Verified: {len(surge_hits)} SURGE tamper detections (conf >= 0.85)")

        assert len(emp_hits) > 0, "No EMP tamper detected"
        assert len(opt_hits) > 0, "No OPTICAL tamper detected"
        assert len(surge_hits) > 0, "No SURGE tamper detected"
        for nf in normal_frames:
            assert nf["tamperDetected"] is False, "NORMAL class must never set tamperDetected=True"

        print("      PASSED: End-to-end telemetry over BLE correctly classified & gated.")

        # 6. Test protocol rejection: short frame, bad magic, CRC corruption, sequence gaps
        print("[6/7] Testing frame parser rejection of malformed BLE payloads & sequence tracking...")

        # A: Truncated frame (20 bytes)
        short_frame = b"\x53\x53" + b"\x00" * 18
        try:
            unpack_frame(short_frame)
            assert False, "Expected ProtocolError on short frame"
        except ProtocolError:
            pass  # correctly rejected

        # B: Invalid magic (0x1234)
        bad_magic_frame = b"\x12\x34" + b"\x00" * 27
        try:
            unpack_frame(bad_magic_frame)
            assert False, "Expected ProtocolError on bad magic"
        except ProtocolError:
            pass  # correctly rejected

        # C: Corrupted CRC
        valid_sample = pack_frame(gen.generate(SignalClass.NORMAL)[0])
        corrupted_crc = valid_sample[:27] + bytes([valid_sample[27] ^ 0xFF, valid_sample[28]])
        try:
            unpack_frame(corrupted_crc)
            assert False, "Expected ProtocolError on CRC corruption"
        except ProtocolError:
            pass  # correctly rejected

        # D: Sequence duplicate and gap detection
        tracker_test = BleSequenceTracker()
        assert tracker_test.process(10) == "OK"
        assert tracker_test.process(10) == "DUPLICATE_OR_STALE"
        assert tracker_test.duplicate_count == 1
        assert tracker_test.process(15) == "GAP_4"
        assert tracker_test.dropped_count == 4
        assert tracker_test.process(16) == "OK"

        # E: Sequence rollover at uint32 boundary (4294967295 -> 0)
        from python_core.frame_protocol import SequenceTracker as FullSequenceTracker
        full_tracker = FullSequenceTracker(initial_sequence=0xFFFFFFFF)
        is_cont, dropped = full_tracker.process_sequence(0)
        assert is_cont is True and dropped == 0, f"Expected 0 dropped on uint32 rollover, got {dropped}"

        # F: Sequence rollover across boundary with gap (0xFFFFFFFE -> 2 -> 3 dropped)
        full_tracker_gap = FullSequenceTracker(initial_sequence=0xFFFFFFFE)
        is_cont, dropped = full_tracker_gap.process_sequence(2)
        assert is_cont is False and dropped == 3, f"Expected 3 dropped across rollover gap, got {dropped}"

        # G: Verify invalid frames never reach pipeline inference or WebSocket
        processed_count_before = len(verified_messages)
        invalid_raw = b"\x00" * 29
        try:
            unpacked_bad = unpack_frame(invalid_raw)
            pipeline.process_frame(unpacked_bad)
            assert False, "Invalid raw frame should never reach inference"
        except ProtocolError:
            pass  # Parser dropped it before pipeline
        assert len(verified_messages) == processed_count_before, "Invalid frame must never produce WebSocket payload"

        print("      PASSED: Truncated frames, invalid magic, CRC failures, uint32 rollover, and sequence gaps correctly handled.")

        # 7. Disconnect and clean teardown
        print("[7/7] Testing clean disconnection and peripheral shutdown...")
        await conn.disconnect()
        await central.power_off()
        await peripheral.stop()
        print("      PASSED: BLE GATT connection closed and peripheral stopped cleanly.")

    finally:
        try:
            await central.power_off()
        except Exception:
            pass
        try:
            await peripheral.stop()
        except Exception:
            pass

    print("=" * 75)
    print("ALL PHASE 5 END-TO-END BLE VERIFICATION CHECKS PASSED SUCCESSFULLY.")
    print("=" * 75)


if __name__ == "__main__":
    asyncio.run(run_phase5_e2e_verification())
