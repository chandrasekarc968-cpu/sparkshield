# SparkShield Android Edge Application

## Module Layout

- `app/`: Android application manifest, build configuration, and application entry point.
- `service/`: Foreground service running under `connectedDevice` foreground service type. Manages wake locks, lifecycle, and rate-limited notifications.
- `inference/`: `InferenceEngine` interface with `CpuInferenceEngine` and `QnnInferenceEngine` (Hexagon HTP) implementations.
- `protocol/`: Kotlin port of 29-byte frame unpacker, CRC-16-CCITT validator, and `FeatureExtractor` sliding window.
- `transport/`: `TelemetryProvider` interface (`MockTelemetryProvider`, `BleTelemetryProvider`).
- `data/`: `TelemetryRepository` interface, in-memory circular buffer, and Room database entities.
- `ui/`: Status view, manual tamper trigger buttons, and real-time monitoring cards.
