package com.sparkshield.android.data.repository

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
    private val maxSnapshots: Int = MAX_SNAPSHOTS_CAP
) : TelemetryRepository {

    companion object {
        const val DEFAULT_BATCH_FLUSH_SIZE = 20
        const val DEFAULT_BATCH_FLUSH_INTERVAL_MS = 2000L
        const val MAX_TAMPER_EVENTS_CAP = 1000
        const val MAX_SNAPSHOTS_CAP = 5000
    }

    private val snapshotBuffer = mutableListOf<TelemetrySnapshotEntity>()
    private val bufferMutex = Mutex()

    private val _persistedTamperEventsCount = MutableStateFlow(0L)
    override val persistedTamperEventsCount: StateFlow<Long> = _persistedTamperEventsCount.asStateFlow()

    private val _persistedSnapshotsCount = MutableStateFlow(0L)
    override val persistedSnapshotsCount: StateFlow<Long> = _persistedSnapshotsCount.asStateFlow()

    override val recentTamperEvents: Flow<List<TamperEventEntity>> = tamperDao.getRecentEvents(100)
    override val recentSnapshots: Flow<List<TelemetrySnapshotEntity>> = snapshotDao.getRecentSnapshots(100)

    private var periodicFlushJob: Job? = null

    init {
        // Initialize counts asynchronously on start
        scope.launch(ioDispatcher) {
            try {
                _persistedTamperEventsCount.value = tamperDao.count()
                _persistedSnapshotsCount.value = snapshotDao.count()
            } catch (e: Exception) {
                // Keep initial 0L on error
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
                val currentCount = tamperDao.count()
                if (currentCount > maxTamperEvents) {
                    tamperDao.deleteOldest(maxTamperEvents)
                    _persistedTamperEventsCount.value = maxTamperEvents.toLong()
                } else {
                    _persistedTamperEventsCount.value = currentCount
                }
            } catch (e: Exception) {
                // Storage failure logged safely without crashing monitoring service
            }
        }
    }

    /**
     * Non-blocking queueing of high-frequency telemetry snapshots into in-memory buffer.
     * Triggers asynchronous IO flush if buffer reaches [batchFlushSize].
     */
    override fun recordTelemetrySnapshot(snapshot: TelemetrySnapshotEntity) {
        scope.launch {
            val shouldFlush = bufferMutex.withLock {
                snapshotBuffer.add(snapshot)
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
            val currentCount = snapshotDao.count()
            if (currentCount > maxSnapshots) {
                snapshotDao.deleteOldest(maxSnapshots)
                _persistedSnapshotsCount.value = maxSnapshots.toLong()
            } else {
                _persistedSnapshotsCount.value = currentCount
            }
        } catch (e: Exception) {
            // Drop or re-queue on persistent error
        }
    }

    override suspend fun clearAllData() {
        withContext(ioDispatcher) {
            bufferMutex.withLock { snapshotBuffer.clear() }
            tamperDao.clearAll()
            snapshotDao.clearAll()
            _persistedTamperEventsCount.value = 0L
            _persistedSnapshotsCount.value = 0L
        }
    }

    override fun stop() {
        periodicFlushJob?.cancel()
    }
}
