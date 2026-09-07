# SparkShield: Cyber-Physical Smart-Meter Tamper-Detection Architecture

SparkShield is a cyber-physical smart-meter tamper-detection architecture designed for edge deployment on Android and Qualcomm platforms. It utilizes high-resolution signal modeling and embedded deep learning to detect physical and electromagnetic attacks against smart energy meters in real time.

> [!IMPORTANT]
> **Safety and Simulation Boundary**:
> SparkShield strictly simulates tamper signals analytically in software. It does not control, damage, bypass, or interface with real electrical meters or physical attack hardware.

---

## Architecture Overview

```
+-------------------------------------------------------------+
|               Tier 1: Virtual Meter Core                    |
|  - Python 3.10+ Signal Generator & Protocol Engine          |
|  - 4 Deterministic Classes: NORMAL, EMP, OPTICAL, SURGE     |
|  - 29-Byte Packed Binary Telemetry Frames with CRC-16-CCITT |
|  - Mock Loopback & BLE GATT Peripheral Transports           |
+-------------------------------------------------------------+
                              |
                     [29-byte binary wire]
             (Mock In-Memory / BLE GATT / TCP / ADB)
                              v
+-------------------------------------------------------------+
|             Tier 2: On-Device Edge Inference                |
|  - Android Application (Kotlin, Foreground Service)         |
|  - Pluggable TelemetryProvider (Mock -> BLE)                |
|  - FeatureExtractor: 16 Features x 8 Frames -> (1, 1, 128)  |
|  - Pluggable InferenceEngine (CPU / ONNX / QNN Hexagon NPU) |
|  - High Confidence Gating (Alerts/Haptics only >= 0.85)     |
|  - Asynchronous TelemetryRepository (In-Memory -> Room)     |
+-------------------------------------------------------------+
                              |
                    [JSON Telemetry Stream]
             (WebSocket Server / Client / Reverse ADB)
                              v
+-------------------------------------------------------------+
|            Tier 3: Desktop Dashboard & Monitoring           |
|  - Dark Mode Web Dashboard (requestAnimationFrame Loop)     |
|  - Waveform & Peak History, Real-time Metrics & Alerts      |
|  - Stage 2: Node-RED Automation Flow Integration            |
+-------------------------------------------------------------+
```

---

## Protocol Specification (29 Bytes)

All multi-byte numeric fields are encoded in **Network Byte Order (Big-Endian)**.

| Byte Offset | Field Name | Type | Unit / Resolution | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0 - 1` | `magic` | `uint16` | `0x5353` | ASCII `'S'`, `'S'` synchronization word |
| `2 - 5` | `sequence_id` | `uint32` | 1 per frame | Monotonically increasing sequence number |
| `6 - 9` | `timestamp_ms` | `uint32` | 1 ms | Relative epoch or meter uptime |
| `10` | `event_flags` | `uint8` | Bitfield | Bit 0: EMP (`0x01`), Bit 1: OPTICAL (`0x02`), Bit 2: SURGE (`0x04`), Bit 3: NORMAL (`0x08`) |
| `11 - 12` | `peak_mv` | `uint16` | 1 mV | Peak voltage reading (0–65,535 mV) |
| `13 - 14` | `rise_time_code`| `uint16` | **10 ns / LSB** | Front rise time (`ns = code * 10`) |
| `15 - 16` | `decay_time_us` | `uint16` | **1 µs / LSB** | Signal decay time (`µs = code * 1`) |
| `17 - 18` | `optical_sensor_mv`| `uint16` | 1 mV | Photodiode sensor reading (0–5,000 mV rail) |
| `19 - 26` | `fft_energy_bins` | `uint8[8]`| Normalized energy | 8 discrete frequency envelope bands |
| `27 - 28` | `crc16` | `uint16` | Checksum | CRC-16-CCITT computed over bytes `0..26` |

---

## Repository Structure

```
.
|-- Makefile                   # Development build and test automation
|-- README.md                  # Project documentation and architecture guide
|-- docker-compose.yml         # Containerized services for virtual meter & dashboard
|-- android_app/               # On-device Android edge application
|   |-- app/                   # App build and Android manifest
|   |-- data/                  # In-memory circular buffer and Room DB
|   |-- inference/             # InferenceEngine interface (CPU / ONNX / QNN Hexagon)
|   |-- protocol/              # Kotlin 29-byte frame parser and feature window
|   |-- service/               # connectedDevice foreground service
|   |-- transport/             # TelemetryProvider (Mock / BLE)
|   +-- ui/                    # Dashboard UI and controls
|-- dashboard/                 # Desktop real-time web monitoring dashboard
|   |-- components/            # UI components (Waveform, Alerts, Metrics)
|   |-- src/                   # Dashboard logic & render loop
|   +-- websocket/             # WebSocket bridge
|-- docs/                      # Architectural and technical documentation
|   |-- architecture.md        # Detailed three-tier architecture & latency spec
|   |-- protocol.md            # 29-byte frame and sliding window tensor layout
|   +-- validation.md          # Verification matrix and test guide
|-- models/                    # Edge ML training and quantization
|   |-- calibration/           # INT8 calibration datasets and manifests
|   |-- export_onnx.py         # Static ONNX graph exporter
|   |-- quantize.py            # INT8 quantizer with scale/offset preservation
|   +-- train.py               # 1D CNN training pipeline (PyTorch)
+-- python_core/               # Virtual Meter Core (Tier 1)
    |-- __init__.py            # Package exports
    |-- ble_peripheral.py      # TelemetryTransport ABC, MockLoopback, and BLE GATT
    |-- crc16.py               # CRC-16-CCITT (False) dual bitwise and lookup-table
    |-- frame_protocol.py      # 29-byte frame pack/unpack/validate and SequenceTracker
    |-- mock_stream.py         # Paced telemetry streaming & TCP server
    |-- signal_models.py       # Deterministic signal generators & 128-float FeatureExtractor
    +-- tests/                 # Comprehensive pytest automated test suite
        |-- test_crc16.py
        |-- test_feature_extractor.py
        |-- test_mock_stream.py
        |-- test_protocol.py
        +-- test_signal_models.py
```

---

## Quickstart & Verification

### Run Automated Tests
```powershell
python -m pytest python_core/tests -v
```

### Stream Synthetic Telemetry (CLI)
```powershell
# Stream 20 frames with an EMP tamper burst injection
python -m python_core.mock_stream --rate 10 --count 20 --tamper emp
```

### Run Telemetry TCP Server
```powershell
python -m python_core.mock_stream --rate 10 --tcp --port 9002
```

---

## Development Roadmap

- [x] **Phase 1**: Virtual Meter Core, 29-byte protocol, deterministic signal models, mock stream, and automated tests.
- [ ] **Phase 2**: 1D CNN model training, static ONNX export, and INT8 quantization.
- [ ] **Phase 3**: Android foreground service (`connectedDevice`), CPU inference, high-confidence alert gating ($\ge 0.85$).
- [ ] **Phase 4**: WebSocket publisher and real-time dark monitoring dashboard.
- [ ] **Phase 5**: BLE transport integration over GATT peripheral.
- [ ] **Phase 6**: Room persistence and Node-RED automation adapter.
- [ ] **Phase 7**: Qualcomm QNN/QAIRT Hexagon HTP NPU hardware acceleration.
