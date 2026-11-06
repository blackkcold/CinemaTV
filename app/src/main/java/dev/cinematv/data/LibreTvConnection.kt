package dev.cinematv.data

import kotlinx.coroutines.CancellationException

/** Request ownership is an immutable server/generation pair; it never exposes credentials. */
data class LibreTvConnection internal constructor(val server: String, val generation: Long)

/** Keeps response effects and the current in-memory cookie in the same connection namespace. */
internal class LibreTvConnectionSession(initialServer: String) {
    private val lock = Any()
    private var current = LibreTvConnection(initialServer, 0L)
    private var cookie: String? = null

    fun context(): LibreTvConnection = synchronized(lock) { current }
    fun isCurrent(context: LibreTvConnection): Boolean = synchronized(lock) { current == context }

    fun replace(server: String, onReplace: () -> Unit = {}): LibreTvConnection = synchronized(lock) {
        val keepCookie = server == current.server
        current = LibreTvConnection(server, current.generation + 1)
        if (!keepCookie) cookie = null
        onReplace()
        current
    }

    fun <T> withCurrent(context: LibreTvConnection, effect: () -> T): T = synchronized(lock) {
        if (context != current) throw CancellationException("LibreTV connection was replaced")
        effect()
    }

    fun check(context: LibreTvConnection) { withCurrent(context) { } }
    fun cookie(context: LibreTvConnection): String? = withCurrent(context) { cookie }

    fun receive(context: LibreTvConnection, status: Int, setCookie: String?) = withCurrent(context) {
        // A late 401 must not sign out the replacement server, including A -> B -> A.
        if (status == 401) cookie = null
        else setCookie?.substringBefore(';')?.takeIf { it.contains('=') }?.let { cookie = it }
    }
}
