# SparkShield Real-Time Dark Monitoring Dashboard (Phase 4)

## 1. Overview

The **SparkShield Real-Time Dark Monitoring Dashboard** is an edge-connected web application designed to visualize smart-meter cyber-physical telemetry, on-device AI classifications, confidence levels, transient waveforms, and tamper alerts in real-time.

> [!IMPORTANT]
> **SIMULATION ONLY BOUNDARY**:
> All sensor signals, peak voltages, transient spikes, and tamper attacks visualized on this dashboard are purely software-generated synthetic models. No physical electrical meters, high-voltage testbeds, lasers, or bypass circuits are connected.

---

## 2. Architecture & Performance Features

- **Rendering Engine**: Decoupled dual HTML5 Canvas graphs (`WaveformCanvas` and `FftCanvas`) driven strictly by `requestAnimationFrame` (60 FPS) to prevent DOM reflow bottlenecks.
- **Bounded Memory Management**: Time-series telemetry and event logs use a fixed-capacity circular ring buffer (`RingBuffer(120)` and bounded event list of 50 items). Zero allocations in steady-state rendering.
- **Fine-Grained DOM Updates**: `MetricsCards` mutates only changed text nodes and CSS state classes without full-page re-renders.
- **High-Priority Threat Visuals**: EMP and Optical tamper states trigger high-priority neon red alerts (`#EF4444`) with animated glowing alert banners. Nominal states render in emerald green (`#10B981`). Inductive surges render in amber warning (`#F59E0B`).
- **Resilience & Watchdog**: Auto-reconnect with exponential backoff and jitter. A 3.0-second watchdog timer automatically transitions status to `STALE` if the publisher stream stalls. Malformed JSON frames are logged safely without crashing the dashboard.
- **Responsive Layout**: Designed for 1440px+ multi-column widescreen displays and responsive mobile device widths (<768px).

---

## 3. WebSocket Message Schema

Clients connect via RFC 6455 WebSockets to `ws://localhost:8765` (or Android device IP: `ws://<device_ip>:8765`).

### JSON Payload Specification
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

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `seqId` | `number` | Monotonically increasing sequence ID counter |
| `timestampMs` | `number` | Millisecond epoch timestamp |
| `eventFlags` | `number` | Wire event bitmask (bit 0=EMP, 1=OPTICAL, 2=SURGE, 3=NORMAL) |
| `peakMv` | `number` | Peak grid sensor voltage in millivolts |
| `riseTimeNs` | `number` | Transient rise time in nanoseconds ($10\text{ ns/LSB}$) |
| `decayTimeUs` | `number` | Transient decay duration in microseconds ($1\ \mu\text{s/LSB}$) |
| `opticalMv` | `number` | Optical photodiode rail sensor voltage in millivolts |
| `fftBins` | `number[8]` | 8-bin frequency energy distribution |
| `classification`| `string` | Model prediction: `"NORMAL"`, `"EMP"`, `"OPTICAL"`, or `"SURGE"` |
| `confidence` | `number` | Softmax probability score $[0.0, 1.0]$ |
| `inferenceTimeUs`| `number` | Inference execution latency in microseconds ($\mu\text{s}$) |
| `tamperDetected`| `boolean` | `true` only when `confidence >= 0.85` and class is not `NORMAL` |

---

## 4. Development & Build Commands

All commands are executed from the `dashboard/` directory:

### Install Dependencies
```bash
npm install
```

### Run Local Development Server
```bash
npm run dev
# Dashboard launches on http://localhost:3000
```

### Production Build
```bash
npm run build
# Outputs optimized static bundle to dashboard/dist/
```

### Preview Production Build
```bash
npm run preview
# Serves dashboard/dist/ on http://localhost:3000
```

### Run Automated Unit Tests
```bash
npm test
# Runs node:test runner for RingBuffer, WebSocketClient, and DashboardState
```

### Launch Standalone Mock Publisher
```bash
npm run mock-server
# Or directly via Python from repository root:
# python -m python_core.mock_ws_server --port 8765
```

---

## 5. Connecting to Live Stream

- **Local Mock Mode**: Run `python -m python_core.mock_ws_server --port 8765`, then open `http://localhost:3000`.
- **Android Device**: Ensure Android app foreground service is running. Connect your browser to `http://localhost:3000?ws=ws://<ANDROID_IP>:8765` or enter the IP in the dashboard header input box.
