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

## Phase 5: Production BLE GATT Transport Integration & Hardening

Phase 5 establishes real-time Bluetooth Low Energy (BLE) GATT telemetry streaming from the Python host peripheral (`SparkShield-Core`) to the Android edge inference engine.

### BLE Architecture & GATT Specifications
- **Device Name**: `SparkShield-Core`
- **Service UUID**: `1A860001-C7E2-432A-8C2A-8B6C7741E001`
- **Telemetry Characteristic UUID**: `1A860002-C7E2-432A-8C2A-8B6C7741E001` (`READ | NOTIFY`)
- **Client Characteristic Configuration Descriptor (CCCD)**: `00002902-0000-1000-8000-00805f9b34fb`
- **MTU Size**: `247` bytes negotiated for atomic, non-fragmented 29-byte frame deliveries
- **Payload**: Strict 29-byte big-endian frames with CRC-16-CCITT validation

### Transport Modes in `bumble_service.py`
The Python Bumble peripheral supports two explicitly separated operational modes:

1. **Virtual Mode (`--mode virtual`)**:
   - Uses Bumble `LocalLink` in-memory software loopback.
   - Deterministic and dependency-free; ideal for CI environments, unit tests, and automated pipeline verification.
   - Run command:
     ```powershell
     python -m python_core.bumble_service --mode virtual --rate 10
     ```

2. **Hardware HCI Mode (`--mode hardware`)**:
   - Connects directly to a physical Bluetooth USB dongle or serial HCI controller via Bumble's transport engine (`bumble.transport.open_transport`).
   - Supports USB dongles (e.g. `--transport usb:0`), UART/Serial (e.g. `--transport serial:COM3:115200`), or HCI sockets.
   - **Fail-Fast Safety**: Fails cleanly with exit code `1` and descriptive error diagnostics if the physical adapter or HCI transport is absent or in use.
   - Run command:
     ```powershell
     python -m python_core.bumble_service --mode hardware --transport usb:0 --rate 10
     ```

### Android Provider Lifecycle & Atomic Fallback
`SparkShieldMonitoringService` and `BleTelemetryProvider` enforce robust lifecycle management:
- **Atomic Provider Transition (`switchProvider`)**: When switching between BLE and MOCK (e.g., during `AUTO` fallback or manual mode toggles), the service atomically cancels the active coroutine collector job, stops the old provider, unregisters BLE callbacks, installs the new provider, starts it, and launches exactly one fresh collection job.
- **`AUTO` Fallback Ingestion**: If BLE permissions are missing or the Bluetooth adapter is disabled, the service logs `BleConnectionState.PermissionDenied` or `AdapterUnavailable`, automatically switches to `MockTelemetryProvider`, and continues frame parsing and inference without stall or pipeline death.
- **Accurate Connection State**: `isConnected` is set to `true` strictly when BLE reaches `BleConnectionState.Streaming` (CCCD notifications enabled) or when MOCK is active.
- **Leak-Free Resource Cleanup**: `BleTelemetryProvider.stop()` unregisters scan callbacks, closes GATT clients, cancels pending reconnect timers, and purges channel buffers.

### Hardware Testing Guide (Real Android Device + Physical Dongle)
For physical hardware validation (beyond the virtual loopback tests):

1. **Host Setup**:
   - Plug a compatible Bluetooth 4.2+ USB adapter into the host PC (e.g., Realtek or Cambridge Silicon Radio chipsets supported by libusb/pyusb or WinUSB).
   - Start the hardware BLE peripheral:
     ```powershell
     python -m python_core.bumble_service --mode hardware --transport usb:0 --rate 10
     ```
   - Verify `SparkShield-Core` starts advertising service `1A860001-C7E2-432A-8C2A-8B6C7741E001`.

2. **Android Device Setup**:
   - Deploy `android_app` to a physical Android device running Android 12+ (API 31+).
   - Grant `Nearby Devices` (Bluetooth) and `Notification` permissions when prompted.
   - In App Settings or Intent launcher, select `ProviderMode.BLE`.
   - The app will scan for `SparkShield-Core`, connect, request MTU 247, discover the telemetry characteristic, write `0x0001` to CCCD, and begin streaming live 29-byte frames into the inference pipeline.

