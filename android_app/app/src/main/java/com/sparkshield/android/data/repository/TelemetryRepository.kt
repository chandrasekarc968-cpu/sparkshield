package com.sparkshield.android.data.repository

import android.util.Log
import com.sparkshield.android.data.dao.TamperEventDao
import com.sparkshield.android.data.dao.TelemetrySnapshotDao
import com.sparkshield.android.data.entity.TamperEventEntity
import com.sparkshield.android.data.entity.TelemetrySnapshotEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository interface defining persistence operations and observable audit streams.
 */
interface TelemetryRepository {
    val recentTamperEvents: Flow<List<TamperEventEntity>>
    val recentSnapshots: Flow<List<TelemetrySnapshotEntity>>
    val persistedTamperEventsCount: StateFlow<Long>
    val persistedSnapshotsCount: StateFlow<Long>

    val persistenceError: StateFlow<String?>
    val persistenceFailureCount: StateFlow<Long>
    val droppedSnapshotsCount: StateFlow<Long>

    fun getTamperEvents(className: String? = null): Flow<List<TamperEventEntity>>

    suspend fun recordTamperEvent(event: TamperEventEntity)
    fun recordTelemetrySnapshot(snapshot: TelemetrySnapshotEntity)
    suspend fun flushPendingSnapshots()
    suspend fun clearAllData()
    fun stop()
}

/**
 * Production implementation of [TelemetryRepository] providing:
 *   1. Non-blocking high-frequency in-memory buffering (10-50 Hz safe)
 *   2. Periodic or batch-size asynchronous flush to Room via [Dispatchers.IO]
 *   3. Enforced table capacities with automatic eviction (1,000 tampers, 5,000 snapshots)
 *   4. Zero UI / inference thread blocking
 */
