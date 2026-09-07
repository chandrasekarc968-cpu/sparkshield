# SparkShield Android Edge Architecture & Implementation (Phase 3)

## 1. System Overview & Architectural Role

Phase 3 delivers the on-device edge telemetry monitoring application for SparkShield. It consumes 29-byte cyber-physical smart-meter telemetry frames, decodes and validates packet integrity, computes a 16-feature mathematical normalization vector per frame, buffers a chronological 8-frame rolling window ($8 \times 16 = 128$ floats), performs on-device neural network classification via ONNX Runtime CPU, and dispatches rate-limited alerts for simulated tamper attacks.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 SPARKSHIELD PHASE 3 ARCHITECTURE                       │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                        │
│   ┌───────────────────────────┐      Flow<ByteArray>                                   │
│   │   TelemetryProvider       │ ────────────────────────┐                              │
│   │  (MockTelemetryProvider)  │                         │                              │
│   └───────────────────────────┘                         ▼                              │
│                                           ┌───────────────────────────┐                │
│                                           │   TelemetryFrameParser    │                │
│                                           │  - Magic: 0x5353          │                │
│                                           │  - CRC-16-CCITT (0..26)   │                │
│                                           │  - Bitfield & Range Valid │                │
│                                           └─────────────┬─────────────┘                │
│                                                         │ ProtocolResult.Success       │
│                                                         ▼                              │
│                                           ┌───────────────────────────┐                │
│                                           │     FeatureExtractor      │                │
│                                           │  - 16 normalized features │                │
│                                           │  - Event flags EXCLUDED   │                │
│                                           └─────────────┬─────────────┘                │
│                                                         │ 16 floats                    │
│                                                         ▼                              │
│                                           ┌───────────────────────────┐                │
│                                           │       FeatureWindow       │                │
│                                           │  - 8-frame FIFO buffer    │                │
│                                           │  - Zero-padded (<8 frames)│                │
│                                           │  - Flat [1, 1, 128] shape │                │
│                                           └─────────────┬─────────────┘                │
│                                                         │ isReady == true              │
│                                                         ▼                              │
│                                           ┌───────────────────────────┐                │
│                                           │   CpuOnnxInferenceEngine  │                │
│                                           │  - ONNX Runtime (CPU)     │                │
│                                           │  - Input:  [1, 1, 128]    │                │
│                                           │  - Output: [1, 4]         │                │
│                                           │  - Softmax & Latency (µs) │                │
│                                           └─────────────┬─────────────┘                │
│                                                         │ InferenceResult              │
│                                                         ▼                              │
│                                           ┌───────────────────────────┐                │
│                                           │         AlertGate         │                │
│                                           │  - Conf >= 0.85 threshold │                │
│                                           │  - NORMAL never alerts    │                │
│                                           │  - 5-second cooldown/class│                │
│                                           │  - NORMAL resets cooldown │                │
│                                           └─────────────┬─────────────┘                │
│                                                         │ Alert Triggered              │
│                                                         ▼                              │
│                                           ┌───────────────────────────┐                │
│                                           │    NotificationHelper     │                │
│                                           │  - SIMULATION ONLY tag    │                │
│                                           │  - High-priority Channel  │                │
│                                           │  - Haptic double-pulse    │                │
│                                           └───────────────────────────┘                │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> **SIMULATION-ONLY BOUNDARY**:
> SparkShield is strictly a research and simulation system. All sensor inputs, transient spikes, optical saturation levels, and energy distributions represent synthetic mathematical waveforms generated by software models. The software does not interface with physical electrical power grids, utility metering equipment, lasers, high-voltage pulse generators, or physical meter bypass hardware.

---

## 2. Environment Setup & Dependency Versions

### Required Environment
- **JDK**: Java 17 LTS (Eclipse Temurin, Azul Zulu, or Microsoft OpenJDK 17).
- **Android SDK Platform**: API Level 35 (`Android 15`).
- **Build Tools**: `35.0.0`.
- **Minimum SDK (`minSdk`)**: API Level 26 (`Android 8.0 Oreo`). This ensures broad compatibility with modern Android devices while providing native support for Notification Channels, Java 8+ time APIs, and background execution limits.
- **Target SDK (`targetSdk`)**: API Level 35.
- **Gradle Version**: 8.7 or higher.

