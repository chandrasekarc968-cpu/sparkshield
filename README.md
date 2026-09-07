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

## Phase 4 Demo Checklist

- [x] **Connection Status**: Green dot indicates live WebSocket connection; automatically reconnects with backoff if publisher restarts.
- [x] **Classification Visuals**: Emerald green card for `NORMAL`; glowing, high-priority pulsing red card and banner for `EMP` and `OPTICAL`; amber card for `SURGE`.
- [x] **Confidence Gating**: Confidence meter dynamically reflects softmax probabilities; alerts trigger strictly at $\ge 0.85$.
- [x] **Dual Waveform Canvas**: Real-time scrolling peak voltage (cyan) and optical sensor rail (amber) rendered smoothly via `requestAnimationFrame`.
- [x] **FFT Spectrum Canvas**: 8-bin frequency bar chart highlighting high-frequency spectral spikes.
- [x] **Latency Profiler**: Displays edge inference execution duration in microseconds ($\mu\text{s}$).
- [x] **Bounded History & Event Log**: Recent tamper attacks logged chronologically up to 50 events without memory leaks.
- [x] **Stream Watchdog**: Stale stream indicator triggers if frames stop for >3 seconds.

---

## Known Limitations

1. **Simulation Boundary**: Telemetry, transient pulses, optical saturations, and inductive surges are generated mathematically by software models. The system does not interface with physical electrical meters, high-voltage equipment, or laser injection hardware.
2. **Deferred BLE (Phase 5)**: Physical Bluetooth Low Energy peripheral ingestion is abstracted behind `TelemetryProvider`.
3. **Deferred Persistence (Phase 6)**: Continuous high-frequency telemetry writes are kept in memory ring buffers to preserve flash longevity; persistent SQLite/Room event logging is deferred to Phase 6.
4. **Deferred Qualcomm QNN (Phase 7)**: Inference is performed on CPU via ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android:1.19.0`). Qualcomm Hexagon NPU hardware acceleration is planned for Phase 7.

---

## Development Roadmap

- [x] **Phase 1**: Virtual Meter Core, 29-byte protocol, deterministic signal models, mock stream, and automated tests.
- [x] **Phase 2**: 1D CNN model training, static ONNX export, numerical parity verification, and INT8 quantization.
- [x] **Phase 3**: Android foreground service (`connectedDevice`), CPU ONNX inference, high-confidence alert gating ($\ge 0.85$).
- [x] **Phase 4**: Asynchronous WebSocket publisher, local mock streamer, and real-time dark monitoring dashboard.
- [ ] **Phase 5**: BLE transport integration over GATT peripheral.
- [ ] **Phase 6**: Room persistence and Node-RED automation adapter.
- [ ] **Phase 7**: Qualcomm QNN/QAIRT Hexagon HTP NPU hardware acceleration.

