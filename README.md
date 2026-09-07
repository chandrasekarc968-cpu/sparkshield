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

---

## Phase 4: WebSocket Telemetry Streaming & Real-Time Dashboard

Phase 4 introduces live multi-client WebSocket publishing from the Android edge service (and standalone local Python mock publisher) directly to the desktop real-time dark monitoring dashboard.

### WebSocket Endpoint & Port Allocation
- **Default Endpoint**: `ws://localhost:8765`
- **Default Dashboard Port**: `http://localhost:3000`
- **Configurable**: Change host/port via `--port <port>` in Python or the dashboard header input field.

### Message Schema (JSON)
Every telemetry frame emitted over WebSocket includes the following 12 fields:

```json
{
  "seqId": 1420,
  "timestampMs": 1725700000000,
  "eventFlags": 1,
  "peakMv": 12500,
  "riseTimeNs": 20,
  "decayTimeUs": 50,
  "opticalMv": 250,
  "fftBins": [10, 15, 20, 25, 30, 20, 10, 5],
  "classification": "EMP",
  "confidence": 0.9650,
  "inferenceTimeUs": 420,
  "tamperDetected": true
}
```

| Field Name | Type | Unit / Semantics | Description |
| :--- | :--- | :--- | :--- |
| `seqId` | `number` | Counter | Monotonically increasing sequence ID counter |
| `timestampMs` | `number` | Milliseconds | Epoch timestamp |
| `eventFlags` | `number` | Bitmask | Bit 0=EMP, 1=OPTICAL, 2=SURGE, 3=NORMAL |
| `peakMv` | `number` | Millivolts | Peak sensor voltage (0–65,535 mV) |
| `riseTimeNs` | `number` | Nanoseconds | Transient rise time ($10\text{ ns/LSB}$) |
| `decayTimeUs` | `number` | Microseconds | Transient decay duration ($1\ \mu\text{s/LSB}$) |
| `opticalMv` | `number` | Millivolts | Photodiode sensor reading (0–5,000 mV) |
| `fftBins` | `number[8]` | Normalized power | 8-bin frequency energy envelope |
| `classification`| `string` | Label | Model decision: `"NORMAL"`, `"EMP"`, `"OPTICAL"`, or `"SURGE"` |
| `confidence` | `number` | $[0.0, 1.0]$ | Softmax probability score of winning class |
| `inferenceTimeUs`| `number` | Microseconds | Execution duration measured on CPU via `System.nanoTime()` |
| `tamperDetected`| `boolean` | Flag | `true` only when `confidence >= 0.85` and class is not `NORMAL` |

---

## Quickstart: Phase 4 Local Development Mode (No Hardware Required)

You can launch and demonstrate the entire telemetry ingestion and real-time visualization pipeline on your local machine without Android hardware, ADB, or physical meters.

### Step 1: Start Mock WebSocket Publisher
```powershell
# From repository root
python -m python_core.mock_ws_server --port 8765
```
Or via Makefile:
```bash
make mock-ws
```

### Step 2: Launch the Real-Time Dashboard
In a separate terminal:
```powershell
cd dashboard
npm install
npm run dev
```
Open your browser to: **`http://localhost:3000`**

### Dashboard Build & Test Commands
```powershell
cd dashboard
npm test              # Run automated unit tests (RingBuffer, WebSocketClient, State)
npm run build         # Produce optimized production bundle in dashboard/dist/
npm run preview       # Preview production build on http://localhost:3000
```

---

## Phase 5: Production BLE GATT Transport Integration

Phase 5 establishes real-time Bluetooth Low Energy (BLE) GATT telemetry streaming from the Python host peripheral to the Android edge inference engine.

### BLE Architecture & GATT Specifications
- **Device Name**: `SparkShield-Core`
- **Service UUID**: `1A860001-C7E2-432A-8C2A-8B6C7741E001`
- **Telemetry Characteristic UUID**: `1A860002-C7E2-432A-8C2A-8B6C7741E001` (`READ | NOTIFY`)
- **Client Characteristic Configuration Descriptor (CCCD)**: `00002902-0000-1000-8000-00805f9b34fb`
- **MTU Size**: `247` bytes negotiated for atomic, non-fragmented 29-byte frame deliveries
- **Payload**: Strict 29-byte big-endian frames with CRC-16-CCITT validation

### Android Permissions (API 31 - 36 / Android 12 - 16)
The following runtime permissions are declared and handled:
- `android.permission.BLUETOOTH_SCAN`: Configured with `neverForLocation` flag
- `android.permission.BLUETOOTH_CONNECT`: For connecting to GATT server and receiving notifications
- `android.permission.POST_NOTIFICATIONS`: For high-priority tamper alerts (Android 13+)
- `android.permission.ACCESS_FINE_LOCATION`: Fallback for legacy devices (Android <= 11)