### Key Dependencies
| Component | Artifact | Version | Purpose |
| :--- | :--- | :--- | :--- |
| **Android Gradle Plugin** | `com.android.tools.build:gradle` | `8.7.0` | Android build pipeline |
| **Kotlin Gradle Plugin** | `org.jetbrains.kotlin:kotlin-gradle-plugin` | `2.0.20` | Kotlin 2.0 language runtime |
| **Kotlin Coroutines** | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.8.1` | Asynchronous streaming (`Flow`), concurrency |
| **AndroidX Core KTX** | `androidx.core:core-ktx` | `1.13.1` | Android system extensions |
| **AndroidX Lifecycle** | `androidx.lifecycle:lifecycle-viewmodel-ktx` | `2.8.5` | MVVM state persistence & lifecycle binding |
| **ONNX Runtime Android** | `ai.onnxruntime:onnxruntime-android` | `1.19.0` | On-device ML inference on CPU |
| **JUnit 4** | `junit:junit` | `4.13.2` | JVM unit testing |
| **Robolectric** | `org.robolectric:robolectric` | `4.13` | Android framework simulation for unit tests |

---

## 3. Build, Test, and Verification Commands

The repository provides automated Gradle and Makefile tasks for multi-platform development.

### Linux / macOS / Bash
```bash
# Build debug APK
cd android_app && ./gradlew assembleDebug

# Run JVM Unit Tests
cd android_app && ./gradlew test

# Run Android Lint analysis
cd android_app && ./gradlew lint

# Install debug APK to connected device / emulator
cd android_app && ./gradlew installDebug
```

### Windows PowerShell / Command Prompt
```powershell
# Build debug APK
cd android_app
.\gradlew.bat assembleDebug

# Run JVM Unit Tests
cd android_app
.\gradlew.bat test

# Run Android Lint analysis
cd android_app
.\gradlew.bat lint

# Install debug APK
cd android_app
.\gradlew.bat installDebug
```

### Repository Makefile Targets
From the workspace root:
```bash
make android-build       # Builds debug APK
make android-test        # Runs all JVM unit tests
make android-lint        # Executes Android Lint
make android-install-debug # Installs APK on active device
make test-all            # Runs both Python test suite and Android test suite
```

---

## 4. Model Asset Placement & Verification

The trained 1D CNN model must be placed in the Android assets directory:
```
android_app/app/src/main/assets/sparkshield_1d_cnn.onnx
```

### Exporting from Checkpoint
If updating or training a new model version using the Python pipeline:
```bash
# 1. Train model checkpoint
python -m models.train --epochs 20 --samples-per-class 1000 --output artifacts/sparkshield.pt

# 2. Export to static-shape ONNX format
python -m models.export_onnx \
  --checkpoint artifacts/sparkshield.pt \
  --output android_app/app/src/main/assets/sparkshield_1d_cnn.onnx

