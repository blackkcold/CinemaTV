package dev.cinematv.ui.tv.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.typography.CinemaTvText

/** One conversion from the nine design levels to Compose text, so pages never re-declare sizes. */
fun TvTextStyle.toTextStyle(): TextStyle = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking,
)

/**
 * Design-system text. Wraps [CinemaTvText] rather than Material Text so the bundled
 * Noto/region font path, semantics and layout behaviour stay identical to existing UI.
 */
@Composable
fun TvText(
    text: String,
    style: TvTextStyle,
    modifier: Modifier = Modifier,
    color: Color = TvColors.Text,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    contentLanguageTag: String? = null,
) {
    CinemaTvText(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        contentLanguageTag = contentLanguageTag,
        style = style.toTextStyle(),
    )
}
