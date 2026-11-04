package dev.cinematv.ui.shared

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.cinematv.R

/** Approved A1 outlines, independent of UI typography. Static even when motion is enabled. */
@Composable
fun CinemaBrandLockup(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.cinema_brand_lockup),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
