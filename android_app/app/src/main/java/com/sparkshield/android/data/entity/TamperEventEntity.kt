package com.sparkshield.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an immutable historical audit record of a confirmed
 * tamper alert (EMP, OPTICAL, SURGE) meeting the >= 0.85 confidence threshold.
 */
@Entity(
    tableName = "tamper_events",
    indices = [
        Index(value = ["timestamp_ms"]),
        Index(value = ["class_name"])
    ]
)
data class TamperEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,

    @ColumnInfo(name = "sequence_id")
    val sequenceId: Long,

    @ColumnInfo(name = "class_name")
    val className: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "peak_mv")
    val peakMv: Int,

    @ColumnInfo(name = "rise_time_ns")
    val riseTimeNs: Long,

    @ColumnInfo(name = "decay_time_us")
    val decayTimeUs: Int,

    @ColumnInfo(name = "optical_mv")
    val opticalMv: Int,

    @ColumnInfo(name = "message")
    val message: String = ""
)
