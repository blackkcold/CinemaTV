package dev.cinematv.data

import dev.cinematv.ui.shared.presentationKey
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class MediaDomainMapperCacheTest {
    private val sourced = MediaItem(
        id = "vod-1",
        title = "Example",
        source = TvSource("source-a", "Source A", "https://example.org/api"),
    )

    @Test
    fun entryIdIsReusedForTheSameItem() {
        val first = MediaDomainMapper.entryId(sourced)
        assertSame(first, MediaDomainMapper.entryId(sourced))
    }

    @Test
    fun presentationKeyUsesTheSameMemo() {
        val first = sourced.presentationKey
        assertSame(first, MediaDomainMapper.entryId(sourced))
        assertSame(first, sourced.presentationKey)
    }

    @Test
    fun copyRecomputesWithoutMutatingTheOriginal() {
        val original = MediaDomainMapper.entryId(sourced)
        val copy = sourced.copy(id = "vod-2")
        val changed = MediaDomainMapper.entryId(copy)
        assertNotSame(original, changed)
        assertSame(original, MediaDomainMapper.entryId(sourced))
    }
}
