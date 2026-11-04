package dev.cinematv.data

import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    @Test fun matchesCanonicalTitlesAcrossPunctuationAndWidth() {
        assertTrue(MediaIdentity.isSameWork("你好，世界", "2025", "你好 世界", "2025"))
        assertTrue(MediaIdentity.isSameWork("ＡＢＣ", "", "ABC", "2024"))
    }

    @Test fun blocksDifferentYearsOrNames() {
        assertFalse(MediaIdentity.isSameWork("Film", "1997", "Film", "2025"))
        assertFalse(MediaIdentity.isSameWork("Film", "2025", "Film 2", "2025"))
        assertFalse(MediaIdentity.isSameWork("", "", "Film", "2025"))
    }
}
