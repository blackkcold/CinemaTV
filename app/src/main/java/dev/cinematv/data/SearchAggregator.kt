package dev.cinematv.data

/**
 * A displayed work plus all original, playable source records.
 *
 * [groupKey] is the stable display identity used for grid/focus keys. It deliberately contains
 * no poster, source order or first-seen order, so streaming arrivals cannot move a focused row.
 * It is empty for non-search consumers (recommendations), which do not use display keys.
 */
data class SearchResultGroup(
    val item: MediaItem,
    val alternatives: List<MediaItem>,
    val groupKey: String = "",
)

/**
 * Conservative, deterministic grouping, never an alias-based identity guess.
 * A film and a series are different works, as are conflicting or ambiguous years.
 * Unknown-year records may only coalesce when the complete record is repeated or
 * an explicit source/id and the title/type agree. Original source IDs are retained.
 *
 * Two grouping paths exist on purpose, and neither may silently replace the other:
 *
 * - [groupWithAlternatives] is the recommendation/legacy path. Its predicates ([workKey],
 *   [sameWork]) are ordinary buckets plus conflict vetoes, and they are reused across
 *   recommendation, library and refresh code. Their behaviour is unchanged here.
 * - [searchGroups] is the search-display path. It uses [SearchGroupIdentity], which is stricter
 *   about a series with an unknown season and which also unions two translations of one work
 *   through a trusted ID. Search may be stricter than recommendation; it must not pretend the
 *   two predicates were unified.
 */
object SearchAggregator {
    private val YEAR = Regex("[12][0-9]{3}")

    fun group(input: List<MediaItem>): List<MediaItem> = groupWithAlternatives(input).map { it.item }

