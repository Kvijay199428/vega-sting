package com.vega.sting.recording

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Identity of one continuous recording session. Every part produced by
 * automatic splitting shares the same [sessionId] and [stem].
 */
data class RecordingSession(
    val sessionId: String,
    val stem: String,
    val isAudio: Boolean,
    val startPartIndex: Int = 1
)

/**
 * Deterministic naming for segmented recordings.
 *
 * Files keep the project's existing timestamp stem and append the part number,
 * e.g. `2026-10-11_16-10-27_part-1.mp4`. Numbering starts at 1, never
 * overwrites an existing file, and the session id is independent of the file
 * name so a session can be recognised in the database even if the directory
 * listing changes.
 */
object RecordingSessionManager {

    private const val STEM_PATTERN = "yyyy-MM-dd_HH-MM-SS"

    /** Creates a session whose stem does not collide with existing files. */
    fun createSession(
        directory: File,
        isAudio: Boolean,
        date: Date = Date()
    ): RecordingSession {
        val base = SimpleDateFormat(STEM_PATTERN, Locale.US).format(date)
        var stem = base
        var suffix = 1
        while (usesStem(directory, stem)) {
            suffix++
            stem = "${base}_$suffix"
        }
        return RecordingSession(
            sessionId = UUID.randomUUID().toString(),
            stem = stem,
            isAudio = isAudio,
            startPartIndex = 1
        )
    }

    fun partExtension(isAudio: Boolean): String = if (isAudio) ".m4a" else ".mp4"

    fun partFileName(stem: String, partIndex: Int, isAudio: Boolean): String =
        "${stem}_part-$partIndex${partExtension(isAudio)}"

    fun partFile(directory: File, session: RecordingSession, partIndex: Int): File =
        File(directory, partFileName(session.stem, partIndex, session.isAudio))

    /** Parses the part number out of a file name, or null if it does not match. */
    fun partIndexOf(fileName: String, stem: String, isAudio: Boolean): Int? {
        val prefix = "${stem}_part-"
        val ext = partExtension(isAudio)
        if (!fileName.startsWith(prefix) || !fileName.endsWith(ext)) return null
        val number = fileName.removePrefix(prefix).removeSuffix(ext)
        return number.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Highest existing part index for a stem, or 0 when none exist. */
    fun highestPartIndex(directory: File, stem: String, isAudio: Boolean): Int {
        val files = directory.listFiles() ?: return 0
        return files.mapNotNull { partIndexOf(it.name, stem, isAudio) }.maxOrNull() ?: 0
    }

    /** Next free part index for a stem (recovery for interrupted sessions). */
    fun nextPartIndex(directory: File, stem: String, isAudio: Boolean): Int =
        highestPartIndex(directory, stem, isAudio) + 1

    private fun usesStem(directory: File, stem: String): Boolean {
        val files = directory.listFiles() ?: return false
        return files.any { it.name.startsWith("${stem}_part-") }
    }
}
