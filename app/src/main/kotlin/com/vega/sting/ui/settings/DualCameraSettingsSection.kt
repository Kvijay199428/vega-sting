package com.vega.sting.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vega.sting.camera.DualCameraCapabilityResult
import com.vega.sting.camera.DualCameraCapabilityStatus
import com.vega.sting.camera.DualCameraConfig
import com.vega.sting.camera.DualCameraLayout
import com.vega.sting.camera.DualCameraQuality
import com.vega.sting.camera.DualCameraScalingMode
import com.vega.sting.camera.DualCameraSplitRatio
import com.vega.sting.ui.theme.TextSecondary

private enum class DualDialog { LAYOUT, RATIO, QUALITY, FPS, SCALING }

/**
 * The DUAL CAMERA settings block. Every row shows the value read from the
 * persisted [DualCameraConfig] and writes changes straight back through the
 * repository, so the displayed state is always the saved state.
 */
@Composable
fun DualCameraSettingsSection(
    config: DualCameraConfig,
    capability: DualCameraCapabilityResult?,
    checkingCapability: Boolean,
    recordingActive: Boolean,
    onConfigChange: (DualCameraConfig) -> Unit,
    onReset: () -> Unit,
    onPreview: () -> Unit,
    onRefreshCapability: () -> Unit
) {
    var dialog by remember { mutableStateOf<DualDialog?>(null) }

    val capabilityLabel = when {
        checkingCapability -> "CHECKING…"
        capability == null -> "UNKNOWN"
        capability.isReady && capability.hasCameraPermission -> "SUPPORTED"
        capability.isReady -> "PERMISSION REQUIRED"
        capability.status == DualCameraCapabilityStatus.CONCURRENT_UNSUPPORTED -> "UNSUPPORTED"
        capability.status == DualCameraCapabilityStatus.MISSING_FACING_CAMERA -> "NO FRONT/BACK CAM"
        else -> "UNAVAILABLE"
    }

    // When the platform cannot run dual capture, nothing in this block may be
    // changed: not the detail rows, and not the MODE toggle itself.
    val capabilitySupported = capability?.isReady == true
    val rowsEnabled = capabilitySupported && !recordingActive
    val detailEnabled = config.enabled && rowsEnabled
    val qualityOptions = capability?.supportedQualities?.takeIf { it.isNotEmpty() }
        ?: DualCameraQuality.entries.toList()
    val fpsOptions = capability?.supportedFps?.takeIf { it.isNotEmpty() }
        ?: DualCameraConfig.FPS_OPTIONS

    SettingsSection(title = "DUAL CAMERA") {
        SettingsItem(
            label = "DUAL CAMERA MODE",
            value = if (config.enabled) "ON" else "OFF",
            onClick = if (rowsEnabled) {
                { onConfigChange(config.copy(enabled = !config.enabled)) }
            } else null
        )
        SettingsItem(
            label = "CAPABILITY",
            value = capabilityLabel,
            onClick = onRefreshCapability
        )
        if (!checkingCapability && capability != null && !capability.isReady && capability.reason != null) {
            Text(
                text = capability.reason,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )
        }

        SettingsItem(
            label = "LAYOUT",
            value = config.layout.label,
            onClick = if (detailEnabled) { { dialog = DualDialog.LAYOUT } } else null
        )
        SettingsItem(
            label = "PORTRAIT PLACEMENT",
            value = if (config.frontOnTop) "FRONT TOP" else "FRONT BOTTOM",
            onClick = if (detailEnabled) { { onConfigChange(config.copy(frontOnTop = !config.frontOnTop)) } } else null
        )
        SettingsItem(
            label = "LANDSCAPE PLACEMENT",
            value = if (config.frontOnLeft) "FRONT LEFT" else "FRONT RIGHT",
            onClick = if (detailEnabled) { { onConfigChange(config.copy(frontOnLeft = !config.frontOnLeft)) } } else null
        )
        SettingsItem(
            label = "SPLIT RATIO",
            value = config.splitRatio.label,
            onClick = if (detailEnabled) { { dialog = DualDialog.RATIO } } else null
        )
        SettingsItem(
            label = "QUALITY",
            value = config.quality.label,
            onClick = if (detailEnabled) { { dialog = DualDialog.QUALITY } } else null
        )
        SettingsItem(
            label = "FPS",
            value = config.fps.toString(),
            onClick = if (detailEnabled) { { dialog = DualDialog.FPS } } else null
        )
        SettingsItem(
            label = "MIRROR FRONT",
            value = if (config.mirrorFront) "ON" else "OFF",
            onClick = if (detailEnabled) { { onConfigChange(config.copy(mirrorFront = !config.mirrorFront)) } } else null
        )
        SettingsItem(
            label = "SCALING",
            value = config.scalingMode.label,
            onClick = if (detailEnabled) { { dialog = DualDialog.SCALING } } else null
        )
        SettingsItem(
            label = "PREVIEW STACKED",
            value = "SHOW",
            onClick = if (rowsEnabled) onPreview else null
        )
        SettingsItem(
            label = "RESET SETTINGS",
            value = "RESET",
            onClick = if (rowsEnabled) onReset else null
        )
    }

    when (dialog) {
        DualDialog.LAYOUT -> SelectionDialog(
            title = "LAYOUT",
            options = DualCameraLayout.entries.map { SelectionOption(it, it.label) },
            selected = config.layout,
            onDismiss = { dialog = null },
            onSelect = { onConfigChange(config.copy(layout = it)); dialog = null }
        )

        DualDialog.RATIO -> SelectionDialog(
            title = "SPLIT RATIO",
            options = DualCameraSplitRatio.entries.map { SelectionOption(it, it.label) },
            selected = config.splitRatio,
            onDismiss = { dialog = null },
            onSelect = { onConfigChange(config.copy(splitRatio = it)); dialog = null }
        )

        DualDialog.QUALITY -> SelectionDialog(
            title = "QUALITY",
            options = qualityOptions.map { SelectionOption(it, it.label) },
            selected = config.quality,
            onDismiss = { dialog = null },
            onSelect = { onConfigChange(config.copy(quality = it)); dialog = null }
        )

        DualDialog.FPS -> SelectionDialog(
            title = "FPS",
            options = fpsOptions.map { SelectionOption(it, it.toString()) },
            selected = config.fps,
            onDismiss = { dialog = null },
            onSelect = { onConfigChange(config.copy(fps = it)); dialog = null }
        )

        DualDialog.SCALING -> SelectionDialog(
            title = "SCALING",
            options = DualCameraScalingMode.entries.map { SelectionOption(it, it.label) },
            selected = config.scalingMode,
            onDismiss = { dialog = null },
            onSelect = { onConfigChange(config.copy(scalingMode = it)); dialog = null }
        )

        null -> Unit
    }
}
