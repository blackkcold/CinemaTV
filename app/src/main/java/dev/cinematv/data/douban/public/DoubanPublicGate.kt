package dev.cinematv.data.douban.public

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * One process-wide, persisted gate for anonymous Douban reads.
 *
 * The deadline survives restarts on purpose: a provider Retry-After is not reset by
 * reopening the app. Every state a user can be stuck in is also readable, so the UI
 * can show a countdown instead of an unexplained pause.
 */
class DoubanPublicGate(
    private val store: DoubanPublicStore? = null,
    private val random: () -> Int = { kotlin.random.Random.nextInt(0, 901) },
    now: () -> Long = System::currentTimeMillis
) {
    private val clock = now
    var notBefore: Long = 0L
        private set
    var stop: DoubanPublicStop? = null
        private set
    private var windowStart: Long = 0L
    private var windowCount: Int = 0
    private var strikes: Int = 0

    init { store?.read()?.let(::restore) }

    fun state(at: Long = clock()): State = State(
        notBefore = notBefore,
        stop = stop,
        strikes = strikes,
        budgetLeft = budgetLeft(at),
        pausedUntil = notBefore.takeIf { it > at }
    )

    data class State(
        val notBefore: Long,
        val stop: DoubanPublicStop?,
        val strikes: Int,
        val budgetLeft: Int,
        val pausedUntil: Long?
    )

    fun budgetLeft(at: Long = clock()): Int {
        rollWindow(at)
        return (BUDGET - windowCount).coerceAtLeast(0)
    }

    /** Throws instead of sleeping when the wait would be long enough to look frozen. */
    fun requireReady(at: Long = clock()) {
        stop?.let { throw DoubanPublicException(it, stopMessage(it), notBefore) }
        rollWindow(at)
        if (windowCount >= BUDGET) {
            val until = windowStart + WINDOW_MS
            notBefore = maxOf(notBefore, until)
            throw DoubanPublicException(DoubanPublicStop.THROTTLED, "本轮访问次数已用完", until)
        }
        if (at < notBefore) {
            val wait = notBefore - at
            if (wait > MAX_SILENT_WAIT_MS) {
                throw DoubanPublicException(DoubanPublicStop.THROTTLED, "豆瓣要求等待后再读取", notBefore)
            }
        }
    }

    suspend fun awaitReady(pause: suspend (Long) -> Unit = { delay(it) }) {
        while (true) {
            val at = clock()
            stop?.let { throw DoubanPublicException(it, stopMessage(it), notBefore) }
            rollWindow(at)
            if (windowCount >= BUDGET) {
                val until = windowStart + WINDOW_MS
                notBefore = maxOf(notBefore, until)
                throw DoubanPublicException(DoubanPublicStop.THROTTLED, "本轮访问次数已用完", until)
            }
            val wait = notBefore - at
            if (wait <= 0L) return
            if (wait > MAX_SILENT_WAIT_MS) {
                throw DoubanPublicException(DoubanPublicStop.THROTTLED, "豆瓣要求等待后再读取", notBefore)
            }
            pause(wait)
        }
    }

    fun attempted(at: Long = clock()) {
        rollWindow(at)
        windowCount++
        notBefore = maxOf(notBefore, at + GAP_MS + random())
        persist()
    }

    fun onSuccess() {
        strikes = 0
        persist()
    }

    fun onThrottled(retryAt: Long, at: Long = clock()) {
        strikes = (strikes + 1).coerceAtMost(MAX_STRIKES)
        val backoff = backoffFor(strikes)
        // A provider Retry-After is authoritative; the local backoff applies only without one.
        notBefore = if (retryAt > at) maxOf(notBefore, retryAt) else maxOf(notBefore, at + backoff)
        persist()
    }

    fun onChallenge(at: Long = clock()) {
        stop = DoubanPublicStop.CHALLENGE
        notBefore = maxOf(notBefore, at + backoffFor((strikes + 1).coerceAtMost(MAX_STRIKES)))
        persist()
    }

    fun onServerError(at: Long = clock()) {
        notBefore = maxOf(notBefore, at + SERVER_COOLDOWN_MS)
        persist()
    }

    /** Explicit user action only: disconnect, or clearing the public cache. */
    fun reset() {
        notBefore = 0L
        stop = null
        windowStart = 0L
        windowCount = 0
        strikes = 0
        persist()
    }

    private fun rollWindow(at: Long) {
        if (windowStart == 0L || at - windowStart >= WINDOW_MS || at < windowStart) {
            windowStart = at
            windowCount = 0
        }
    }

    private fun backoffFor(hits: Int): Long {
        var value = BASE_BACKOFF_MS
        repeat((hits - 1).coerceAtLeast(0)) {
            value = if (value > MAX_BACKOFF_MS / 2) MAX_BACKOFF_MS else value * 2
        }
        return value.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun stopMessage(stop: DoubanPublicStop): String = when (stop) {
        DoubanPublicStop.CHALLENGE -> "豆瓣要求安全验证，请稍后再试"
        DoubanPublicStop.THROTTLED -> "豆瓣暂时限制了访问，稍后自动继续"
        else -> "豆瓣读取已暂停"
    }

    private fun persist() {
        val value = JSONObject()
            .put("notBefore", notBefore)
            .put("stop", stop?.name.orEmpty())
            .put("windowStart", windowStart)
            .put("windowCount", windowCount)
            .put("struck", strikes)
        runCatching { store?.write(value.toString()) }
    }

    private fun restore(raw: String) {
        runCatching {
            val value = JSONObject(raw)
            notBefore = value.optLong("notBefore").coerceAtLeast(0)
            stop = value.optString("stop").takeIf { it.isNotBlank() }?.let(DoubanPublicStop::valueOf)
            windowStart = value.optLong("windowStart").coerceAtLeast(0)
            windowCount = value.optInt("windowCount").coerceIn(0, BUDGET)
            strikes = value.optInt("struck").coerceIn(0, MAX_STRIKES)
            // A clock that moved backwards must not strand the gate for days.
            val at = clock()
            if (notBefore > at + MAX_BACKOFF_MS + WINDOW_MS) notBefore = at + MAX_BACKOFF_MS
        }.onFailure {
            notBefore = 0L; stop = null; windowStart = 0L; windowCount = 0; strikes = 0
        }
    }

    companion object {
        const val GAP_MS = 3_000L
        const val WINDOW_MS = 10 * 60_000L
        const val BUDGET = 20
        const val BASE_BACKOFF_MS = 10 * 60_000L
        const val MAX_BACKOFF_MS = 2 * 60 * 60_000L
        const val SERVER_COOLDOWN_MS = 60_000L
        const val MAX_STRIKES = 4
        /** Longer waits surface as a countdown instead of an in-place pause. */
        const val MAX_SILENT_WAIT_MS = 5_000L
    }
}

/** Non-secret gate state; never contains cookies or account identifiers. */
interface DoubanPublicStore {
    fun read(): String?
    fun write(value: String)
}
