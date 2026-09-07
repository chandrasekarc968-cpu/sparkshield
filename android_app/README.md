# SparkShield Android Edge Monitoring Application (Phase 3)

The **SparkShield Android Edge Application** provides real-time on-device smart-meter telemetry frame parsing, mathematical feature extraction, sliding-window buffering, edge neural network inference (CPU via ONNX Runtime), and confidence-gated tamper alerting.

> [!IMPORTANT]
> **SIMULATION ONLY BOUNDARY**:
> All sensor readings, transients, optical saturations, and telemetry streams are purely mathematical software simulations. The system does not interface with physical electrical meters, high-voltage equipment, lasers, RF attack hardware, or meter bypass circuits.

---

## 1. System Requirements & Environment

- **JDK**: Java Development Kit 17 (Eclipse Temurin 17 or Microsoft OpenJDK 17)
- **Android SDK**: `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` (Android 8.0 Oreo or newer)
- **Build System**: Gradle 8.7+ with Android Gradle Plugin (AGP) 8.7.0 and Kotlin 2.0.20
- **Inference Runtime**: ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android:1.19.0`)
- **Model Asset**: `app/src/main/assets/sparkshield_1d_cnn.onnx`

---

## 2. Directory Structure

```
android_app/
├── app/
│   ├── build.gradle.kts                 <-- App-level build script (Sdk 35, Java 17)
│   ├── proguard-rules.pro               <-- Proguard keep rules for ONNX & data models
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      <-- FOREGROUND_SERVICE, POST_NOTIFICATIONS, VIBRATE
│       │   ├── assets/
│       │   │   └── sparkshield_1d_cnn.onnx <-- Deployed static ONNX model [1, 1, 128]
│       │   ├── java/com/sparkshield/android/
│       │   │   ├── MainActivity.kt      <-- Diagnostic UI with StateFlow observer
│       │   │   ├── protocol/
│       │   │   │   ├── Crc16Ccitt.kt    <-- calculate(bytes, offset, length): Int
│       │   │   │   ├── TelemetryFrame.kt<-- toByteArray(): ByteArray
│       │   │   │   ├── TelemetryFrameParser.kt <-- parse(bytes): ProtocolResult<TelemetryFrame>
│       │   │   │   ├── SequenceTracker.kt      <-- process(sequenceId): SequenceStatus
│       │   │   │   └── ProtocolResult.kt       <-- Generic Result & ProtocolError
│       │   │   ├── features/
│       │   │   │   ├── Normalization.kt <-- log1p and feature scaling constants
│       │   │   │   ├── FeatureLayout.kt <-- 16-feature indices & layout comments
│       │   │   │   ├── FeatureWindow.kt <-- 8-frame FIFO buffer with zero-padding
│       │   │   │   └── FeatureExtractor.kt <-- 16 normalized features (flags excluded)
│       │   │   ├── transport/
│       │   │   │   ├── TelemetryProvider.kt     <-- Flow<ByteArray> interface
│       │   │   │   └── MockTelemetryProvider.kt <-- 10 Hz deterministic synthetic stream
│       │   │   ├── inference/
│       │   │   │   ├── ClassLabels.kt   <-- NORMAL(0), EMP(1), OPTICAL(2), SURGE(3)
│       │   │   │   ├── InferenceResult.kt <-- Prediction, confidence, latency (µs)
│       │   │   │   ├── InferenceEngine.kt <-- load(), infer(), close()
│       │   │   │   └── CpuOnnxInferenceEngine.kt <-- ONNX Runtime CPU execution
│       │   │   ├── service/
│       │   │   │   ├── SparkShieldMonitoringService.kt <-- connectedDevice foreground service
│       │   │   │   ├── AlertGate.kt     <-- Strict confidence >= 0.85, 5s cooldown
│       │   │   │   └── NotificationHelper.kt <-- Notification channels & haptics
│       │   │   └── ui/
│       │   │       ├── MonitoringState.kt     <-- Diagnostic metrics state data class
│       │   │       └── MonitoringViewModel.kt <-- Lifecycle-aware ViewModel
│       │   └── res/
│       │       ├── layout/activity_main.xml
│       │       ├── values/colors.xml
│       │       ├── values/strings.xml
│       │       └── drawable/ic_shield.xml
│       └── test/java/com/sparkshield/android/
│           ├── protocol/
│           │   ├── Crc16CcittTest.kt
│           │   └── TelemetryFrameParserTest.kt
│           ├── features/
│           │   └── FeatureExtractorTest.kt
│           ├── service/
│           │   └── AlertGateTest.kt
│           ├── transport/
│           │   └── MockTelemetryProviderTest.kt
│           └── inference/
│               └── CpuOnnxInferenceEngineTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 3. How to Build & Run

