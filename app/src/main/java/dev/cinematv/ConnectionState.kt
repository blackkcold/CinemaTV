package dev.cinematv

import dev.cinematv.data.ServerStatus

/**
 * One connection truth for every surface.
 *
 * `isConnected` used to mean only "an address is stored", which let the home hero ask for a
 * connection that already worked while the settings panel said the opposite. Each state below
 * owns exactly one user-visible sentence and one next action, so TV, phone and any future
 * surface cannot disagree.
 */
sealed interface ConnectionState {
    data object Unconfigured : ConnectionState
    data object Connecting : ConnectionState
    data object Authenticating : ConnectionState
    data class AuthRequired(val expired: Boolean) : ConnectionState
    data object AuthRejected : ConnectionState
    /** Reachable, no password configured, so protected routes answer 503 by design. */
    data object PasswordNotConfigured : ConnectionState
    data class Unreachable(val failure: ConnectionFailure, val message: String) : ConnectionState
    data class Incompatible(val message: String) : ConnectionState
    data class Ready(val vodSources: Int, val liveSources: Int) : ConnectionState
    data class ReadyNoSources(val verified: Boolean) : ConnectionState
}

/** True when the session answered and content requests may run. */
val ConnectionState.isUsable: Boolean
    get() = this is ConnectionState.Ready || this is ConnectionState.ReadyNoSources

internal fun connectionStateFor(server: String, status: ServerStatus): ConnectionState = when {
    server.isBlank() -> ConnectionState.Unconfigured
    status.verified && (status.sources.isNotEmpty() || status.liveSources.isNotEmpty()) ->
        ConnectionState.Ready(status.sources.size, status.liveSources.size)
    status.verified -> ConnectionState.ReadyNoSources(verified = true)
    status.passwordRequired -> ConnectionState.AuthRequired(expired = false)
    else -> ConnectionState.PasswordNotConfigured
}

internal fun connectionStateForFailure(server: String, error: Throwable): ConnectionState {
    if (server.isBlank()) return ConnectionState.Unconfigured
    val failure = ConnectionErrors.classify(error)
    val message = ConnectionErrors.message(error)
    return when (failure) {
        // A lost session must keep the query and already-received results; it is a login
        // prompt, not a content error and never an automatic retry.
        ConnectionFailure.AUTHENTICATION -> ConnectionState.AuthRequired(expired = true)
        ConnectionFailure.PROTOCOL -> ConnectionState.Incompatible(message)
        else -> ConnectionState.Unreachable(failure, message)
    }
}

/** What a surface should offer next for [state]. */
enum class ConnectionAction { EDIT_CONNECTION, WAIT, SEARCH, EDIT_SOURCES }

object ConnectionCopy {
    fun summary(state: ConnectionState): String = when (state) {
        ConnectionState.Unconfigured -> "尚未配置影视服务"
        ConnectionState.Connecting -> "正在连接影视服务…"
        ConnectionState.Authenticating -> "正在验证密码…"
        is ConnectionState.AuthRequired -> if (state.expired) "会话已失效，请重新登录" else "已连接，需要登录"
        ConnectionState.AuthRejected -> "密码不正确，请重新输入"
        ConnectionState.PasswordNotConfigured -> "服务器未配置访问密码（PASSWORD），请联系管理员"
        is ConnectionState.Unreachable -> state.message
        is ConnectionState.Incompatible -> state.message
        is ConnectionState.Ready -> "已连接 · 点播源 ${state.vodSources} 个 · 直播订阅 ${state.liveSources} 个"
        is ConnectionState.ReadyNoSources -> "已连接 · 没有启用的片源，请在片源设置中添加"
    }

    fun action(state: ConnectionState): ConnectionAction = when (state) {
        ConnectionState.Unconfigured,
        is ConnectionState.AuthRequired,
        ConnectionState.AuthRejected,
        ConnectionState.PasswordNotConfigured,
        is ConnectionState.Unreachable,
        is ConnectionState.Incompatible -> ConnectionAction.EDIT_CONNECTION
        ConnectionState.Connecting, ConnectionState.Authenticating -> ConnectionAction.WAIT
        is ConnectionState.Ready -> ConnectionAction.SEARCH
        is ConnectionState.ReadyNoSources -> ConnectionAction.EDIT_SOURCES
    }
}