class RoomTelemetryRepository(
    private val tamperDao: TamperEventDao,
    private val snapshotDao: TelemetrySnapshotDao,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val batchFlushSize: Int = DEFAULT_BATCH_FLUSH_SIZE,
    private val batchFlushIntervalMs: Long = DEFAULT_BATCH_FLUSH_INTERVAL_MS,
    private val maxTamperEvents: Int = MAX_TAMPER_EVENTS_CAP,
    private val maxSnapshots: Int = MAX_SNAPSHOTS_CAP,
    private val maxBufferCapacity: Int = MAX_BUFFER_CAPACITY
) : TelemetryRepository {

    companion object {
        const val DEFAULT_BATCH_FLUSH_SIZE = 20
        const val DEFAULT_BATCH_FLUSH_INTERVAL_MS = 2000L
        const val MAX_TAMPER_EVENTS_CAP = 1000
        const val MAX_SNAPSHOTS_CAP = 5000
        const val MAX_BUFFER_CAPACITY = 1000
        private const val TAG = "RoomTelemetryRepository"
    }

    private val snapshotBuffer = mutableListOf<TelemetrySnapshotEntity>()
    private val bufferMutex = Mutex()

    private val _persistedTamperEventsCount = MutableStateFlow(0L)
    override val persistedTamperEventsCount: StateFlow<Long> = _persistedTamperEventsCount.asStateFlow()

    private val _persistedSnapshotsCount = MutableStateFlow(0L)
    override val persistedSnapshotsCount: StateFlow<Long> = _persistedSnapshotsCount.asStateFlow()

    private val _persistenceError = MutableStateFlow<String?>(null)
    override val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    private val _persistenceFailureCount = MutableStateFlow(0L)
    override val persistenceFailureCount: StateFlow<Long> = _persistenceFailureCount.asStateFlow()

    private val _droppedSnapshotsCount = MutableStateFlow(0L)
    override val droppedSnapshotsCount: StateFlow<Long> = _droppedSnapshotsCount.asStateFlow()

    override val recentTamperEvents: Flow<List<TamperEventEntity>> = tamperDao.getRecentEvents(100)
    override val recentSnapshots: Flow<List<TelemetrySnapshotEntity>> = snapshotDao.getRecentSnapshots(100)

    override fun getTamperEvents(className: String?): Flow<List<TamperEventEntity>> {
        return if (className == null) {
            tamperDao.getRecentEvents(500)
        } else {
            tamperDao.getEventsByClass(className)
        }
    }

    private var periodicFlushJob: Job? = null

    init {
        // Initialize counts asynchronously on start
        scope.launch(ioDispatcher) {
            try {
                _persistedTamperEventsCount.value = tamperDao.count()
                _persistedSnapshotsCount.value = snapshotDao.count()
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch initial database counts: ${e.message}")
            }
        }

        // Start periodic flush timer
        periodicFlushJob = scope.launch(ioDispatcher) {
            while (isActive) {
                delay(batchFlushIntervalMs)
                flushPendingSnapshotsInternal()
            }
        }
    }

    /**
     * Persist confirmed tamper alert immediately on IO dispatcher and enforce capacity limit.
     */
    override suspend fun recordTamperEvent(event: TamperEventEntity) {
        withContext(ioDispatcher) {
            try {
                tamperDao.insert(event)
                _persistenceError.value = null
                val currentCount = tamperDao.count()
                if (currentCount > maxTamperEvents) {
                    tamperDao.deleteOldest(maxTamperEvents)
                    _persistedTamperEventsCount.value = maxTamperEvents.toLong()
                } else {
                    _persistedTamperEventsCount.value = currentCount
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist tamper event seq=${event.sequenceId}: ${e.message}", e)
                _persistenceError.value = "Tamper event write failure: ${e.message}"
                _persistenceFailureCount.update { it + 1 }
            }
        }
    }

    /**
     * Non-blocking queueing of high-frequency telemetry snapshots into bounded in-memory buffer.
     * Triggers asynchronous IO flush if buffer reaches [batchFlushSize].
     */
    override fun recordTelemetrySnapshot(snapshot: TelemetrySnapshotEntity) {
        scope.launch {
            val shouldFlush = bufferMutex.withLock {
                if (snapshotBuffer.size < maxBufferCapacity) {
                    snapshotBuffer.add(snapshot)
                } else {
                    // Buffer capacity reached; drop oldest to prevent unbounded memory growth
                    snapshotBuffer.removeAt(0)
                    snapshotBuffer.add(snapshot)
                    _droppedSnapshotsCount.update { it + 1 }
                    Log.w(TAG, "Buffer capacity reached; dropped oldest snapshot")
                }
                snapshotBuffer.size >= batchFlushSize
            }
            if (shouldFlush) {
                launch(ioDispatcher) {
                    flushPendingSnapshotsInternal()
                }
            }
        }
    }

    /**
     * Flushes all currently pending in-memory telemetry snapshots to SQLite in a batch.
     */
    override suspend fun flushPendingSnapshots() {
        withContext(ioDispatcher) {
            flushPendingSnapshotsInternal()
        }
    }

    private suspend fun flushPendingSnapshotsInternal() {
        val toPersist: List<TelemetrySnapshotEntity> = bufferMutex.withLock {
            if (snapshotBuffer.isEmpty()) return
            val copy = ArrayList(snapshotBuffer)
            snapshotBuffer.clear()
            copy
        }

        try {
            snapshotDao.insertAll(toPersist)
            _persistenceError.value = null
            val currentCount = snapshotDao.count()
            if (currentCount > maxSnapshots) {
                snapshotDao.deleteOldest(maxSnapshots)
                _persistedSnapshotsCount.value = maxSnapshots.toLong()
            } else {
                _persistedSnapshotsCount.value = currentCount
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush ${toPersist.size} snapshots: ${e.message}", e)
            _persistenceError.value = "Snapshot batch write failure: ${e.message}"
            _persistenceFailureCount.update { it + 1 }

            // Safely re-queue up to available buffer capacity without unbounded memory growth
            bufferMutex.withLock {
                val availableSpace = maxBufferCapacity - snapshotBuffer.size
                if (availableSpace > 0) {
                    val toRequeue = toPersist.take(availableSpace)
                    val dropped = toPersist.size - toRequeue.size
                    snapshotBuffer.addAll(0, toRequeue)
                    if (dropped > 0) {
                        _droppedSnapshotsCount.update { it + dropped }
                        Log.w(TAG, "Re-queue capacity exceeded; dropped $dropped snapshots")
                    }
                } else {
                    _droppedSnapshotsCount.update { it + toPersist.size }
                    Log.w(TAG, "Buffer full during failure recovery; dropped ${toPersist.size} snapshots")
                }
            }
        }
    }

    override suspend fun clearAllData() {
        withContext(ioDispatcher) {
            bufferMutex.withLock { snapshotBuffer.clear() }
            tamperDao.clearAll()
            snapshotDao.clearAll()
            _persistedTamperEventsCount.value = 0L
            _persistedSnapshotsCount.value = 0L
            _persistenceError.value = null
            _persistenceFailureCount.value = 0L
            _droppedSnapshotsCount.value = 0L
        }
    }

    override fun stop() {
        periodicFlushJob?.cancel()
    }
}
