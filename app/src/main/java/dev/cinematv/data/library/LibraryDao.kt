package dev.cinematv.data.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Blocking DAO belongs to the persistence layer. LocalMediaLibrary runs every call on Dispatchers.IO. */
@Dao
interface LibraryDao {
    @Upsert fun upsertWork(value: WorkEntity)
    @Upsert fun upsertSeason(value: SeasonEntity)
    @Upsert fun upsertSource(value: SourceMappingEntity)
    @Upsert fun upsertEpisodes(values: List<EpisodeEntity>)
    @Upsert fun upsertEntry(value: LibraryEntryEntity)
    @Upsert fun upsertProgress(value: EpisodeProgressEntity)
    @Upsert fun upsertMetadata(value: LibraryMetadataEntity)

    @Query("SELECT * FROM library_entries WHERE sourceMappingId = :id")
    fun entry(id: String): LibraryEntryEntity?
    @Query("SELECT * FROM source_mappings WHERE id = :id")
    fun source(id: String): SourceMappingEntity?
    @Query("SELECT * FROM episode_progress WHERE episodeId = :id")
    fun progress(id: String): EpisodeProgressEntity?
    @Query("SELECT value FROM library_metadata WHERE `key` = :key")
    fun metadata(key: String): String?
    @Query("SELECT * FROM library_entries ORDER BY updatedAt DESC, sourceMappingId")
    fun entries(): List<LibraryEntryEntity>
    @Query("SELECT * FROM source_mappings")
    fun sources(): List<SourceMappingEntity>
    @Query("SELECT * FROM episode_progress WHERE sourceMappingId = :id")
    fun progressForSource(id: String): List<EpisodeProgressEntity>
    @Query("DELETE FROM episode_progress WHERE episodeId = :id")
    fun deleteProgress(id: String)
    @Query("DELETE FROM episodes WHERE sourceMappingId = :id AND seasonId = :seasonId")
    fun deleteEpisodes(id: String, seasonId: String)
    @Query("SELECT * FROM episode_progress")
    fun progressRecords(): List<EpisodeProgressEntity>
    @Query("DELETE FROM episode_progress")
    fun deleteProgress()
    @Query("UPDATE library_entries SET historyAt = NULL, lastEpisodeIndex = 0")
    fun clearHistory()
}
