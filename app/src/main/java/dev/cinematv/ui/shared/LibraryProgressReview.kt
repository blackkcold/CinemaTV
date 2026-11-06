package dev.cinematv.ui.shared

import dev.cinematv.data.MediaDomainMapper
import dev.cinematv.data.MediaItem
import dev.cinematv.data.library.LibrarySnapshot
import dev.cinematv.data.library.QuarantineReason
import dev.cinematv.data.usableMetadata

/** A read-only review never transfers old numeric positions into a different playlist. */
data class LibraryProgressReview(
    val id: String,
    val item: MediaItem?,
    val oldEpisodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val reason: QuarantineReason
) {
    val reasonText: String get() = when (reason) {
        QuarantineReason.LEGACY_POSITION_ONLY -> "旧记录只保存了当时的集数位置，无法确认当前对应的剧集。"
        QuarantineReason.MISSING_OR_AMBIGUOUS_IDENTITY -> "当时的片单缺少明确的剧集身份，或存在重复身份。"
        QuarantineReason.NOT_IN_CURRENT_PLAYLIST -> "原剧集不在当前片单中，或来源中的剧集身份已变化。"
    }
}

object LibraryProgressReviews {
    fun project(snapshot: LibrarySnapshot, nowMs: Long = System.currentTimeMillis()): List<LibraryProgressReview> {
        val records = snapshot.records.associateBy { MediaDomainMapper.entryId(it.item) }
        return snapshot.quarantinedProgress.sortedByDescending { it.progress.updatedAt }.map { record ->
            val progress = record.progress
            LibraryProgressReview(progress.episodeId, records[progress.sourceMappingId]?.item?.usableMetadata(nowMs),
                progress.episodeIndex, progress.positionMs, progress.durationMs, progress.updatedAt, record.reason)
        }
    }
}
