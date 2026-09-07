package com.sparkshield.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
abstract class SparkShieldDatabase : RoomDatabase() {

    abstract fun tamperEventDao(): TamperEventDao
    abstract fun telemetrySnapshotDao(): TelemetrySnapshotDao

    companion object {
        const val DATABASE_NAME = "sparkshield_edge.db"

        /**
         * Migration from version 1 to 2:
         * Adds performance indexes on timestamps and query-filtering columns
         * (class_name for tamper events, tamper_detected for telemetry snapshots).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tamper_events_timestamp_ms` ON `tamper_events` (`timestamp_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tamper_events_class_name` ON `tamper_events` (`class_name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_snapshots_timestamp_ms` ON `telemetry_snapshots` (`timestamp_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_snapshots_tamper_detected` ON `telemetry_snapshots` (`tamper_detected`)")
            }
        }

        @Volatile
        private var instance: SparkShieldDatabase? = null

        fun getInstance(context: Context): SparkShieldDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SparkShieldDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
            }
        }

        fun setInstanceForTesting(testDb: SparkShieldDatabase?) {
            instance = testDb
        }
    }
}
