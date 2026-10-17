package com.vega.sting.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vega.sting.R
import com.vega.sting.ui.theme.AccentOrange
import com.vega.sting.ui.theme.Border
import com.vega.sting.ui.theme.Panel
import com.vega.sting.ui.theme.TextPrimary
import com.vega.sting.updater.UpdateChannel

/**
 * A persistent reminder that the *installed* build is a DEBUG (prerelease)
 * build. It is driven by [UpdateChannel.installed] — the channel baked into this
 * APK — not by the user's selected update subscription, so switching the
 * subscription to STABLE never hides the fact that a prerelease is running.
 *
 * Renders nothing for a STABLE build.
 */
@Composable
fun DebugBuildBanner(onManageChannel: () -> Unit) {
    val installedChannel = remember { UpdateChannel.installed() }
    if (installedChannel != UpdateChannel.DEBUG) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .border(0.5.dp, Border)
            .clickable(onClick = onManageChannel)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.update_debug_build_banner),
            style = MaterialTheme.typography.labelSmall,
            color = AccentOrange,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = stringResource(R.string.update_debug_build_banner_action),
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
}
