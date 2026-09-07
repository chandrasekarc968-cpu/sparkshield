# SparkShield Validation and Verification Guide

## 1. Automated Test Matrix

The following test suite validates the core cyber-physical virtual meter and protocol layers:

| ID | Test Case | Target / Function | Pass Criteria | Status |
| :--- | :--- | :--- | :--- | :--- |
| `TC-01` | CRC-16-CCITT Known Vector | `test_crc16_standard_check_vector` | Input `b"123456789"` returns `0x29B1` | **PASS** |
| `TC-02` | CRC-16 Dual Engine Consistency | `test_crc16_bitwise_and_table_equivalence` | Bitwise and lookup table outputs match across variable buffer sizes | **PASS** |
| `TC-03` | 29-Byte Frame Length | `test_frame_length_is_strictly_29_bytes` | Packed bytes length is exactly 29 | **PASS** |
| `TC-04` | Big-Endian Encoding | `test_big_endian_field_encoding` | Multi-byte fields correctly placed at byte offsets in MSB order | **PASS** |
| `TC-05` | Round-Trip Pack/Unpack | `test_round_trip_pack_unpack` | `unpack(pack(frame)) == frame` across all fields | **PASS** |
| `TC-06` | Bad Magic Rejection | `test_bad_magic_rejection` | Corrupted magic (`!= 0x5353`) rejected by validator and unpacker | **PASS** |
| `TC-07` | Bad CRC Rejection | `test_bad_crc_rejection` | Bit flip in payload raises `CrcMismatchError` and fails validation | **PASS** |
| `TC-08` | Sequence Discontinuity Detection | `test_sequence_tracker_continuity` | Correctly flags gaps, computes dropped frame count, detects duplicates | **PASS** |
| `TC-09` | 32-bit Sequence Wraparound | `test_sequence_tracker_wraparound` | Smooth rollover from `0xFFFFFFFF` to `0` without false drop alert | **PASS** |
| `TC-10` | Deterministic Signal Generation | `test_signal_generator_determinism` | Identical seed produces identical frames and waveforms | **PASS** |
| `TC-11` | 4 Signal Classes Parameters | `test_*_signal_characteristics` | NORMAL, EMP, OPTICAL, SURGE conform to physical bounds and units | **PASS** |
| `TC-12` | Feature Tensor Shape | `test_feature_extractor_tensor_shape` | Sliding window output shape is strictly `(1, 1, 128)` float32 | **PASS** |
| `TC-13` | Feature Normalization Bounds | `test_feature_extractor_normalization_bounds` | All 128 tensor values are strictly within `[0.0, 1.0]` with no NaNs/Infs | **PASS** |
| `TC-14` | FIFO Window Shifting | `test_feature_extractor_sliding_window_fifo` | Oldest frame is shifted out as new frame enters | **PASS** |
| `TC-15` | Stream Pacing & Generation | `test_mock_streamer_deterministic_pacing` | Continuous streaming yields valid 29-byte frames at configured rate | **PASS** |
| `TC-16` | Tamper Burst Lifecycle | `test_tamper_burst_injection_lifecycle` | Injected tamper class persists for requested burst count and reverts | **PASS** |
| `TC-17` | Packet Loss Simulation | `test_transport_packet_loss_simulation` | Simulated loss ratio properly drops packets in loopback queue | **PASS** |

---

## 2. Running Automated Tests

Execute the complete Python test suite via pytest:

```powershell
python -m pytest python_core/tests -v
```

Expected output:
```
python_core/tests/test_crc16.py ................ [PASS]
python_core/tests/test_feature_extractor.py .... [PASS]
python_core/tests/test_mock_stream.py .......... [PASS]
python_core/tests/test_protocol.py ............. [PASS]
python_core/tests/test_signal_models.py ........ [PASS]
============================= 27 passed in 0.43s ==============================
```

---

## 3. Known Limitations and Phase Boundaries

1. **Analytic EMP Transient Model**:
   - EMP pulses are simulated analytically through physics equations rather than RF ADC sampling to respect Nyquist limits on standard microcontrollers.
2. **Hardware Acceleration Status**:
   - QNN/Hexagon HTP hardware acceleration is currently architectural and behind the `InferenceEngine` interface; it is not marked complete until validated on physical Qualcomm hardware.
