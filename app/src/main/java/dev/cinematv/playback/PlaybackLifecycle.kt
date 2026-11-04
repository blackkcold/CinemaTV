package dev.cinematv.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player

/** Pause off-screen without resuming a player that the user had paused. */
@Composable
fun ObservePlaybackLifecycle(player: Player) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { resumeOnStart = player.playWhenReady; player.pause() }
                Lifecycle.Event.ON_START -> if (resumeOnStart) { player.play(); resumeOnStart = false }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
