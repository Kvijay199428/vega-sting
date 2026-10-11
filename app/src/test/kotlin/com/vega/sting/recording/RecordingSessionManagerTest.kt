package com.vega.sting.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

/**
 * Naming and recovery rules for split recordings. Uses real temporary
 * directories so the "never overwrite" and "next part index" behaviour is
 * exercised end to end.
 */
class RecordingSessionManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `part names keep the existing stem and append the part number`() {
        assertEquals(
            "2026-10-11_16-10-27_part-1.mp4",
            RecordingSessionManager.partFileName("2026-10-11_16-10-27", 1, isAudio = false)
        )
        assertEquals(
            "2026-10-11_16-10-27_part-7.m4a",
            RecordingSessionManager.partFileName("2026-10-11_16-10-27", 7, isAudio = true)
        )
    }

    @Test
    fun `part index round-trips through the file name`() {
        val name = RecordingSessionManager.partFileName("stem", 3, isAudio = false)
        assertEquals(3, RecordingSessionManager.partIndexOf(name, "stem", isAudio = false))
        assertNull(RecordingSessionManager.partIndexOf("stem.mp4", "stem", isAudio = false))
        assertNull(RecordingSessionManager.partIndexOf(name, "stem", isAudio = true))
    }

    @Test
    fun `a fresh session starts at part one`() {
        val dir = temp.newFolder()
        val session = RecordingSessionManager.createSession(dir, isAudio = false, date = Date(0))
        assertEquals(1, session.startPartIndex)
        assertTrue(session.stem.isNotBlank())
        assertTrue(session.sessionId.isNotBlank())
        assertTrue(session.stem.matches(Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}""")))
    }

    @Test
    fun `a colliding stem gains a suffix instead of overwriting`() {
        val dir = temp.newFolder()
        val date = Date(0)
        val first = RecordingSessionManager.createSession(dir, isAudio = false, date)
        // Simulate the first session having written a part.
        RecordingSessionManager.partFile(dir, first, 1).createNewFile()

        val second = RecordingSessionManager.createSession(dir, isAudio = false, date)

        assertNotEquals(first.stem, second.stem)
        assertTrue(second.stem.startsWith(first.stem))
    }

    @Test
    fun `recovery continues after the highest existing part`() {
        val dir = temp.newFolder()
        val session = RecordingSessionManager.createSession(dir, isAudio = false, date = Date(0))
        RecordingSessionManager.partFile(dir, session, 1).createNewFile()
        RecordingSessionManager.partFile(dir, session, 2).createNewFile()

        assertEquals(2, RecordingSessionManager.highestPartIndex(dir, session.stem, isAudio = false))
        assertEquals(3, RecordingSessionManager.nextPartIndex(dir, session.stem, isAudio = false))
    }

    @Test
    fun `audio parts do not count towards video part numbering`() {
        val dir = temp.newFolder()
        val session = RecordingSessionManager.createSession(dir, isAudio = false, date = Date(0))
        RecordingSessionManager.partFile(dir, session, 1).createNewFile()

        assertEquals(0, RecordingSessionManager.highestPartIndex(dir, session.stem, isAudio = true))
        assertEquals(1, RecordingSessionManager.nextPartIndex(dir, session.stem, isAudio = true))
    }
}
