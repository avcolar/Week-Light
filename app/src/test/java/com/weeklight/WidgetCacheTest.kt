package com.weeklight

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCacheTest {
    @Test
    fun renderKeyOnlyMatchesSameWidgetInputs() {
        val first = LocalDate.of(2026, 1, 1)
        val key = RenderKey(setOf(1L, 2L), first, first.plusDays(6), "Asia/Shanghai", true)
        val snapshot = DotSnapshot(key, mapOf(first to 2), 100L)

        assertTrue(snapshot.matches(key))
        assertFalse(snapshot.matches(key.copy(zoneId = "UTC")))
        assertFalse(snapshot.matches(key.copy(calendarIds = setOf(1L))))
        assertFalse(snapshot.matches(key.copy(permissionGranted = false)))
    }

}
