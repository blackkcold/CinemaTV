package dev.cinematv.playback

import kotlin.math.abs

/**
 * Coordinates the periodic and terminal progress saves so a paused or buffering player
 * does not keep rewriting the library, while a pause, seek, or route exit still persists
 * a meaningful final position.
 */
object PlaybackProgressWritePolicy {
    const val MIN_DELTA_MS = 1_000L

    /**
     * Callers filter `C.TIME_UNSET` before calling: 0 is a valid seek position and
     * must remain persistable.
     */
    fun shouldPersistWhilePlaying(positionMs: Long, lastPersistedMs: Long, isPlaying: Boolean): Boolean =
        isPlaying && abs(positionMs - lastPersistedMs) >= MIN_DELTA_MS

    fun shouldPersistFinal(positionMs: Long, lastPersistedMs: Long): Boolean =
        abs(positionMs - lastPersistedMs) >= MIN_DELTA_MS
}
