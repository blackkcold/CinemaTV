package dev.cinematv.ui.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.Ivory
import dev.cinematv.ui.tv.components.cinemaGlassMaterial
import dev.cinematv.ui.tv.design.LocalTvEffects

/** Only a supplied scene in this Compose owner enables live glass. Material and crisp
 * foreground are siblings, so failure repaint cannot cover the label. Material3 owns
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
    val reduced = LocalTvEffects.current.reducedMotion
    val focus by animateFloatAsState(if (focused && enabled) 1f else 0f,
        if (reduced) snap() else spring(.65f, 320f), label = "mobile-glass-focus")
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f,
        if (reduced) snap() else spring(.55f, 380f), label = "mobile-glass-press")
    val scale = if (reduced) 1f else 1f + .045f * focus - .035f * press
    Box(modifier, contentAlignment = Alignment.Center, propagateMinConstraints = true) {
        // Never scale the common ancestor: the recording coordinates must stay unchanged.
        Box(Modifier.matchParentSize().alpha(if (enabled) 1f else .45f)
            .cinemaGlassMaterial(radius = 26.dp, focus = focus, press = press, prominent = !secondary,
                layerBlock = { scaleX = scale; scaleY = scale }))
        Button(onClick = onClick, enabled = enabled,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            interactionSource = interactions, shape = RoundedCornerShape(26.dp),
            elevation = null, contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent, contentColor = Ivory,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Ivory.copy(alpha = .38f)
            )) {
            // Only the visual foreground scales. Material3's role/click target remains
            // an untransformed 48dp minimum even while a finger holds its outer edge.
            Row(Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content)
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
