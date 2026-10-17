package com.vega.sting.camera

import android.graphics.Color as AndroidColor
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A real, live dual-camera preview. The two camera streams are composited by
 * [DualCameraCompositor] into an [android.view.SurfaceView], using exactly the
 * same geometry as the recorded output, so what is shown here matches what will
 * be written to the file.
 *
 * The preview never fakes frames: when the device cannot capture both cameras
 * concurrently it reports that honestly instead of showing coloured panels.
 */
@Composable
fun DualCameraPreviewDialog(
    config: DualCameraConfig,
    orientationMode: String,
    portraitOutput: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember { DualCameraController(context) }
    var state by remember { mutableStateOf(DualCameraController.State.CHECKING) }
    var message by remember { mutableStateOf<String?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }

    val listener = remember {
        object : DualCameraController.Listener {
            override fun onState(newState: DualCameraController.State, msg: String?) {
                state = newState
                message = msg
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // Apply setting changes to the live compositor without reopening cameras.
    LaunchedEffect(config) {
        controller.updateConfig(config)
    }

    // Follow the recorder's orientation rule: under AUTO the preview must switch
    // between portrait stack and landscape split exactly as the recorded file
    // would, while an explicit layout choice keeps its own shape.
    val portraitFrame = config.layout.resolve(portraitOutput) == DualCameraLayout.PORTRAIT_STACK

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DUAL CAMERA PREVIEW", color = MaterialTheme.colorScheme.primary) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (portraitFrame) 9f / 16f else 16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    // Only keep a camera surface alive while it can show real
                    // frames: on UNSUPPORTED/ERROR the surface is torn down so the
                    // honest message below is visible instead of a black panel.
                    if (state != DualCameraController.State.UNSUPPORTED &&
                        state != DualCameraController.State.ERROR
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    // A default-z SurfaceView is placed *behind* its
                                    // window, and the (opaque) dialog surface hides
                                    // it — the preview would render but never be
                                    // seen. Raising it above the window makes the
                                    // composited frames visible.
                                    setZOrderOnTop(true)
                                    setBackgroundColor(AndroidColor.BLACK)
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            surfaceReady = true
                                            val w = holder.surfaceFrame.width().takeIf { it > 0 } ?: 720
                                            val h = holder.surfaceFrame.height().takeIf { it > 0 } ?: 1280
                                            controller.startPreview(config, orientationMode, holder.surface, w, h, listener)
                                        }

                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                            if (width > 0 && height > 0) {
                                                controller.updatePreviewSurface(holder.surface, width, height)
                                            }
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            surfaceReady = false
                                            controller.stop()
                                        }
                                    })
                                }
                            }
                        )
                    }

                    when (state) {
                        DualCameraController.State.CHECKING,
                        DualCameraController.State.STARTING -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = if (state == DualCameraController.State.CHECKING)
                                        "Checking camera capabilities…" else "Starting cameras…",
                                    color = Color.White,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        }

                        DualCameraController.State.UNSUPPORTED,
                        DualCameraController.State.ERROR -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = if (state == DualCameraController.State.UNSUPPORTED)
                                        "DUAL CAMERA NOT SUPPORTED" else "PREVIEW FAILED",
                                    color = Color(0xFFFF6B6B),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = message ?: "This device cannot capture both cameras at once.",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        DualCameraController.State.PREVIEWING -> {
                            // Real frames are rendered by the compositor underneath.
                            if (!surfaceReady) {
                                Text("Waiting for surface…", color = Color.White)
                            }
                        }

                        DualCameraController.State.IDLE -> Unit
                    }
                }
                Text(
                    text = "Layout: ${config.layout.label} · Ratio: ${config.splitRatio.label} · " +
                        "Scaling: ${config.scalingMode.label}" +
                        if (config.mirrorFront) " · Mirrored" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        }
    )
}
