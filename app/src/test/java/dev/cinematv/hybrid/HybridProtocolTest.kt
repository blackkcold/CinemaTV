package dev.cinematv.hybrid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HybridProtocolTest {
    private fun request(method: String, params: JSONObject = JSONObject(), id: String = "session_1") =
        JSONObject().put("v", 1).put("id", id).put("method", method).put("params", params).toString()

    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("Expected rejection $code") }
        catch (failure: HybridRequestFailure) { assertEquals(code, failure.code) }
    }

    @Test fun exactHttpsTopFrameIsRequired() {
        assertTrue(HybridProtocol.trustedOrigin(HybridPolicy.ORIGIN, true))
        assertTrue(HybridProtocol.trustedOrigin("${HybridPolicy.ORIGIN}:443/", true))
        listOf("http://appassets.androidplatform.net", "https://appassets.androidplatform.net.evil.invalid",
            "https://evil.invalid@appassets.androidplatform.net", "https://appassets.androidplatform.net:444",
            "https://appassets.androidplatform.net/path", "https://appassets.androidplatform.net?query", "file:///android_asset/")
            .forEach { assertFalse(it, HybridProtocol.trustedOrigin(it, true)) }
        assertFalse(HybridProtocol.trustedOrigin(HybridPolicy.ORIGIN, false))
    }

    @Test fun bridgeAllowsOnlyKnownMethodsAndArguments() {
        val accepted = HybridProtocol.decode(request("playback.open", JSONObject().put("key", "opaque").put("episodeId", "opaque_episode")))
        assertEquals("playback.open", accepted.method)
        rejected("METHOD_NOT_ALLOWED") { HybridProtocol.decode(request("playUrl", JSONObject().put("url", "file:///private"))) }
        rejected("BAD_PARAMS") { HybridProtocol.decode(request("playback.open", JSONObject().put("key", "opaque").put("episodeId", "opaque_episode").put("url", "https://untrusted.invalid"))) }
        rejected("INVALID_MESSAGE") { HybridProtocol.decode(request("navigation.back", id = "x\n\"script")) }
        rejected("PROTOCOL_UNSUPPORTED") { HybridProtocol.decode(request("navigation.back").replace("\"v\":1", "\"v\":2")) }
    }

    @Test fun presentationActionsAcceptBoundedIntentAndProvenanceOnly() {
        assertEquals("playback.open", HybridProtocol.decode(request("playback.open", JSONObject()
            .put("key", "media_opaque").put("episodeId", "episode_opaque").put("action", "RESTART"))).method)
        assertEquals("search.filter", HybridProtocol.decode(request("search.filter", JSONObject()
            .put("type", "SERIES").put("year", "2025").put("minRating", 8)
            .put("ratingSource", "Douban").put("topOnly", false))).method)
        assertEquals("live.group", HybridProtocol.decode(request("live.group", JSONObject().put("id", "group_opaque"))).method)
        rejected("BAD_PARAMS") { HybridProtocol.decode(request("live.group", JSONObject()
            .put("id", "group_opaque").put("url", "https://untrusted.invalid"))) }
    }

    @Test fun requestLimitUsesUtf8AndRejectsExcessiveNesting() {
        rejected("REQUEST_TOO_LARGE") { HybridProtocol.decode(request("search.submit", JSONObject().put("query", "汉".repeat(12_000)))) }
        rejected("INVALID_MESSAGE") { HybridProtocol.decode("[".repeat(1000) + "0" + "]".repeat(1000)) }
        assertEquals("search.submit", HybridProtocol.decode(request("search.submit", JSONObject().put("query", "[\"quoted\"]"))).method)
    }

    @Test fun booleanAndIntegerParamsDoNotCoerceUntrustedStrings() {
        rejected("BAD_PARAMS") { HybridProtocol.boolean(JSONObject().put("reduced", "true"), "reduced") }
        rejected("BAD_PARAMS") { HybridProtocol.integer(JSONObject().put("page", "4"), "page", 100) }
        rejected("BAD_PARAMS") { HybridProtocol.integer(JSONObject().put("page", 1.5), "page", 100) }
        rejected("BAD_PARAMS") { HybridProtocol.integer(JSONObject().put("page", -1), "page", 100) }
        assertEquals(4, HybridProtocol.integer(JSONObject().put("page", 4), "page", 100))
    }

    @Test fun bookmarkMustBelongToCurrentRouteAndHaveBoundedCoordinates() {
        val mark = JSONObject().put("route", "route_x").put("focus", "card:movies:media_x")
            .put("scrolls", JSONObject().put("page", JSONArray(listOf(0, 500))))
        assertEquals(500, HybridProtocol.bookmark(mark, "route_x").getJSONObject("scrolls").getJSONArray("page").getInt(1))
        rejected("CONFLICT") { HybridProtocol.bookmark(mark, "route_y") }
        mark.getJSONObject("scrolls").put("page", JSONArray(listOf(0, 2_000_001)))
        rejected("BAD_PARAMS") { HybridProtocol.bookmark(mark, "route_x") }
        mark.put("scrolls", JSONObject().put("__proto__", JSONArray(listOf(0, 0))))
        rejected("BAD_PARAMS") { HybridProtocol.bookmark(mark, "route_x") }
    }

    @Test fun duplicateAndExcessConcurrentRequestsCannotExecute() {
        val gate = HybridRequestGate { 0L }
        repeat(8) { gate.enter("id_$it") }
        rejected("BRIDGE_BUSY") { gate.enter("id_9") }
        rejected("DUPLICATE_REQUEST") { gate.enter("id_0") }
        gate.leave()
        gate.enter("id_9")
        assertEquals(8, gate.inFlight)
    }

    @Test fun sustainedFocusFloodIsBoundedEvenWithImmediateReplies() {
        var time = 0L
        val gate = HybridRequestGate { time }
        repeat(60) { gate.enter("id_$it"); gate.leave() }
        rejected("BRIDGE_BUSY") { gate.enter("after_limit") }
        time = 1_000_000_000L
        gate.enter("after_window")
        assertEquals(1, gate.inFlight)
    }

    @Test fun opaqueKeysAreStableWithinSessionAndCannotRevealUrls() {
        val raw = "https://service.invalid/private?token=credential"
        val ids = HybridOpaqueIds("test_session")
        val key = ids.key("media", raw)
        assertTrue(key.matches(Regex("media_[0-9a-f]{32}")))
        assertEquals(key, ids.key("media", raw))
        assertNotEquals(key, ids.key("episode", raw))
        assertNotEquals(key, HybridOpaqueIds("other_session").key("media", raw))
        assertFalse(key.contains("token"))
    }

    @Test fun androidInstallationDoesNotImplyHybridEligibility() {
        assertFalse(HybridPolicy.evaluate(26, "140.0.0.0", true).enabled)
        assertFalse(HybridPolicy.evaluate(35, "110.0.0.0", true).enabled)
        assertFalse(HybridPolicy.evaluate(35, null, true).enabled)
        assertFalse(HybridPolicy.evaluate(35, "140.0.0.0", false).enabled)
        assertTrue(HybridPolicy.evaluate(29, "111.0.5563.0", true).enabled)
    }
}
