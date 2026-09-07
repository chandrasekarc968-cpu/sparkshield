package com.sparkshield.android.transport

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for smart-meter telemetry frame sources.
 *
 * Exposes a stream of raw 29-byte packed big-endian frames.
 * The system is software-only simulation.
 */
interface TelemetryProvider {
    /**
     * Flow emitting raw 29-byte binary telemetry frames.
     */
    val frames: Flow<ByteArray>

    /**
     * Start the telemetry ingestion pipeline.
     */
    suspend fun start()

    /**
     * Stop the telemetry ingestion pipeline and disconnect.
     */
    suspend fun stop()
}
