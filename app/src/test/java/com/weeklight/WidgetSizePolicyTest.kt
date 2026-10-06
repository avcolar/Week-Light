package com.weeklight

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizePolicyTest {
    @Test
    fun twoByTwoUsesScrollableAgendaForAnyConfiguredWeekCount() {
        assertEquals(0, WidgetSizePolicy.maxWeeks(110, 110))
        assertEquals(4, WidgetSizePolicy.visibleWeeks(110, 110, 4))
        assertEquals(8, WidgetSizePolicy.visibleWeeks(110, 110, 8))
        assertEquals(3, WidgetSizePolicy.visibleWeeks(356, 268, 4))
        assertEquals(4, WidgetSizePolicy.visibleWeeks(356, 316, 4))
    }

    @Test
    fun widthAndHeightMustLeaveRoomForTheScrollableAgenda() {
        assertEquals(0, WidgetSizePolicy.maxWeeks(219, 1000))
        assertEquals(0, WidgetSizePolicy.maxWeeks(220, 171))
        assertEquals(1, WidgetSizePolicy.maxWeeks(220, 172))
        assertEquals(3, WidgetSizePolicy.maxWeeks(356, 268))
        assertEquals(4, WidgetSizePolicy.maxWeeks(356, 316))
        assertEquals(8, WidgetSizePolicy.maxWeeks(500, 1000))
    }
}