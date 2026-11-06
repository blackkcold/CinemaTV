package dev.cinematv.playback

/**
 * A screen-owned player may resume only the playback that this screen suspended.
 * A user pause, a system interruption or a command received off-screen cancels
 * that pending resume. Audio-focus changes themselves remain owned by ExoPlayer.
 */
internal class ForegroundPlaybackPolicy(autoPlay: Boolean = true) {
    private var foreground = false
    private var resumeOnStart = autoPlay
    private var released = false

    fun onStart(): Boolean {
        if (released || foreground) return false
        foreground = true
        val resume = resumeOnStart
        resumeOnStart = false
        return resume
    }

    fun onStop(playRequested: Boolean, suppressed: Boolean, ended: Boolean) {
        if (released || !foreground) return
        foreground = false
        resumeOnStart = playRequested && !suppressed && !ended
    }

    /** Never turn a headset/media-controller command into off-screen audio. */
    fun requestPlay(): Boolean {
        resumeOnStart = false
        return foreground && !released
    }

    fun cancelResume() { resumeOnStart = false }

    fun release() {
        released = true
        foreground = false
        resumeOnStart = false
    }
}