### Building via Gradle
On Linux / macOS:
```bash
cd android_app
./gradlew assembleDebug
```
On Windows:
```powershell
cd android_app
.\gradlew.bat assembleDebug
```

### Running JVM Unit Tests
```bash
# Linux/macOS
./gradlew test

# Windows
.\gradlew.bat test
```

### Code Quality Lint
```bash
# Linux/macOS
./gradlew lint

# Windows
.\gradlew.bat lint
```

### Deploying to Connected Device / Emulator
```bash
# Linux/macOS
./gradlew installDebug

# Windows
.\gradlew.bat installDebug
```

---

## 4. Model Asset Placement & Generation

The ONNX model is loaded by `CpuOnnxInferenceEngine` from:
```
android_app/app/src/main/assets/sparkshield_1d_cnn.onnx
```

If missing, it can be generated directly from the PyTorch checkpoint using the root repository script:
```bash
python -m models.export_onnx \
  --checkpoint artifacts/sparkshield.pt \
  --output android_app/app/src/main/assets/sparkshield_1d_cnn.onnx
```

Model specifications:
- **Input Name**: `"input"`
- **Input Shape**: `[1, 1, 128]` (float32)
- **Output Name**: `"logits"`
- **Output Shape**: `[1, 4]` (float32 logits for NORMAL, EMP, OPTICAL, SURGE)

---

## 5. Mock Telemetry & Default Demo Sequence

`MockTelemetryProvider` implements `TelemetryProvider` using a Kotlin `Flow<ByteArray>` emitting at 10 Hz (100 ms interval).

### Default Demo Schedule
By default, the provider executes a continuous repeating cycle:
1. **20 frames** NORMAL (Nominal 50/60 Hz grid fundamental + odd harmonics)
2. **8 frames** EMP (Ultrafast transient, 10–30 ns rise, microsecond decay, broad RF energy)
3. **20 frames** NORMAL
4. **8 frames** OPTICAL (Saturated optical rail 3800–4950 mV, slow front)
5. **20 frames** NORMAL
6. **8 frames** SURGE (Inductive switching damped ring, 7500–18500 mV, millisecond decay)
7. **Repeat**

---

## 6. Protocol & Feature Extraction Parity

### Wire Format (29 bytes, big-endian)
- `Bytes 0-1`: magic uint16 (`0x5353`, ASCII `'SS'`)
- `Bytes 2-5`: sequence_id uint32
- `Bytes 6-9`: timestamp_ms uint32
- `Byte 10`: event_flags uint8 (bit 0=EMP, bit 1=OPTICAL, bit 2=SURGE, bit 3=NORMAL)
- `Bytes 11-12`: peak_mv uint16
- `Bytes 13-14`: rise_time_code uint16 (10 ns/LSB)
- `Bytes 15-16`: decay_time_us uint16 (1 µs/LSB)
- `Bytes 17-18`: optical_sensor_mv uint16
- `Bytes 19-26`: fft_energy_bins uint8[8]
- `Bytes 27-28`: crc16 uint16 (CRC-16-CCITT over bytes 0..26)

### 16 Normalized Features per Frame
- `0`: `peak_mv / 65535.0`
- `1`: `rise_time_code / 65535.0`
- `2`: `log1p(rise_time_code) / log1p(65535.0)`
- `3`: `decay_time_us / 65535.0`
- `4`: `log1p(decay_time_us) / log1p(65535.0)`
- `5`: `optical_sensor_mv / 65535.0`
- `6`: `min(optical_sensor_mv / 5000.0, 1.0)`
- `7`: `sum(fft_bins[4..7]) / (sum(fft_bins[0..7]) + 1e-5)`
- `8..15`: `fft_bins[0..7] / 255.0`

