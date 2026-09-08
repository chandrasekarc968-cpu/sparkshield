package com.sparkshield.android.data

import com.sparkshield.android.data.dao.TamperEventDao
import com.sparkshield.android.data.dao.TelemetrySnapshotDao
import com.sparkshield.android.data.entity.TamperEventEntity
import com.sparkshield.android.data.entity.TelemetrySnapshotEntity
import com.sparkshield.android.data.repository.RoomTelemetryRepository
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * JVM unit tests verifying Room database entities, DAOs, batch buffering, and eviction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryRepositoryTest {

    private class FakeTamperDao : TamperEventDao {
        val events = mutableListOf<TamperEventEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<TamperEventEntity>>(emptyList())
        var shouldFailInsert: Boolean = false

        override suspend fun insert(event: TamperEventEntity): Long {
            if (shouldFailInsert) throw IOException("Simulated SQLite disk write failure")
            val assigned = if (event.id == 0L) event.copy(id = nextId++) else event
            events.add(assigned)
            flow.value = events.toList()
            return assigned.id
        }

        override suspend fun insertAll(events: List<TamperEventEntity>): List<Long> {
            return events.map { insert(it) }
        }

        override fun getRecentEvents(limit: Int): Flow<List<TamperEventEntity>> = flow.asStateFlow()

        override fun getEventsByClass(className: String): Flow<List<TamperEventEntity>> =
            flow.asStateFlow().map { list -> list.filter { it.className == className } }

        override suspend fun getAllEvents(): List<TamperEventEntity> = events.toList()

        override suspend fun count(): Long = events.size.toLong()

        override suspend fun deleteOldest(keepCount: Int): Int {
            if (events.size <= keepCount) return 0
            val deleteCount = events.size - keepCount
            // Keep newest elements (highest IDs / end of list)
            val toRetain = events.takeLast(keepCount)
            events.clear()
            events.addAll(toRetain)
            flow.value = events.toList()
            return deleteCount
        }

        override suspend fun clearAll(): Int {
            val size = events.size
            events.clear()
            flow.value = emptyList()
            return size
        }
    }

    private class FakeSnapshotDao : TelemetrySnapshotDao {
        val snapshots = mutableListOf<TelemetrySnapshotEntity>()
        private var nextId = 1L
        private val flow = MutableStateFlow<List<TelemetrySnapshotEntity>>(emptyList())
        var shouldFailInsert: Boolean = false

        override suspend fun insert(snapshot: TelemetrySnapshotEntity): Long {
            if (shouldFailInsert) throw IOException("Simulated SQLite snapshot write failure")
            val assigned = if (snapshot.id == 0L) snapshot.copy(id = nextId++) else snapshot
            snapshots.add(assigned)
            flow.value = snapshots.toList()
            return assigned.id
        }

        override suspend fun insertAll(snapshots: List<TelemetrySnapshotEntity>): List<Long> {
            if (shouldFailInsert) throw IOException("Simulated SQLite snapshot batch failure")
            return snapshots.map { insert(it) }
        }

        override fun getRecentSnapshots(limit: Int): Flow<List<TelemetrySnapshotEntity>> = flow.asStateFlow()

        override suspend fun count(): Long = snapshots.size.toLong()

        override suspend fun deleteOldest(keepCount: Int): Int {
            if (snapshots.size <= keepCount) return 0
            val deleteCount = snapshots.size - keepCount
            val toRetain = snapshots.takeLast(keepCount)
            snapshots.clear()
            snapshots.addAll(toRetain)
            flow.value = snapshots.toList()
            return deleteCount
        }

        override suspend fun clearAll(): Int {
            val size = snapshots.size
            snapshots.clear()
            flow.value = emptyList()
            return size
        }
    }

    private lateinit var tamperDao: FakeTamperDao
    private lateinit var snapshotDao: FakeSnapshotDao
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        tamperDao = FakeTamperDao()
        snapshotDao = FakeSnapshotDao()
    }

    @Test
    fun testTamperEventEntityFieldsAndMapping() {
        val event = TamperEventEntity(
            id = 1L,
            timestampMs = 1725700001000L,
            sequenceId = 42L,
            className = "EMP",
            confidence = 0.985f,
            peakMv = 15000,
            riseTimeNs = 20L,
            decayTimeUs = 5,
            opticalMv = 120,
            message = "High-voltage EMP-like transient detected"
        )

        assertEquals(1L, event.id)
        assertEquals(1725700001000L, event.timestampMs)
        assertEquals(42L, event.sequenceId)
        assertEquals("EMP", event.className)
        assertEquals(0.985f, event.confidence, 0.0001f)
        assertEquals(15000, event.peakMv)
        assertEquals(20L, event.riseTimeNs)
        assertEquals(5, event.decayTimeUs)
        assertEquals(120, event.opticalMv)
        assertTrue(event.message.contains("EMP"))
    }

    @Test
    fun testTelemetrySnapshotEntityFieldsAndMapping() {
        val snapshot = TelemetrySnapshotEntity(
            id = 1L,
            timestampMs = 1725700002000L,
            sequenceId = 100L,
            eventFlags = 0x08,
            peakMv = 3300,
            riseTimeNs = 250L,
            decayTimeUs = 500,
            opticalMv = 300,
            classification = "NORMAL",
            confidence = 0.99f,
            inferenceTimeUs = 400L,
            tamperDetected = false
        )

        assertEquals(100L, snapshot.sequenceId)
        assertEquals(0x08, snapshot.eventFlags)
        assertEquals("NORMAL", snapshot.classification)
        assertFalse(snapshot.tamperDetected)
    }

    @Test
    fun testRepositoryTamperEventPersistenceAndEviction() = runTest(testDispatcher) {
        val repository = RoomTelemetryRepository(
            tamperDao = tamperDao,
            snapshotDao = snapshotDao,
            scope = this,
            ioDispatcher = testDispatcher,
            maxTamperEvents = 5 // Low limit for test
        )

        // Insert 7 tamper events
        for (i in 1..7) {
            repository.recordTamperEvent(
                TamperEventEntity(
                    timestampMs = 1000L * i,
                    sequenceId = i.toLong(),
                    className = "EMP",
                    confidence = 0.95f,
                    peakMv = 10000 + i,
                    riseTimeNs = 20L,
                    decayTimeUs = 5,
                    opticalMv = 100
                )
            )
        }

        advanceUntilIdle()

        // Verify total retained matches cap (5)
        assertEquals(5L, tamperDao.count())
        assertEquals(5L, repository.persistedTamperEventsCount.value)

        // Verify oldest events (1 and 2) were evicted, leaving 3..7
        val remaining = tamperDao.getAllEvents()
        assertEquals(5, remaining.size)
        assertEquals(3L, remaining[0].sequenceId)
        assertEquals(7L, remaining[4].sequenceId)

        repository.stop()
    }

    @Test
    fun testRepositorySnapshotBatchBufferAndEviction() = runTest(testDispatcher) {
        val repository = RoomTelemetryRepository(
            tamperDao = tamperDao,
            snapshotDao = snapshotDao,
            scope = this,
            ioDispatcher = testDispatcher,
            batchFlushSize = 5,
            batchFlushIntervalMs = 1000L,
            maxSnapshots = 10
        )

        // Add 4 snapshots (below batchFlushSize 5)
        for (i in 1..4) {
            repository.recordTelemetrySnapshot(
                TelemetrySnapshotEntity(
                    timestampMs = 1000L * i,
                    sequenceId = i.toLong(),
                    eventFlags = 8,
                    peakMv = 3000,
                    riseTimeNs = 250L,
                    decayTimeUs = 500,
                    opticalMv = 300,
                    classification = "NORMAL",
                    confidence = 0.99f,
                    inferenceTimeUs = 350L,
                    tamperDetected = false
                )
            )
        }

        advanceUntilIdle()
        // Buffer has not flushed yet
        assertEquals(0L, snapshotDao.count())

        // Add 5th snapshot -> triggers flush
        repository.recordTelemetrySnapshot(
            TelemetrySnapshotEntity(
                timestampMs = 5000L,
                sequenceId = 5L,
                eventFlags = 8,
                peakMv = 3000,
                riseTimeNs = 250L,
                decayTimeUs = 500,
                opticalMv = 300,
                classification = "NORMAL",
                confidence = 0.99f,
                inferenceTimeUs = 350L,
                tamperDetected = false
            )
        )

        advanceUntilIdle()
        assertEquals(5L, snapshotDao.count())

        // Add 8 more snapshots (total 13, cap is 10)
        for (i in 6..13) {
            repository.recordTelemetrySnapshot(
                TelemetrySnapshotEntity(
                    timestampMs = 1000L * i,
                    sequenceId = i.toLong(),
                    eventFlags = 8,
                    peakMv = 3000,
                    riseTimeNs = 250L,
                    decayTimeUs = 500,
                    opticalMv = 300,
                    classification = "NORMAL",
                    confidence = 0.99f,
                    inferenceTimeUs = 350L,
                    tamperDetected = false
                )
            )
        }

        // Force manual flush
        repository.flushPendingSnapshots()
        advanceUntilIdle()

        // Bounded capacity enforced to 10
        assertEquals(10L, snapshotDao.count())

        repository.stop()
    }

    @Test
    fun testEntityIndicesDefinedOnTimestampAndTamperStatus() {
        val tamperAnnotation = TamperEventEntity::class.java.getAnnotation(androidx.room.Entity::class.java)
        assertNotNull("TamperEventEntity must be annotated with @Entity", tamperAnnotation)
        val tamperIndexColumns = tamperAnnotation.indices.map { it.value.toList() }
        assertTrue(tamperIndexColumns.any { it.contains("timestamp_ms") })
        assertTrue(tamperIndexColumns.any { it.contains("class_name") })

        val snapshotAnnotation = TelemetrySnapshotEntity::class.java.getAnnotation(androidx.room.Entity::class.java)
        assertNotNull("TelemetrySnapshotEntity must be annotated with @Entity", snapshotAnnotation)
        val snapshotIndexColumns = snapshotAnnotation.indices.map { it.value.toList() }
        assertTrue(snapshotIndexColumns.any { it.contains("timestamp_ms") })
        assertTrue(snapshotIndexColumns.any { it.contains("tamper_detected") })
    }

    @Test
    fun testRoomMigrationV1ToV2ExecutesIndexCreation() {
        val executedSql = mutableListOf<String>()
        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        SparkShieldDatabase.MIGRATION_1_2.migrate(fakeDb)

        assertTrue(executedSql.any { it.contains("index_tamper_events_timestamp_ms") })
        assertTrue(executedSql.any { it.contains("index_tamper_events_class_name") })
        assertTrue(executedSql.any { it.contains("index_telemetry_snapshots_timestamp_ms") })
        assertTrue(executedSql.any { it.contains("index_telemetry_snapshots_tamper_detected") })
    }

    @Test
    fun testTamperPersistenceWriteFailureHandling() = runTest(testDispatcher) {
        val repository = RoomTelemetryRepository(
            tamperDao = tamperDao,
            snapshotDao = snapshotDao,
            scope = this,
            ioDispatcher = testDispatcher
        )

        tamperDao.shouldFailInsert = true

        val event = TamperEventEntity(
            timestampMs = 1000L,
            sequenceId = 1L,
            className = "EMP",
            confidence = 0.99f,
            peakMv = 15000,
            riseTimeNs = 20L,
            decayTimeUs = 5,
            opticalMv = 100
        )

        repository.recordTamperEvent(event)
        advanceUntilIdle()

        assertNotNull(repository.persistenceError.value)
        assertTrue(repository.persistenceError.value!!.contains("Tamper event write failure"))
        assertEquals(1L, repository.persistenceFailureCount.value)

        // Reset error on subsequent success
        tamperDao.shouldFailInsert = false
        repository.recordTamperEvent(event.copy(sequenceId = 2L))
        advanceUntilIdle()

        assertNull(repository.persistenceError.value)
        assertEquals(1L, repository.persistenceFailureCount.value)
        assertEquals(1L, repository.persistedTamperEventsCount.value)

        repository.stop()
    }

    @Test
    fun testSnapshotPersistenceWriteFailureAndRequeue() = runTest(testDispatcher) {
        val repository = RoomTelemetryRepository(
            tamperDao = tamperDao,
            snapshotDao = snapshotDao,
            scope = this,
            ioDispatcher = testDispatcher,
            batchFlushSize = 3,
            maxBufferCapacity = 50
        )

        snapshotDao.shouldFailInsert = true

        // Queue 3 snapshots to trigger flush
        for (i in 1..3) {
            repository.recordTelemetrySnapshot(
                TelemetrySnapshotEntity(
                    timestampMs = 1000L * i,
                    sequenceId = i.toLong(),
                    eventFlags = 8,
                    peakMv = 3000,
                    riseTimeNs = 250L,
                    decayTimeUs = 500,
                    opticalMv = 300,
                    classification = "NORMAL",
                    confidence = 0.99f,
                    inferenceTimeUs = 350L,
                    tamperDetected = false
                )
            )
        }
        advanceUntilIdle()

        // Verify write failure reported and records re-queued
        assertNotNull(repository.persistenceError.value)
        assertEquals(1L, repository.persistenceFailureCount.value)
        assertEquals(0L, snapshotDao.count())

        // Recover: database is healthy again
        snapshotDao.shouldFailInsert = false
        repository.flushPendingSnapshots()
        advanceUntilIdle()

        // The 3 re-queued snapshots should now be persisted successfully
        assertEquals(3L, snapshotDao.count())
        assertNull(repository.persistenceError.value)

        repository.stop()
    }

    @Test
    fun testBufferCapacityExceededDropsOldestAndAccountsForDropped() = runTest(testDispatcher) {
        val repository = RoomTelemetryRepository(
            tamperDao = tamperDao,
            snapshotDao = snapshotDao,
            scope = this,
            ioDispatcher = testDispatcher,
            batchFlushSize = 100, // Do not auto-flush
            maxBufferCapacity = 5
        )

        // Insert 7 snapshots with max buffer capacity 5
        for (i in 1..7) {
            repository.recordTelemetrySnapshot(
                TelemetrySnapshotEntity(
                    timestampMs = 1000L * i,
                    sequenceId = i.toLong(),
                    eventFlags = 8,
                    peakMv = 3000,
                    riseTimeNs = 250L,
                    decayTimeUs = 500,
                    opticalMv = 300,
                    classification = "NORMAL",
                    confidence = 0.99f,
                    inferenceTimeUs = 350L,
                    tamperDetected = false
                )
            )
        }
        advanceUntilIdle()

        // 2 snapshots should be dropped
        assertEquals(2L, repository.droppedSnapshotsCount.value)

        // Flush remaining 5
        repository.flushPendingSnapshots()
        advanceUntilIdle()

        assertEquals(5L, snapshotDao.count())
        // Oldest preserved sequence ID should be 3 (1 and 2 were dropped)
        assertEquals(3L, snapshotDao.snapshots[0].sequenceId)

        repository.stop()
    }

    @Test
    fun testCleanShutdownFlushesPendingSnapshots() = runTest(testDispatcher) {
        val repository = RoomTelemetryRepository(
            tamperDao = tamperDao,
            snapshotDao = snapshotDao,
            scope = this,
            ioDispatcher = testDispatcher,
            batchFlushSize = 20 // Below auto-flush size
        )

        // Add 5 snapshots
        for (i in 1..5) {
            repository.recordTelemetrySnapshot(
                TelemetrySnapshotEntity(
                    timestampMs = 1000L * i,
                    sequenceId = i.toLong(),
                    eventFlags = 8,
                    peakMv = 3000,
                    riseTimeNs = 250L,
                    decayTimeUs = 500,
                    opticalMv = 300,
                    classification = "NORMAL",
                    confidence = 0.99f,
                    inferenceTimeUs = 350L,
                    tamperDetected = false
                )
            )
        }
        advanceUntilIdle()
        assertEquals(0L, snapshotDao.count())

        // Explicit shutdown flush
        repository.flushPendingSnapshots()
        advanceUntilIdle()
        assertEquals(5L, snapshotDao.count())

        repository.stop()
    }
}
