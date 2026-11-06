package dev.cinematv.data

import java.net.URI
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class SourceSubscription(val url: String, val name: String = "", val refreshDays: Int = 1) {
    val id: String get() = SourceSubscriptionContract.id("subscription", url)
    val displayName: String get() = name.ifBlank { runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "片源列表订阅" } }
}

data class SubscriptionParseStats(
    val format: String = "libretv",
    val skipped: Int = 0,
    val skippedByReason: Map<String, Int> = emptyMap(),
    val skippedSamples: List<String> = emptyList(),
    val truncated: Int = 0,
    val invalidItems: Int = 0,
    val duplicates: Int = 0,
    val invalidOptionalUrls: Int = 0
) {
    fun description(): String = buildList {
        val names = mapOf("spider" to "Spider 引擎", "xml" to "XML 接口", "unsearchable" to "不支持搜索",
            "nonM3uLive" to "非 M3U 直播", "disabled" to "已停用", "invalidUrl" to "地址不可用")
        if (skipped > 0) add("服务端跳过 $skipped 条" + skippedByReason.entries.joinToString("、", "（", "）") {
            "${names[it.key] ?: "其他"} ${it.value}"
        })
        if (invalidItems > 0) add("客户端忽略 $invalidItems 条无效记录")
        if (invalidOptionalUrls > 0) add("已移除 $invalidOptionalUrls 个无效详情或节目单地址")
        if (duplicates > 0) add("合并 $duplicates 条重复地址")
        if (truncated > 0) add("数量上限截断 $truncated 条")
        if (skippedSamples.isNotEmpty()) add("跳过示例：${skippedSamples.joinToString("、")}")
    }.joinToString("；").ifBlank { "全部条目均可导入" }
}

data class SourceListSnapshot(val name: String, val sources: List<TvSource>,
    val liveSources: List<LiveSource>, val stats: SubscriptionParseStats)

data class SourceSubscriptionRecord(val subscription: SourceSubscription, val snapshot: SourceListSnapshot,
    val updatedAt: Long, val revision: String) {
    fun refreshDue(now: Long = System.currentTimeMillis()): Boolean =
        now >= updatedAt && now - updatedAt >= subscription.refreshDays * 86_400_000L
}

data class SubscriptionSourceChanges(val added: Int, val removed: Int, val changed: Int)
data class SourceSubscriptionDiff(val vod: SubscriptionSourceChanges, val live: SubscriptionSourceChanges) {
    companion object {
        fun between(previous: SourceListSnapshot?, next: SourceListSnapshot): SourceSubscriptionDiff {
            fun <T> count(old: List<T>, fresh: List<T>, url: (T) -> String): SubscriptionSourceChanges {
                val before = old.associateBy(url)
                val after = fresh.associateBy(url)
                return SubscriptionSourceChanges((after.keys - before.keys).size, (before.keys - after.keys).size,
                    (after.keys intersect before.keys).count { after[it] != before[it] })
            }
            return SourceSubscriptionDiff(count(previous?.sources.orEmpty(), next.sources, TvSource::url),
                count(previous?.liveSources.orEmpty(), next.liveSources, LiveSource::url))
        }
    }
}

data class SourceSubscriptionPreview(val subscription: SourceSubscription, val snapshot: SourceListSnapshot,
    val connection: LibreTvConnection, val sessionRevision: Long, val previousRevision: String?,
    val fetchedAt: Long, val changes: SourceSubscriptionDiff)

/** Decodes only LibreTV's normalized API output. TVBOX/Spider parsing remains on the upstream service. */
internal object SourceSubscriptionContract {
    const val MAX_SUBSCRIPTIONS = 20
    const val MAX_VOD = 100
    const val MAX_LIVE = 50
    private const val MAX_SCAN = 1_000

    fun id(prefix: String, url: String): String = prefix + "_" + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun normalize(value: SourceSubscription): SourceSubscription {
        val url = value.url.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("订阅地址必须为有效的 HTTP(S) URL")
        require(value.url.length <= 2_048) { "订阅地址过长" }
        require(url.username.isBlank() && url.password.isBlank()) { "订阅地址不能包含账户密码" }
        val cleaned = url.newBuilder().fragment(null).build().toString()
        return SourceSubscription(cleaned, value.name.trim().take(120), value.refreshDays.coerceIn(1, 365))
    }

    fun advertised(array: JSONArray?): List<SourceSubscription> = buildList {
        if (array == null) return@buildList
        for (index in 0 until minOf(array.length(), MAX_SCAN)) {
            val value = array.opt(index)
            val record = value as? JSONObject
            val rawUrl = if (value is String) value else record?.optString("url").orEmpty()
            val days = record?.optDouble("refreshDays", 1.0)?.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 1
            val subscription = runCatching { normalize(SourceSubscription(rawUrl, record?.text("name").orEmpty(), days)) }.getOrNull()
            if (subscription != null && none { it.url == subscription.url } && size < MAX_SUBSCRIPTIONS) add(subscription)
        }
    }

