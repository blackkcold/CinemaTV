package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class SourceResponseIdentityTest {
    private val source = TvSource("device-b-key", "Source", "https://source.example.test/api")

    @Test fun cachedKeyFromAnotherClientRebindsToTheRequestedUrl() {
        assertTrue(SourceResponseIdentity.matches(source, "device-a-key", source.url))
        assertEquals(source, SourceResponseIdentity.resolve(listOf(source), "device-a-key", source.url))
    }

    @Test fun matchingKeyNeverOverridesAnExplicitDifferentUrl() {
        assertFalse(SourceResponseIdentity.matches(source, source.key, "https://wrong.example.test/api"))
        assertNull(SourceResponseIdentity.resolve(listOf(source), source.key, "https://wrong.example.test/api"))
    }

    @Test fun oldResponsesWithoutUrlStillRequireTheirSuppliedKeyToMatch() {
        assertTrue(SourceResponseIdentity.matches(source, source.key, null))
        assertFalse(SourceResponseIdentity.matches(source, "other", "null"))
        assertEquals(source, SourceResponseIdentity.resolve(listOf(source), source.key, ""))
    }
}
