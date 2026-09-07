# SparkShield Node-RED Industrial Automation Adapter (Phase 6)

The **SparkShield Node-RED Flow** integrates the on-device and edge inference telemetry pipeline into industrial automation systems, SCADA environments, and operations dashboards.

> [!IMPORTANT]
> **Safety Boundary**:
> All incoming events represent simulated tamper signals and software inference detections. The adapter executes monitoring, logging, and notification flows only; it does not issue physical electrical control commands.

---

## 1. Flow Overview

```
[WebSocket In (ws://<host>:8765/telemetry)] ───► [WS Connection Status & Health Monitor]
                │
                ▼
  [JSON Parse (12 Telemetry Fields)]
                │
                ├─────────────────────────────────────────────────┐
                │ (tamperDetected == false)                       │ (tamperDetected == true)
                ▼                                                 ▼
[Grid Baseline Monitor (Function)]               [Tamper Class Splitter (Switch)]
  - Voltage Moving Avg                             ├── EMP (Severity 1: Critical)
  - HF/LF Energy Ratio                             ├── OPTICAL (Severity 2: Critical)
  - Diagnostic Console Stream                      └── SURGE (Severity 3: High Warning)
                                                                  │
                                                                  ▼
                                                   [5-Second Debounce Gate (Rate Limit)]
                                                                  │
                                                                  ▼
                                                   [Multi-Channel Alert Dispatcher]
                                                     ├── MQTT: sparkshield/alerts/{class} (optional)
                                                     ├── Webhook: POST /api/v1/alerts (optional)
                                                     └── Debug Console Output (active)
```

---

## 2. Ingested 12-Field Telemetry Schema

The flow ingests the standard SparkShield WebSocket JSON payload emitted by the Android service and Python publisher:

| Field | Type | Description |
| :--- | :--- | :--- |
| `seqId` | `number` | Monotonically increasing frame sequence identifier |
| `timestampMs` | `number` | Relative timestamp or device epoch (ms) |
| `eventFlags` | `number` | Packed event flag byte (bit 0=EMP, 1=OPT, 2=SRG, 3=NORM) |
| `peakMv` | `number` | Signal peak amplitude (mV) |
| `riseTimeNs` | `number` | Signal front rise time (ns) |
| `decayTimeUs` | `number` | Signal duration/decay time (µs) |
| `opticalMv` | `number` | Photodiode rail voltage (mV) |
| `fftBins` | `number[8]` | 8-band normalized frequency spectrum |
| `classification`| `string` | Predicted class: `"NORMAL"`, `"EMP"`, `"OPTICAL"`, `"SURGE"` |
| `confidence` | `number` | Model inference probability (0.0 to 1.0) |
| `inferenceTimeUs`| `number`| On-device inference duration (µs) |
| `tamperDetected` | `boolean`| Assertion flag (`class != NORMAL && conf >= 0.85`) |

---

## 3. Sub-Flows & Logic

### A. Connection Health & Error Tracking
- `node_ws_status`: Scoped to `node_ws_in` to monitor connection transitions (`connected`, `disconnected`, `error`).
- `node_ws_health_check`: Emits `{ connectionState, healthStatus: "ONLINE" | "OFFLINE_OR_ERROR", connected, error }`.
- `node_ws_catch`: Catches ingestion errors and reports warning payloads to debug console without interrupting execution.

### B. Grid Baseline Monitoring
For `tamperDetected == false` frames:
- Computes high-frequency to low-frequency energy ratio:
  $$\text{HF Ratio} = \frac{\sum_{i=4}^7 \text{bin}_i}{\sum_{i=0}^7 \text{bin}_i + 10^{-5}}$$
- Monitors nominal 50/60 Hz voltage stability.
- Routes baseline diagnostics to console or dashboard gauges.

### C. Classification Routing & Severity Escalation
For `tamperDetected == true` frames:
- **EMP**: Escalated as **Critical Level 1** (ultrafast transient, broadband electromagnetic injection).
- **OPTICAL**: Escalated as **Critical Level 2** (photodiode rail saturation, physical enclosure breach/laser).
- **SURGE**: Escalated as **Warning Level 3** (inductive switching transients, potential power anomaly).

### D. 5-Second Debounce Gate
- Each tamper class passes through an independent rate-limiting delay node configured for **1 message per 5 seconds** (`drop: true`).
- Matches the Android `AlertGate` 5000 ms cooldown to prevent alert storms and flapping during continuous tamper bursts.

### E. Dispatch Outputs
- **MQTT Output**: Publishes JSON alerts to broker (default `localhost:1883`) on topic `sparkshield/alerts/{tamperType}` with QoS 1. *Disabled by default* (`"d": true`) for clean local testing.
- **HTTP Webhook**: Dispatches POST request with structured alert JSON to `http://localhost:8080/api/v1/alerts`. *Disabled by default* (`"d": true`) for clean local testing.
- **Debug Logger**: Prints formatted alerts to Node-RED sidebar and server console (active).

---

## 4. How to Import and Run

1. **Start Node-RED**:
   ```bash
   node-red
   ```
2. Open the Node-RED editor in your browser (`http://localhost:1880`).
3. Click the top-right hamburger menu $\to$ **Import**.
4. Select or paste the contents of [`automation/node-red-flow.json`](file:///c:/Users/Chand/Documents/New%20folder/sparkshield/sparkshield/automation/node-red-flow.json).
5. Ensure the WebSocket client node points to your active SparkShield telemetry source:
   - For local Python publisher: `ws://localhost:8765/telemetry` (default canonical endpoint)
   - For Android emulator: `ws://10.0.2.2:8765/telemetry`
   - For Android hardware via ADB: Run `adb reverse tcp:8765 tcp:8765` then use `ws://localhost:8765/telemetry`
   - For Android device over Wi-Fi: `ws://<android-ip>:8765/telemetry`
6. *(Optional)* To enable MQTT or HTTP Webhook dispatch:
   - Double-click the `MQTT Alert Publisher` node, click **Enable** (top-left of node dialog), configure broker, and deploy.
   - Double-click the `HTTP Webhook Dispatcher` node, click **Enable**, configure your REST endpoint, and deploy.
7. Click **Deploy**.

---

## 5. Troubleshooting & Health Diagnostics

- **Connection Error / Red Status**: If Node-RED displays a red square on `SparkShield WS Receiver`, verify the publisher is running with `curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Host: localhost:8765" -H "Sec-WebSocket-Key: SGVsbG8sIHdvcmxkIQ==" -H "Sec-WebSocket-Version: 13" http://localhost:8765/telemetry` or check `WS Health Log` in the debug sidebar.
- **Port Conflict**: If running multiple instances or changing port, edit `ws_client_sparkshield` in Node-RED Configuration Nodes to match your custom port.

