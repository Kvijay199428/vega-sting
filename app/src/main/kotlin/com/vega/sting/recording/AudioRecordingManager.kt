package com.vega.sting.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.vega.sting.database.FinalizationReason
import java.io.File

/**
 * Records audio and, when splitting is enabled, rolls over into sequential
 * parts without an application-level pause.
 *
 * Rotation uses the platform's own [MediaRecorder.setMaxFileSize] /
 * [MediaRecorder.setNextOutputFile] mechanism where the device supports it, so
 * the container is finalised by the framework rather than by copying bytes.
 * When that mechanism is unavailable the recorder falls back to stopping and
 * reopening, and marks the session as [gapPossible].
 */
class AudioRecordingManager(private val context: Context) {

    interface Listener {
        fun onSegmentStarted(file: File, partIndex: Int)
        fun onSegmentFinalized(
            file: File,
            partIndex: Int,
            reason: FinalizationReason,
            gapPossible: Boolean
        )
        fun onRecorderError(message: String)
    }

    private companion object {
        const val TAG = "AudioRecordingManager"
        const val SAMPLE_RATE = 44100
        const val BIT_RATE = 128_000
    }

    private var recorder: MediaRecorder? = null
    private var directory: File? = null
    private var session: RecordingSession? = null
    private var segmentLimitBytes: Long = 0L
    private var splitEnabled: Boolean = false
    private var partIndex: Int = 0
    private var currentFile: File? = null
    private var listener: Listener? = null

    private var seamlessRotation = true
    private var rotationInProgress = false
    private var stopping = false

    var gapPossible: Boolean = false
        private set

    val activePartIndex: Int get() = partIndex
    val activeFile: File? get() = currentFile

    fun startAudioRecording(
        directory: File,
        session: RecordingSession,
        segmentLimitBytes: Long,
        splitEnabled: Boolean,
        listener: Listener
    ) {
        this.directory = directory
        this.session = session
        this.segmentLimitBytes = segmentLimitBytes
        this.splitEnabled = splitEnabled
        this.listener = listener
        this.partIndex = session.startPartIndex.coerceAtLeast(1)
        this.seamlessRotation = true
        this.rotationInProgress = false
        this.stopping = false
        this.gapPossible = false

        val first = RecordingSessionManager.partFile(directory, session, partIndex)
        currentFile = first
        openRecorder(first)
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun openRecorder(file: File) {
        val created = newRecorder()
        try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                if (splitEnabled && segmentLimitBytes > 0L) {
                    setMaxFileSize(segmentLimitBytes)
                    setOnInfoListener { _, what, _ -> handleInfo(what) }
                }
                prepare()
                if (splitEnabled && segmentLimitBytes > 0L) {
                    armNextOutputFile(this, partIndex + 1)
                }
                start()
            }
            recorder = created
            listener?.onSegmentStarted(file, partIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio part $partIndex", e)
            releaseQuietly(created)
            recorder = null
            // A part that never produced data must not look like a recording.
            if (file.length() == 0L) file.delete()
            listener?.onRecorderError(e.message ?: "Audio recorder failed to start")
        }
    }

    /** Arms the file the recorder will roll into once the size limit is hit. */
    private fun armNextOutputFile(target: MediaRecorder, nextIndex: Int) {
        val dir = directory ?: return
        val s = session ?: return
        val nextFile = RecordingSessionManager.partFile(dir, s, nextIndex)
        try {
            target.setNextOutputFile(nextFile)
        } catch (e: Exception) {
            Log.w(TAG, "setNextOutputFile unavailable; audio rotation falls back to restart", e)
            seamlessRotation = false
        }
    }

    private fun handleInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                if (!rotationInProgress) rolloverToNextPart()
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                // No next file was armed (unsupported device): restart manually.
                // A spurious event right after a seamless rollover lands on a
                // freshly opened, near-empty file and is ignored.
                val grown = segmentLimitBytes > 0L &&
                    (currentFile?.length() ?: 0L) >= segmentLimitBytes / 2
                if (!rotationInProgress && !stopping && grown) restartForNextPart()
            }
        }
    }

    /** Seamless path: the recorder already opened the next part by itself. */
    private fun rolloverToNextPart() {
        rotationInProgress = true
        try {
            val finalized = currentFile
            val finalizedIndex = partIndex
            if (finalized != null) {
                listener?.onSegmentFinalized(
                    finalized, finalizedIndex, FinalizationReason.SIZE_LIMIT, gapPossible
                )
            }
            partIndex = finalizedIndex + 1
            val dir = directory ?: return
            val s = session ?: return
            currentFile = RecordingSessionManager.partFile(dir, s, partIndex)
            listener?.onSegmentStarted(currentFile!!, partIndex)
            recorder?.let { if (seamlessRotation) armNextOutputFile(it, partIndex + 1) }
        } finally {
            rotationInProgress = false
        }
    }

    /** Fallback path: the old recorder stopped at the limit; reopen for the next part. */
    private fun restartForNextPart() {
        rotationInProgress = true
        try {
            val finalized = currentFile
            val finalizedIndex = partIndex
            releaseQuietly(recorder)
            recorder = null
            if (finalized != null) {
                listener?.onSegmentFinalized(
                    finalized, finalizedIndex, FinalizationReason.SIZE_LIMIT, gapPossible
                )
            }
            if (stopping) return
            partIndex = finalizedIndex + 1
            val dir = directory ?: return
            val s = session ?: return
            gapPossible = true
            currentFile = RecordingSessionManager.partFile(dir, s, partIndex)
            openRecorder(currentFile!!)
        } finally {
            rotationInProgress = false
        }
    }

    fun stopAudioRecording(reason: FinalizationReason = FinalizationReason.MANUAL_STOP) {
        if (stopping) return
        stopping = true
        val finalized = currentFile
        val finalizedIndex = partIndex
        releaseQuietly(recorder)
        recorder = null
        if (finalized != null) {
            if (finalized.length() > 0L) {
                listener?.onSegmentFinalized(finalized, finalizedIndex, reason, gapPossible)
            } else {
                // Nothing usable was written: keep the file identifiable but do
                // not present it as a finished recording.
                listener?.onSegmentFinalized(
                    finalized, finalizedIndex, FinalizationReason.ERROR, gapPossible
                )
            }
        }
        listener = null
        currentFile = null
    }

    private fun releaseQuietly(target: MediaRecorder?) {
        if (target == null) return
        try {
            target.stop()
        } catch (_: Exception) {
        }
        try {
            target.reset()
        } catch (_: Exception) {
        }
        try {
            target.release()
        } catch (_: Exception) {
        }
    }
}
