package com.sparkshield.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sparkshield.android.data.dao.TamperEventDao
import com.sparkshield.android.data.dao.TelemetrySnapshotDao
import com.sparkshield.android.data.entity.TamperEventEntity
import com.sparkshield.android.data.entity.TelemetrySnapshotEntity

/**
 * On-device Room database for SparkShield telemetry and tamper event auditing.
 */
@Database(
    entities = [
        TamperEventEntity::class,
        TelemetrySnapshotEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SparkShieldDatabase : RoomDatabase() {

    abstract fun tamperEventDao(): TamperEventDao
    abstract fun telemetrySnapshotDao(): TelemetrySnapshotDao

    companion object {
        const val DATABASE_NAME = "sparkshield_edge.db"

        @Volatile
        private var instance: SparkShieldDatabase? = null

        fun getInstance(context: Context): SparkShieldDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SparkShieldDatabase::class.java,
                    DATABASE_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
            }
        }

        fun setInstanceForTesting(testDb: SparkShieldDatabase?) {
            instance = testDb
        }
    }
}
