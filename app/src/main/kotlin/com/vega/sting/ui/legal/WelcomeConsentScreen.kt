package com.vega.sting.ui.legal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vega.sting.R
import com.vega.sting.legal.LegalDocument
import com.vega.sting.ui.components.TerminalButton
import com.vega.sting.ui.theme.*

/**
 * Mandatory first-run gate. Both checkboxes start unchecked and
 * "Accept & Continue" stays disabled until both are ticked, so consent is always
 * an affirmative, un-prechecked action. Exiting is always offered.
 */
@Composable
fun WelcomeConsentScreen(onAccept: () -> Unit) {
    val context = LocalContext.current
    var termsChecked by remember { mutableStateOf(false) }
    var privacyChecked by remember { mutableStateOf(false) }
    var openDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val canAccept = termsChecked && privacyChecked

    // Consent is mandatory: back must not be a way past the gate.
    BackHandler { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.legal_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.legal_welcome_body),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(20.dp))

        ConsentCheckRow(
            label = stringResource(R.string.legal_accept_terms),
            checked = termsChecked,
            onToggle = { termsChecked = it }
        )
        ConsentCheckRow(
            label = stringResource(R.string.legal_ack_privacy),
            checked = privacyChecked,
            onToggle = { privacyChecked = it }
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.legal_documents_label),
            style = MaterialTheme.typography.titleSmall,
            color = AccentYellow,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))

        LegalDocument.entries.forEach { document ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openDocument = document }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(document.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentOrange,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "[ READ ]",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentOrange,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        TerminalButton(
            text = stringResource(R.string.legal_accept_continue),
            onClick = onAccept,
            enabled = canAccept,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        TerminalButton(
            text = stringResource(R.string.legal_exit),
            onClick = { (context as? android.app.Activity)?.finish() },
            modifier = Modifier.fillMaxWidth(),
            isStopMode = true
        )
    }

    openDocument?.let { document ->
        LegalDocumentDialog(document = document, onDismiss = { openDocument = null })
    }
}

@Composable
private fun ConsentCheckRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(it) },
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = AccentOrange,
                uncheckedColor = TextSecondary,
                checkmarkColor = Background
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) TextPrimary else TextSecondary,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Full-text offline reader, used from both onboarding and Settings. */
@Composable
fun LegalDocumentDialog(
    document: LegalDocument,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val body = remember(document) { document.read(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(document.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = document.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, Border)
                        .padding(6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange)
            ) {
                Text(
                    text = stringResource(R.string.legal_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentOrange,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    )
}
