package dev.cinematv

enum class ConnectionFailure { AUTHENTICATION, FORBIDDEN, NOT_FOUND, TIMEOUT, NETWORK, SERVER, UNKNOWN }

object ConnectionErrors {
    fun classify(error: Throwable): ConnectionFailure {
        val msg = error.message.orEmpty().lowercase()
        return when {
            "http 401" in msg || "unauthorized" in msg -> ConnectionFailure.AUTHENTICATION
            "http 403" in msg -> ConnectionFailure.FORBIDDEN
            "http 404" in msg -> ConnectionFailure.NOT_FOUND
            "timeout" in msg || "timed out" in msg -> ConnectionFailure.TIMEOUT
            "connection" in msg || "unknownhost" in msg || "unable to resolve" in msg ||
                "network" in msg -> ConnectionFailure.NETWORK
            "http 5" in msg -> ConnectionFailure.SERVER
            else -> ConnectionFailure.UNKNOWN
        }
    }

    fun message(error: Throwable): String = when (classify(error)) {
        ConnectionFailure.AUTHENTICATION -> "服务会话已失效，请重新登录"
        ConnectionFailure.FORBIDDEN -> "服务器拒绝访问（403），请检查权限与片源"
        ConnectionFailure.NOT_FOUND -> "资源不存在（404），可尝试更换来源"
        ConnectionFailure.TIMEOUT -> "连接超时，请检查服务器及网络"
        ConnectionFailure.NETWORK -> "网络不可用或服务器无法访问"
        ConnectionFailure.SERVER -> "上游服务异常，请稍后重试"
        ConnectionFailure.UNKNOWN -> error.message ?: "未知错误"
    }
}
