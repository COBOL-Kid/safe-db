package com.safedb.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePaletteTest {
    @Test
    fun idsAreStableUniqueAndLowercase() {
        val ids = ThemePalette.entries.map { it.id }

        assertEquals(ids.size, ids.distinct().size)
        assertEquals(ids, ids.map(String::lowercase))
        assertEquals(ThemePalette.ControlBlue, ThemePalette.DEFAULT)
    }

    @Test
    fun persistedIdsResolveWithSafeFallback() {
        assertEquals(ThemePalette.SignalTeal, ThemePalette.fromId(" SIGNAL-TEAL "))
        assertEquals(ThemePalette.Oxide, ThemePalette.fromId("oxide"))
        assertEquals(ThemePalette.DEFAULT, ThemePalette.fromId("unknown"))
        assertEquals(ThemePalette.DEFAULT, ThemePalette.fromId(null))
    }
}
