package com.sparkshield.android.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for smart-meter telemetry frame sources.
 *
 * Decouples the service and processing pipeline from the underlying transport,
 * allowing seamless switching between local mock stream (Phase 3) and BLE peripheral scanning (Phase 5).
 */
interface TelemetryProvider {

    /**
     * Shared flow emitting raw 29-byte big-endian telemetry frame buffers.
     */
    val rawFrameFlow: SharedFlow<ByteArray>

    /**
     * Observable state flow indicating whether the transport is active and streaming.
     */
    val isConnected: StateFlow<Boolean>

    /**
     * Start the telemetry ingestion pipeline.
     */
    fun start()

    /**
     * Stop the telemetry ingestion pipeline and disconnect.
     */
    fun stop()
}