### Automated Test Suite vs. Hardware Validation Notice
> [!NOTE]
> **Validation Transparency**:
> - `tests/verify_phase5_ble.py` validates the complete BLE GATT protocol stack, MTU negotiation, characteristic discovery, CCCD subscription, ONNX inference, alert gating, CRC rejection, and sequence tracking over a **virtual Bumble bus** (`LocalLink`).
> - Physical wireless propagation and OS-level Bluetooth stack performance require the hardware test procedure documented above with a physical dongle and target Android device.

### Verification & Test Commands
```powershell
# 1. Run Python protocol and model regression suite
python -m pytest python_core/tests models/tests -v

# 2. Run Android parity verifier
python tests/verify_android_parity.py

# 3. Run Phase 4 WebSocket and dashboard verifier
python tests/verify_phase4.py

# 4. Run Phase 5 virtual-bus BLE GATT end-to-end verifier
python tests/verify_phase5_ble.py

# 5. Run Phase 6 Room persistence & Node-RED verifier
python tests/verify_phase6.py

# 6. Run Dashboard test and build
cd dashboard && npm test && npm run build
```

---

## Phase 6: On-Device Room Persistence & Node-RED Automation Adapter

Phase 6 implements local SQLite audit logging via Android Jetpack Room and provides an industrial automation integration bridge via Node-RED.

### On-Device Room Database Persistence
- **Database**: `SparkShieldDatabase` (`sparkshield_edge.db`, Schema Version 2)
- **Indexes & Migration Strategy**:
  - `tamper_events`: Indexes on `(timestamp_ms)` and `(class_name)`.
  - `telemetry_snapshots`: Indexes on `(timestamp_ms)` and `(tamper_detected)`.
  - Upgrades from Version 1 to Version 2 use deterministic `MIGRATION_1_2` (`CREATE INDEX IF NOT EXISTS`) without relying on destructive migration fallback in production.
- **TamperEventEntity (`tamper_events`)**:
  - Automatically records confirmed tamper alerts (`EMP`, `OPTICAL`, `SURGE`) with confidence $\ge 0.85$.
  - Captures timestamp, sequence ID, classification, confidence, peak voltage, rise time, decay time, optical sensor readings, and message.
  - Strict bounded capacity: auto-evicts oldest records to cap table size at **1,000 events**.
- **TelemetrySnapshotEntity (`telemetry_snapshots`)**:
  - Periodically audits 12-field telemetry frames for forensic analysis.
  - **Flash Wear Prevention & Bounded Buffer**: High-frequency telemetry (10–50 Hz) is queued in a bounded in-memory buffer (`DEFAULT_BATCH_FLUSH_SIZE = 20`, flush interval = 2s, buffer cap = 1,000) and written asynchronously on `Dispatchers.IO` in transactions.
  - Strict bounded capacity: auto-evicts oldest records to cap table size at **5,000 snapshots**.
- **Failure Resilience & Zero Data Loss**:
  - Database write errors never crash foreground monitoring or block inference.
  - Failures update observable `persistenceError` and `persistenceFailureCount` StateFlows.
  - Unwritten snapshots are safely re-queued up to buffer capacity and accounted for via `droppedSnapshotsCount`.
- **Clean Service Shutdown**:
  - `stopMonitoring()` executes a synchronous flush with a 2-second timeout before coroutine cancellation, ensuring pending in-memory snapshots are safely written to flash.
- **UI Exposure**: `TelemetryRepository` provides live `recentTamperEvents` flow and persisted counter StateFlows to `MainActivity`.

### Node-RED Automation Adapter (`automation/node-red-flow.json`)
- **Canonical Endpoint**: Connects to the standard SparkShield WebSocket endpoint (`ws://localhost:8765/telemetry` or `ws://<host>:8765/telemetry`).
- **Connection Health & Diagnostics**:
  - `node_ws_status` and `node_ws_health_check` track real-time socket state (`ONLINE` vs `OFFLINE_OR_ERROR`).
  - `node_ws_catch` catches frame parsing and ingestion errors and logs warnings to console without halting execution.
