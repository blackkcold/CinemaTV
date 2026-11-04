package dev.cinematv.ui.mobile

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.Ivory
import dev.cinematv.ui.tv.components.GlassControlMaterial
import dev.cinematv.ui.tv.components.LocalGlassBackdrop
import dev.cinematv.ui.tv.design.GlassQuality
import dev.cinematv.ui.tv.design.LocalTvEffects

/** Phone V4 is intentionally static depth, not backdrop refraction. Material3 owns
 * the single click target, accessibility role, keyboard handling and touch ripple.
 */
@Composable
internal fun MobileGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    secondary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val effects = LocalTvEffects.current
    CompositionLocalProvider(LocalGlassBackdrop provides null,
        LocalTvEffects provides effects.copy(glass = GlassQuality.BASIC)) {
        Box(modifier.clip(RoundedCornerShape(26.dp)), propagateMinConstraints = true) {
            GlassControlMaterial(focused && enabled, pressed && enabled, false, !secondary,
                Modifier.matchParentSize().alpha(if (enabled) 1f else .45f))
            Button(onClick = onClick, enabled = enabled,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                interactionSource = interactions, shape = RoundedCornerShape(26.dp),
                elevation = null, contentPadding = contentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent, contentColor = Ivory,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Ivory.copy(alpha = .38f)
                ), content = content)
        }
    }
}

@Composable
internal fun MobileGlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) = MobileGlassButton(onClick, modifier, enabled, contentPadding, secondary = true, content = content)
