package com.weeklight

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSettingsTest {
    @Test
    fun selectedCalendarIdsAreIndependent() {
        val settings = WidgetSettings(weeks = 3, selectedCalendarIds = setOf("1", "2"))
        assertEquals(setOf("1", "2"), settings.selectedCalendarIds)
        assertEquals("system", settings.theme)
    }

    @Test
    fun taskAndDotColorSettingsHaveIndependentDefaults() {
        val settings = WidgetSettings(taskCalendarIds = setOf("2"), holidayColor = 1, eventColor = 2, taskColor = 3)
        assertEquals(setOf("2"), settings.taskCalendarIds)
        assertEquals(1, settings.holidayColor)
        assertEquals(2, settings.eventColor)
        assertEquals(3, settings.taskColor)
    }}

