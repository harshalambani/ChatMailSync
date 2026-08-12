package com.chatmailsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared dialog furniture.
 *
 * These dialogs carry the app's only genuinely dangerous decisions, and they
 * were previously one long run-on string in a single Text(): the mailbox
 * folder was set off by four literal spaces (which do almost nothing in a
 * proportional font), and the numbered steps wrapped straight into each other
 * so "1. Open your mail app." read as part of the paragraph above it. The
 * pieces below give the parts of that message different visual weight, so the
 * thing the user has to go and act on is the thing that stands out.
 */

/**
 * A mailbox path, rendered as a literal value rather than as prose. Monospace
 * and boxed because it is a string the user has to go and match exactly in a
 * different app - it is data, not a sentence.
 */
@Composable
fun FolderChip(name: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            name,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * One instruction in a numbered list, with the number in its own fixed-width
 * column so wrapped lines hang under the text rather than under the digit.
 */
@Composable
fun NumberedStep(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small all-caps heading inside a dialog body. */
@Composable
fun DialogSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A body paragraph inside a dialog, at the colour M3 uses for dialog text. */
@Composable
fun DialogBody(text: String, emphasis: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        color = if (emphasis) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
fun DialogRule() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * An AlertDialog whose confirm button is drawn in the error colour, for
 * actions that cannot be undone. The default TextButton blue made "Reset and
 * re-archive" look as routine as "Cancel".
 */
@Composable
fun DestructiveAlertDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Cancel",
    body: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        // Material 3 already makes this slot scrollable when it outgrows the
        // screen, so a long body degrades to a scroll rather than pushing the
        // buttons off - but a dialog you have to scroll to find the buttons in
        // is a dialog people confirm without reading. 10.dp rather than the
        // usual 16.dp keeps the reset gate inside one screen on a small phone.
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = body)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}
