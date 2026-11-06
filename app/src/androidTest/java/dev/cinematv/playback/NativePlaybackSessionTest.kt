package dev.cinematv.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import dev.cinematv.NotoTestActivity
import dev.cinematv.UiFixtureServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3/controller/audio-focus and Activity lifecycle; offline synthetic video only. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativePlaybackSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun mediaControlsRespectUserPauseSystemFocusAndForegroundOwnership() {
        val fixture = UiFixtureServer(instrumentation.context)
        val scenario = ActivityScenario.launch(NotoTestActivity::class.java)
        lateinit var playback: NativePlaybackSession
        lateinit var controllerFuture: ListenableFuture<MediaController>
        var controller: MediaController? = null
        var audio: AudioManager? = null
        var focus: AudioFocusRequest? = null
        try {
            scenario.onActivity { activity ->
                playback = NativePlaybackSession(activity, "offline-playback-test", "${fixture.base}/stream.mp4",
                    "Offline playback fixture", 0L, cacheVod = false)
                // A short synthetic fixture must not end while exercising system transitions.
                playback.player.repeatMode = Player.REPEAT_MODE_ONE
                controllerFuture = MediaController.Builder(activity, playback.token).buildAsync()
                audio = activity.getSystemService(AudioManager::class.java)
                activity.setContent {
                    DisposableEffect(playback) { onDispose { playback.close() } }
                    ObservePlaybackLifecycle(playback)
                    AndroidView(factory = { PlayerView(it).apply { player = playback.player; useController = false } },
                        onRelease = { it.player = null })
                }
            }
            val remote = controllerFuture.get(10, TimeUnit.SECONDS)
            controller = remote
            await("foreground playback") { playback.player.isPlaying && playback.player.currentPosition > 150 }
            main {
                assertEquals(C.USAGE_MEDIA, playback.player.audioAttributes.usage)
                assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, playback.player.audioAttributes.contentType)
                assertEquals("Offline playback fixture", remote.mediaMetadata.title.toString())
                assertTrue(remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                assertFalse(remote.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                assertFalse(remote.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                remote.pause()
            }
            await("controller pause") { !playback.player.playWhenReady }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            main { assertFalse("User pause survives background/foreground", playback.player.playWhenReady) }

            main { remote.play() }
            await("controller play") { playback.player.isPlaying }
            scenario.moveToState(Lifecycle.State.CREATED)
            main { assertFalse("No background playback", playback.player.playWhenReady) }
            // A second pause must cancel the pending resume even though the engine is already paused.
            main { remote.pause(); remote.seekTo(1_000L) }
            await("ordered off-screen controller pause") { playback.player.currentPosition == 1_000L }
            scenario.moveToState(Lifecycle.State.RESUMED)
            main { assertFalse("Controller pause while stopped cancels resume", playback.player.playWhenReady) }

            main { remote.play() }
            await("play before off-screen command") { playback.player.isPlaying }
            scenario.moveToState(Lifecycle.State.CREATED)
            main { remote.play(); remote.seekTo(2_000L) }
            await("ordered off-screen controller play") { playback.player.currentPosition == 2_000L }
            main { assertFalse("A headset cannot start hidden video", playback.player.playWhenReady) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            main { assertFalse("Rejected off-screen play must not be queued", playback.player.playWhenReady) }

            main { remote.play() }
            await("play before audio-focus interruption") { playback.player.isPlaying }
            main {
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
                    .build()
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio!!.requestAudioFocus(focus!!))
            }
            await("ExoPlayer audio-focus suppression") {
                !playback.player.isPlaying && playback.player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
            main {
                assertTrue("Transient focus keeps the user's play intent", playback.player.playWhenReady)
                assertTrue(playback.handleMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
                playback.handleMediaKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                assertFalse("Toggle pauses even while output is suppressed", playback.player.playWhenReady)
                audio!!.abandonAudioFocusRequest(focus!!)
                focus = null
            }
            await("focus regained after user pause") {
                playback.player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
            main { assertFalse(playback.player.playWhenReady) }

            main {
                playback.handleMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                playback.handleMediaKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                assertTrue("One physical press toggles once", playback.player.playWhenReady)
                playback.handleMediaKey(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1))
                assertTrue("A held media button cannot toggle repeatedly", playback.player.playWhenReady)
            }
            await("playing before output disconnection") { playback.player.isPlaying }
            scenario.moveToState(Lifecycle.State.CREATED)
            // The OS protects AUDIO_BECOMING_NOISY from app broadcasts. Invoke the same receiver
            // handler to exercise its Media3/resume side effects without claiming a hardware unplug.
            main { playback.onAudioOutputDisconnected() }
            scenario.moveToState(Lifecycle.State.RESUMED)
            main { assertFalse("Output loss while stopped cancels foreground resume", playback.player.playWhenReady) }
            scenario.close()
            await("system session released with screen") { !remote.isConnected }
        } finally {
            main {
                focus?.let { audio?.abandonAudioFocusRequest(it) }
                controller?.release()
            }
            scenario.close()
            fixture.close()
        }
    }

    private fun main(block: () -> Unit) { instrumentation.runOnMainSync { block() } }

    private fun await(label: String, timeoutMs: Long = 15_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            main { done = predicate() }
            if (done) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out: $label")
    }
}