    /**
     * Recommendation/legacy grouping. Unchanged: unknown-season series still share the
     * `unknown` season bucket here, and conflict splitting keeps its older per-record isolation.
     * Search does not use this entry point.
     */
    fun groupWithAlternatives(input: List<MediaItem>): List<SearchResultGroup> {
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        input.distinct().forEachIndexed { index, item ->
            val key = workKey(item) ?: sourceKey(item) ?: "unknown:$index"
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.flatMap(::splitConflictingIds).map { entries ->
            val preferred = entries.firstOrNull { it.poster.isNotBlank() } ?: entries.first()
            SearchResultGroup(withSourceCount(preferred, entries), entries.toList())
        }
    }

    /**
     * Search-display grouping with a stable [SearchResultGroup.groupKey].
     *
     * 1. each record gets its own evidence key ([SearchGroupIdentity.resolve]); a record with no
     *    evidence gets a stable per-listing orphan key, never an input index;
     * 2. buckets sharing one trusted ID are unioned, so two translations of one work are one row;
     * 3. the group publishes a canonical key that ignores arrival order and language;
     * 4. remaining contradictions (conflicting provider IDs, known years, types or seasons) split
     *    the bucket with an evidence-derived suffix.
     */
    fun searchGroups(input: List<MediaItem>): List<SearchResultGroup> {
        val byKey = linkedMapOf<String, MutableList<MediaItem>>()
        input.distinct().forEach { item ->
            val key = SearchGroupIdentity.resolve(item) ?: SearchGroupIdentity.orphanKey(item)
            byKey.getOrPut(key.value) { mutableListOf() }.add(item)
        }
        return unionTrustedIdBuckets(byKey).flatMap { entries ->
            val splits = splitConflictingMembers(entries)
            splits.map { split ->
                // Publish the key of the group that actually remains, never the key of the bucket
                // it was split from: a group must not be named by another group's ID.
                val canonical = SearchGroupIdentity.canonicalKey(split.members)?.value
                val key = when {
                    // Unproven identity: one listing, its own key, never a shared evidence key.
                    split.isolated -> SearchGroupIdentity.orphanKey(split.members.first()).value
                    splits.size == 1 -> canonical ?: SearchGroupIdentity.orphanKey(split.members.first()).value
                    // A real split needs distinct keys. Two groups can share a canonical key
                    // (same trusted ID, different known years), which would duplicate a grid key.
                    canonical != null -> "$canonical#${split.signature}"
                    else -> SearchGroupIdentity.orphanKey(split.members.first()).value
                }
                val preferred = split.members.firstOrNull { it.poster.isNotBlank() } ?: split.members.first()
                SearchResultGroup(withSourceCount(preferred, split.members), split.members.toList(), key)
            }
        }
    }

    /**
     * Map each previous display group to its new key when the two groups share a member.
     *
     * A group key only changes when the evidence behind it changes (a merge, a split, a conflict
     * appearing). Members are the evidence, so an intersection is a safe migration signal.
     * When several new groups claim one old key, the first in the new result order wins, which
     * keeps the mapping deterministic across streaming rebuilds.
     */
    fun remap(previous: List<SearchHit>, current: List<SearchHit>): Map<String, String> {
        if (previous.isEmpty() || current.isEmpty()) return emptyMap()
        val previousByMember = linkedMapOf<String, String>()
        previous.forEach { hit ->
            hit.alternatives.forEach { member ->
                previousByMember.putIfAbsent(MediaDomainMapper.entryId(member), hit.displayKey)
            }
        }
        val mapped = linkedMapOf<String, String>()
        current.forEach { hit ->
            val newKey = hit.displayKey
            hit.alternatives.forEach { member ->
                val oldKey = previousByMember[MediaDomainMapper.entryId(member)] ?: return@forEach
                if (oldKey != newKey) mapped.putIfAbsent(oldKey, newKey)
            }
        }
        return mapped
    }

    internal fun withSourceCount(item: MediaItem, entries: List<MediaItem>): MediaItem =
        item.copy(sourceCount = entries.mapNotNull { it.source?.url?.trim()?.takeIf(String::isNotBlank) }
            .distinct().size.coerceAtLeast(1))

    internal fun workKey(item: MediaItem): String? {
        val title = MediaIdentity.normalizedTitle(item.title)
        val year = item.year.trim()
        if (!item.mediaTypeKnown || title.isBlank() || !Regex("[12][0-9]{3}").matches(year)) return null
        val season = if (item.isTv) item.seasonNumber?.toString() ?: "unknown" else "movie"
        return "work:${item.isTv}:$season:$year:$title"
    }

    private fun sourceKey(item: MediaItem): String? {
        val url = item.source?.url?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (item.id.isBlank() || MediaIdentity.normalizedTitle(item.title).isBlank()) return null
        // Length-prefixed components prevent delimiter collisions in provider IDs/URLs.
        return "source:" + listOf(url, item.id, MediaIdentity.normalizedTitle(item.title),
            item.year.trim(), item.isTv.toString(), if (item.isTv) item.seasonNumber?.toString() ?: "" else "")
            .joinToString("") { "${it.length}:$it" }
    }

    internal fun sameWork(a: MediaItem, b: MediaItem): Boolean {
        if (a.mediaTypeKnown && b.mediaTypeKnown && a.isTv != b.isTv) return false
        if ((a.isTv || b.isTv) && a.seasonNumber != b.seasonNumber) return false
        val idsA = explicitIds(a)
        val idsB = explicitIds(b)
        if (idsA.any { (provider, id) -> idsB[provider]?.let { it != id } == true }) return false
        val yearA = a.year.trim()
        val yearB = b.year.trim()
        if (yearA.isNotBlank() && yearB.isNotBlank() && yearA != yearB) return false
        val titleA = MediaIdentity.normalizedTitle(a.title)
        val titleB = MediaIdentity.normalizedTitle(b.title)
        val sourceA = a.source?.url?.takeIf(String::isNotBlank)
        val sourceB = b.source?.url?.takeIf(String::isNotBlank)
        val sameSource = (a.source == null && b.source == null) || (sourceA != null && sourceA == sourceB)
        if (a.id.isNotBlank() && a.id == b.id && sameSource && titleA.isNotBlank() && titleA == titleB) return true
        if (yearA != yearB) return false
        val aWork = workKey(a)
        val bWork = workKey(b)
        if (aWork != null && bWork != null) return aWork == bWork
        val aSource = sourceKey(a)
        val bSource = sourceKey(b)
        if (aSource != null && bSource != null) return aSource == bSource
        // Stable metadata IDs can connect a saved record with refreshed artwork/progress,
        // but never connect a metadata namespace to an arbitrary CMS namespace.
        return a.source == null && b.source == null && a.id.isNotBlank() && a.id == b.id &&
            titleA.isNotBlank() && titleA == titleB
    }

    private fun explicitIds(item: MediaItem): Map<String, String> = item.metadataIds.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .associate { it.key.trim().lowercase(java.util.Locale.ROOT) to it.value.trim() }

    /**
     * Union buckets that share one trusted ID, so two translations of one work form one group.
     * Only buckets that already exist are merged; an ID-less translation keeps its own title
     * bucket, which is a conservative under-merge rather than a guessed merge.
     */
    private fun unionTrustedIdBuckets(
        buckets: LinkedHashMap<String, MutableList<MediaItem>>,
    ): List<MutableList<MediaItem>> {
        val parent = linkedMapOf<String, String>()
        fun find(start: String): String {
            var key = start
            var hops = 0
            while (hops++ < buckets.size + 1) {
                val next = parent[key] ?: return key
                if (next == key) return key
                key = next
            }
            return key
        }
        val keyByTrusted = linkedMapOf<String, String>()
        buckets.forEach { (key, members) ->
            parent.putIfAbsent(key, key)
            members.forEach { item ->
                val trusted = SearchGroupIdentity.trustedKey(item) ?: return@forEach
                val owner = keyByTrusted.getOrPut(trusted) { key }
                if (owner != key) {
                    val rootOwner = find(owner)
                    val rootKey = find(key)
                    if (rootOwner != rootKey) parent[rootKey] = rootOwner
                }
            }
        }
        val merged = linkedMapOf<String, MutableList<MediaItem>>()
        buckets.forEach { (key, members) ->
            merged.getOrPut(find(key)) { mutableListOf() }.addAll(members)
        }
        return merged.values.toList()
    }

    /**
     * Legacy conflict split for the recommendation path: unchanged behaviour, including its
     * `ambiguous:$index` isolation of a record that misses a conflicting namespace.
     */
    private fun splitConflictingIds(entries: List<MediaItem>): List<List<MediaItem>> {
        val allIds = entries.flatMap { explicitIds(it).entries }
        val conflictingProviders = allIds.groupBy { it.key }
            .filterValues { ids -> ids.map { it.value }.distinct().size > 1 }.keys.sorted()
        if (conflictingProviders.isEmpty()) return listOf(entries)
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        entries.forEachIndexed { index, item ->
            val ids = explicitIds(item)
            // Missing any conflicting namespace leaves this record ambiguous. Do not
            // attach it arbitrarily to whichever candidate happened to arrive first.
            val key = if (conflictingProviders.any { ids[it] == null }) "ambiguous:$index" else
                "ids:" + conflictingProviders.joinToString("") { provider ->
                    val id = ids.getValue(provider)
                    "${provider.length}:$provider${id.length}:$id"
                }
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.toList()
    }

    /**
     * Search conflict split. Keys are derived from evidence, never from arrival order: the suffix
     * names the conflicting namespaces and the values each record carries. A record missing a
     * conflicting namespace cannot be attributed to any candidate, so it is marked isolated and
     * gets its own per-listing key instead of joining whichever ambiguous record came first.
     */
    private data class SplitGroup(
        val members: List<MediaItem>,
        val isolated: Boolean,
        val signature: String,
    )

    private fun splitConflictingMembers(entries: List<MediaItem>): List<SplitGroup> {
        val idsByName = linkedMapOf<String, MutableSet<String>>()
        entries.forEach { item ->
            explicitIds(item).forEach { (namespace, value) ->
                idsByName.getOrPut(namespace) { linkedSetOf() }.add(value)
            }
        }
        val conflictingNamespaces = idsByName.filterValues { it.size > 1 }.keys.sorted()
        val years = entries.map { it.year.trim() }.filter { YEAR.matches(it) }.distinct()
        val types = entries.filter { it.mediaTypeKnown }.map { if (it.isTv) "tv" else "movie" }.distinct()
        val seasons = entries.filter { it.mediaTypeKnown && it.isTv }.mapNotNull { it.seasonNumber }.distinct()
        val yearConflicts = years.size > 1
        val typeConflicts = types.size > 1
        val seasonConflicts = seasons.size > 1
        if (conflictingNamespaces.isEmpty() && !yearConflicts && !typeConflicts && !seasonConflicts) {
            return listOf(SplitGroup(entries, isolated = false, signature = ""))
        }
        val buckets = linkedMapOf<String, MutableList<MediaItem>>()
        val isolatedKeys = mutableSetOf<String>()
        entries.forEach { item ->
            val ids = explicitIds(item)
            val missingEvidence = conflictingNamespaces.any { ids[it] == null } ||
                (yearConflicts && !YEAR.matches(item.year.trim())) ||
                (typeConflicts && !item.mediaTypeKnown) ||
                (seasonConflicts && item.seasonNumber == null)
            val signature = if (missingEvidence) {
                // Isolate: identity is unproven, and two unproven records are not each other.
                "isolated:" + MediaDomainMapper.entryId(item)
            } else buildString {
                conflictingNamespaces.forEach { namespace ->
                    val value = ids.getValue(namespace)
                    append("$namespace:${value.length}:$value|")
                }
                append("year:").append(if (yearConflicts) item.year.trim() else "-")
                append("|type:").append(if (typeConflicts) (if (item.isTv) "tv" else "movie") else "-")
                append("|season:").append(if (seasonConflicts) item.seasonNumber?.toString() ?: "-" else "-")
            }
            if (missingEvidence) isolatedKeys += signature
            buckets.getOrPut(signature) { mutableListOf() }.add(item)
        }
        return buckets.map { (signature, members) ->
            SplitGroup(members.toList(), isolated = signature in isolatedKeys,
                // A digest keeps the published key short and delimiter-free.
                signature = MediaDomainMapper.shortDigest(signature))
        }
    }
}
