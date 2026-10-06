package com.weeklight

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSettingsStoreTest {
    @Test
    fun normalizesWeekCountToSupportedRange() {
        assertEquals(1, normalizeWeeks(-1))
        assertEquals(4, normalizeWeeks(4))
        assertEquals(8, normalizeWeeks(99))
    }
}