    fun decode(root: JSONObject): SourceListSnapshot {
        if ((!root.has("sources") && !root.has("liveSources")) ||
            (root.has("sources") && root.optJSONArray("sources") == null) ||
            (root.has("liveSources") && root.optJSONArray("liveSources") == null))
            throw LibreTvProtocolException("片源列表响应缺少有效的 sources / liveSources 数组")
        val vod = mutableListOf<TvSource>()
        val live = mutableListOf<LiveSource>()
        var invalid = 0
        var duplicate = 0
        var optional = 0
        var trimmed = 0
        fun scan(array: JSONArray?, limit: Int, consume: (JSONObject, String) -> Unit) {
            if (array == null) return
            val seen = mutableSetOf<String>()
            var accepted = 0
            for (index in 0 until minOf(array.length(), MAX_SCAN)) {
                val item = array.optJSONObject(index)
                val url = item?.text("url").orEmpty()
                if (item == null || url.length > 2_048 || !SourceResolutionPolicy.usableUrl(url)) { invalid++; continue }
                if (!seen.add(url)) { duplicate++; continue }
                if (accepted >= limit) { trimmed++; continue }
                accepted++
                consume(item, url)
            }
            trimmed += (array.length() - MAX_SCAN).coerceAtLeast(0)
        }
        fun optionalUrl(item: JSONObject, field: String): String {
            val value = item.text(field)
            if (value.isBlank() || (value.length <= 2_048 && SourceResolutionPolicy.usableUrl(value))) return value
            optional++
            return ""
        }
        scan(root.optJSONArray("sources"), MAX_VOD) { item, url ->
            vod += TvSource(id("subscription_vod", url), item.text("name").ifBlank { URI(url).host }.take(120), url,
                optionalUrl(item, "detail"))
        }
        scan(root.optJSONArray("liveSources"), MAX_LIVE) { item, url ->
            live += LiveSource(id("subscription_live", url), item.text("name").ifBlank { URI(url).host }.take(120), url,
                optionalUrl(item, "epg"))
        }
        if (vod.isEmpty() && live.isEmpty()) throw LibreTvProtocolException("订阅没有可用源；已保留原有片源")
        val stats = readStats(root.optJSONObject("stats"))
        return SourceListSnapshot(root.text("name").take(120), vod.toList(), live.toList(),
            stats.copy(invalidItems = stats.invalidItems + invalid, duplicates = stats.duplicates + duplicate,
                invalidOptionalUrls = stats.invalidOptionalUrls + optional, truncated = stats.truncated + trimmed))
    }

    private fun readStats(root: JSONObject?): SubscriptionParseStats {
        if (root == null) return SubscriptionParseStats()
        val reasons = root.optJSONObject("skippedByReason")
        val counts = reasons?.keys()?.asSequence()?.take(12)?.mapNotNull { key ->
            reasons.optInt(key, 0).coerceIn(0, 1_000_000).takeIf { it > 0 }?.let { key.take(40) to it }
        }?.toMap().orEmpty()
        val samples = root.optJSONArray("skippedSamples")
        return SubscriptionParseStats(root.text("format").takeIf { it in setOf("libretv", "tvbox", "source-catalog") } ?: "libretv",
            root.optInt("skipped", 0).coerceIn(0, 1_000_000), counts,
            (0 until minOf(samples?.length() ?: 0, 3)).map { samples!!.optString(it).take(120) },
            root.optInt("truncated", 0).coerceIn(0, 1_000_000),
            root.optInt("invalidItems", 0).coerceIn(0, 1_000_000),
            root.optInt("duplicates", 0).coerceIn(0, 1_000_000),
            root.optInt("invalidOptionalUrls", 0).coerceIn(0, 1_000_000))
    }

    fun encodeRecords(records: List<SourceSubscriptionRecord>): String = JSONObject().put("schema", 1)
        .put("subscriptions", JSONArray(records.map { record ->
            JSONObject().put("url", record.subscription.url).put("name", record.subscription.name)
                .put("refreshDays", record.subscription.refreshDays).put("updatedAt", record.updatedAt)
                .put("revision", record.revision).put("snapshot", encodeSnapshot(record.snapshot))
        })).toString()

    fun decodeRecords(raw: String?): List<SourceSubscriptionRecord> {
        if (raw == null) return emptyList()
        val root = JSONObject(raw)
        require(root.optInt("schema") == 1) { "不支持的订阅存储版本" }
        val array = root.getJSONArray("subscriptions")
        require(array.length() <= MAX_SUBSCRIPTIONS) { "订阅资料数量超限" }
        val seen = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val subscription = normalize(SourceSubscription(row.getString("url"), row.text("name"), row.optInt("refreshDays", 1)))
            require(seen.add(subscription.url)) { "订阅资料存在重复地址" }
            val revision = row.getString("revision").takeIf { it.isNotBlank() } ?: error("订阅资料缺少版本")
            val rawSnapshot = row.getJSONObject("snapshot")
            val snapshot = decode(rawSnapshot)
            require(rawSnapshot.getJSONArray("sources").length() == snapshot.sources.size &&
                rawSnapshot.getJSONArray("liveSources").length() == snapshot.liveSources.size) { "已保存订阅包含损坏的片源记录" }
            SourceSubscriptionRecord(subscription, snapshot, row.getLong("updatedAt").coerceAtLeast(0), revision)
        }
    }

    fun encodeSnapshot(snapshot: SourceListSnapshot): JSONObject = JSONObject().put("name", snapshot.name)
        .put("sources", JSONArray(snapshot.sources.map { JSONObject().put("name", it.name).put("url", it.url).put("detail", it.detail) }))
        .put("liveSources", JSONArray(snapshot.liveSources.map { JSONObject().put("name", it.name).put("url", it.url).put("epg", it.epg) }))
        .put("stats", JSONObject().put("format", snapshot.stats.format).put("skipped", snapshot.stats.skipped)
            .put("skippedByReason", JSONObject(snapshot.stats.skippedByReason)).put("skippedSamples", JSONArray(snapshot.stats.skippedSamples))
            .put("truncated", snapshot.stats.truncated).put("invalidItems", snapshot.stats.invalidItems)
            .put("duplicates", snapshot.stats.duplicates).put("invalidOptionalUrls", snapshot.stats.invalidOptionalUrls))

    private fun JSONObject.text(key: String) = optString(key).trim().takeUnless { it == "null" }.orEmpty()
}