- **Ingestion & Routing**:
  1. **Baseline Grid Monitoring**: Tracks nominal voltage and HF/LF spectral energy ratios for normal frames (`tamperDetected == false`).
  2. **Tamper Classification & Routing**: Splits detected attacks (`tamperDetected == true`) into EMP, OPTICAL, and SURGE channels.
  3. **5-Second Debounce Gate**: Rate-limits repeated attacks of the same class (1 message per 5 seconds), matching the Android `AlertGate` cooldown.
  4. **Multi-Channel Dispatch**:
     - MQTT Output: Disabled by default (`"d": true`) for zero-dependency local testing. Can be enabled to publish to `sparkshield/alerts/{class}`.
     - HTTP Webhook: Disabled by default (`"d": true`) for zero-dependency local testing. Can be enabled to dispatch POST alerts to `/api/v1/alerts`.
     - Debug Console: Pre-wired and active for immediate inspection in Node-RED debug sidebar.

---

## Phase 6 Checklist

- [x] **Room Entities & DAOs**: `TamperEventEntity`, `TelemetrySnapshotEntity`, `TamperEventDao`, `TelemetrySnapshotDao`.
- [x] **Schema Indexes & Version 2**: Added `timestamp_ms`, `class_name`, and `tamper_detected` indexes with `MIGRATION_1_2`.
- [x] **Thread-Safe Repository**: `RoomTelemetryRepository` with bounded in-memory buffer (1,000 items), batch flushes, and background eviction.
- [x] **Zero Synchronous Flash Writes**: High-frequency telemetry (10–50 Hz) buffered in memory; written in transactions on `Dispatchers.IO`.
- [x] **Failure Resilience**: Persistence errors logged, error StateFlows exposed, and unwritten records safely re-queued.
- [x] **Clean Shutdown Flush**: Pending snapshots synchronously flushed with timeout before service destruction.
- [x] **Capacity Boundaries**: Max 1,000 tamper events and max 5,000 snapshots enforced via SQL eviction queries.
- [x] **UI Exposure**: Persisted counters displayed in `MainActivity`; `recentTamperEvents` flow exposed in `MonitoringViewModel`.
- [x] **Node-RED Flow Configuration**: Canonical port `ws://localhost:8765/telemetry`, connection health tracking, 5s debounce, and safe local testing defaults.
- [x] **Deterministic E2E Verification**: `tests/verify_phase6.py` verifies SQLite schema parity, eviction limits, batch buffering, Node-RED JSON schema, cross-layer unit consistency, and malformed frame rejection.

---

## Known Limitations

1. **Simulation Boundary**: All telemetry, transient waveforms, optical saturations, and inductive surges are generated mathematically by software models. The system does not interface with physical electrical meters, high-voltage equipment, or laser injection hardware.
2. **Deferred Qualcomm QNN (Phase 7)**: Edge inference runs on CPU via ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android:1.19.0`). Qualcomm Hexagon NPU hardware acceleration is planned for Phase 7.

---

## Development Roadmap

- [x] **Phase 1**: Virtual Meter Core, 29-byte protocol, deterministic signal models, mock stream, and automated tests.
- [x] **Phase 2**: 1D CNN model training, static ONNX export, numerical parity verification, and INT8 quantization.
- [x] **Phase 3**: Android foreground service (`connectedDevice`), CPU ONNX inference, high-confidence alert gating ($\ge 0.85$).
- [x] **Phase 4**: Asynchronous WebSocket publisher, local mock streamer, and real-time dark monitoring dashboard.
- [x] **Phase 5**: Production BLE transport integration (Bumble peripheral, GATT service/characteristic, Android BleTelemetryProvider, AUTO fallback).
- [x] **Phase 6**: Room persistence and Node-RED automation adapter.
- [ ] **Phase 7**: Qualcomm QNN/QAIRT Hexagon HTP NPU hardware acceleration.


