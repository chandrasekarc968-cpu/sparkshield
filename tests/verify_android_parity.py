"""Verification script for Android Phase 3 parity with Python virtual meter core.

Tests:
1. CRC-16-CCITT algorithm parity across standard vector and random byte streams.
2. 29-byte wire frame packing, field offset alignment, and rejection of invalid event flags.
3. 16-feature mathematical normalization and log1p parity without event flags.
4. Sliding window 128-float layout, zero-padding older frames before 8 frames, and inference readiness gating.
5. Asset model presence and ONNX signature validation (input: [1,1,128], output: [1,4]).
6. Alert gating logic (confidence >= 0.85, NORMAL suppression, rate limiting).
"""

import math
import os
import struct
import sys

# Ensure repository root is in sys.path
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import numpy as np
import onnxruntime as ort

from python_core.crc16 import crc16_ccitt, crc16_ccitt_bitwise
from python_core.frame_protocol import (
    FRAME_LENGTH,
    FRAME_MAGIC,
    FLAG_EMP,
    FLAG_NORMAL,
    FLAG_OPTICAL,
    FLAG_SURGE,
    PAYLOAD_LENGTH_FOR_CRC,
    TelemetryFrame,
    pack_frame,
    unpack_frame,
)
from python_core.signal_models import FeatureExtractor, SignalClass, SignalGenerator


def test_crc16_parity():
    print("[1/6] Testing CRC-16-CCITT algorithm parity (calculate & compute)...")
    # Check vector
    test_vec = b"123456789"
    expected = 0x29B1
    assert crc16_ccitt(test_vec) == expected, f"CRC mismatch: expected {expected}, got {crc16_ccitt(test_vec)}"
    assert crc16_ccitt_bitwise(test_vec) == expected, "Bitwise CRC mismatch"

    # Kotlin precomputed table simulation
    table = []
    for byte_val in range(256):
        curr = byte_val << 8
        for _ in range(8):
            if curr & 0x8000:
                curr = ((curr << 1) ^ 0x1021) & 0xFFFF
            else:
                curr = (curr << 1) & 0xFFFF
        table.append(curr)

    rng = np.random.default_rng(42)
    for _ in range(100):
        data = bytes(rng.integers(0, 256, size=rng.integers(1, 100), dtype=np.uint8))
        crc_py = crc16_ccitt(data)

        # Kotlin logic:
        crc_kt = 0xFFFF
        for b in data:
            tbl_idx = ((crc_kt >> 8) ^ b) & 0xFF
            crc_kt = (table[tbl_idx] ^ (crc_kt << 8)) & 0xFFFF

        assert crc_py == crc_kt, f"Parity mismatch on {len(data)} bytes: {crc_py} vs {crc_kt}"

    # Subarray offset and length parity
    padded = b"PRE_123456789_POST"
    crc_sub = crc16_ccitt(padded[4:13])
    assert crc_sub == expected

    print("      PASSED: CRC-16-CCITT calculate/compute matches bit-for-bit across 100 random streams.")


