package com.weeklight

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class EventDotsTest {
    private val zone = ZoneId.of("America/New_York")

    private fun millis(date: String, time: String, zone: ZoneId = this.zone): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Test fun selectedCalendarsAndStaleIds() {
        assertEquals(setOf(2L), usableCalendarIds(setOf(1L, 2L), setOf(2L, 3L)))
        val events = listOf(
            EventInstance(1, millis("2026-12-31", "09:00"), millis("2026-12-31", "10:00"), false),
            EventInstance(2, millis("2026-12-31", "11:00"), millis("2026-12-31", "12:00"), false),
        )
        val day = LocalDate.parse("2026-12-31")
        assertEquals(mapOf(day to 1), eventDotCounts(events, setOf(2L), day, day, zone))
        assertEquals(emptyMap<LocalDate, Int>(), eventDotCounts(events, emptySet(), day, day, zone))
    }

    @Test fun timedEventsRespectMidnightAndMonthYearBoundaries() {
        val events = listOf(
            EventInstance(1, millis("2026-12-31", "23:00"), millis("2027-01-01", "00:00"), false),
            EventInstance(1, millis("2027-01-01", "22:00"), millis("2027-01-03", "02:00"), false),
        )
        assertEquals(mapOf(
            LocalDate.parse("2026-12-31") to 1,
            LocalDate.parse("2027-01-01") to 1,
            LocalDate.parse("2027-01-02") to 1,
            LocalDate.parse("2027-01-03") to 1,
        ), eventDotCounts(events, setOf(1), LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-03"), zone))
    }

    @Test fun allDayUsesUtcDatesRegardlessOfDeviceZone() {
        val event = EventInstance(1, millis("2026-01-01", "00:00", ZoneOffset.UTC), millis("2026-01-03", "00:00", ZoneOffset.UTC), true)
        assertEquals(mapOf(LocalDate.parse("2026-01-01") to 1, LocalDate.parse("2026-01-02") to 1),
            eventDotCounts(listOf(event), setOf(1), LocalDate.parse("2025-12-31"), LocalDate.parse("2026-01-03"), ZoneId.of("America/Los_Angeles")))
    }

    @Test fun dstAndRecurringInstancesAreCountedSeparatelyAndCapped() {
        val event = EventInstance(1, millis("2026-03-07", "23:00"), millis("2026-03-09", "01:00"), false)
        val recurring = EventInstance(1, millis("2026-03-08", "10:00"), millis("2026-03-08", "11:00"), false)
        assertEquals(mapOf(
            LocalDate.parse("2026-03-07") to 1,
            LocalDate.parse("2026-03-08") to 3,
            LocalDate.parse("2026-03-09") to 1,
        ), eventDotCounts(listOf(event, recurring, recurring, recurring), setOf(1), LocalDate.parse("2026-03-07"), LocalDate.parse("2026-03-09"), zone))
    }

    @Test fun agendaSortsDatedTitlesAndReportsOverflow() {
        val first = LocalDate.parse("2026-12-31")
        val last = first.plusDays(1)
        val entries = listOf(
            EventInstance(1, millis("2027-01-01", "13:00"), millis("2027-01-01", "14:00"), false, 3, "Task-like entry"),
            EventInstance(1, millis("2026-12-31", "09:00"), millis("2026-12-31", "10:00"), false, 2, "Meeting"),
            EventInstance(1, millis("2026-12-31", "00:00", ZoneOffset.UTC), millis("2027-01-01", "00:00", ZoneOffset.UTC), true, 1, "Holiday"),
            EventInstance(2, millis("2026-12-31", "08:00"), millis("2026-12-31", "09:00"), false, 4, "Other calendar"),
        )
        val agenda = agendaEntries(entries, setOf(1L), first, last, zone, 2)
        assertEquals(listOf("Holiday", "Meeting"), agenda.rows.map { it.title })
        assertEquals(listOf(first, first), agenda.rows.map { it.date })
        assertEquals(1, agenda.overflowCount)
        assertEquals(mapOf(first to 2, last to 1), eventDotCounts(entries, setOf(1L), first, last, zone))
    }

    @Test fun agendaDatesMultidayEntriesAcrossDstAndExcludesMidnightEnd() {
        val first = LocalDate.parse("2026-03-07")
        val last = first.plusDays(2)
        val entry = EventInstance(1, millis("2026-03-07", "23:00"), millis("2026-03-09", "00:00"), false, 9, "Overnight")
        assertEquals(listOf(first, first.plusDays(1)), agendaEntries(listOf(entry), setOf(1L), first, last, zone, 3).rows.map { it.date })
    }

    @Test
    fun classifiesKindsWithoutGuessingTitles() {
        val day = LocalDate.parse("2026-10-01")
        val entries = listOf(
            EventInstance(1, millis("2026-10-01", "00:00", ZoneOffset.UTC), millis("2026-10-02", "00:00", ZoneOffset.UTC), true, 1, "Company event"),
            EventInstance(1, millis("2026-10-01", "09:00"), millis("2026-10-01", "10:00"), false, 2, "Holiday-like title"),
            EventInstance(2, millis("2026-10-01", "11:00"), millis("2026-10-01", "12:00"), false, 3, "Task-like title"),
        )
        val rows = allAgendaEntries(entries, setOf(1L, 2L), setOf(2L), day, day, zone)
        assertEquals(listOf(EventKind.HOLIDAY, EventKind.EVENT, EventKind.TASK), rows.map { it.kind })
        assertEquals(3, eventDotKinds(entries, setOf(1L, 2L), setOf(2L), day, day, zone)[day]?.size)
    }
    @Test fun allMixedAgendaRowsStaySortedAndUncapped() {
        val first = LocalDate.parse("2026-10-01")
        val next = first.plusDays(1)
        val entries = listOf(
            EventInstance(2, millis("2026-10-01", "10:00"), millis("2026-10-01", "11:00"), false, 4, "Task"),
            EventInstance(1, millis("2026-10-01", "09:00"), millis("2026-10-01", "10:00"), false, 3, "Meeting"),
            EventInstance(2, millis("2026-10-01", "00:00", ZoneOffset.UTC), millis("2026-10-02", "00:00", ZoneOffset.UTC), true, 2, "All-day task"),
            EventInstance(1, millis("2026-10-01", "00:00", ZoneOffset.UTC), millis("2026-10-02", "00:00", ZoneOffset.UTC), true, 1, "Holiday"),
            EventInstance(2, millis("2026-10-02", "08:00"), millis("2026-10-02", "09:00"), false, 5, "Next task"),
            EventInstance(3, millis("2026-10-01", "08:00"), millis("2026-10-01", "09:00"), false, 6, "Unselected"),
        )
        val rows = allAgendaEntries(entries, setOf(1L, 2L), setOf(2L), first, next, zone)
        assertEquals(listOf("Holiday", "All-day task", "Meeting", "Task", "Next task"), rows.map { it.title })
        assertEquals(listOf(EventKind.HOLIDAY, EventKind.TASK, EventKind.EVENT, EventKind.TASK, EventKind.TASK), rows.map { it.kind })
        assertEquals(listOf(first, first, first, first, next), rows.map { it.date })
    }
}
