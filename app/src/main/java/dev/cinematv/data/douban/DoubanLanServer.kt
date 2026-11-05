package dev.cinematv.data.douban

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/** Temporary LAN control plane. Never receives or returns passwords, cookies, account IDs or lists. */
class DoubanLanServer private constructor(private val ip: String, private val openLogin: () -> Unit) : NanoHTTPD(ip, 0) {
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val expires = System.currentTimeMillis() + 10 * 60_000
    val url: String get() = "http://$ip:$listeningPort/$token/"
    override fun serve(session: IHTTPSession): Response {
        if(System.currentTimeMillis() >= expires) return reply(Response.Status.GONE, "会话已过期，请在电视上重新打开。")
        val expectedHost = "$ip:$listeningPort"
        if(session.headers["host"] != expectedHost) return reply(Response.Status.FORBIDDEN, "Forbidden")
        val prefix = "/$token/"
        if(!session.uri.startsWith(prefix)) return reply(Response.Status.NOT_FOUND, "Not found")
        if(session.method == Method.GET && session.uri == prefix) return reply(Response.Status.OK, PAGE, "text/html; charset=utf-8")
        if(session.method == Method.POST && session.uri == "${prefix}login") {
            if(session.headers["origin"] != "http://$expectedHost" || !MessageDigest.isEqual(session.headers["x-cinematv-token"].orEmpty().toByteArray(), token.toByteArray())) return reply(Response.Status.FORBIDDEN, "Forbidden")
            if(session.headers["content-length"]?.toLongOrNull()?.let { it > 0 } == true) return reply(Response.Status.BAD_REQUEST, "No body allowed")
            openLogin()
            return reply(Response.Status.OK, "已请求电视打开官方登录，请在电视上确认。")
        }
        return reply(Response.Status.METHOD_NOT_ALLOWED, "Not available")
    }
    private fun reply(status: Response.Status, text: String, type: String = "text/plain; charset=utf-8") = newFixedLengthResponse(status, type, text).apply {
        addHeader("Cache-Control", "no-store"); addHeader("Referrer-Policy", "no-referrer"); addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
    }
    companion object {
        fun open(openLogin: () -> Unit): DoubanLanServer {
            val ip = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: error("请连接同一局域网后重试")
            return DoubanLanServer(ip, openLogin).apply { start(SOCKET_READ_TIMEOUT, true) }
        }
        private val PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>CinemaTV 本机设置</title><style>body{background:#11151d;color:#fff;font:17px system-ui;max-width:38em;margin:8vh auto;padding:24px}button{font:inherit;padding:14px 22px;border:0;border-radius:16px;background:#bbd4ff}p{line-height:1.7;color:#cad2df}</style><h1>CinemaTV · 豆瓣账号</h1><p>登录发生在电视上的豆瓣官方页面。手机仅遥控打开，不接收账号或 Cookie。打开后，请用豆瓣 App 扫电视上的官方登录二维码，再在电视确认本机保存。</p><button id="open">在电视上打开官方登录</button><p id="state">此设置入口 10 分钟后失效。请勿转发地址。</p><script>document.getElementById('open').onclick=async()=>{const b=document.getElementById('open');b.disabled=true;try{const r=await fetch('login',{method:'POST',headers:{'X-CinemaTV-Token':location.pathname.split('/')[1]}});document.getElementById('state').textContent=await r.text()}catch(e){document.getElementById('state').textContent='无法连接电视，请重新打开设置入口'}finally{b.disabled=false}}</script></html>"""
    }
}
