package llc.applabs.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import llc.applabs.design.tokens.ShapeDialog

/**
 * AppLabs confirmation dialog.
 *
 * Use for destructive actions, important decisions, and information
 * that requires explicit acknowledgement.
 */
@Composable
fun AppLabsConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    isDestructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            if (isDestructive) {
                AppLabsDestructiveButton(text = confirmLabel, onClick = onConfirm)
            } else {
                AppLabsPrimaryButton(text = confirmLabel, onClick = onConfirm)
            }
        },
        dismissButton = {
            AppLabsTextButton(text = dismissLabel, onClick = onDismiss)
        },
        shape = ShapeDialog,
        modifier = modifier,
    )
}

/**
 * AppLabs info dialog — single dismiss button, no confirm action.
 */
@Composable
fun AppLabsInfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "OK",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            AppLabsPrimaryButton(text = dismissLabel, onClick = onDismiss)
        },
        shape = ShapeDialog,
        modifier = modifier,
    )
}
