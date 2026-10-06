package com.weeklight

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateGridTest {
    @Test
    fun pagesByVisibleWeeks() {
        val day = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2026, 12, 27), DateGrid.dates(day, 2, 0, Locale.US).first())
        assertEquals(LocalDate.of(2027, 1, 10), DateGrid.dates(day, 2, 1, Locale.US).first())
        assertEquals(LocalDate.of(2026, 12, 13), DateGrid.dates(day, 2, -1, Locale.US).first())
        assertEquals(56, DateGrid.dates(day, 8, 0, Locale.US).size)
    }

    @Test
    fun usesLocaleFirstDayAndLabels() {
        val day = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2026, 12, 27), DateGrid.dates(day, 1, 0, Locale.US).first())
        assertEquals(LocalDate.of(2026, 12, 28), DateGrid.dates(day, 1, 0, Locale.UK).first())
        assertEquals("Su", DateGrid.weekdayLabels(Locale.US).first())
        assertEquals("Mo", DateGrid.weekdayLabels(Locale.UK).first())
        assertTrue(DateGrid.weekdayLabels(Locale.UK).size == 7)
    }
}

