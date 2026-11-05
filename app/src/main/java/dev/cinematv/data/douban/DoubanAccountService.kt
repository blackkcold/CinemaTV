package dev.cinematv.data.douban

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Shared across the native login and account screen; never sends credentials to a BFF/LAN browser. */
class DoubanAccountService internal constructor(private val vault: DoubanStorage,
    private val transport: DoubanTransport = OfficialDoubanTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val access: DoubanFeatureAccess = DoubanFeatureAccess.DENIED) {
    private val gate = DoubanRequestGate(now)
    private val syncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lock = Mutex()
    private var cookie = ""
    private var savedAt = 0L
    private var initialized = false
    private val mutable = MutableStateFlow(DoubanAccountState())
    val state: StateFlow<DoubanAccountState> = mutable
    private var ticket = -1L // Accessed only while holding the operation mutex.
    private var accountState: DoubanAccountState
        get() = mutable.value
        set(value) { access.guard(ticket) { mutable.value = value } }
    init { access.onRevoked { mutable.value = DoubanAccountState() } }
    private suspend fun <T> authorized(block: suspend () -> T): T {
        val captured = access.capture()
        return coroutineScope {
            val job = currentCoroutineContext()[Job]!!
            val registration = access.onRevoked { job.cancel(DoubanFeatureDisabledException()) }
            try {
                access.check(captured)
                lock.withLock {
                    ticket = captured
                    access.check(ticket)
                    try { block() } finally {
                        if (runCatching { access.check(captured) }.isFailure) {
                            cookie = ""; savedAt = 0; initialized = false
                            cursors.clear(); staged.clear(); done.clear(); totals.clear()
                        }
                    }
                }
            } finally { registration.close() }
        }
    }
    private val cursors = mutableMapOf<DoubanShelf, Int>()
    private val staged = linkedMapOf<String, DoubanEntry>()
    private val done = mutableSetOf<DoubanShelf>()
    private val totals = mutableMapOf<DoubanShelf, Int>()
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!access.isEnabled) return@withContext
        authorized {
        if (initialized) return@authorized
        initialized = true
        try {
            val stored = access.guard(ticket) { vault.read() } ?: return@authorized
            access.guard(ticket) {
                cookie = stored.optString("cookie"); savedAt = stored.optLong("savedAt")
                gate.restore(stored.optLong("notBefore"), stored.optString("stop").takeIf { it.isNotBlank() }?.let(DoubanPhase::valueOf))
                val owner = DoubanIdentity(stored.getString("owner"), stored.getString("name"))
                val entries = stored.optJSONArray("entries")?.let { list -> (0 until list.length()).map { i -> decode(list.getJSONObject(i)) } }.orEmpty()
                val completed = stored.optJSONArray("completed")?.let { list -> (0 until list.length()).map { DoubanShelf.valueOf(list.getString(it)) }.toSet() }.orEmpty()
                val snapshot = stored.optLong("syncedAt").takeIf { it > 0 }?.let { DoubanSnapshot(owner, entries, it, stored.optBoolean("complete"), completed) }
                val expired = cookie.isBlank() || (savedAt <= 0 || savedAt > now() || now() - savedAt > 7 * 24 * 60 * 60_000L)
                accountState = DoubanAccountState(if(expired) DoubanPhase.NEEDS_LOGIN else gate.stopped ?: DoubanPhase.CONNECTED, owner, snapshot, if(expired) "请重新扫码验证本机登录" else "已保存本机登录 · 仅手动同步")
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { access.check(ticket); cookie = ""; accountState = DoubanAccountState(DoubanPhase.ERROR, message = "本机会话无法解密，请断开后重新登录；未向外发送数据") }
    } }
    /** Called only by explicit on-device confirmation after the user finishes official login. */
    suspend fun acceptLocalSession(localCookie: String) = withContext(Dispatchers.IO) { authorized {
        val previousState = accountState
        val previousCookie = cookie
        val previousSavedAt = savedAt
        val previousStop = gate.stopped
        accountState = accountState.copy(phase = DoubanPhase.VERIFYING, message = "正在验证本机登录")
        try {
            require(localCookie.isNotBlank()) { "Missing local login" }
            // A new explicit login may verify once; do not clear an existing denial until success.
            val owner = DoubanHtmlParser.identity(request("/mine/", localCookie) {
                val wait = gate.notBefore - now()
                if (wait > 0) pause(wait)
                currentCoroutineContext().ensureActive(); access.check(ticket); gate.attempted()
                if(cookie.isNotBlank()) persist()
            })
            currentCoroutineContext().ensureActive(); access.check(ticket)
            access.guard(ticket) {
                gate.userReauthenticated(); cookie = localCookie; savedAt = now(); cursors.clear(); staged.clear(); done.clear(); totals.clear()
                val old = accountState.snapshot?.takeIf { it.owner.id == owner.id }
                val candidate = DoubanAccountState(DoubanPhase.CONNECTED, owner, old, "登录成功 · 会话已加密保存在这台设备")
                persist(candidate); accountState = candidate
            }
        } catch (e: CancellationException) { accountState = previousState.copy(message = "验证已取消，原连接状态保留"); throw e } catch (e: Exception) {
            access.guard(ticket) {
                cookie = previousCookie; savedAt = previousSavedAt; gate.restore(gate.notBefore, previousStop)
                accountState = previousState; failed(e)
            }
        }
    } }
    suspend fun sync(continuePartial: Boolean = false) = withContext(Dispatchers.IO) {
        if (!syncRunning.compareAndSet(false, true)) return@withContext
        try { authorized {
        val owner = accountState.owner ?: return@authorized
        if (cookie.isBlank() || (savedAt <= 0 || savedAt > now() || now() - savedAt > 7 * 24 * 60 * 60_000L)) { accountState = accountState.copy(phase = DoubanPhase.NEEDS_LOGIN, message = "请重新扫码"); return@authorized }
        if (!continuePartial || staged.isEmpty()) { cursors.clear(); staged.clear(); done.clear(); totals.clear() }
        accountState = accountState.copy(phase = DoubanPhase.SYNCING, message = "正在按 4 秒以上间隔读取个人片单")
        var pages = 0
        val previousScores = accountState.snapshot?.entries.orEmpty().filter { it.communityScoreCheckedAt > 0 && now() - it.communityScoreCheckedAt in 0..86_400_000L }.associateBy { it.subjectId }
        try {
            for (shelf in DoubanShelf.entries.filter { it !in done }) {
                var start = cursors[shelf] ?: 0
                while (pages < 20) {
                    val page = DoubanHtmlParser.collection(request("/people/${owner.id}/${shelf.path}?start=$start&sort=time&rating=all&filter=all&mode=grid", cookie) { awaitGate(); persist() }, owner.id, shelf, start)
                    currentCoroutineContext().ensureActive(); access.check(ticket); pages++
                    val total = requireNotNull(page.total)
                    require(totals[shelf] == null || totals[shelf] == total) { "Collection changed during pagination" }
                    totals[shelf] = total
                    page.entries.forEach { entry ->
                        val key = "${entry.shelf}:${entry.subjectId}"
                        require(key !in staged) { "Repeated subject across pages" }
                        val score = previousScores[entry.subjectId]
                        staged[key] = entry.copy(communityScore = score?.communityScore, communityScoreCheckedAt = score?.communityScoreCheckedAt ?: 0)
                    }
                    accountState = accountState.copy(message = "${shelf.label} · 已读 ${staged.size} 条 · 本轮 $pages 页")
                    if(page.nextStart == null) { require(staged.values.count { it.shelf == shelf } == total); done += shelf; cursors.remove(shelf); break }
                    start = page.nextStart; cursors[shelf] = start
                }
                if(pages >= 20) break
            }
            val complete = done.size == DoubanShelf.entries.size
            val snapshot = DoubanSnapshot(owner, staged.values.toList(), now(), complete, done.toSet())
            val candidate = accountState.copy(phase = DoubanPhase.CONNECTED, snapshot = snapshot, message = if(complete) "同步完成 · ${staged.size} 条个人记录" else "本轮已读取 $pages 页，点击继续同步；尚未覆盖全部片单")
            access.guard(ticket) { persist(candidate); accountState = candidate }
        } catch(e: CancellationException) { accountState = accountState.copy(phase = DoubanPhase.CONNECTED, message = "同步已取消，保留上次数据"); throw e }
        catch(e: Exception) { failed(e) }
    } } finally { syncRunning.set(false) }
    }
    /** Explicit, bounded score lookup for the user's own imported subjects, never a global catalog crawl. */
    suspend fun syncScores() = withContext(Dispatchers.IO) {
        if (!syncRunning.compareAndSet(false, true)) return@withContext
        try { authorized {
            val snapshot = accountState.snapshot ?: return@authorized
            if(cookie.isBlank() || savedAt <= 0 || savedAt > now() || now() - savedAt > 7 * 24 * 60 * 60_000L) {
                accountState = accountState.copy(phase = DoubanPhase.NEEDS_LOGIN, message = "请重新扫码"); return@authorized
            }
            val subjects = snapshot.entries.filter { it.communityScoreCheckedAt == 0L || now() - it.communityScoreCheckedAt > 86_400_000L }.map { it.subjectId }.distinct().take(10)
            accountState = accountState.copy(phase = DoubanPhase.SYNCING, message = "按间隔读取最多 10 部作品的豆瓣社区评分")
            val scores = mutableMapOf<String, Double?>()
            try {
                subjects.forEach { id ->
                    val html = request("/subject/$id/", cookie) { awaitGate(); persist() }
                    currentCoroutineContext().ensureActive(); access.check(ticket)
                    scores[id] = DoubanHtmlParser.communityScore(html, id)
                    accountState = accountState.copy(message = "已检查 ${scores.size} / ${subjects.size} 部作品的社区评分")
                }
                val next = snapshot.copy(entries = snapshot.entries.map { item -> if(item.subjectId in scores) item.copy(communityScore = scores[item.subjectId], communityScoreCheckedAt = now()) else item })
                val candidate = accountState.copy(phase = DoubanPhase.CONNECTED, snapshot = next, message = "已检查 ${scores.size} 部作品的社区评分；无评分仍保持未知")
                access.guard(ticket) { persist(candidate); accountState = candidate }
                next.entries.forEach { entry -> val key = "${entry.shelf}:${entry.subjectId}"; if(key in staged) staged[key] = entry }
            } catch(e: CancellationException) { accountState = accountState.copy(phase = DoubanPhase.CONNECTED, message = "评分读取已取消，保留上次数据"); throw e }
            catch(e: Exception) { failed(e) }
        } } finally { syncRunning.set(false) }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) { lock.withLock {
        cookie = ""; savedAt = 0; cursors.clear(); staged.clear(); done.clear(); totals.clear(); vault.clear(); mutable.value = DoubanAccountState(); initialized = false
    } }
    private suspend fun request(path: String, cookie: String, beforeRequest: suspend () -> Unit): String =
        transport.get(path, cookie, access, ticket, beforeRequest)
    private suspend fun awaitGate() {
        gate.stopped?.let { gate.requireAllowed() }
        val wait = gate.notBefore - now()
        if(wait > 0) pause(wait)
        currentCoroutineContext().ensureActive(); access.check(ticket); gate.requireAllowed(); gate.attempted()
    }
    private fun failed(error: Exception) = access.guard(ticket) {
        val access = error as? DoubanAccessException
        val phase = access?.phase ?: DoubanPhase.ERROR
        if(phase in listOf(DoubanPhase.CHALLENGE, DoubanPhase.NEEDS_LOGIN, DoubanPhase.RATE_LIMITED)) gate.stop(phase, access?.retryAt ?: 0)
        accountState = accountState.copy(phase = phase, message = access?.message ?: "页面无法安全解析，保留上次数据；不会自动重试", retryAt = access?.retryAt ?: 0)
        if(cookie.isNotBlank()) runCatching { persist() }
    }
    private fun persist(candidate: DoubanAccountState = accountState) = access.guard(ticket) {
        val owner = candidate.owner ?: return@guard
        val snapshot = candidate.snapshot
        vault.write(JSONObject().put("cookie", cookie).put("savedAt", savedAt).put("owner", owner.id).put("name", owner.displayName)
            .put("notBefore", gate.notBefore).put("stop", gate.stopped?.name.orEmpty())
            .put("syncedAt", snapshot?.syncedAt ?: 0).put("complete", snapshot?.complete ?: false)
            .put("completed", JSONArray(snapshot?.completedShelves?.map { it.name }.orEmpty()))
            .put("entries", JSONArray(snapshot?.entries.orEmpty().map(::encode))))
    }
    private fun encode(it: DoubanEntry) = JSONObject().put("id", it.subjectId).put("title", it.title).put("shelf", it.shelf.name).put("stars", it.personalStars).put("date", it.markedDate).put("score", it.communityScore).put("scoreAt", it.communityScoreCheckedAt)
    private fun decode(j: JSONObject) = DoubanEntry(j.getString("id"), j.getString("title"), DoubanShelf.valueOf(j.getString("shelf")), j.optInt("stars").takeIf { it in 1..5 }, j.optString("date"), if(j.isNull("score")) null else j.getDouble("score"), j.optLong("scoreAt"))
}
