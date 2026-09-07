package com.sparkshield.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity storing periodic or trigger-based telemetry snapshots for
 * forensic analysis and grid baseline audit logging.
 */
@Entity(
    tableName = "telemetry_snapshots",
    indices = [
        Index(value = ["timestamp_ms"]),
        Index(value = ["tamper_detected"])
    ]
)
data class TelemetrySnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long,

    @ColumnInfo(name = "sequence_id")
    val sequenceId: Long,

    @ColumnInfo(name = "event_flags")
    val eventFlags: Int,

    @ColumnInfo(name = "peak_mv")
    val peakMv: Int,

    @ColumnInfo(name = "rise_time_ns")
    val riseTimeNs: Long,

    @ColumnInfo(name = "decay_time_us")
    val decayTimeUs: Int,

    @ColumnInfo(name = "optical_mv")
    val opticalMv: Int,

    @ColumnInfo(name = "classification")
    val classification: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "inference_time_us")
    val inferenceTimeUs: Long,

    @ColumnInfo(name = "tamper_detected")
    val tamperDetected: Boolean
)
