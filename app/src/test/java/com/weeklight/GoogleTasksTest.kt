package com.weeklight

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTasksTest {
    private val day = LocalDate.parse("2026-10-01")

    @Test fun dueIsDateOnlyAcrossDeviceZones() {
        val original = TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "America/Los_Angeles").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
                assertEquals(day, taskDueDate("2026-10-01T00:00:00.000Z"))
            }
            assertEquals(null, taskDueDate(null))
            assertEquals(null, taskDueDate("2026-02-30T00:00:00Z"))
            assertEquals(null, taskDueDate("2026-10-01garbage"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun filtersNonVisibleAndUndatedTasks() {
        val rows = listOf(
            TaskRecord("open", "Open", "2026-10-01T00:00:00Z", "needsAction", false, false),
            TaskRecord("done", "Done", "2026-10-01T00:00:00Z", "completed", false, false),
            TaskRecord("deleted", "Deleted", "2026-10-01T00:00:00Z", "needsAction", true, false),
            TaskRecord("hidden", "Hidden", "2026-10-01T00:00:00Z", "needsAction", false, true),
            TaskRecord("undated", "Undated", null, "needsAction", false, false),
            TaskRecord("outside", "Outside", "2026-10-02T00:00:00Z", "needsAction", false, false),
        )
        assertEquals(listOf(TaskItem("open", "list-a", "Open", day)), visibleTasks(rows, "list-a", day, day))
    }

    @Test fun paginationUsesEveryPageAndRejectsLoops() {
        assertEquals(listOf(1, 2), collectPages { token ->
            if (token == null) TaskPage(listOf(1), "next") else TaskPage(listOf(2), null)
        })
        var rejected = false
        try {
            collectPages { TaskPage(emptyList<Int>(), "same") }
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun mergeKeepsCalendarRowsAndShowsTaskColorKind() {
        val event = AgendaEntry(day, "Meeting", 8, 10, false, EventKind.EVENT)
        val task = TaskItem("opaque-id", "remote-list", "Pay bill", day)
        val rows = mergeTaskAgenda(listOf(event), listOf(task))
        assertEquals(listOf("Pay bill", "Meeting"), rows.map { it.title })
        assertEquals(listOf(EventKind.TASK, EventKind.EVENT), rows.map { it.kind })
        assertEquals(listOf(EventKind.EVENT, EventKind.EVENT, EventKind.TASK),
            mergeTaskDots(mapOf(day to List(3) { EventKind.EVENT }), listOf(task))[day])
    }

    @Test fun widgetAccountAndListsAreIndependentOfLocalCalendarIds() {
        val first = WidgetSettings(selectedCalendarIds = setOf("1"), taskCalendarIds = setOf("1"),
            googleTaskAccount = "one@example.com", googleTaskListIds = setOf("opaque-list"))
        val second = WidgetSettings(selectedCalendarIds = setOf("2"), googleTaskAccount = "two@example.com",
            googleTaskListIds = setOf("other-list"))
        val snapshot = TaskSnapshot(first.googleTaskAccount, first.googleTaskListIds, day, day, emptyList(), TaskState.RECONNECT)
        assertTrue(snapshot.matches(first, day, day))
        assertFalse(snapshot.matches(second, day, day))
        assertEquals(setOf("1"), first.taskCalendarIds)
        assertEquals(setOf("opaque-list"), first.googleTaskListIds)
    }
}
