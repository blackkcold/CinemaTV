package dev.cinematv.ui.tv.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.CinemaViewModel
import dev.cinematv.ConnectionCopy
import dev.cinematv.ConnectionAction
import dev.cinematv.Page
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.metadataLine
import dev.cinematv.ui.tv.design.*
import dev.cinematv.ui.tv.focus.*

@Composable
fun CinemaHero(item: MediaItem?, model: CinemaViewModel, eyebrow: String = "", compact: Boolean = false, belowFocus: FocusRequester? = null, reserveNavigation: Boolean = false) {
    val layout = LocalTvLayout.current
    val entry = LocalContentFocus.current
    val navigation = LocalNavigationFocus.current
    val target = rememberFocusTarget(model, "hero", "primary")
    // The hero used to offer "连接服务器" whenever there was no featured item, even when the
    // server was connected. The next action now follows the single connection state.
    val state = model.connectionState
    val action = ConnectionCopy.action(state)
    val heroLabel = when {
        item != null -> "观看详情"
        action == ConnectionAction.SEARCH -> "开始搜索"
        action == ConnectionAction.EDIT_SOURCES -> "添加片源"
        action == ConnectionAction.WAIT -> "正在连接…"
        else -> "连接影视服务"
    }
    val height = (if (compact) layout.height * .46f else layout.heroHeight) - if (reserveNavigation) layout.contentTop else 0.dp
    Box(Modifier.fillMaxWidth().height(height.coerceAtLeast(228.dp))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = layout.gutter, bottom = 18.dp)
            .fillMaxWidth(if (compact) .78f else .65f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (eyebrow.isNotBlank()) Text(eyebrow, color = TvColors.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
            Text(item?.title ?: "每个好故事\n都值得被看见", color = TvColors.Text,
                fontSize = if (compact) 34.sp else 42.sp, lineHeight = if (compact) 40.sp else 48.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item?.metadataLine().orEmpty().ifBlank { ConnectionCopy.summary(state) },
                color = TvColors.Text.copy(alpha = .75f), fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1)
            if (!compact) Text(item?.description?.takeIf(String::isNotBlank) ?: "让故事占据大屏，让观影回归简单。",
                color = TvColors.Text.copy(alpha = .78f), fontSize = 15.sp, lineHeight = 22.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.85f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                GlassAction(heroLabel,
                    Modifier.focusRequester(entry).focusRequester(target).focusProperties { up = navigation; down = belowFocus ?: FocusRequester.Default }
                        .width(148.dp).height(45.dp), prominent = true,
                    onFocus = { model.focusCoordinator.remember(model.focusRoute, "hero", "primary") }) {
                    when {
                        item != null -> model.open(item)
                        action == ConnectionAction.SEARCH -> model.navigate(Page.SEARCH)
                        else -> model.navigate(Page.SETTINGS)
                    }
                }
                GlassAction("搜索影片", Modifier.focusProperties { down = belowFocus ?: FocusRequester.Default }.width(128.dp).height(45.dp)) { model.navigate(Page.SEARCH) }
            }
        }
    }
}