### Provider Modes: `AUTO`, `BLE`, `MOCK`
The Android monitoring service supports runtime-selectable telemetry ingestion:
1. **`AUTO` (Default)**: Inspects Bluetooth availability and runtime permissions. If present, connects to `SparkShield-Core` over BLE. If BLE permissions or hardware adapter are unavailable, it seamlessly falls back to `MockTelemetryProvider` with a visible status reason (`Fallback to MOCK: Bluetooth permissions not granted`).
2. **`BLE`**: Strictly requires BLE connection; attempts reconnection with bounded exponential backoff (1s, 2s, 4s, 8s, up to 15s max).
3. **`MOCK`**: Pure local deterministic synthetic streaming without touching Bluetooth radio.

### Running the Python Bumble Peripheral
To launch the Bumble BLE peripheral:
```powershell
python -m python_core.bumble_service --rate 10 --name SparkShield-Core
```
Or via Makefile:
```bash
make run-ble-peripheral
```

### Bluetooth Troubleshooting
- **Missing Permissions**: Grant `Nearby Devices` (Bluetooth) permission to SparkShield in Android App Info settings.
- **Adapter Disabled**: Turn on Bluetooth in system settings.
- **Peripheral Not Found**: Ensure Python peripheral is running and not already bonded to another central.
- **Notification Stalls**: Check if MTU 247 negotiation completed. Bounded channel buffer (`100` frames, `DROP_OLDEST`) ensures backpressure never blocks GATT callback threads.

### Verification & Test Commands
```powershell
# Run Bumble BLE unit tests
python -m pytest python_core/tests/test_ble_bumble.py -v

# Run Phase 5 end-to-end BLE GATT pipeline verifier
python tests/verify_phase5_ble.py

# Run complete regression suite
make test-all
```

---

## Phase 5 Demo Checklist

- [x] **Peripheral Advertising**: Python Bumble peripheral advertises `SparkShield-Core` with service `1A860001-C7E2-432A-8C2A-8B6C7741E001`.
- [x] **GATT Characteristic**: Telemetry characteristic `1A860002-C7E2-432A-8C2A-8B6C7741E001` supports `READ` and `NOTIFY`.
- [x] **MTU 247 Negotiation**: Peripheral and central negotiate MTU $\ge 247$ to stream 29-byte frames atomically.
- [x] **Android BleTelemetryProvider**: Implements `TelemetryProvider` with non-blocking bounded buffering (`Channel(100, DROP_OLDEST)`).
- [x] **Provider Mode Auto-Fallback**: In `AUTO` mode, gracefully falls back to `MockTelemetryProvider` when Bluetooth permissions or adapter are missing.
- [x] **Frame Integrity & CRC**: Validates length (29B), magic (`0x5353`), CRC-16, and field ranges; drops malformed frames cleanly.
- [x] **Sequence Tracking**: SequenceTracker monitors monotonically increasing sequence IDs and logs gaps and duplicate frames.
- [x] **Bounded Backoff Reconnect**: Reconnection attempts scale exponentially (1s, 2s, 4s, 8s) up to 15s max.
- [x] **End-to-End Pipeline**: Verified flow: Bumble Peripheral $\to$ BleTelemetryProvider $\to$ FeatureExtractor $\to$ ONNX Inference $\to$ WebSocket Publisher $\to$ Dashboard.

---

## Known Limitations

1. **Simulation Boundary**: All telemetry, transient waveforms, optical saturations, and inductive surges are generated mathematically by software models. The system does not interface with physical electrical meters, high-voltage equipment, or laser injection hardware.
2. **Deferred Persistence (Phase 6)**: High-rate telemetry frames are processed in-memory to preserve flash endurance; persistent SQLite/Room event logging is deferred to Phase 6.
3. **Deferred Qualcomm QNN (Phase 7)**: Edge inference runs on CPU via ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android:1.19.0`). Qualcomm Hexagon NPU hardware acceleration is planned for Phase 7.

---

## Development Roadmap

- [x] **Phase 1**: Virtual Meter Core, 29-byte protocol, deterministic signal models, mock stream, and automated tests.
- [x] **Phase 2**: 1D CNN model training, static ONNX export, numerical parity verification, and INT8 quantization.
- [x] **Phase 3**: Android foreground service (`connectedDevice`), CPU ONNX inference, high-confidence alert gating ($\ge 0.85$).
- [x] **Phase 4**: Asynchronous WebSocket publisher, local mock streamer, and real-time dark monitoring dashboard.
- [x] **Phase 5**: Production BLE transport integration (Bumble peripheral, GATT service/characteristic, Android BleTelemetryProvider, AUTO fallback).
- [ ] **Phase 6**: Room persistence and Node-RED automation adapter.
- [ ] **Phase 7**: Qualcomm QNN/QAIRT Hexagon HTP NPU hardware acceleration.


