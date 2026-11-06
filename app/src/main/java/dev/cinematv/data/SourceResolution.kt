package dev.cinematv.data

import java.io.IOException
import java.net.URI
import okhttp3.Call
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Outcomes belong to a search invocation, never global mutable repository state. */
data class SourceSearchOutcome(
    val sourceKey: String,
    val ok: Boolean,
    val error: String = "",
    val timedOut: Boolean = false,
    val ms: Long? = null
)
data class SearchSnapshot(
    val items: List<MediaItem> = emptyList(),
    val outcomes: List<SourceSearchOutcome> = emptyList(),
    val complete: Boolean = false
)
enum class ResolutionPhase { SEARCHING, TRYING_SOURCE, RESOLVED }
data class ResolutionProgress(
    val phase: ResolutionPhase,
    val queryIndex: Int = 0,
    val queryCount: Int = 0,
    val attempt: Int = 0,
    val maxAttempts: Int = 6,
    val sourceName: String = ""
)
class LibreTvHttpException(val status: Int, val serverMessage: String,
    val connection: LibreTvConnection? = null, val sessionRevision: Long? = null) :
    IOException("HTTP $status: ${serverMessage.ifBlank { "服务暂不可用" }}")
class LibreTvProtocolException(message: String) : IOException(message)
class SourceResolutionException(message: String) : IOException(message)

/** Only affirmative identity evidence permits trying a candidate. No fuzzy title/ID mapping. */
object SourceResolutionPolicy {
    const val MAX_QUERIES = 3
    const val MAX_DETAILS = 6
    const val TIMEOUT_MS = 75_000L
    fun queries(item: MediaItem): List<String> = (listOf(item.title) + item.aliases)
        .map(String::trim).filter { it.isNotBlank() && it.length <= 100 }
        .distinctBy(MediaIdentity::normalizedTitle).take(MAX_QUERIES)

    fun matches(work: MediaItem, candidate: MediaItem): Boolean =
        queries(work).any { MediaIdentity.isSameWork(it, work.year, candidate.title, candidate.year) } &&
            (!work.mediaTypeKnown || !candidate.mediaTypeKnown || work.isTv == candidate.isTv) &&
            (work.seasonNumber == null || candidate.seasonNumber == null || work.seasonNumber == candidate.seasonNumber)

    fun usableUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun accessDenied(message: String): Boolean = Regex(
        "(?i)(HTTP\\s*(?:400|401|403|407|429|451)|forbidden|unauthori[sz]ed|access.denied|captcha|PASSWORD|未登录|拒绝|禁止|验证|访问受限|内网|保留地址)"
    ).containsMatchIn(message)

    fun mayTryAlternative(error: IOException): Boolean = when (error) {
        is LibreTvProtocolException -> false
        is LibreTvHttpException -> error.status in setOf(404, 408, 500, 502, 504) && !accessDenied(error.serverMessage)
        else -> !accessDenied(error.message.orEmpty())
    }
}

/** Searches at most canonical + two supplied aliases and fetches at most six known candidates.
 * A cancellation, access restriction, rate limit or protocol error is never a fallback signal.
 */
class CatalogSourceResolver(
    private val search: suspend (String) -> SearchSnapshot,
    private val fetchDetail: suspend (MediaItem) -> MediaItem
) {
    suspend fun resolve(work: MediaItem, onProgress: (ResolutionProgress) -> Unit = {}): MediaItem =
        withTimeoutOrNull(SourceResolutionPolicy.TIMEOUT_MS) {
            val queries = SourceResolutionPolicy.queries(work)
            val seen = mutableSetOf<Pair<String, String>>()
            var attempts = 0
            for ((index, query) in queries.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(ResolutionProgress(ResolutionPhase.SEARCHING, index + 1, queries.size, attempts))
                val snapshot = search(query)
                if (!snapshot.complete) throw SourceResolutionException("搜索响应未完成，请重试")
                if (snapshot.outcomes.any { !it.ok && SourceResolutionPolicy.accessDenied(it.error) }) {
                    throw SourceResolutionException("来源访问受限，请检查服务器或来源授权")
                }
                for (candidate in snapshot.items) {
                    currentCoroutineContext().ensureActive()
                    val source = candidate.source ?: continue
                    if (!source.enabled || !SourceResolutionPolicy.usableUrl(source.url) ||
                        !SourceResolutionPolicy.matches(work, candidate) ||
                        !seen.add(source.url to candidate.id)) continue
                    if (attempts >= SourceResolutionPolicy.MAX_DETAILS) break
                    attempts++
                    onProgress(ResolutionProgress(ResolutionPhase.TRYING_SOURCE, index + 1, queries.size, attempts,
                        sourceName = source.name))
                    val detail = try { fetchDetail(candidate) } catch (error: IOException) {
                        if (!SourceResolutionPolicy.mayTryAlternative(error)) throw error
                        continue
                    }
                    if (!SourceResolutionPolicy.matches(work, detail) || !SourceResolutionPolicy.matches(candidate, detail) || detail.episodes.isEmpty() ||
                        detail.episodes.any { !SourceResolutionPolicy.usableUrl(it) }) continue
                    onProgress(ResolutionProgress(ResolutionPhase.RESOLVED, index + 1, queries.size, attempts,
                        sourceName = source.name))
                    return@withTimeoutOrNull detail
                }
                if (attempts >= SourceResolutionPolicy.MAX_DETAILS) break
            }
            throw SourceResolutionException("未找到可确认与《${work.title}》匹配且可播放的来源（已尝试 $attempts 个）")
        } ?: throw SourceResolutionException("片源匹配超时，请稍后重试")
}

/** Shared by ordinary JSON search/detail. Cancellation uses OkHttp Call.cancel to close the socket while IO is
 * blocked, rather than merely cancelling a coroutine that can only observe its next suspension.
 * Kept independent of Android/JSON so the real socket lifecycle can also be exercised on the JVM.
 */
internal suspend fun <T> withCancellableCall(
    call: Call,
    read: suspend (Response) -> T
): T = callbackFlow {
    val network = launch(Dispatchers.IO) {
        try {
            call.execute().use { response ->
                send(read(response))
                close()
            }
        } catch (cancelled: CancellationException) { close(cancelled); throw cancelled }
        catch (error: Exception) { close(error) }
        finally { call.cancel() }
    }
    awaitClose { call.cancel(); network.cancel() }
}.first()
