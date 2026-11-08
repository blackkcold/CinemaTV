package dev.cinematv.ui.tv.pages

import androidx.compose.runtime.Composable
import dev.cinematv.ui.tv.components.TvConfirmDialog

/** Destructive library actions always open on Cancel and keep D-pad focus in the dialog. */
@Composable
internal fun LibraryCleanupConfirmationDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    TvConfirmDialog(
        title = title,
        message = description,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}
