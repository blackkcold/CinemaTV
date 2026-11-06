package dev.cinematv.hybrid

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One live document, eight pending calls, explicit handshake, no stale replies after reload/disposal. */
class HybridBridgeDispatcher(
    private val gateway: CinemaPresentationGateway,
    private val scope: CoroutineScope,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
) : WebViewCompat.WebMessageListener, AutoCloseable {
    private var epoch = 0L
    private var closed = false
    private var gate = HybridRequestGate()
    private var proxy: JavaScriptReplyProxy? = null
    private val jobs = mutableSetOf<Job>()
    private data class BackRequest(val token: String, val route: String, val epoch: Long)
    private var backRequest: BackRequest? = null
    private var backTimer: Job? = null
    private var backSequence = 0L
    var ready = false
        private set

    fun documentStarted() {
        epoch++
        backTimer?.cancel(); backTimer = null; backRequest = null
        ready = false
        proxy = null
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        gate = HybridRequestGate()
    }

    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri,
        isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (closed || !HybridProtocol.trustedOrigin(sourceOrigin.toString(), isMainFrame) ||
            view.url != HybridPolicy.ENTRY_URL || message.type != WebMessageCompat.TYPE_STRING) return
        val raw = message.data ?: return
        val request = try { HybridProtocol.decode(raw) } catch (error: HybridRequestFailure) {
            // Never reflect unvalidated input into an error response or diagnostic.
            error.requestId?.let { reply(replyProxy, it, error = error.code) }
            return
        }
        if (!ready && request.method != "app.ready") { reply(replyProxy, request.id, error = "NOT_READY"); return }
        val document = epoch
        val admission = gate
        try { admission.enter(request.id) }
        catch (error: HybridRequestFailure) { reply(replyProxy, request.id, error = error.code); return }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (request.method == "navigation.back") acceptBack(request.params)
                val result = gateway.dispatch(request)
                if (closed || epoch != document) return@launch
                if (request.method == "app.ready") {
                    proxy = replyProxy
                    ready = true
                    onReady()
                }
                reply(replyProxy, request.id, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: HybridRequestFailure) {
                if (!closed && epoch == document) reply(replyProxy, request.id, error = failure.code)
            } catch (_: Exception) {
                if (!closed && epoch == document) reply(replyProxy, request.id, error = "OPERATION_FAILED")
            } finally { admission.leave(); jobs.remove(job) }
        }
        jobs.add(job)
        job.start()
    }

    /** The web records its exact latest focus before acknowledging. A stalled document cannot trap Back. */
    fun requestBack() {
        if (closed) return
        if (!ready || proxy == null) { gateway.back(); return }
        if (backRequest != null) return
        val pending = BackRequest("back_${epoch}_${++backSequence}", gateway.routeKey(), epoch)
        backRequest = pending
        proxy?.let { send(it, JSONObject().put("v", 1).put("event", "host.back")
            .put("payload", JSONObject().put("token", pending.token))) }
        backTimer = scope.launch {
            delay(350)
            if (!closed && backRequest == pending) {
                backRequest = null; backTimer = null
                if (epoch == pending.epoch && gateway.routeKey() == pending.route) gateway.back()
            }
        }
    }

    private fun acceptBack(params: JSONObject) {
        if (params.has("token")) {
            val token = HybridProtocol.string(params, "token", 96)
            val pending = backRequest
            if (pending == null || token != pending.token || pending.epoch != epoch || pending.route != gateway.routeKey())
                throw HybridRequestFailure("CONFLICT")
        }
        backTimer?.cancel(); backTimer = null; backRequest = null
    }

    private fun reply(target: JavaScriptReplyProxy, id: String, result: JSONObject? = null, error: String? = null) {
        val value = JSONObject().put("v", 1).put("id", id)
        if (error != null) value.put("error", JSONObject().put("code", error)) else value.put("result", result ?: JSONObject())
        send(target, value)
    }

    fun state(payload: JSONObject) {
        if (!ready || closed) return
        proxy?.let { send(it, JSONObject().put("v", 1).put("event", "state").put("payload", gateway.stamp(payload))) }
    }

    fun visibility(visible: Boolean) {
        if (!ready || closed) return
        proxy?.let { send(it, JSONObject().put("v", 1).put("event", "host.visibility")
            .put("payload", JSONObject().put("visible", visible))) }
    }

    private fun send(target: JavaScriptReplyProxy, value: JSONObject) {
        if (closed) return
        val raw = value.toString()
        if (raw.toByteArray(Charsets.UTF_8).size > HybridProtocol.MAX_REPLY_BYTES) {
            onFailure("界面数据超出显示上限，请使用兼容界面缩小范围。")
            return
        }
        runCatching { target.postMessage(raw) }
            .onFailure { if (!closed) onFailure("界面通信已中断，可以重新打开界面。") }
    }

    override fun close() {
        if (closed) return
        visibility(false)
        closed = true
        documentStarted()
    }
}
