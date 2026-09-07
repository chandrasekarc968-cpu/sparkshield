# SparkShield Real-Time Dashboard and Bridge

## Overview

A responsive dark-mode monitoring dashboard built to visualize real-time smart-meter telemetry and tamper detection states.

## Directory Structure

- `src/`: Core dashboard frontend logic and entry point.
- `websocket/`: WebSocket client / server bridge connecting to Android `WebSocketPublisher` or local Python mock stream.
- `components/`: UI components (Waveform canvas with `requestAnimationFrame`, Alert Banner, Metrics cards, Latency profiler).
