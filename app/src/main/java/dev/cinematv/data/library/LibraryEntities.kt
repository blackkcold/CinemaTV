package dev.cinematv.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "works")
data class WorkEntity(@PrimaryKey val id: String, val title: String, val kind: String, val year: String)

@Entity(tableName = "seasons", indices = [Index("workId")])
data class SeasonEntity(@PrimaryKey val id: String, val workId: String, val number: Int?, val title: String)

@Entity(tableName = "source_mappings", indices = [Index("workId")])
data class SourceMappingEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val sourceId: String,
    val sourceName: String,
    val sourceUrl: String,
    val providerItemId: String,
    /** Full backwards-compatible provider-neutral snapshot; codec lives in data/. */
    val itemJson: String
)

@Entity(tableName = "episodes", indices = [Index("seasonId"), Index("sourceMappingId")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seasonId: String,
    val sourceMappingId: String,
    val episodeIndex: Int,
    val title: String,
    val playbackUrl: String
)

@Entity(tableName = "library_entries", indices = [Index("historyAt"), Index("updatedAt")])
data class LibraryEntryEntity(
    @PrimaryKey val sourceMappingId: String,
    val status: String? = null,
    val favorite: Boolean = false,
    val updatedAt: Long = 0L,
    val historyAt: Long? = null,
    val lastEpisodeIndex: Int = 0
)

@Entity(tableName = "episode_progress", indices = [Index("sourceMappingId")])
data class EpisodeProgressEntity(
    @PrimaryKey val episodeId: String,
    val sourceMappingId: String,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

@Entity(tableName = "library_metadata")
data class LibraryMetadataEntity(@PrimaryKey val key: String, val value: String)
