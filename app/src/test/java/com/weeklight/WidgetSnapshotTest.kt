package com.weeklight

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    private val first = LocalDate.of(2026, 9, 21)
    private val key = RenderKey(setOf(2L, 7L), first, first.plusDays(13), "Asia/Shanghai", true)

    @Test fun snapshotMatchesOnlyItsOwnRenderKey() {
        val snapshot = DotSnapshot(key, mapOf(first to 3), 123L)
        assertTrue(snapshot.matches(key))
        assertEquals(3, snapshot.counts[first])
        assertFalse(snapshot.matches(key.copy(calendarIds = setOf(2L))))
        assertFalse(snapshot.matches(key.copy(first = first.plusDays(7))))
        assertFalse(snapshot.matches(key.copy(zoneId = "UTC")))
        assertFalse(snapshot.matches(key.copy(permissionGranted = false)))
    }
}
