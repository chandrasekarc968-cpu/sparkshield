# SparkShield Core Architecture

## 1. System Overview

SparkShield is a cyber-physical smart-meter tamper-detection architecture structured into three tiers:

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

## 2. Component Design & Interfaces

### 2.1. Telemetry Provider (`TelemetryProvider`)
Decouples frame ingestion from subsequent processing.
- `MockTelemetryProvider`: Ingests simulated frames locally or via TCP/loopback.
- `BleTelemetryProvider`: Scans for SparkShield BLE GATT peripheral (`0000FE50-0000-1000-8000-00805F9B34FB`), subscribes to notifications (`0000FE51-0000-1000-8000-00805F9B34FB`), and unpacks frames.

### 2.2. Inference Engine (`InferenceEngine`)
Decouples machine learning runtimes from application logic.
- `CpuInferenceEngine`: Default fallback using ONNX Runtime CPU or TensorFlow Lite CPU.
- `QnnInferenceEngine`: Qualcomm AI Runtime (QAIRT) / Qualcomm Neural Network (QNN) Hexagon HTP backend for hardware-accelerated NPU execution.
- Gating policy: Alerts and haptic notifications are **only triggered when classification confidence $\ge 0.85$**, suppressing false alarms.

### 2.3. Telemetry Repository (`TelemetryRepository`)
Handles telemetry persistence and history buffering.
- In-memory circular buffer for immediate dashboard streaming and low overhead.
- Room database implementation for persistent logging of tamper events.

### 2.4. Telemetry Publisher (`TelemetryPublisher`)
Exposes telemetry data and model predictions downstream.
- `WebSocketPublisher`: Streams JSON telemetry packets over a local WebSocket server to connected dashboard clients or ADB reverse port forwards.

---

## 3. End-to-End Latency and Benchmarking Policy

> [!CAUTION]
> **Benchmarking Integrity**:
> No sub-millisecond or end-to-end latency claims may be made without empirical profiling on target Qualcomm hardware.

SparkShield instruments each processing stage with nanosecond/microsecond timers:

```
[Frame Received]
       |
       +---> 1. Frame Decode Time (T_decode)
       |
       +---> 2. Feature Extraction & Window Roll Time (T_feat)
       |
       +---> 3. Model Execution Time (T_infer)
       |
       +---> 4. Database Write Time (T_db)
       |
       +---> 5. WebSocket Publish Time (T_ws)
       |
[Dashboard Render]
       |
       +---> 6. UI Render Loop Timing (T_render)
```

Total processing latency $T_{\text{total}} = T_{\text{decode}} + T_{\text{feat}} + T_{\text{infer}} + T_{\text{db}} + T_{\text{ws}}$. All telemetry events log these discrete intervals.

---

## 4. Quantized Model Deployment Note

When deploying INT8 quantized models (e.g. for Qualcomm Hexagon HTP):
- Signed INT8 outputs must be dequantized using the model's actual calibration scale and zero-point:
  $$\text{float\_val} = (\text{int8\_val} - \text{zero\_point}) \times \text{scale}$$
- Never treat raw signed INT8 bytes as unsigned values without proper mathematical transformation.
