package dev.cinematv.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Pause off-screen without resuming a player that the user had paused. */
@Composable
fun ObservePlaybackLifecycle(playback: NativePlaybackSession) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, playback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playback.onStop()
                Lifecycle.Event.ON_START -> playback.onStart()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
