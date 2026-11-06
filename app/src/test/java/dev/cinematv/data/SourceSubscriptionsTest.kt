package dev.cinematv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceSubscriptionsTest {
    private fun payload(vod: List<Pair<String, String>> = listOf("VOD" to "https://vod.example.test/api"),
        live: List<Pair<String, String>> = emptyList()) = JSONObject()
        .put("sources", JSONArray(vod.map { JSONObject().put("name", it.first).put("url", it.second) }))
        .put("liveSources", JSONArray(live.map { JSONObject().put("name", it.first).put("url", it.second) }))

    @Test fun advertisedSubscriptionsWorkWithoutAnyDefaultMediaSources() {
        val found = SourceSubscriptionContract.advertised(JSONArray("""[
          {"url":"https://lists.example.test/list.json","name":"Default","refreshDays":"10"},
          "https://lists.example.test/list.json",
          {"url":"file:///no","name":"Invalid"},
          {"url":"https://user:secret@lists.example.test/private"}
        ]"""))
        assertEquals(1, found.size)
        assertEquals("https://lists.example.test/list.json", found.single().url)
        assertEquals(10, found.single().refreshDays)
    }

    @Test fun tvboxIsAlreadyNormalizedByUpstreamAndSkippedReasonsSurvive() {
        val response = payload(live = listOf("Live" to "https://live.example.test/list.m3u"))
            .put("stats", JSONObject("""{"format":"tvbox","skipped":3,"skippedByReason":{"spider":1,"xml":1,"nonM3uLive":1},"skippedSamples":["Synthetic spider"],"truncated":2}"""))
        val decoded = SourceSubscriptionContract.decode(response)
        assertEquals(1, decoded.sources.size)
        assertEquals(1, decoded.liveSources.size)
        assertEquals(3, decoded.stats.skipped)
        assertEquals(2, decoded.stats.truncated)
        assertTrue(decoded.stats.description().contains("Spider"))
        assertTrue(decoded.sources.single().key.startsWith("subscription_vod_"))
        assertFalse(decoded.sources.single().key.startsWith("custom_"))
    }

    @Test fun duplicateAndInvalidEntriesDoNotDisplaceValidSources() {
        val response = payload(listOf("One" to "https://vod.example.test/api", "Duplicate" to "https://vod.example.test/api",
            "Bad" to "javascript:alert(1)", "Credentials" to "https://user:secret@vod.example.test/api"),
            listOf("Live" to "https://live.example.test/list.m3u"))
        response.getJSONArray("liveSources").getJSONObject(0).put("epg", "file:///invalid")
        val decoded = SourceSubscriptionContract.decode(response)
        assertEquals(1, decoded.sources.size)
        assertEquals(1, decoded.stats.duplicates)
        assertEquals(2, decoded.stats.invalidItems)
        assertEquals(1, decoded.stats.invalidOptionalUrls)
        assertEquals("", decoded.liveSources.single().epg)
    }

    @Test fun refreshDiffIsUrlBasedAndStorageRetainsOwnershipAndStats() {
        val before = SourceSubscriptionContract.decode(payload(listOf("Old name" to "https://vod.example.test/one",
            "Removed" to "https://vod.example.test/two")))
        val after = SourceSubscriptionContract.decode(payload(listOf("New name" to "https://vod.example.test/one",
            "Added" to "https://vod.example.test/three")))
        val changes = SourceSubscriptionDiff.between(before, after)
        assertEquals(SubscriptionSourceChanges(1, 1, 1), changes.vod)
        assertEquals(before.sources.first().key, after.sources.first().key)
        val record = SourceSubscriptionRecord(SourceSubscription("https://lists.example.test/list.json", "List", 10), after,
            updatedAt = 1_000L, revision = "reviewed-revision")
        assertEquals(listOf(record), SourceSubscriptionContract.decodeRecords(SourceSubscriptionContract.encodeRecords(listOf(record))))
        assertFalse(record.refreshDue(1_001))
        assertTrue(record.refreshDue(1_000 + 10 * 86_400_000L))
    }

    @Test fun sourceAndSubscriptionLimitsAreBounded() {
        val response = payload((0..100).map { "V$it" to "https://vod.example.test/$it" },
            (0..50).map { "L$it" to "https://live.example.test/$it.m3u" })
        val decoded = SourceSubscriptionContract.decode(response)
        assertEquals(100, decoded.sources.size)
        assertEquals(50, decoded.liveSources.size)
        assertEquals(2, decoded.stats.truncated)
    }

    @Test fun urlNormalizationDoesNotTrimMeaningfulQueryTokens() {
        val subscription = SourceSubscriptionContract.normalize(SourceSubscription("https://lists.example.test/data?token=abc/"))
        assertEquals("https://lists.example.test/data?token=abc/", subscription.url)
        assertEquals("https://lists.example.test/feed/", SourceSubscriptionContract.normalize(SourceSubscription("https://lists.example.test/feed/")).url)
        assertEquals("https://lists.example.test/feed?token=abc?", SourceSubscriptionContract.normalize(SourceSubscription("https://lists.example.test/feed?token=abc?")).url)
        assertNotEquals(SourceSubscriptionContract.normalize(SourceSubscription("https://lists.example.test/feed")),
            SourceSubscriptionContract.normalize(SourceSubscription("https://lists.example.test/feed/")))
    }

    @Test fun invalidOrEmptyOutputFailsInsteadOfBecomingAnEmptyRefresh() {
        listOf(JSONObject(), JSONObject().put("sources", "not-an-array"), payload(emptyList())).forEach { response ->
            try { SourceSubscriptionContract.decode(response); fail("Invalid source list was accepted") }
            catch (_: LibreTvProtocolException) { }
        }
    }

    @Test fun corruptPersistenceCannotSilentlyBecomeAnEmptyOverwritableStore() {
        try { SourceSubscriptionContract.decodeRecords("{\"schema\":999}"); fail("Unknown store accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(emptyList<SourceSubscriptionRecord>(), SourceSubscriptionContract.decodeRecords(null))
    }
}