# 3. Verify parity
python -m models.evaluate --onnx android_app/app/src/main/assets/sparkshield_1d_cnn.onnx
```

### Model Runtime Constraints
- **Input Node Name**: `"input"`
- **Input Tensor Dimensions**: Static `[1, 1, 128]` (Batch size = 1, Channels = 1, Sequence length = 128 float32 values).
- **Output Node Name**: `"logits"`
- **Output Tensor Dimensions**: Static `[1, 4]` (Logits for classes: `0=NORMAL`, `1=EMP`, `2=OPTICAL`, `3=SURGE`).
- **Softmax Stability**: Softmax is calculated on the raw logits using max-subtraction stabilization:
  $$\text{Softmax}(z_i) = \frac{e^{z_i - \max(z)}}{\sum_j e^{z_j - \max(z)}}$$
- **Fail-Safe Loading**: If `sparkshield_1d_cnn.onnx` is missing or corrupted, `CpuOnnxInferenceEngine.load()` returns a structured `Result.failure(FileNotFoundException)` explaining the missing asset rather than crashing or substituting random weights.

---

## 5. 29-Byte Protocol Compatibility

The wire protocol in Kotlin exactly mirrors `python_core/frame_protocol.py` down to byte-level offsets, endianness, bitfields, and CRC polynomials.

### Frame Layout
| Byte Offset | Field Name | Type | Constraints / Semantics |
| :--- | :--- | :--- | :--- |
| `0..1` | `magic` | `uint16` (Big-Endian) | Fixed `0x5353` (ASCII `'SS'`) |
| `2..5` | `sequence_id` | `uint32` (Big-Endian) | Monotonically increasing counter |
| `6..9` | `timestamp_ms` | `uint32` (Big-Endian) | Millisecond epoch or uptime timestamp |
| `10` | `event_flags` | `uint8` | Bitfield: bit 0 = EMP, bit 1 = OPTICAL, bit 2 = SURGE, bit 3 = NORMAL |
| `11..12` | `peak_mv` | `uint16` (Big-Endian) | Grid sensor peak voltage (mV) |
| `13..14` | `rise_time_code` | `uint16` (Big-Endian) | Transient rise time ($10\text{ ns/LSB}$) |
| `15..16` | `decay_time_us` | `uint16` (Big-Endian) | Transient decay duration ($1\ \mu\text{s/LSB}$) |
| `17..18` | `optical_sensor_mv`| `uint16` (Big-Endian) | Photodiode rail voltage (mV) |
| `19..26` | `fft_energy_bins` | `uint8[8]` | 8-bin spectral power distribution |
| `27..28` | `crc16` | `uint16` (Big-Endian) | CRC-16-CCITT (poly `0x1021`, init `0xFFFF`) calculated over bytes `0..26` |

### CRC-16-CCITT Implementation
- Polynomial: `0x1021` ($x^{16} + x^{12} + x^5 + 1$).
- Initial Value: `0xFFFF`.
- Input & Output Reflection: None.
- Standard Validation Vector: ASCII `b"123456789"` yields `0x29B1` (`10673`).
- Kotlin implementation provides both a 256-entry lookup table (`Crc16Ccitt.calculate`) for performance and a bitwise reference implementation (`Crc16Ccitt.compute`).

### Packet Validation Rules
Packets are rejected with explicit structured `ProtocolError` categories if:
1. Frame size $\ne 29$ bytes (`ProtocolError.MalformedLength`).
2. Magic bytes $\ne 0x5353$ (`ProtocolError.MagicMismatch`).
3. Received CRC $\ne$ calculated CRC (`ProtocolError.CrcMismatch`).
4. `event_flags == 0x00`, upper bits (bits 4–7) $\ne 0$, or invalid flag combinations (e.g., NORMAL + tamper flag simultaneously, or multiple tamper flags set simultaneously) (`ProtocolError.InvalidEventFlags`).
5. Any field exceeds physical sensor envelope constraints (`ProtocolError.OutOfRangeValue`).

---

## 6. Mathematical Feature Extraction & Sliding Window

### 16 Normalized Features
Each frame is transformed into 16 normalized floating-point features:

| Index | Feature Description | Mathematical Formula | Physical Range / Units |
| :--- | :--- | :--- | :--- |
| `0` | Peak voltage normalized | $\frac{\text{peak\_mv}}{65535.0}$ | $[0.0, 1.0]$ |
| `1` | Rise time linear | $\frac{\text{rise\_time\_code}}{65535.0}$ | $[0.0, 1.0]$ |
| `2` | Rise time log-scaled | $\frac{\ln(1 + \text{rise\_time\_code})}{\ln(1 + 65535.0)}$ | $[0.0, 1.0]$ (resolves nanosecond scales) |
| `3` | Decay time linear | $\frac{\text{decay\_time\_us}}{65535.0}$ | $[0.0, 1.0]$ |
| `4` | Decay time log-scaled | $\frac{\ln(1 + \text{decay\_time\_us})}{\ln(1 + 65535.0)}$ | $[0.0, 1.0]$ (resolves microsecond scales) |
| `5` | Optical sensor linear | $\frac{\text{optical\_sensor\_mv}}{65535.0}$ | $[0.0, 1.0]$ |
| `6` | Optical rail saturation | $\min\left(\frac{\text{optical\_sensor\_mv}}{5000.0}, 1.0\right)$ | $[0.0, 1.0]$ (5.0V rail clamp) |
| `7` | High-frequency spectral ratio | $\frac{\sum_{i=4}^7 \text{bin}_i}{\sum_{i=0}^7 \text{bin}_i + 10^{-5}}$ | $[0.0, 1.0]$ (EMP / transient HF indicator) |
| `8..15`| FFT spectral bins 0..7 | $\frac{\text{bin}_i}{255.0}$ for $i \in [0..7]$ | $[0.0, 1.0]$ each |

> [!CAUTION]
> **LEAKAGE PREVENTION**:
> `event_flags` (Byte 10) are intentionally **excluded** from the feature vector. They serve solely as ground-truth simulation markers. Including them in features would cause artificial label leakage into the inference engine.

### 8-Frame Rolling Window & Zero-Padding
- The 1D CNN classifier requires a temporal window of 8 frames ($8 \times 16 = 128$ floats).
- **Chronological Ordering**: Oldest frame features are positioned first (indices `0..15`), newest frame features last (indices `112..127`).
- **Zero-Padding**: When fewer than 8 valid frames have been received (e.g. at startup or after resets), slots for older frames are zero-padded (`0.0f`).
- **Readiness Gating**: By default, `FeatureWindow.isReadyForInference` returns `false` until 8 consecutive valid frames have populated the buffer, preventing premature edge classifications on partially padded data.

---

## 7. Mock Telemetry Provider & Demo Cycle

`MockTelemetryProvider` implements `TelemetryProvider` without network or Bluetooth overhead, emitting valid 29-byte frames via a Kotlin `StateFlow`/`Flow` at 10 Hz (configurable interval, default 100 ms).

### Repeating Demo Sequence
```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  20 Frames   │ ──> │   8 Frames   │ ──> │  20 Frames   │ ──> │   8 Frames   │
│    NORMAL    │     │     EMP      │     │    NORMAL    │     │   OPTICAL    │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
       ▲                                                              │
       │                                                              ▼
