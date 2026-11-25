package dev.cinematv.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID

/**
 * One native player and one system media session for one on-screen playback.
 * UI controls and external controllers share the same foreground-aware Player.
 * No background playback service or playback-resumption receiver is advertised.
 */
@OptIn(UnstableApi::class)
class NativePlaybackSession(
    context: Context,
    mediaId: String,
    uri: String,
    title: String,
    positionMs: Long,
    cacheVod: Boolean,
    autoPlay: Boolean = true
) : AutoCloseable {
    private val app = context.applicationContext
    private val engine = PlaybackFactory.create(app, cacheVod)
    private val foreground = ForegroundPlaybackPolicy(autoPlay)
    private var released = false
    private var noisyReceiverRegistered = false

    val player: Player = object : ForwardingSimpleBasePlayer(engine) {
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (released) return Futures.immediateVoidFuture()
            if (playWhenReady) {
                if (!foreground.requestPlay()) return Futures.immediateVoidFuture()
                if (engine.playbackState == Player.STATE_ENDED) engine.seekToDefaultPosition()
                if (engine.playbackState == Player.STATE_IDLE) engine.prepare()
            } else {
                foreground.cancelResume()
            }
            return super.handleSetPlayWhenReady(playWhenReady)
        }

        override fun handleStop(): ListenableFuture<*> {
            foreground.cancelResume()
            if (released) return Futures.immediateVoidFuture()
            engine.pause()
            return super.handleStop()
        }
    }

    private val mediaSession = try {
        MediaSession.Builder(app, player)
            // Compose may create the replacement before disposing its predecessor.
            .setId("cinematv-${UUID.randomUUID()}")
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(SessionCommands.EMPTY)
                        .setAvailablePlayerCommands(if (controller.isTrusted) CONTROLS else READ_ONLY)
                        .build()
                )
            })
            .build()
    } catch (error: Throwable) {
        player.release()
        throw error
    }

    val token: SessionToken get() = mediaSession.token

    private val interruptionListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                foreground.cancelResume()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) foreground.cancelResume()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) foreground.cancelResume()
        }

        override fun onPlayerError(error: PlaybackException) { foreground.cancelResume() }
    }

    // ExoPlayer handles unplugging while playing. Keep the pending foreground
    // resume cancelled as well if headphones are unplugged while already stopped.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onAudioOutputDisconnected()
        }
    }

    init {
        try {
            engine.addListener(interruptionListener)
            ContextCompat.registerReceiver(app, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyReceiverRegistered = true
            engine.setMediaItem(MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build())
            engine.seekTo(positionMs.coerceAtLeast(0L))
            engine.prepare()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    internal fun onAudioOutputDisconnected() {
        if (released) return
        foreground.cancelResume()
        engine.pause()
    }

    fun onStart() {
        if (!released && foreground.onStart()) engine.play()
    }

    fun onStop() {
        if (released) return
        foreground.onStop(
            playRequested = engine.playWhenReady,
            suppressed = engine.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            ended = engine.playbackState == Player.STATE_ENDED || engine.playerError != null
        )
        engine.pause()
    }

    /** Activity key dispatch and the platform media session must not toggle twice. */
    fun handleMediaKey(event: KeyEvent): Boolean {
        if (event.keyCode !in MEDIA_KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.isCanceled) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> player.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> player.togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) player.seekBack()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) player.seekForward()
        }
        return true
    }

    override fun close() {
        if (released) return
        released = true
        foreground.release()
        if (noisyReceiverRegistered) app.unregisterReceiver(noisyReceiver)
        engine.removeListener(interruptionListener)
        // The session must detach before its Player releases video/audio resources.
        mediaSession.release()
        player.release()
    }

    private companion object {
        val READ_ONLY: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_TRACKS
        ).build()
        val CONTROLS: Player.Commands = READ_ONLY.buildUpon().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS
        ).build()
        val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    }
}

/** Buffering or focus suppression must still allow an explicit pause. */
fun Player.togglePlayPause() {
    if (playWhenReady) pause() else play()
}
