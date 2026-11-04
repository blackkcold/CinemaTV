package dev.cinematv.ui.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.cinematv.R

/** Approved TMDB brand asset. Preserve its colors, ratio and orientation. */
@Composable
fun TmdbLogo() {
    Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB",
        contentScale = ContentScale.Fit, modifier = Modifier.size(154.dp, 20.dp))
}
