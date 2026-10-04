package com.vega.sting.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Recording::class, CameraSessionTelemetry::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun cameraSessionTelemetryDao(): CameraSessionTelemetryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from v1 → v2: adds camera_session_telemetry table.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS camera_session_telemetry (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        device TEXT NOT NULL DEFAULT '',
                        cameraId TEXT NOT NULL DEFAULT '',
                        resolution TEXT NOT NULL DEFAULT '',
                        fps INTEGER NOT NULL DEFAULT 0,
                        bitrate INTEGER NOT NULL DEFAULT 0,
                        codec TEXT NOT NULL DEFAULT '',
                        stabilization INTEGER NOT NULL DEFAULT 0,
                        extensionMode TEXT,
                        success INTEGER NOT NULL DEFAULT 1,
                        failureReason TEXT,
                        durationMs INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Migration from v2 → v3: adds trash-retention and real-resolution columns
         * to `recordings`.
         *
         * `deletedAt` is nullable because it is only meaningful for trashed rows;
         * existing rows are backfilled with NULL, which the retention query treats
         * as "not yet deleted". `width`/`height` are NOT NULL DEFAULT 0 so that
         * pre-existing rows and audio recordings have a defined unknown value
         * (0) rather than NULL, matching the Kotlin entity defaults.
         *
         * The declared column order does not matter to Room, which matches
         * columns by name, so a plain ALTER is sufficient here.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE recordings ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vega_sting_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
