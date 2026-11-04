package dev.cinematv.data.library

import android.content.Context
import dev.cinematv.data.LibraryStatus
import dev.cinematv.data.MediaDomainMapper
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
        val saved = storeItemLocked(item)
        val current = entryLocked(saved)
        dao.upsertEntry(current.copy(
            status = if (status == LibraryStatus.FAVORITE) current.status else status?.name,
            favorite = current.favorite || status == LibraryStatus.FAVORITE,
            updatedAt = now()
        ))
    }

    /** Favorite is independent of watching/watched status, so toggling it does not erase a status. */
    suspend fun toggleFavorite(item: MediaItem) = mutate {
        require(item.id.isNotBlank()) { "A favorite needs a provider ID" }
        val saved = storeItemLocked(item)
        val current = entryLocked(saved)
        dao.upsertEntry(current.copy(favorite = !current.favorite, updatedAt = now()))
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

    suspend fun clearHistory() = mutate {
        dao.deleteProgress()
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
        val saved = storeItemLocked(item)
        val safeIndex = index.coerceAtLeast(0)
        val entry = entryLocked(saved)
        val episodeId = MediaDomainMapper.episodeId(saved, safeIndex)
        val old = dao.progress(episodeId)?.toDomain()
        val progress = EpisodeProgress.record(episodeId, entry.sourceMappingId, safeIndex, position, duration, timestamp, old)
        dao.upsertProgress(EpisodeProgressEntity(progress.episodeId, progress.sourceMappingId,
            progress.episodeIndex, progress.positionMs, progress.durationMs, progress.updatedAt))
        // Legacy history often had no episode URLs. Keep a real episode row for its recorded index.
        if (saved.episodes.getOrNull(safeIndex) == null) {
            dao.upsertEpisodes(listOf(EpisodeEntity(episodeId, MediaDomainMapper.seasonId(saved),
                entry.sourceMappingId, safeIndex, saved.episodeNames.getOrNull(safeIndex) ?: "第${safeIndex + 1}集", "")))
        }
        dao.upsertEntry(entry.copy(historyAt = timestamp, lastEpisodeIndex = safeIndex,
            updatedAt = maxOf(entry.updatedAt, timestamp)))
    }

    private fun entryLocked(item: MediaItem): LibraryEntryEntity {
        val key = MediaDomainMapper.entryId(item)
        return dao.entry(key) ?: LibraryEntryEntity(key)
    }

    private fun storeItemLocked(incoming: MediaItem): MediaItem {
        val previous = dao.source(MediaDomainMapper.entryId(incoming))?.itemJson?.let(MediaItemCodec::decode)
        val item = if (previous == null) incoming else MediaSnapshotMerger.merge(previous, incoming)
        // A legacy flat list had no season number. Enriching that same source listing must not
        // strand its checkpoints under the unknown-season key. Explicit season 1 -> 2 never moves.
        if (previous != null && previous.seasonNumber == null && item.seasonNumber != null) {
            dao.progressForSource(MediaDomainMapper.entryId(item)).forEach { old ->
                if (old.episodeId == MediaDomainMapper.episodeId(previous, old.episodeIndex)) {
                    val newId = MediaDomainMapper.episodeId(item, old.episodeIndex)
                    val existing = dao.progress(newId)
                    if (existing == null || old.updatedAt > existing.updatedAt)
                        dao.upsertProgress(old.copy(episodeId = newId))
                    dao.deleteProgress(old.episodeId)
                }
            }
        }
        if (previous != null && MediaDomainMapper.seasonId(previous) != MediaDomainMapper.seasonId(item)) {
            dao.deleteEpisodes(MediaDomainMapper.entryId(item), MediaDomainMapper.seasonId(previous))
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
                    progress[MediaDomainMapper.episodeId(item, entry.lastEpisodeIndex)] else null
                val state = entry.status?.let { runCatching { LibraryStatus.valueOf(it) }.getOrNull() }
                LibraryRecord(item.copy(positionMs = last?.positionMs ?: 0,
                    durationMs = last?.durationMs ?: 0, lastEpisode = last?.episodeIndex ?: 0),
                    state, entry.favorite, entry.updatedAt, entry.historyAt)
            }
            LibrarySnapshot(ready = true, records = records, episodeProgress = progress)
        })
    }

    companion object {
        const val LEGACY_IMPORT_KEY = "shared_preferences_import_v1"


    }
}

private fun EpisodeProgressEntity.toDomain() = EpisodeProgress(episodeId, sourceMappingId,
    episodeIndex, positionMs, durationMs, updatedAt)
