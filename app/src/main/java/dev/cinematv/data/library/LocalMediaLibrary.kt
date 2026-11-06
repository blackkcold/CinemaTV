package dev.cinematv.data.library

import android.content.Context
import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.usableMetadata
import dev.cinematv.data.MediaItem
import dev.cinematv.domain.EpisodeProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/**
 * Durable library with a synchronous, immutable in-memory view. Constructing this object does not
 * read preferences or open SQLite. Await initialize() once before presenting the loaded library,
 * observe snapshots for updates, and launch suspend mutations from a lifecycle-owned scope.
 * Every disk access and JSON decode occurs on the I/O dispatcher, never during a Compose read.
 */
class LocalMediaLibrary(
    private val databaseProvider: () -> CinemaLibraryDatabase,
    private val legacyProvider: () -> LegacyLibraryImport,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this(
        { CinemaLibraryDatabase.get(context.applicationContext) },
        { LegacyLibraryImport.read(context.applicationContext.getSharedPreferences("cinema_settings", Context.MODE_PRIVATE)) }
    )

    private val mutex = Mutex()
    private val state = MutableStateFlow(LibrarySnapshot())
    val snapshots: StateFlow<LibrarySnapshot> = state.asStateFlow()
    val snapshot: LibrarySnapshot get() = state.value
    private var initialized = false
    private val db by lazy(databaseProvider)
    private val dao get() = db.libraryDao()

    suspend fun initialize(): LibraryImportReport = withContext(io) {
        mutex.withLock { initializeLocked() }
    }

    private fun initializeLocked(): LibraryImportReport {
        if (initialized) return LibraryImportReport(false)
        var report = LibraryImportReport(false)
        if (dao.metadata(LEGACY_IMPORT_KEY) == null) {
            // Read/parse everything first. A malformed JSON array cannot create a partial import.
            val legacy = legacyProvider()
            db.runInTransaction {
                // Check inside the transaction too, making multiple repository instances safe.
                if (dao.metadata(LEGACY_IMPORT_KEY) == null) {
                    importLocked(legacy)
                    dao.upsertMetadata(LibraryMetadataEntity(LEGACY_IMPORT_KEY, "complete"))
                    dao.upsertMetadata(LibraryMetadataEntity("legacy_skipped_records", legacy.skippedRecords.toString()))
                    report = LibraryImportReport(true, dao.entries().size, legacy.skippedRecords)
                }
            }
        }
        refreshLocked()
        initialized = true
        return report
    }

    private fun importLocked(legacy: LegacyLibraryImport) {
        val timestamp = now().coerceAtLeast(legacy.history.size.toLong())
        legacy.rawValues.forEach { (key, value) ->
            dao.upsertMetadata(LibraryMetadataEntity("legacy_backup_$key", value))
        }
        legacy.favorites.asReversed().forEach { item ->
            val saved = storeItemLocked(item)
            val existing = entryLocked(saved)
            dao.upsertEntry(existing.copy(favorite = true, updatedAt = timestamp))
        }
        // Legacy arrays were newest first. Process backwards so the newest duplicate wins.
        legacy.statuses.asReversed().forEach { (status, item) ->
            val saved = storeItemLocked(item)
            val existing = entryLocked(saved)
            dao.upsertEntry(existing.copy(
                status = if (status == LibraryStatus.FAVORITE) existing.status else status.name,
                favorite = existing.favorite || status == LibraryStatus.FAVORITE,
                updatedAt = timestamp
            ))
        }
        legacy.history.withIndex().toList().asReversed().forEach { (index, item) ->
            saveProgressLocked(item, item.positionMs, item.lastEpisode, item.durationMs, timestamp - index)
        }
        // Original SharedPreferences values remain untouched as a recovery/rollback source.
    }

    suspend fun setStatus(item: MediaItem, status: LibraryStatus?) = mutate {
        require(item.id.isNotBlank()) { "A library item needs a provider ID" }
        val saved = currentItemOrBootstrapLocked(item)
        val current = entryLocked(saved)
        dao.upsertEntry(current.copy(
            status = if (status == LibraryStatus.FAVORITE) current.status else status?.name,
            favorite = current.favorite || status == LibraryStatus.FAVORITE,
            updatedAt = now()
        ))
    }

    /** Explicit personal import. Exact Douban IDs only; recheck conflicts in the database transaction. */
    suspend fun importDouban(entries: List<dev.cinematv.data.douban.DoubanEntry>,
        access: dev.cinematv.data.douban.DoubanFeatureAccess): Pair<Int, Int> {
        val ticket = access.capture()
        var added = 0
        var conflicts = 0
        withContext(io) { mutex.withLock { access.guard(ticket) {
            initializeLocked()
            db.runInTransaction {
            val known = dao.sources().map { MediaItemCodec.decode(it.itemJson) }
            entries.groupBy { it.subjectId }.forEach { (id, records) ->
                if (records.map { it.shelf }.distinct().size != 1) { conflicts++; return@forEach }
                val record = records.first()
                val target = when (record.shelf) {
                    dev.cinematv.data.douban.DoubanShelf.WATCHED -> LibraryStatus.WATCHED
                    dev.cinematv.data.douban.DoubanShelf.WANT -> LibraryStatus.WANT_TO_WATCH
                    dev.cinematv.data.douban.DoubanShelf.WATCHING -> LibraryStatus.WATCHING
                }
                val matches = known.filter { it.metadataIds["douban"] == id }
                // Preserve all providers and existing progress. Do not fabricate movie/series metadata or scores.
                val targets = matches.ifEmpty { listOf(MediaItem("douban:$id", record.title,
                    metadataIds = mapOf("douban" to id), mediaTypeKnown = false)) }
                targets.forEach { item ->
                    val current = entryLocked(item)
                    if (current.status != null && current.status != target.name) conflicts++
                    else if (current.status == null) {
                        val saved = currentItemOrBootstrapLocked(item)
                        dao.upsertEntry(entryLocked(saved).copy(status = target.name, updatedAt = now()))
                        added++
                    }
                }
            }
            }
            refreshLocked()
        } } }
        return added to conflicts
    }

    /** Favorite is independent of watching/watched status, so toggling it does not erase a status. */
    suspend fun toggleFavorite(item: MediaItem) = mutate {
        require(item.id.isNotBlank()) { "A favorite needs a provider ID" }
        val saved = currentItemOrBootstrapLocked(item)
        val current = entryLocked(saved)
        dao.upsertEntry(current.copy(favorite = !current.favorite, updatedAt = now()))
    }

    /** Explicit desired state is safe when an IPC acknowledgement is delayed or lost. */
    suspend fun setFavorite(item: MediaItem, favorite: Boolean) = mutate {
        require(item.id.isNotBlank()) { "A favorite needs a provider ID" }
        val saved = currentItemOrBootstrapLocked(item)
        val current = entryLocked(saved)
        if (current.favorite != favorite) {
            dao.upsertEntry(current.copy(favorite = favorite, updatedAt = now()))
        }
    }

    suspend fun saveProgress(item: MediaItem, positionMs: Long, episodeIndex: Int = 0, durationMs: Long = 0L) = mutate {
        if (item.id.isNotBlank()) saveProgressLocked(item, positionMs, episodeIndex, durationMs, now())
    }

    /** Refresh descriptive metadata/episode URLs without changing library flags or viewing history. */
    suspend fun cacheItem(item: MediaItem) = mutate {
        if (item.id.isNotBlank()) {
            val saved = storeItemLocked(item)
            dao.upsertEntry(entryLocked(saved))
        }
    }

    /** Transaction-level compare-and-swap also guards against another library instance/device import. */
    suspend fun associateWork(source: MediaItem, target: MediaItem, season: Int?, revision: String,
        reviewedMembers: List<MediaItem> = listOf(target)) = mutateAssociation {
        val overlay = currentAssociationsLocked(revision)
        fun current(expected: MediaItem): MediaItem {
            val item = dao.source(MediaDomainMapper.entryId(expected))?.itemJson?.let(MediaItemCodec::decode)
                ?.usableMetadata(now()) ?: error("Source is no longer available")
            check(associationEvidence(item) == associationEvidence(expected)) { "Evidence changed; review again" }
            return item
        }
        val reviewedIds = reviewedMembers.map(MediaDomainMapper::entryId)
        check(reviewedIds.size == reviewedIds.toSet().size && reviewedIds.toSet() ==
            overlay.targetEntryIds(MediaDomainMapper.entryId(source), MediaDomainMapper.entryId(target))) {
            "Group membership changed or was not completely reviewed"
        }
        // Group revision alone cannot detect a catalog refresh on another previewed member.
        reviewedMembers.forEach { current(it) }
        val next = overlay.associate(current(source), current(target), season, now())
        dao.upsertMetadata(LibraryMetadataEntity(LocalWorkAssociationCodec.KEY, LocalWorkAssociationCodec.encode(next)))
    }

    suspend fun unlinkWork(sourceId: String, revision: String) = mutateAssociation {
        val next = currentAssociationsLocked(revision).unlink(sourceId)
        dao.upsertMetadata(LibraryMetadataEntity(LocalWorkAssociationCodec.KEY, LocalWorkAssociationCodec.encode(next)))
    }

    suspend fun undoWorkAssociation(revision: String) = mutateAssociation {
        val next = currentAssociationsLocked(revision).undo()
        dao.upsertMetadata(LibraryMetadataEntity(LocalWorkAssociationCodec.KEY, LocalWorkAssociationCodec.encode(next)))
    }

    private suspend fun mutateAssociation(block: () -> Unit) = withContext(io) {
        mutex.withLock {
            initializeLocked()
            // Even a rejected stale writer must reload the winning revision before another review.
            try { db.runInTransaction(block) } finally { refreshLocked() }
        }
    }

    private fun currentAssociationsLocked(revision: String): LocalWorkAssociations =
        LocalWorkAssociationCodec.decode(dao.metadata(LocalWorkAssociationCodec.KEY)).also {
            check(it.writable && it.revision == revision) { "Association changed or uses an unsupported version" }
        }

    suspend fun clearHistory() = mutate {
        dao.deleteProgress()
        dao.deleteCheckpointEvidence()
        dao.clearHistory()
    }

    private suspend fun mutate(block: () -> Unit) = withContext(io) {
        mutex.withLock {
            initializeLocked()
            db.runInTransaction(block)
            refreshLocked()
        }
    }

    private fun saveProgressLocked(item: MediaItem, position: Long, index: Int, duration: Long, timestamp: Long) {
        // A late player callback is not a catalog refresh. Keep the newer cached playlist,
        // and never manufacture identity by borrowing its URLs/names for a legacy record.
        val current = currentItemOrBootstrapLocked(item)
        val safeIndex = index.coerceAtLeast(0)
        val entry = entryLocked(current)
        val identity = CheckpointIdentity.resolve(item, index, current)
        val episodeId = identity.episodeId
        val evidenceKey = "checkpoint_evidence:$episodeId"
        if (dao.metadata(evidenceKey) == null)
            dao.upsertMetadata(LibraryMetadataEntity(evidenceKey, MediaItemCodec.encode(item)))
        val old = dao.progress(episodeId)?.toDomain()
        val progress = EpisodeProgress.record(episodeId, entry.sourceMappingId, safeIndex, position, duration, timestamp, old)
        dao.upsertProgress(EpisodeProgressEntity(progress.episodeId, progress.sourceMappingId,
            progress.episodeIndex, progress.positionMs, progress.durationMs, progress.updatedAt))
        // Keep an archival episode row without replacing a current episode's rotated URL.
        if (identity.currentIndex == null) {
            dao.upsertEpisodes(listOf(EpisodeEntity(episodeId, MediaDomainMapper.seasonId(item),
                entry.sourceMappingId, safeIndex, item.episodeNames.getOrNull(index) ?: "第${safeIndex + 1}集",
                item.episodes.getOrNull(index).orEmpty())))
        }
        dao.upsertEntry(entry.copy(historyAt = timestamp, lastEpisodeIndex = identity.currentIndex ?: -1,
            updatedAt = maxOf(entry.updatedAt, timestamp)))
    }

    /** Flag/progress writes do not carry catalog authority, including delayed player callbacks. */
    private fun currentItemOrBootstrapLocked(item: MediaItem): MediaItem =
        dao.source(MediaDomainMapper.entryId(item))?.itemJson?.let(MediaItemCodec::decode)
            ?: storeItemLocked(item)

    private fun entryLocked(item: MediaItem): LibraryEntryEntity {
        val key = MediaDomainMapper.entryId(item)
        return dao.entry(key) ?: LibraryEntryEntity(key)
    }

    private fun storeItemLocked(incoming: MediaItem): MediaItem {
        val previous = dao.source(MediaDomainMapper.entryId(incoming))?.itemJson?.let(MediaItemCodec::decode)
        val item = if (previous == null) incoming else MediaSnapshotMerger.merge(previous, incoming)
        if (previous != null) {
            // Preserve one original evidence snapshot per checkpoint, not every URL refresh.
            dao.progressForSource(MediaDomainMapper.entryId(previous)).forEach { checkpoint ->
                val key = "checkpoint_evidence:${checkpoint.episodeId}"
                if (dao.metadata(key) == null)
                    dao.upsertMetadata(LibraryMetadataEntity(key, MediaItemCodec.encode(previous)))
            }
            val entry = dao.entry(MediaDomainMapper.entryId(item))
            if (entry?.historyAt != null) {
                val oldIdentity = MediaDomainMapper.episodeIdentity(previous, entry.lastEpisodeIndex)
                val oldId = oldIdentity?.let { MediaDomainMapper.episodeId(previous, entry.lastEpisodeIndex) }
                val newIndex = item.episodes.indices.firstOrNull { MediaDomainMapper.episodeIdentity(item, it) != null &&
                    MediaDomainMapper.episodeId(item, it) == oldId }
                // -1 is an explicit unresolved last episode; never substitute episode zero.
                dao.upsertEntry(entry.copy(lastEpisodeIndex = newIndex ?: -1))
            }
        }
        val graph = MediaDomainMapper.map(item)
        dao.upsertWork(WorkEntity(graph.work.id, graph.work.title, graph.work.kind.name, graph.work.year))
        dao.upsertSeason(SeasonEntity(graph.season.id, graph.season.workId, graph.season.number, graph.season.title))
        dao.upsertSource(SourceMappingEntity(graph.source.id, graph.source.workId, graph.source.sourceId,
            graph.source.sourceName, graph.source.sourceUrl, graph.source.providerItemId, MediaItemCodec.encode(item)))
        if (graph.episodes.isNotEmpty()) dao.upsertEpisodes(graph.episodes.map {
            EpisodeEntity(it.id, it.seasonId, it.sourceMappingId, it.index, it.title, it.playbackUrl)
        })
        return item
    }

    private fun refreshLocked() {
        state.value = db.runInTransaction(Callable {
            val items = dao.sources().associate { it.id to MediaItemCodec.decode(it.itemJson) }
            val progress = dao.progressRecords().associate { it.episodeId to it.toDomain() }
            val records = dao.entries().mapNotNull { entry ->
                val item = items[entry.sourceMappingId] ?: return@mapNotNull null
                val last = if (entry.historyAt != null)
                    item.episodes.indices.firstOrNull { it == entry.lastEpisodeIndex &&
                        MediaDomainMapper.episodeIdentity(item, it) != null }?.let {
                        progress[MediaDomainMapper.episodeId(item, it)]?.copy(episodeIndex = it)
                    } else null
                val state = entry.status?.let { runCatching { LibraryStatus.valueOf(it) }.getOrNull() }
                LibraryRecord(item.copy(positionMs = last?.positionMs ?: 0,
                    durationMs = last?.durationMs ?: 0, lastEpisode = last?.episodeIndex ?: -1),
                    state, entry.favorite, entry.updatedAt, entry.historyAt)
            }
            LibrarySnapshot(ready = true, records = records, episodeProgress = progress,
                associations = LocalWorkAssociationCodec.decode(dao.metadata(LocalWorkAssociationCodec.KEY)))
        })
    }

    companion object {
        const val LEGACY_IMPORT_KEY = "shared_preferences_import_v1"


    }
}

private fun EpisodeProgressEntity.toDomain() = EpisodeProgress(episodeId, sourceMappingId,
    episodeIndex, positionMs, durationMs, updatedAt)