┌──────────────┐                                               ┌──────────────┐
│    REPEAT    │ <──────────────────────────────────────────── │  20 Frames   │
│    CYCLE     │ <── 8 Frames SURGE <── 20 Frames NORMAL <─── │    NORMAL    │
└──────────────┘                                               └──────────────┘
```

1. **NORMAL (20 frames)**: Nominal grid conditions (50/60 Hz fundamental, minor 3rd/5th harmonics, optical sensor nominal 100–500 mV).
2. **EMP (8 frames)**: High-voltage fast transient (10–30 ns rise, microsecond decay, broadband RF energy).
3. **NORMAL (20 frames)**: Nominal return.
4. **OPTICAL (8 frames)**: Photodiode saturation (3800–4950 mV, 5V rail clamp, low RF energy).
5. **NORMAL (20 frames)**: Nominal return.
6. **SURGE (8 frames)**: Inductive switching transient (7500–18500 mV, millisecond oscillatory decay).
7. **Repeat indefinitely**.

---

## 8. Confidence-Gated Alerting & Foreground Service

### Alert Gate Specifications
- **Confidence Threshold**: Strict $\ge 0.85$ (85%).
  - At `0.849`: Alert is suppressed.
  - At `0.850` and `0.851`: Alert is permitted.
- **NORMAL Suppression**: Predictions classified as `NORMAL` never generate alerts, regardless of confidence.
- **Gate Reset on Normal**: Receipt of a valid `NORMAL` prediction immediately resets the alert cooldown tracker.
- **Rate-Limiting Cooldown**: Repeated alerts for the same tamper class within 5000 ms (5 seconds) are suppressed to avoid notification flooding.
- **Standard Alert Copy**:
  - EMP: `"High-voltage EMP-like transient detected in simulation"`
  - OPTICAL: `"Optical saturation event detected in simulation"`
  - SURGE: `"Inductive surge event detected in simulation"`

### Android Foreground Service
- **Service Class**: `SparkShieldMonitoringService` (`android.app.Service`).
- **Foreground Service Type**: `connectedDevice` (declared in `AndroidManifest.xml`).
- **Threading & Lifecycle**: Runs with a `SupervisorJob` on `Dispatchers.Default` background dispatcher, leaving the main looper free for UI rendering.
- **Diagnostics Metrics**: Atomic tracking of:
  - `receivedFrames`
  - `validFrames`
  - `invalidFrames`
  - `droppedFrames` (sequence discontinuity gaps)
  - `inferredFrames`
  - `tamperAlerts`
- **Power Management**: No wake lock is held by default. Android OS scheduling handles background execution cleanly under the foreground service notification.

---

## 9. Diagnostic Activity UI

`MainActivity` acts as a local diagnostic inspection screen:
- **Connection Controls**: Start and Stop buttons controlling `SparkShieldMonitoringService`.
- **Model Status Indicator**: Displays whether the ONNX model is loaded or describes loading errors.
- **Live Classification Card**: Color-coded tamper indicators (Green = NORMAL, Red = EMP, Amber = OPTICAL, Purple = SURGE).
- **Inference Metrics**: Shows confidence percentage, probabilities breakdown, and inference execution latency in microseconds ($\mu\text{s}$).
- **Telemetry Counters**: Real-time display of total received, valid, invalid, dropped, and alerted frames.
- **Protocol Health**: Shows the most recent protocol parsing error string.
- **Resilience**: Operates gracefully if notifications are disabled, if the ONNX model fails to load, or if malformed frames are encountered.

---

## 10. Known Limitations & Deferred Architecture

### Deferred Phases
1. **Phase 4 (WebSocket & Desktop Dashboard)**:
   - *Rationale*: Network client synchronization belongs to the backend telemetry routing tier. Mixing remote sockets into the initial Android edge layer introduces unnecessary failure modes during on-device model validation.
2. **Phase 5 (Physical Bluetooth Low Energy / BLE Transport)**:
   - *Rationale*: Physical GATT client ingestion requires target RF hardware and Android BLE stack permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, fine location). Separating BLE behind `TelemetryProvider` allows pure software simulation and deterministic test suites.
3. **Phase 6 (Room Database Persistence)**:
   - *Rationale*: High-frequency continuous telemetry writes (10–100 Hz) to SQLite/Room can saturate flash storage and introduce GC pauses on low-end devices. Persistent event logging will be added in Phase 6 with write-buffering.
4. **Phase 7 (Qualcomm QNN / Hexagon HTP Hardware Acceleration)**:
   - *Rationale*: Qualcomm Neural Network (QNN) SDK and Hexagon Tensor Processor runtime libraries require device-specific native NDK shared libraries (`.so`) and specific Snapdragon hardware. The CPU ONNX Runtime implementation establishes the numerical reference baseline first.

---

## 11. Verification Matrix

| Test Suite | Location | Coverage |
| :--- | :--- | :--- |
| **CRC-16 Validation** | `Crc16CcittTest.kt` | Known vector `b"123456789"` = `0x29B1`, all-zero bytes, single-byte mutations. |
| **Frame Parsing** | `TelemetryFrameParserTest.kt` | Valid 29-byte frame, truncated length, invalid magic, bad CRC, flag conflicts. |
| **Feature Parity** | `FeatureExtractorTest.kt` | 16 normalized features, zero-padding, chronological order, shape $[1, 1, 128]$. |
| **Alert Gating** | `AlertGateTest.kt` | Confidence at 0.849, 0.850, 0.851; 5-second cooldown; NORMAL gate reset. |
| **Mock Stream** | `MockTelemetryProviderTest.kt` | Deterministic sequences, 20-8-20-8-20-8 cycling, malformed injection recovery. |
| **ONNX Runtime** | `CpuOnnxInferenceEngineTest.kt`| Model asset loading, tensor shapes, latency tracking, softmax numerical stability. |
| **Cross-Platform Parity** | `tests/verify_android_parity.py` | Python vs Kotlin CRC, frame serialization, feature values, ONNX inference parity. |
