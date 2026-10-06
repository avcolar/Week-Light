package com.weeklight

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal data class CalendarOption(val id: Long, val name: String, val account: String, val accountType: String)

internal object CalendarRepository {
    fun calendars(context: Context): List<CalendarOption> {
        val columns = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, columns,
            CalendarContract.Calendars.VISIBLE + " = ?", arrayOf("1"), null,
        ) ?: error("Calendar provider unavailable")
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(CalendarOption(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getString(3).orEmpty()))
            }
        }
    }

    fun instances(context: Context, selectedIds: Set<Long>, first: LocalDate, last: LocalDate, zone: ZoneId): List<EventInstance> {
        if (selectedIds.isEmpty()) return emptyList()
        // Include the UTC all-day boundary as well as local timed-event boundaries.
        val start = minOf(first.atStartOfDay(zone).toInstant().toEpochMilli(), first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val end = maxOf(last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
        )
        val ids = selectedIds.sorted()
        val selection = CalendarContract.Instances.CALENDAR_ID + " IN (" + ids.joinToString(",") { "?" } + ")"
        val cursor = context.contentResolver.query(uri, columns, selection, ids.map(Long::toString).toTypedArray(), CalendarContract.Instances.BEGIN + " ASC, " + CalendarContract.Instances.EVENT_ID + " ASC")
            ?: error("Calendar provider unavailable")
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(EventInstance(it.getLong(0), it.getLong(1), it.getLong(2), it.getInt(3) != 0, it.getLong(4), it.getString(5).orEmpty()))
            }
        }
    }
}
