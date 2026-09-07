# SparkShield Validation and Verification Guide

## 1. Automated Test Matrix

The following test suite validates the core cyber-physical virtual meter, protocol, and ML pipeline layers:

### Phase 1: Virtual Meter Core & Protocol Tests (`python_core/tests`)

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

### Phase 2: Edge ML Pipeline, ONNX, and Quantization Tests (`models/tests`)

| ID | Test Case | Target / Function | Pass Criteria | Status |
| :--- | :--- | :--- | :--- | :--- |
| `TC-18` | Dataset Shapes and Types | `test_dataset_generation_shapes_and_types` | `X` is strictly `(N, 1, 128)` float32, `y` is `(N,)` int64, class balanced | **PASS** |
| `TC-19` | Leak-Free Split Isolation | `test_leak_free_split_isolation` | No identical samples exist between train, val, and test splits | **PASS** |
| `TC-20` | Model Forward Pass | `test_model_forward_pass` | `SparkShield1DCNN` transforms `(B, 1, 128)` to `(B, 4)` across batch sizes | **PASS** |
| `TC-21` | Mini-Training & Checkpoint | `test_mini_training_and_checkpoint_payload` | Loss decreases, validation Macro F1 computed, checkpoint keys complete | **PASS** |
| `TC-22` | Static ONNX Dimensions | `test_onnx_export_static_shape_and_validation` | Input is static `[1, 1, 128]`, output is static `[1, 4]`, passes checker | **PASS** |
| `TC-23` | ONNX Numerical Parity | `test_onnx_runtime_inference_numerical_parity` | PyTorch and ONNX Runtime outputs match within $\le 10^{-5}$ tolerance | **PASS** |
| `TC-24` | Calibration Generator | `test_calibration_dataset_generator` | 200 balanced samples `(200, 1, 128)`, manifest distributions verified | **PASS** |
| `TC-25` | Calibration Data Reader | `test_calibration_data_reader` | Yields dict with `(1, 1, 128)` arrays with exact input node name | **PASS** |
| `TC-26` | INT8 Quantization & Manifest | `test_int8_quantization_and_manifest_recording` | Static QDQ ONNX executes to `(1, 4)`, scale/zero-point parameters recorded | **PASS** |

---

## 2. Running Automated Tests

Execute the complete 36-test suite across Phase 1 and Phase 2:

```powershell
python -m pytest python_core/tests models/tests -v
```

Expected output:
```
python_core/tests/test_crc16.py ................ [PASS]
python_core/tests/test_feature_extractor.py .... [PASS]
python_core/tests/test_mock_stream.py .......... [PASS]
python_core/tests/test_protocol.py ............. [PASS]
python_core/tests/test_signal_models.py ........ [PASS]
models/tests/test_onnx_export.py ............... [PASS]
models/tests/test_quantization.py .............. [PASS]
models/tests/test_training_pipeline.py ......... [PASS]
============================= 36 passed in 18.36s ==============================
```

---

## 3. Known Limitations and Phase Boundaries

1. **Analytic EMP Transient Model**:
   - EMP pulses are simulated analytically through transient differential equations rather than raw RF ADC sampling to avoid Nyquist aliasing.
2. **Hardware Acceleration Status**:
   - Quantized ONNX model has been verified with ONNX Runtime CPU. Qualcomm QNN/QAIRT Hexagon HTP execution is architectural and will be benchmarked on target Qualcomm hardware in Phase 7.
