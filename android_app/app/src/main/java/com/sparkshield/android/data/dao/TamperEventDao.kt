package com.sparkshield.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sparkshield.android.data.entity.TamperEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for confirmed tamper detection events.
 */
@Dao
interface TamperEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: TamperEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<TamperEventEntity>): List<Long>

    @Query("SELECT * FROM tamper_events ORDER BY id DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<TamperEventEntity>>

    @Query("SELECT * FROM tamper_events ORDER BY id DESC")
    suspend fun getAllEvents(): List<TamperEventEntity>

    @Query("SELECT COUNT(*) FROM tamper_events")
    suspend fun count(): Long

    @Query("DELETE FROM tamper_events WHERE id NOT IN (SELECT id FROM tamper_events ORDER BY id DESC LIMIT :keepCount)")
    suspend fun deleteOldest(keepCount: Int): Int

    @Query("DELETE FROM tamper_events")
    suspend fun clearAll(): Int
}
