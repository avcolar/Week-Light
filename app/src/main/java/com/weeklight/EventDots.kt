package com.weeklight

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal enum class EventKind {
    HOLIDAY,
    EVENT,
    TASK,
}

internal data class EventInstance(
    val calendarId: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val eventId: Long = 0,
    val title: String = "",
)

internal data class AgendaEntry(
    val date: LocalDate,
    val title: String,
    val eventId: Long,
    val beginMillis: Long,
    val allDay: Boolean,
    val kind: EventKind = EventKind.EVENT,
)

internal data class AgendaSummary(val rows: List<AgendaEntry>, val overflowCount: Int)

internal fun mergeTaskDots(
    calendarDots: Map<LocalDate, List<EventKind>>,
    tasks: List<TaskItem>,
): Map<LocalDate, List<EventKind>> {
    val result = calendarDots.toMutableMap()
    tasks.groupBy(TaskItem::due).forEach { (date, dayTasks) ->
        val kinds = result[date].orEmpty().take(3)
        val withTasks = kinds + List(dayTasks.size.coerceAtMost(3)) { EventKind.TASK }
        result[date] = if (kinds.size == 3 && EventKind.TASK !in kinds) kinds.take(2) + EventKind.TASK else withTasks.take(3)
    }
    return result
}

internal fun mergeTaskAgenda(calendarRows: List<AgendaEntry>, tasks: List<TaskItem>): List<AgendaEntry> =
    (calendarRows + tasks.map { task ->
        AgendaEntry(task.due, task.title, task.id.hashCode().toLong(), 0L, true, EventKind.TASK)
    }).sortedWith(compareBy<AgendaEntry> { it.date }
        .thenBy { !it.allDay }
        .thenBy { it.beginMillis }
        .thenBy { it.eventId })

internal fun usableCalendarIds(selected: Set<Long>, available: Set<Long>): Set<Long> =
    selected.intersect(available)

internal fun eventKind(instance: EventInstance, taskCalendarIds: Set<Long>): EventKind =
    when {
        instance.calendarId in taskCalendarIds -> EventKind.TASK
        instance.allDay -> EventKind.HOLIDAY
        else -> EventKind.EVENT
    }

internal fun eventDotKinds(
    instances: List<EventInstance>,
    selectedIds: Set<Long>,
    taskCalendarIds: Set<Long>,
    first: LocalDate,
    last: LocalDate,
    zone: ZoneId,
): Map<LocalDate, List<EventKind>> {
    val kinds = mutableMapOf<LocalDate, MutableList<EventKind>>()
    if (selectedIds.isEmpty() || last.isBefore(first)) return emptyMap()
    instances.forEach { instance ->
        if (instance.calendarId !in selectedIds) return@forEach
        forEachVisibleDay(instance, first, last, zone) { day ->
            val dayKinds = kinds.getOrPut(day) { mutableListOf() }
            if (dayKinds.size < 3) dayKinds += eventKind(instance, taskCalendarIds)
        }
    }
    return kinds
}

internal fun eventDotCounts(
    instances: List<EventInstance>,
    selectedIds: Set<Long>,
    first: LocalDate,
    last: LocalDate,
    zone: ZoneId,
): Map<LocalDate, Int> =
    eventDotKinds(instances, selectedIds, emptySet(), first, last, zone)
        .mapValues { (_, kinds) -> kinds.size }

internal fun allAgendaEntries(
    instances: List<EventInstance>,
    selectedIds: Set<Long>,
    taskCalendarIds: Set<Long>,
    first: LocalDate,
    last: LocalDate,
    zone: ZoneId,
): List<AgendaEntry> {
    if (last.isBefore(first)) return emptyList()
    val entries = mutableListOf<AgendaEntry>()
    instances.forEach { instance ->
        if (instance.calendarId !in selectedIds) return@forEach
        forEachVisibleDay(instance, first, last, zone) { day ->
            entries += AgendaEntry(
                date = day,
                title = instance.title,
                eventId = instance.eventId,
                beginMillis = instance.beginMillis,
                allDay = instance.allDay,
                kind = eventKind(instance, taskCalendarIds),
            )
        }
    }
    return entries.sortedWith(
        compareBy<AgendaEntry> { it.date }
            .thenBy { !it.allDay }
            .thenBy { it.beginMillis }
            .thenBy { it.eventId },
    )
}

internal fun agendaEntries(
    instances: List<EventInstance>,
    selectedIds: Set<Long>,
    first: LocalDate,
    last: LocalDate,
    zone: ZoneId,
    limit: Int,
): AgendaSummary {
    val rows = allAgendaEntries(instances, selectedIds, emptySet(), first, last, zone)
    val safeLimit = limit.coerceAtLeast(0)
    return AgendaSummary(rows.take(safeLimit), (rows.size - safeLimit).coerceAtLeast(0))
}

private inline fun forEachVisibleDay(
    instance: EventInstance,
    first: LocalDate,
    last: LocalDate,
    zone: ZoneId,
    visit: (LocalDate) -> Unit,
) {
    if (instance.endMillis <= instance.beginMillis) return
    val eventZone = if (instance.allDay) ZoneOffset.UTC else zone
    val start = Instant.ofEpochMilli(instance.beginMillis).atZone(eventZone).toLocalDate()
    val end = Instant.ofEpochMilli(instance.endMillis - 1).atZone(eventZone).toLocalDate()
    var day = maxOf(start, first)
    val finalDay = minOf(end, last)
    while (!day.isAfter(finalDay)) {
        visit(day)
        day = day.plusDays(1)
    }
}
