package dev.cinematv.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.focus.LocalNavigationFocus
import dev.cinematv.ui.tv.design.TvSpace

/**
 * A deliberately manual hero pager: no auto-advance, no surprise motion. One item has no
 * controls at all; several items get previous/next actions and quiet position dots.
 * The current page is composed directly so the production entry focus target is stable.
 */
@Composable
fun TvHero(
    itemCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    if (itemCount <= 0) return
    var page by rememberSaveable(itemCount) { mutableIntStateOf(0) }
    val navigation = LocalNavigationFocus.current
    Box(modifier.fillMaxWidth()) {
        content(page.coerceIn(0, itemCount - 1))
        if (itemCount > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = TvSpace.xxl, bottom = TvSpace.lg)
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvSpace.xs),
            ) {
                GlassAction("‹", Modifier.size(width = 36.dp, height = 36.dp)
                    .focusProperties { up = navigation }.testTag("hero-page-prev"),
                    enabled = page > 0) { page-- }
                repeat(itemCount) { index ->
                    val active = index == page
                    Box(Modifier.size(if (active) 7.dp else 5.dp)
                        .background(if (active) TvColors.Text else TvColors.Secondary.copy(alpha = .45f),
                            CircleShape)
                        .testTag("hero-page-dot-$index"))
                }
                GlassAction("›", Modifier.size(width = 36.dp, height = 36.dp)
                    .focusProperties { up = navigation }.testTag("hero-page-next"),
                    enabled = page < itemCount - 1) { page++ }
            }
        }
    }
}
