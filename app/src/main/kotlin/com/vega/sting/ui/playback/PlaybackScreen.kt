package com.vega.sting.ui.playback

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingType
import com.vega.sting.ui.components.DetailHeaderBar
import com.vega.sting.ui.theme.*
import java.io.File

@Composable
fun PlaybackScreen(recording: Recording, onBack: () -> Unit) {
    val file = File(recording.path)
    val uri = Uri.fromFile(file)
    val isAudio = recording.type == RecordingType.AUDIO

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        DetailHeaderBar(
            title = recording.name,
            onBack = onBack,
            trailing = {
                Text(
                    text = "PLAYBACK",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentYellow
                )
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Panel),
            contentAlignment = Alignment.Center
        ) {
            if (!file.exists()) {
                Text(text = "FILE NOT FOUND", color = Error, style = MaterialTheme.typography.bodyLarge)
            } else if (isAudio) {
                AudioPlayer(uri = uri)
            } else {
                VideoPlayer(file = file, uri = uri)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun VideoPlayer(file: File, uri: Uri) {
    val videoAspectRatio = remember(file.absolutePath) {
        readVideoAspectRatio(file)
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val containerAspectRatio =
            if (maxHeight == 0.dp) videoAspectRatio else maxWidth / maxHeight
        val videoModifier = if (containerAspectRatio > videoAspectRatio) {
            Modifier
                .fillMaxHeight()
                .aspectRatio(videoAspectRatio)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(videoAspectRatio)
        }

        AndroidView(
            modifier = videoModifier,
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(uri)
                    val mediaController = android.widget.MediaController(context)
                    mediaController.setAnchorView(this)
                    setMediaController(mediaController)
                    start()
                }
            }
        )
    }
}

@Composable
fun AudioPlayer(uri: Uri) {
    // A simple UI for audio playback using MediaPlayer could be implemented here,
    // but for simplicity, we can also use VideoView as it supports audio.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(uri)
                val mediaController = android.widget.MediaController(context)
                mediaController.setAnchorView(this)
                setMediaController(mediaController)
                start()
            }
        }
    )
    Text(
        text = "AUDIO PLAYING",
        color = AccentOrange,
        style = MaterialTheme.typography.headlineSmall
    )
}

private fun readVideoAspectRatio(file: File): Float {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: 16
        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: 9
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        val displayWidth = if (rotation == 90 || rotation == 270) height else width
        val displayHeight = if (rotation == 90 || rotation == 270) width else height
        (displayWidth.toFloat() / displayHeight.toFloat()).coerceIn(0.25f, 4f)
    } catch (_: Exception) {
        16f / 9f
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}