### Zero-Padding & Readiness Gating
- Rolling 8-frame buffer: 8 frames $\times$ 16 features = 128 floats.
- Chronological order: oldest frame features first (indices 0..15), newest last (indices 112..127).
- Before 8 valid frames are received, older slots are zero-padded (`0.0f`).
- Inference is strictly gated until at least 8 valid frames are available (`isReadyForInference == true`), unless warm-up mode is enabled.

---

## 7. Confidence-Gated Alerting & Haptics

- **Threshold**: Confidence $\ge 0.85$ (85%).
- **NORMAL Class**: Never triggers a tamper alert; resets the gate cooldown tracking.
- **Spam Prevention**: Consecutive alerts for the same tamper class are suppressed within 5 seconds (5000 ms cooldown).
- **Notification Channel**: `SparkShield Simulation Alerts` (High Importance).
- **Alert Messages**:
  - EMP: `"High-voltage EMP-like transient detected in simulation"`
  - OPTICAL: `"Optical saturation event detected in simulation"`
  - SURGE: `"Inductive surge event detected in simulation"`
- All alert titles and notifications explicitly display: **SIMULATION ONLY**.

---

## 8. Bluetooth Low Energy (BLE) GATT Integration & Provider Lifecycle (Phase 5)

`BleTelemetryProvider` implements the `TelemetryProvider` interface over Bluetooth Low Energy:
- **Service UUID**: `1A860001-C7E2-432A-8C2A-8B6C7741E001`
- **Telemetry Characteristic UUID**: `1A860002-C7E2-432A-8C2A-8B6C7741E001` (`READ | NOTIFY`)
- **CCCD Descriptor UUID**: `00002902-0000-1000-8000-00805f9b34fb`
- **MTU**: Requests `247` bytes upon connection for atomic 29-byte frame delivery without fragmentation.
- **Buffering**: Bounded channel buffer `Channel<ByteArray>(100, BufferOverflow.DROP_OLDEST)` guarantees that inference processing or UI lag never stalls the GATT callback thread.
- **Connection Gating**: `isConnected` is set to `true` strictly when `BleConnectionState.Streaming` is achieved (CCCD notification descriptor successfully written).

### Provider Mode Selection & Lifecycle Architecture
Configurable at runtime via Intent extra `SparkShieldMonitoringService.EXTRA_PROVIDER_MODE`:
- `AUTO` (Default): Evaluates BLE permissions and adapter state. If available, connects to `SparkShield-Core`. If Bluetooth permissions are denied or the adapter is disabled, the service automatically initiates an atomic fallback to `MockTelemetryProvider` with a visible status reason.
- `BLE`: Dedicated BLE mode with bounded exponential reconnect backoff (1s, 2s, 4s, 8s, up to 15s max).
- `MOCK`: Pure deterministic synthetic stream generation.

### Atomic Provider Transition (`switchProvider`)
`SparkShieldMonitoringService` ensures zero coroutine leaks or duplicate collectors during provider switches:
1. Cancels any active frame processing coroutine job atomically.
2. Cancels any active BLE connection state monitor job.
3. Invokes `stop()` on the outgoing provider, ensuring all GATT connections, scan callbacks, and channel buffers are released.
4. Updates the provider reference and initializes the incoming provider (`start()`).
5. Launches exactly one coroutine collector on `frames` to resume feature extraction and inference without interruption.

### Hardware Testing Procedure (Physical Device)
1. Ensure the Python BLE peripheral is running on the host in hardware mode:
   ```powershell
   python -m python_core.bumble_service --mode hardware --transport usb:0 --rate 10
   ```
2. Build and install the debug APK onto a physical Android device:
   ```bash
   ./gradlew installDebug
   ```
3. Grant runtime `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, and `POST_NOTIFICATIONS` permissions.
4. Observe GATT connection, MTU 247 negotiation, and real-time streaming on the diagnostic screen.

---

## 9. Deferred Phases & Rationale

- **Phase 6 (Room Database Persistence)**: Deferred to prevent flash storage write wear during continuous high-frequency telemetry streaming.
- **Phase 7 (Qualcomm QNN / Hexagon HTP Acceleration)**: Deferred until CPU ONNX baseline is established; isolated behind `InferenceEngine`.
