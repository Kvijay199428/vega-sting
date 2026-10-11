package com.vega.sting.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Recording::class, CameraSessionTelemetry::class],
    version = 4,
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

        /**
         * Migration from v3 → v4: adds recording-session metadata to
         * `recordings` so split parts can be grouped, recovered and finalised
         * idempotently.
         *
         * Every new column has a NOT NULL default or is nullable, so existing
         * rows (including those already in Trash) are preserved untouched and
         * simply read as standalone recordings: `sessionId` stays NULL, which
         * SQLite treats as distinct under the unique index, so several legacy
         * rows can coexist.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN sessionId TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN partIndex INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE recordings ADD COLUMN segmentStatus TEXT NOT NULL DEFAULT 'FINALIZED'")
                db.execSQL("ALTER TABLE recordings ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN endedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recordings ADD COLUMN finalizationReason TEXT")
                db.execSQL("ALTER TABLE recordings ADD COLUMN isSessionComplete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recordings_sessionId ON recordings(sessionId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_recordings_sessionId_partIndex " +
                        "ON recordings(sessionId, partIndex)"
                )
            }
        }

        /** Every migration, exposed for instrumentation migration tests. */
        fun migrations(): Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vega_sting_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
