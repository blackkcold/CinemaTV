package dev.cinematv.ui.tv.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.ui.GlassAction
import dev.cinematv.ui.tv.components.CinemaGlassSurface
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.typography.CinemaTvText as Text

/** Destructive library actions always open on Cancel and keep D-pad focus in the dialog. */
@Composable
internal fun LibraryCleanupConfirmationDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val cancel = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false
    )) {
        CinemaGlassSurface(Modifier.width(480.dp), radius = 26.dp) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(title, color = TvColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = TvColors.Secondary, fontSize = 16.sp, lineHeight = 24.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassAction("取消", Modifier.weight(1f).height(44.dp).focusRequester(cancel).focusProperties {
                        left = FocusRequester.Cancel
                        right = confirm
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }, onClick = onDismiss)
                    GlassAction(confirmLabel, Modifier.weight(1f).height(44.dp).focusRequester(confirm).focusProperties {
                        left = cancel
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }, onClick = onConfirm)
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; cancel.requestFocus() }
    }
}