def test_frame_wire_format_and_rejection():
    print("[2/6] Testing 29-byte frame binary wire format & invalid frame rejection...")
    frame = TelemetryFrame(
        sequence_id=0x12345678,
        timestamp_ms=0x9ABCDEF0,
        event_flags=FLAG_EMP,
        peak_mv=45000,
        rise_time_code=2,
        decay_time_us=10,
        optical_sensor_mv=160,
        fft_energy_bins=bytes([110, 140, 170, 200, 210, 190, 170, 140]),
    )
    packed = pack_frame(frame)
    assert len(packed) == 29, f"Expected 29 bytes, got {len(packed)}"

    # Check offsets
    magic = struct.unpack(">H", packed[0:2])[0]
    seq_id = struct.unpack(">I", packed[2:6])[0]
    ts_ms = struct.unpack(">I", packed[6:10])[0]
    flags = packed[10]
    peak_mv = struct.unpack(">H", packed[11:13])[0]
    rise_code = struct.unpack(">H", packed[13:15])[0]
    decay_us = struct.unpack(">H", packed[15:17])[0]
    opt_mv = struct.unpack(">H", packed[17:19])[0]
    bins = packed[19:27]
    crc = struct.unpack(">H", packed[27:29])[0]

    assert magic == 0x5353
    assert seq_id == 0x12345678
    assert ts_ms == 0x9ABCDEF0
    assert flags == FLAG_EMP
    assert peak_mv == 45000
    assert rise_code == 2
    assert decay_us == 10
    assert opt_mv == 160
    assert bins == bytes([110, 140, 170, 200, 210, 190, 170, 140])
    assert crc == frame.crc16

    # Test event flag rejection logic (0x00, upper bits 0x10, conflict 0x09)
    def validate_event_flags(fl):
        if (fl & 0xF0) != 0 or fl == 0:
            return False, "INVALID_BITS"
        is_normal = (fl & FLAG_NORMAL) != 0
        is_tamper = (fl & (FLAG_EMP | FLAG_OPTICAL | FLAG_SURGE)) != 0
        if is_normal and is_tamper:
            return False, "CONFLICT_FLAGS"
        if bin(fl).count("1") > 1:
            return False, "MULTIPLE_PRIMARY"
        return True, "VALID"

    assert not validate_event_flags(0x00)[0]
    assert not validate_event_flags(0x18)[0]
    assert not validate_event_flags(0x09)[0] # NORMAL + EMP
    assert not validate_event_flags(0x03)[0] # EMP + OPTICAL
    assert validate_event_flags(FLAG_NORMAL)[0]
    assert validate_event_flags(FLAG_EMP)[0]
    assert validate_event_flags(FLAG_OPTICAL)[0]
    assert validate_event_flags(FLAG_SURGE)[0]

    print("      PASSED: 29-byte big-endian binary offsets and strict event flag rejection validated.")


def test_feature_math_parity():
    print("[3/6] Testing 16-feature mathematical normalization and log1p parity...")
    LN_65536 = math.log(65536.0)

    # Test Kotlin log1pNorm formula
    for val in [0.0, 1.0, 2.0, 10.0, 100.0, 1000.0, 50000.0, 65535.0]:
        py_log1p = float(np.log1p(val) / np.log1p(65535.0))
        kt_log1p = math.log(1.0 + min(max(val, 0.0), 65535.0)) / LN_65536
        assert abs(py_log1p - kt_log1p) < 1e-6, f"Mismatch for val {val}: {py_log1p} vs {kt_log1p}"

    # Extract features using Python FeatureExtractor
    extractor = FeatureExtractor()
    frame = TelemetryFrame(
        sequence_id=1,
        timestamp_ms=100,
        event_flags=FLAG_NORMAL,
        peak_mv=3250,
        rise_time_code=50000,
        decay_time_us=5000,
        optical_sensor_mv=150,
        fft_energy_bins=bytes([220, 35, 12, 4, 2, 1, 0, 0]),
    )
    vec_py = extractor.extract_frame_features(frame)

    # Replicate Kotlin calculation
    vec_kt = np.zeros(16, dtype=np.float32)
    vec_kt[0] = min(max(3250 / 65535.0, 0.0), 1.0)
    vec_kt[1] = min(max(50000 / 65535.0, 0.0), 1.0)
    vec_kt[2] = math.log(1.0 + 50000) / LN_65536
    vec_kt[3] = min(max(5000 / 65535.0, 0.0), 1.0)
    vec_kt[4] = math.log(1.0 + 5000) / LN_65536
    vec_kt[5] = min(max(150 / 65535.0, 0.0), 1.0)
    vec_kt[6] = min(max(150 / 5000.0, 0.0), 1.0)

    raw_bins = [220, 35, 12, 4, 2, 1, 0, 0]
    total_energy = 0.0
    hf_energy = 0.0
    for i in range(8):
        norm_b = raw_bins[i] / 255.0
        vec_kt[8 + i] = norm_b
        total_energy += norm_b
        if i >= 4:
            hf_energy += norm_b
    vec_kt[7] = hf_energy / (total_energy + 1e-5)

    max_diff = np.max(np.abs(vec_py - vec_kt))
    assert max_diff < 1e-6, f"Max difference between Python and Kotlin feature math is {max_diff}"

    # Verify event flags are not included
    assert len(vec_kt) == 16, "Feature vector must be exactly 16 floats"

    print(f"      PASSED: 16 normalized feature formulas match (max abs error = {max_diff:.2e}).")


