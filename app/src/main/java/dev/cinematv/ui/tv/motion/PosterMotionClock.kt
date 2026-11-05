package dev.cinematv.ui.tv.motion

/**
 * One monotonic time source owns key timestamps, freshness and expiry waits.
 * Production uses Android uptime; deterministic component tests supply their
 * Compose animation clock for both reads and waits, never mixing the two.
 */
internal interface PosterMotionClock {
    fun nowMillis(): Long
    suspend fun awaitHintExpiry(issuedAtMillis: Long)
}

/** Also safe for expired/backward/extreme inputs; never extends the 110 ms window. */
internal fun posterHintRemainingMillis(issuedAtMillis: Long, nowMillis: Long): Long =
    if (posterHintIsFresh(issuedAtMillis, nowMillis))
        PosterMotionTokens.DIRECTION_HINT_MILLIS - (nowMillis - issuedAtMillis)
    else 0L
