package com.sparkshield.android.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronous WebSocket publisher decoupling inference execution from network broadcasting.
 *
 * Requirements satisfied:
 * - Telemetry publishing is fully asynchronous via bounded channel (capacity = 64, DROP_OLDEST)
 * - Inference pipeline and alert handling never block regardless of network latency or slow clients
 * - Multi-client broadcast support with automatic stale connection pruning
 * - Periodic heartbeat ping keeping connections alive
 * - Clean lifecycle startup and shutdown with the foreground service
 */
class WebSocketPublisher(
    val port: Int = 8765,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val tag = "SparkShieldPublisher"
    private val isRunning = AtomicBoolean(false)
    private val server = WebSocketServer(port = port)

    private val publisherScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var broadcastJob: Job? = null
    private var heartbeatJob: Job? = null

    // Bounded channel: never blocks producer, drops oldest on backpressure
    private val messageChannel = Channel<TelemetryWsMessage>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val clientCount: Int
        get() = server.clientCount

    /**
     * Starts the underlying WebSocket server and asynchronous dispatch coroutines.
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true

        val started = server.start()
        if (!started) return false

        isRunning.set(true)

        // Asynchronous broadcast consumer
        broadcastJob = publisherScope.launch {
            for (message in messageChannel) {
                try {
                    val json = message.toJson()
                    server.broadcast(json)
                } catch (e: Exception) {
                    Log.w(tag, "Error broadcasting telemetry message: ${e.message}")
                }
            }
        }

        // Heartbeat keep-alive timer (every 5 seconds)
        heartbeatJob = publisherScope.launch {
            while (isActive && isRunning.get()) {
                delay(5000L)
                server.sendHeartbeat()
            }
        }

        Log.i(tag, "WebSocket publisher started on port $port")
        return true
    }

    /**
     * Enqueues a telemetry message for asynchronous transmission.
     *
     * Non-blocking guarantee:
     * Drops oldest message if channel is full; caller returns immediately.
     */
    fun publish(message: TelemetryWsMessage): Boolean {
        if (!isRunning.get()) return false

        val result = messageChannel.trySend(message)
        if (result.isFailure) {
            Log.w(tag, "Outbound telemetry frame dropped due to buffer saturation")
            return false
        }
        return true
    }

    /**
     * Alias for [publish] for backward compatibility with service callers.
     */
    fun enqueueMessage(message: TelemetryWsMessage): Boolean = publish(message)

    /**
     * Cleanly shuts down the publisher, terminates background coroutines,
     * and releases network sockets.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        Log.i(tag, "Stopping WebSocket publisher...")
        heartbeatJob?.cancel()
        heartbeatJob = null

        broadcastJob?.cancel()
        broadcastJob = null

        server.stop()
        Log.i(tag, "WebSocket publisher stopped cleanly.")
    }
}