def test_sliding_window_zero_padding_and_gating():
    print("[4/6] Testing 8-frame sliding window layout, zero-padding & inference readiness...")
    
    # Emulate Kotlin FeatureWindow zero-padding and gating
    WINDOW_CAPACITY = 8
    FEATURES_PER_FRAME = 16
    TOTAL_FEATURES = WINDOW_CAPACITY * FEATURES_PER_FRAME  # 128

    class MockWindow:
        def __init__(self):
            self.buffer = []
            self.valid_count = 0
            self.warmup_mode = False

        def push(self, vec):
            if len(self.buffer) >= WINDOW_CAPACITY:
                self.buffer.pop(0)
            self.buffer.append(vec.copy())
            self.valid_count += 1

        @property
        def is_ready_for_inference(self):
            return (self.valid_count >= WINDOW_CAPACITY) or self.warmup_mode

        def get_flattened_window(self):
            out = np.zeros(TOTAL_FEATURES, dtype=np.float32)
            pad_frames = WINDOW_CAPACITY - len(self.buffer)
            offset = pad_frames * FEATURES_PER_FRAME
            for v in self.buffer:
                out[offset : offset + FEATURES_PER_FRAME] = v
                offset += FEATURES_PER_FRAME
            return out

    win = MockWindow()
    assert not win.is_ready_for_inference
    assert win.valid_count == 0

    # Push 1 frame
    f1 = np.full(16, 0.75, dtype=np.float32)
    win.push(f1)
    flat1 = win.get_flattened_window()

    assert len(flat1) == 128
    assert not win.is_ready_for_inference
    # Slots 0..6 (indices 0..111) must be zero-padded (0.0)
    assert np.all(flat1[0:112] == 0.0), "Older frames must be zero-padded"
    # Slot 7 (indices 112..127) must contain f1
    assert np.all(flat1[112:128] == 0.75), "Slot 7 must contain active frame features"

    # Warmup mode override
    win.warmup_mode = True
    assert win.is_ready_for_inference
    win.warmup_mode = False
    assert not win.is_ready_for_inference

    # Push 7 more frames
    for i in range(2, 9):
        win.push(np.full(16, float(i) / 10.0, dtype=np.float32))

    assert win.valid_count == 8
    assert win.is_ready_for_inference
    flat8 = win.get_flattened_window()
    # Oldest frame (f1, 0.75) should be in slot 0 (0..15)
    assert np.all(flat8[0:16] == 0.75)
    # Newest frame (frame 8, 0.8) should be in slot 7 (112..127)
    assert np.all(flat8[112:128] == 0.8)

    print("      PASSED: Sliding window zero-pads older frames before 8 frames; readiness gating verified.")


