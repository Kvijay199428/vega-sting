package com.vega.sting.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test for the v3 → v4 `recordings` migration.
 *
 * This builds a real SQLite database with the v3 schema, runs
 * [AppDatabase.MIGRATION_3_4] on it exactly as Room would, and then asserts the
 * resulting shape. It deliberately does not depend on exported schema JSON
 * (which does not exist for the pre-upgrade versions), so it works from a plain
 * `connectedAndroidTest` run.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "vega-sting-migration-test.db"
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private lateinit var context: Context

    private val newColumns = listOf(
        "sessionId",
        "partIndex",
        "segmentStatus",
        "startedAt",
        "endedAt",
        "finalizationReason",
        "isSessionComplete"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate3To4_addsColumnsAndKeepsLegacyRows() {
        createVersion3Database()

        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        error("onCreate must not run during an upgrade test")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion == 3 && newVersion == 4) {
                            AppDatabase.MIGRATION_3_4.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db = helper.writableDatabase
        try {
            // Every new column is present.
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(recordings)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            }
            assertTrue("missing columns: ${newColumns - columns}", columns.containsAll(newColumns))

            // Legacy rows survive with safe defaults; the active row keeps its
            // data and the trashed row keeps its deletedAt.
            db.query(
                "SELECT name, sessionId, partIndex, segmentStatus, isSessionComplete, deleted, deletedAt " +
                    "FROM recordings ORDER BY id"
            ).use { cursor ->
                assertEquals(2, cursor.count)

                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-1.mp4", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals("FINALIZED", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(0, cursor.getInt(5))
                assertNull(cursor.getString(6))

                assertTrue(cursor.moveToNext())
                assertEquals("deleted.mp4", cursor.getString(0))
                assertEquals(1, cursor.getInt(5))
                assertEquals(950L, cursor.getLong(6))
            }

            // The unique index allows many legacy NULL-sessionId rows...
            db.execSQL(insertSql(name = "null-a", sessionId = null))
            db.execSQL(insertSql(name = "null-b", sessionId = null))

            // ...but rejects a duplicate (sessionId, partIndex) pair.
            db.execSQL(insertSql(name = "dup-1", sessionId = "session-1"))
            var rejected = false
            try {
                db.execSQL(insertSql(name = "dup-2", sessionId = "session-1"))
            } catch (_: SQLiteConstraintException) {
                rejected = true
            }
            assertTrue("unique (sessionId, partIndex) was not enforced", rejected)
        } finally {
            helper.close()
        }
    }

    private fun insertSql(name: String, sessionId: String?): String {
        val sessionValue = sessionId?.let { "'$it'" } ?: "NULL"
        return "INSERT INTO recordings " +
            "(name, timestamp, path, type, codec, size, deleted, sessionId, partIndex) VALUES " +
            "('$name', '1', '/storage/$name.mp4', 'VIDEO', 'H264', '0', '0', $sessionValue, '1')"
    }

    private fun createVersion3Database() {
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_V3_RECORDINGS)
                        db.execSQL(CREATE_V3_TELEMETRY)
                        db.execSQL(
                            "INSERT INTO recordings " +
                                "(name, timestamp, path, type, codec, size, deleted) VALUES " +
                                "('legacy-1.mp4', '1000', '/storage/legacy-1.mp4', 'VIDEO', 'H264', '1234', '0')"
                        )
                        db.execSQL(
                            "INSERT INTO recordings " +
                                "(name, timestamp, path, type, codec, size, deleted, deletedAt) VALUES " +
                                "('deleted.mp4', '900', '/storage/deleted.mp4', 'AUDIO', 'AAC', '50', '1', '950')"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private companion object {
        val CREATE_V3_RECORDINGS = """
            CREATE TABLE IF NOT EXISTS recordings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                path TEXT NOT NULL,
                originalPath TEXT,
                type TEXT NOT NULL,
                codec TEXT NOT NULL,
                size INTEGER NOT NULL,
                deleted INTEGER NOT NULL,
                deletedAt INTEGER,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        val CREATE_V3_TELEMETRY = """
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
        """.trimIndent()
    }
}
