package dev.cinematv.data.douban

/** Personal stars are deliberately separate from Douban's community score. */
enum class DoubanShelf(val path: String, val label: String) { WATCHED("collect", "看过"), WANT("wish", "想看"), WATCHING("do", "在看") }
data class DoubanEntry(val subjectId: String, val title: String, val shelf: DoubanShelf,
    val personalStars: Int? = null, val markedDate: String = "", val communityScore: Double? = null, val communityScoreCheckedAt: Long = 0) {
    init { require(subjectId.matches(Regex("[0-9]{1,16}"))); require(title.isNotBlank()); require(personalStars == null || personalStars in 1..5); require(communityScore == null || communityScore in 0.0..10.0) }
}
data class DoubanIdentity(val id: String, val displayName: String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))); require(displayName.isNotBlank()) }
}
data class DoubanPage(val entries: List<DoubanEntry>, val nextStart: Int?, val total: Int?)
data class DoubanSnapshot(val owner: DoubanIdentity, val entries: List<DoubanEntry>, val syncedAt: Long,
    val complete: Boolean, val completedShelves: Set<DoubanShelf>, val message: String = "")
enum class DoubanPhase { DISCONNECTED, AWAITING_USER, VERIFYING, CONNECTED, SYNCING, NEEDS_LOGIN, CHALLENGE, RATE_LIMITED, ERROR }
data class DoubanAccountState(val phase: DoubanPhase = DoubanPhase.DISCONNECTED, val owner: DoubanIdentity? = null,
    val snapshot: DoubanSnapshot? = null, val message: String = "尚未连接豆瓣", val retryAt: Long = 0)
class DoubanAccessException(val phase: DoubanPhase, override val message: String, val retryAt: Long = 0) : Exception(message)

/** The final URL after explicit same-origin redirects; identity evidence, never a cookie. */
data class DoubanFetch(val body: String, val finalUrl: String = "")

/** One shared process service owns this gate. A persisted deadline cannot be reset by refresh. */
class DoubanRequestGate(private val now: () -> Long = System::currentTimeMillis) {
    var notBefore: Long = 0; private set
    var stopped: DoubanPhase? = null; private set
    fun restore(deadline: Long, stop: DoubanPhase?) { notBefore = maxOf(notBefore, deadline); stopped = stop }
    fun requireAllowed() { stopped?.let { throw DoubanAccessException(it, "请先在豆瓣官方页面处理访问限制", notBefore) }; if (now() < notBefore) throw DoubanAccessException(DoubanPhase.RATE_LIMITED, "请等待后再同步", notBefore) }
    fun attempted() { notBefore = maxOf(notBefore, now() + 4_000) }
    fun stop(phase: DoubanPhase, deadline: Long = 0) { if (phase != DoubanPhase.RATE_LIMITED) stopped = phase; notBefore = maxOf(notBefore, deadline) }
    fun userReauthenticated() { stopped = null /* provider Retry-After deliberately survives */ }
    /** Explicit user action: disconnect. Provider-directed waits are otherwise never shortened. */
    fun reset() { notBefore = 0; stopped = null }
}

/** Never infer identity from title, and never silently replace a local mark. */
enum class DoubanImportDecision { ADD, ALREADY_MATCHED, CONFLICT }
object DoubanImportPolicy {
    fun decision(localStatus: String?, incoming: DoubanShelf): DoubanImportDecision {
        val target = when(incoming) { DoubanShelf.WATCHED -> "WATCHED"; DoubanShelf.WANT -> "WANT_TO_WATCH"; DoubanShelf.WATCHING -> "WATCHING" }
        return when(localStatus) { null -> DoubanImportDecision.ADD; target -> DoubanImportDecision.ALREADY_MATCHED; else -> DoubanImportDecision.CONFLICT }
    }
}

interface DoubanStorage { fun read(): org.json.JSONObject?; fun write(value: org.json.JSONObject); fun clear() }