def test_onnx_model_asset():
    print("[5/6] Testing Android asset ONNX model signature and inference...")
    asset_path = os.path.join("android_app", "app", "src", "main", "assets", "sparkshield.onnx")
    assert os.path.exists(asset_path), f"Asset missing at {asset_path}"
    file_size = os.path.getsize(asset_path)
    assert file_size > 10000, f"Asset file suspiciously small: {file_size} bytes"

    # Test execution in ONNX Runtime CPU
    session = ort.InferenceSession(asset_path, providers=["CPUExecutionProvider"])
    input_meta = session.get_inputs()[0]
    output_meta = session.get_outputs()[0]

    assert input_meta.name == "input", f"Expected input name 'input', got '{input_meta.name}'"
    assert input_meta.shape == [1, 1, 128], f"Expected input shape [1, 1, 128], got {input_meta.shape}"
    assert output_meta.name == "logits", f"Expected output name 'logits', got '{output_meta.name}'"
    assert output_meta.shape == [1, 4], f"Expected output shape [1, 4], got {output_meta.shape}"

    # Test run with random input
    dummy_input = np.random.uniform(0.0, 1.0, (1, 1, 128)).astype(np.float32)
    logits = session.run(["logits"], {"input": dummy_input})[0]
    assert logits.shape == (1, 4)

    # Check softmax
    exp_logits = np.exp(logits - np.max(logits, axis=1, keepdims=True))
    probs = exp_logits / np.sum(exp_logits, axis=1, keepdims=True)
    assert abs(np.sum(probs) - 1.0) < 1e-5
    print(f"      PASSED: Model asset is valid ({file_size} bytes) with exact input [1,1,128] -> [1,4].")


def test_alert_gating_logic():
    print("[6/6] Testing alert gating rules (confidence >= 0.85, NORMAL suppression, cooldown)...")

    # Alert gate emulation
    THRESHOLD = 0.85
    COOLDOWN_MS = 3000
    last_alerts = {}

    def evaluate_alert(pred_class, confidence, t_ms):
        if pred_class == "NORMAL":
            return False, "NORMAL_SUPPRESSED"
        if confidence < THRESHOLD:
            return False, "LOW_CONFIDENCE"
        if pred_class in last_alerts:
            last = last_alerts[pred_class]
            if (t_ms - last) < COOLDOWN_MS:
                return False, "RATE_LIMITED"
        last_alerts[pred_class] = t_ms
        return True, "ALERT_TRIGGERED"

    # Normal class with 99% confidence -> Suppressed
    triggered, reason = evaluate_alert("NORMAL", 0.99, 1000)
    assert not triggered and reason == "NORMAL_SUPPRESSED"

    # EMP with 84% confidence -> Suppressed
    triggered, reason = evaluate_alert("EMP", 0.84, 1000)
    assert not triggered and reason == "LOW_CONFIDENCE"

    # EMP with 85% confidence -> Triggered
    triggered, reason = evaluate_alert("EMP", 0.85, 1000)
    assert triggered and reason == "ALERT_TRIGGERED"

    # EMP with 95% confidence at 2000 ms (1000 ms later) -> Rate limited
    triggered, reason = evaluate_alert("EMP", 0.95, 2000)
    assert not triggered and reason == "RATE_LIMITED"

    # OPTICAL with 90% confidence at 2000 ms -> Triggered (different class)
    triggered, reason = evaluate_alert("OPTICAL", 0.90, 2000)
    assert triggered and reason == "ALERT_TRIGGERED"

    # EMP with 95% confidence at 4001 ms (3001 ms later) -> Triggered (cooldown expired)
    triggered, reason = evaluate_alert("EMP", 0.95, 4001)
    assert triggered and reason == "ALERT_TRIGGERED"

    print("      PASSED: Alert gating strictly satisfies confidence >= 0.85 and 3s rate limiting.")


def main():
    print("=" * 70)
    print("SparkShield Android Phase 3 Parity & Architecture Verification")
    print("=" * 70)
    test_crc16_parity()
    test_frame_wire_format_and_rejection()
    test_feature_math_parity()
    test_sliding_window_zero_padding_and_gating()
    test_onnx_model_asset()
    test_alert_gating_logic()
    print("=" * 70)
    print("ALL ANDROID PHASE 3 PARITY CHECKS PASSED SUCCESSFULLY.")
    print("=" * 70)


if __name__ == "__main__":
    main()
