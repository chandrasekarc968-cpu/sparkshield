package com.sparkshield.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sparkshield.android.data.entity.TelemetrySnapshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for buffered telemetry snapshots.
 */
@Dao
interface TelemetrySnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: TelemetrySnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<TelemetrySnapshotEntity>): List<Long>

    @Query("SELECT * FROM telemetry_snapshots ORDER BY id DESC LIMIT :limit")
    fun getRecentSnapshots(limit: Int = 100): Flow<List<TelemetrySnapshotEntity>>

    @Query("SELECT COUNT(*) FROM telemetry_snapshots")
    suspend fun count(): Long

    @Query("DELETE FROM telemetry_snapshots WHERE id NOT IN (SELECT id FROM telemetry_snapshots ORDER BY id DESC LIMIT :keepCount)")
    suspend fun deleteOldest(keepCount: Int): Int

    @Query("DELETE FROM telemetry_snapshots")
    suspend fun clearAll(): Int
}
