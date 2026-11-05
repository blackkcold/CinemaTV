package dev.cinematv

import dev.cinematv.data.MediaItem
import dev.cinematv.ui.shared.cardMetadataLine
import dev.cinematv.ui.shared.metadataLine
import org.junit.Assert.*
import org.junit.Test

class CardMetadataPolicyTest {
    @Test fun narrowCardPrioritizesNumericScoreTogetherWithItsRealSource() {
        val item = MediaItem("1", "A", year = "2025", category = "剧情", rating = "8.0", ratingSource = "Synthetic", sourceCount = 2)
        assertEquals("★ 8.0 Synthetic · 2025 · 剧情 · 2 个片源", item.cardMetadataLine())
        assertEquals("2025 · 剧情 · ★ Synthetic 8.0 · 2 个片源", item.metadataLine())
        assertEquals("★ 8.0 来源评分 · 2025 · 剧情 · 2 个片源", item.copy(ratingSource = "").cardMetadataLine())
    }
    @Test fun compactCardCannotReviveUnlicensedTransportOrInvalidScores() {
        val item = MediaItem("1", "A", year = "2025", category = "剧情", rating = "8.0")
        for (hidden in listOf(item.copy(ratingSource = "LibreTV"), item.copy(ratingSource = "Douban"),
            item.copy(rating = "NaN"), item.copy(rating = "11"), item.copy(catalogLicenseExpiresAt = 1L))) {
            assertEquals("2025 · 剧情", hidden.cardMetadataLine())
        }
    }
    @Test fun unscoredCardMetadataRetainsExistingOrderAndEmptyFallback() {
        val item = MediaItem("1", "A", year = "2026", category = "Drama")
        assertEquals(item.metadataLine(), item.cardMetadataLine())
        assertEquals("", item.copy(year = "", category = "").cardMetadataLine())
    }

}
