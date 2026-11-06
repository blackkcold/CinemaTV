package dev.cinematv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery is the only content source a LibreTV-only install has, so its cache identity has to
 * be exact: a stale rail from another server or another source set would be wrong, not slow.
 */
class HomeDiscoveryStoreTest {

    private fun source(key: String, enabled: Boolean = true) =
        TvSource(key, key, "https://$key.example/api.php/provide/vod", enabled = enabled)

    @Test fun cacheKeyIsStableAndOrderIndependent() {
        val a = HomeDiscoveryStore.keyFor("http://lan:8899", 3, "星际", listOf(source("a"), source("b")))
        val b = HomeDiscoveryStore.keyFor("http://lan:8899", 3, "星际", listOf(source("b"), source("a")))
        assertEquals(a, b)
        assertEquals(32, a.length)
    }

    @Test fun cacheKeySeparatesServerSessionQueryAndSourceSet() {
        val base = HomeDiscoveryStore.keyFor("http://lan:8899", 3, "星际", listOf(source("a")))
        assertNotEquals(base, HomeDiscoveryStore.keyFor("http://other:8899", 3, "星际", listOf(source("a"))))
        assertNotEquals(base, HomeDiscoveryStore.keyFor("http://lan:8899", 4, "星际", listOf(source("a"))))
        assertNotEquals(base, HomeDiscoveryStore.keyFor("http://lan:8899", 3, "悬疑", listOf(source("a"))))
        assertNotEquals(base, HomeDiscoveryStore.keyFor("http://lan:8899", 3, "星际", listOf(source("b"))))
    }

    @Test fun disabledSourcesDoNotChangeTheCacheIdentity() {
        val enabled = HomeDiscoveryStore.keyFor("http://lan:8899", 3, "星际",
            listOf(source("a"), source("b", enabled = false)))
        val onlyA = HomeDiscoveryStore.keyFor("http://lan:8899", 3, "星际", listOf(source("a")))
        assertEquals(onlyA, enabled)
    }

    @Test fun rotationWalksEveryQueryAndWrapsAround() {
        val seen = HomeDiscoveryStore.QUERIES.indices.map { HomeDiscoveryStore.queryAt(7, it) }
        assertEquals(HomeDiscoveryStore.QUERIES.size, seen.distinct().size)
        assertEquals(seen.first(), HomeDiscoveryStore.queryAt(7, HomeDiscoveryStore.QUERIES.size))
    }

    @Test fun queriesAreShortTitleTermsNotCategoryClaims() {
        assertTrue(HomeDiscoveryStore.QUERIES.isNotEmpty())
        HomeDiscoveryStore.QUERIES.forEach { query ->
            assertEquals("query must stay a short search term: $query", 2, query.length)
            assertTrue("query must not be a category label: $query",
                query !in setOf("电影", "电视剧", "动作片", "喜剧片", "科幻片"))
        }
    }
}
