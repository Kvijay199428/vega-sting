package com.vega.sting.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ShareManager {

    fun shareRecordings(context: Context, recordings: List<Recording>) {
        if (recordings.isEmpty()) {
            Toast.makeText(context, "No valid files selected", Toast.LENGTH_SHORT).show()
            return
        }

        val validRecordings = recordings.filter { File(it.path).exists() }
        
        if (validRecordings.isEmpty()) {
            Toast.makeText(context, "No valid files selected", Toast.LENGTH_SHORT).show()
            return
        }

        val totalSize = validRecordings.sumOf { File(it.path).length() }
        val threshold = 2L * 1024 * 1024 * 1024 // 2GB

        Toast.makeText(context, "Preparing share...", Toast.LENGTH_SHORT).show()

        if (validRecordings.size >= 20 || totalSize > threshold) {
            shareAsZip(context, validRecordings)
        } else if (validRecordings.size == 1) {
            shareSingleRecording(context, validRecordings.first())
        } else {
            shareMultipleRecordings(context, validRecordings)
        }
    }

    private fun shareAsZip(context: Context, recordings: List<Recording>) {
        Toast.makeText(context, "Zipping large selection...", Toast.LENGTH_LONG).show()
        val handler = Handler(Looper.getMainLooper())

        Thread {
            try {
                val zipFile = File(context.cacheDir, "SharedRecordings.zip")
                val out = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))

                recordings.forEach { recording ->
                    val file = File(recording.path)
                    if (file.exists()) {
                        val fi = FileInputStream(file)
                        val origin = BufferedInputStream(fi, 2048)
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)

                        val data = ByteArray(2048)
                        var count: Int
                        while (origin.read(data, 0, 2048).also { count = it } != -1) {
                            out.write(data, 0, count)
                        }
                        origin.close()
                    }
                }
                out.close()

                val uri = buildContentUri(context, zipFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                handler.post { launchChooser(context, shareIntent) }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post { Toast.makeText(context, "Failed to zip files", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun shareSingleRecording(context: Context, recording: Recording) {
        val file = File(recording.path)
        val uri = buildContentUri(context, file)
        val mimeType = getMimeType(recording.type)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, recording.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        launchChooser(context, shareIntent)
    }

    // Phase 3: Direct App Target
    fun shareToWhatsApp(context: Context, recording: Recording) {
        val file = File(recording.path)
        val uri = buildContentUri(context, file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(recording.type)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // Phase 3: Watermark rendering placeholder
    fun applyWatermarkAndShare(context: Context, recording: Recording) {
        Toast.makeText(context, "Watermark processing not yet implemented", Toast.LENGTH_SHORT).show()
        shareSingleRecording(context, recording)
    }

    // Phase 3: Compression placeholder
    fun compressAndShare(context: Context, recording: Recording) {
        Toast.makeText(context, "Compression not yet implemented", Toast.LENGTH_SHORT).show()
        shareSingleRecording(context, recording)
    }

    private fun shareMultipleRecordings(context: Context, recordings: List<Recording>) {
        val uris = ArrayList<Uri>()
        var isMixed = false
        var firstType: RecordingType? = null

        for (recording in recordings) {
            val file = File(recording.path)
            uris.add(buildContentUri(context, file))

            if (firstType == null) {
                firstType = recording.type
            } else if (firstType != recording.type) {
                isMixed = true
            }
        }

        val mimeType = if (isMixed) "*/*" else getMimeType(firstType!!)

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        launchChooser(context, shareIntent)
    }

    private fun buildContentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }

    private fun getMimeType(type: RecordingType): String {
        return when (type) {
            RecordingType.VIDEO -> "video/mp4"
            RecordingType.AUDIO -> "audio/mp4"
            else -> "*/*"
        }
    }

    private fun launchChooser(context: Context, shareIntent: Intent) {
        val chooserIntent = Intent.createChooser(shareIntent, "Share Recordings")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    }
}
