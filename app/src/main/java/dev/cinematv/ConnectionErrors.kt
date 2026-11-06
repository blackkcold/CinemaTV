package dev.cinematv

import dev.cinematv.data.LibreTvHttpException
import dev.cinematv.data.LibreTvProtocolException

enum class ConnectionFailure { AUTHENTICATION, FORBIDDEN, NOT_FOUND, RATE_LIMITED, TIMEOUT, NETWORK, SERVER, PROTOCOL, UNKNOWN }

object ConnectionErrors {
    fun classify(error: Throwable): ConnectionFailure {
        val msg = error.message.orEmpty().lowercase()
        if (error is LibreTvHttpException) return when (error.status) {
            401 -> ConnectionFailure.AUTHENTICATION
            403 -> ConnectionFailure.FORBIDDEN
            404 -> ConnectionFailure.NOT_FOUND
            408, 504 -> ConnectionFailure.TIMEOUT
            429 -> ConnectionFailure.RATE_LIMITED
            in 500..599 -> ConnectionFailure.SERVER
            else -> ConnectionFailure.UNKNOWN
        }
        if (error is LibreTvProtocolException || error is org.json.JSONException) return ConnectionFailure.PROTOCOL
        return when {
            "http 401" in msg || "unauthorized" in msg -> ConnectionFailure.AUTHENTICATION
            "http 403" in msg -> ConnectionFailure.FORBIDDEN
            "http 404" in msg -> ConnectionFailure.NOT_FOUND
            "http 429" in msg -> ConnectionFailure.RATE_LIMITED
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
        ConnectionFailure.RATE_LIMITED -> "请求过于频繁（429），请稍后重试"
        ConnectionFailure.TIMEOUT -> "连接超时，请检查服务器及网络"
        ConnectionFailure.NETWORK -> "网络不可用或服务器无法访问"
        ConnectionFailure.SERVER -> "上游服务异常，请稍后重试"
        ConnectionFailure.PROTOCOL -> "服务器响应格式无效，请检查 LibreTV 版本与连接配置"
        ConnectionFailure.UNKNOWN -> error.message ?: "未知错误"
    }
}
