package dev.cinematv.ui

import androidx.compose.runtime.Composable
import dev.cinematv.data.MediaItem
import dev.cinematv.ui.tv.components.BackdropLayer

/** Shared still-image renderer; each device owns its own layout. */
@Composable
fun CinemaBackdrop(item: MediaItem?) = BackdropLayer(item)
