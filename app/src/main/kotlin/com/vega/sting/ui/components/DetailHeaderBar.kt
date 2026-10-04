package com.vega.sting.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.vega.sting.R
import com.vega.sting.ui.theme.AccentOrange
import com.vega.sting.ui.theme.TextPrimary

/**
 * Header for every screen below Home.
 *
 * Each secondary screen is a child of Home, so it carries a Back affordance in the
 * header and the system Back gesture returns to Home. Previously some screens
 * parked a BACK / CLOSE / DONE button in their footer instead, which separated the
 * navigation control from the title and, on Settings, competed with the real
 * settings actions for the same row.
 *
 * The Back control is a full 48dp target with an explicit content description
 * instead of a bare text glyph, so it is reachable by touch and announced by
 * TalkBack.
 */
@Composable
fun DetailHeaderBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(LocalContentColor.current.copy(alpha = 0f))
                .clickable(onClick = onBack)
                .clearAndSetSemantics { contentDescription = "Back to recordings" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                tint = AccentOrange,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )

        trailing?.invoke()
    }
}
