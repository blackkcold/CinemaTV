package dev.cinematv.data

import kotlinx.coroutines.CancellationException

/** Request ownership is an immutable server/generation pair; it never exposes credentials. */
data class LibreTvConnection internal constructor(val server: String, val generation: Long)

/** Not a data class: its in-memory cookie must never appear in generated logging/toString output. */
internal class LibreTvRequestSession(val connection: LibreTvConnection, val revision: Long, val cookie: String?)

/** Keeps response effects and the current in-memory cookie in the same connection namespace. */
internal class LibreTvConnectionSession(initialServer: String) {
    private val lock = Any()
    private var current = LibreTvConnection(initialServer, 0L)
    private var cookie: String? = null
    private var revision = 0L

    fun context(): LibreTvConnection = synchronized(lock) { current }
    fun isCurrent(context: LibreTvConnection): Boolean = synchronized(lock) { current == context }

    fun replace(server: String, clearSession: Boolean = false, onReplace: () -> Unit = {}): LibreTvConnection = synchronized(lock) {
        val keepCookie = server == current.server && !clearSession
        current = LibreTvConnection(server, current.generation + 1)
        if (!keepCookie) cookie = null
        revision++
        onReplace()
        current
    }

    fun <T> withCurrent(context: LibreTvConnection, effect: () -> T): T = synchronized(lock) {
        if (context != current) throw CancellationException("LibreTV connection was replaced")
        effect()
    }

    fun check(context: LibreTvConnection) { withCurrent(context) { } }
    fun cookie(context: LibreTvConnection): String? = withCurrent(context) { cookie }
    fun snapshot(context: LibreTvConnection): LibreTvRequestSession = withCurrent(context) {
        LibreTvRequestSession(context, revision, cookie)
    }
    fun isCurrent(context: LibreTvConnection, responseRevision: Long): Boolean = synchronized(lock) {
        current == context && revision == responseRevision
    }
    fun <T> withCurrent(session: LibreTvRequestSession, effect: () -> T): T = withCurrent(session.connection) {
        if (session.revision != revision) throw CancellationException("LibreTV session was replaced")
        effect()
    }
    fun check(session: LibreTvRequestSession) { withCurrent(session) { } }

    fun receive(context: LibreTvConnection, status: Int, setCookie: String?): LibreTvRequestSession =
        receive(snapshot(context), status, setCookie)

    fun receive(session: LibreTvRequestSession, status: Int, setCookie: String?): LibreTvRequestSession = withCurrent(session) {
        // A late 401 must not sign out the replacement server, including A -> B -> A.
        val receivedCookie = if (status == 401) null
            else setCookie?.substringBefore(';')?.takeIf { it.contains('=') } ?: cookie
        if (receivedCookie != cookie) { cookie = receivedCookie; revision++ }
        LibreTvRequestSession(session.connection, revision, cookie)
    }
}
