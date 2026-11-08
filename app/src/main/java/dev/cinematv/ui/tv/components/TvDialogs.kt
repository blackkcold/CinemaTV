package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.tv.design.TvText
import dev.cinematv.ui.tv.design.TvType

/**
 * A dialog never samples the page scene. Compose's dialog window is another owner,
 * and the contract keeps it Basic; one Back reaches [onDismissRequest] exactly once.
 */
@Composable
fun TvDialogFrame(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 600.dp,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CinemaGlassSurface(modifier.padding(TvSpace.xxl).widthIn(max = maxWidth).fillMaxWidth(),
            radius = 26.dp, level = dev.cinematv.ui.tv.design.GlassRenderMode.BASIC) {
            Column(Modifier.padding(TvSpace.xl), verticalArrangement = Arrangement.spacedBy(TvSpace.md),
                horizontalAlignment = Alignment.Start) {
                content()
            }
        }
    }
}

/** Confirmations default to Cancel, so a stray OK never performs the destructive action. */
@Composable
fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "取消",
    destructive: Boolean = false,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    TvDialogFrame(onDismissRequest = onDismiss, modifier = modifier) {
        TvText(title, style = TvType.h2, color = TvColors.Text)
        TvText(message, style = TvType.body, color = TvColors.Secondary)
        TvActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.sm, Alignment.End)) {
            GlassAction(cancelLabel, Modifier.focusRequester(cancelFocus), onClick = onDismiss)
            GlassAction(confirmLabel, Modifier, prominent = true, onClick = onConfirm)
        }
    }
}
