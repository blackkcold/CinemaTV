package dev.cinematv.ui.icons

import org.junit.Assert.*
import org.junit.Test

class CinemaIconTest {
    @Test fun onlyBackMirrorsInRtl() {
        assertEquals(listOf(CinemaSymbol.Back), CinemaSymbol.entries.filter { it.autoMirrorRtl })
    }

    @Test fun lightSurfacesAndLowCostUseApprovedMonochrome() {
        assertEquals(CinemaIconTier.Compact, cinemaIconTier(onLightSurface = false, lowCost = false))
        assertEquals(CinemaIconTier.Monochrome, cinemaIconTier(onLightSurface = true, lowCost = false))
        assertEquals(CinemaIconTier.Monochrome, cinemaIconTier(onLightSurface = false, lowCost = true))
    }

    @Test fun selectionChangesPaletteWithoutChangingTheSymbol() {
        CinemaSymbol.entries.forEach { symbol ->
            assertEquals(symbol.defaultResource, cinemaIconResource(symbol, false, CinemaIconTier.Compact))
            assertEquals(symbol.selectedResource, cinemaIconResource(symbol, true, CinemaIconTier.Compact))
            assertNotEquals(symbol.defaultResource, symbol.selectedResource)
            assertEquals(symbol.monochromeResource, cinemaIconResource(symbol, false, CinemaIconTier.Monochrome))
            assertEquals(symbol.monochromeResource, cinemaIconResource(symbol, true, CinemaIconTier.Monochrome))
        }
    }
}
