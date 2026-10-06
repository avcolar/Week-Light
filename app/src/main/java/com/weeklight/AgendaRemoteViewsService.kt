package com.weeklight

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgendaRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        AgendaFactory(applicationContext, intent)
}

private class AgendaFactory(
    private val context: android.content.Context,
    private val sourceIntent: Intent,
) : RemoteViewsService.RemoteViewsFactory {
    private var settings = WidgetSettings()
    private var rows: List<AgendaEntry> = emptyList()
    private var renderToken = 0L

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = runBlocking(Dispatchers.IO) {
            val widgetId = sourceIntent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            renderToken = sourceIntent.getLongExtra(WeeklightWidgetProvider.EXTRA_RENDER_TOKEN, 0L)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return@runBlocking emptyList()
            settings = WidgetSettingsStore.read(context, widgetId)
            val first = sourceIntent.getStringExtra(WeeklightWidgetProvider.EXTRA_FIRST_DATE)
                ?.let(LocalDate::parse) ?: return@runBlocking emptyList()
            val last = sourceIntent.getStringExtra(WeeklightWidgetProvider.EXTRA_LAST_DATE)
                ?.let(LocalDate::parse) ?: return@runBlocking emptyList()
            val zone = ZoneId.systemDefault()
            val selectedIds = settings.selectedCalendarIds.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
            val calendarRows = if (selectedIds.isEmpty() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
            ) emptyList() else try {
                val usableIds = usableCalendarIds(selectedIds, CalendarRepository.calendars(context).mapTo(mutableSetOf()) { it.id })
                val instances = CalendarRepository.instances(context, usableIds, first, last, zone)
                allAgendaEntries(
                    instances = instances,
                    selectedIds = usableIds,
                    taskCalendarIds = settings.taskCalendarIds.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() },
                    first = first,
                    last = last,
                    zone = zone,
                )
            } catch (_: RuntimeException) {
                emptyList()
            }
            val tasks = GoogleTaskSnapshotStore.read(context, widgetId)
                ?.takeIf { it.matches(settings, first, last) }?.rows.orEmpty()
            mergeTaskAgenda(calendarRows, tasks)
        }
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val entry = rows.getOrNull(position) ?: return null
        val views = RemoteViews(context.packageName, R.layout.agenda_row)
        val locale = Locale.getDefault()
        val shortDate = entry.date.format(DateTimeFormatter.ofPattern("MMM d", locale))
        val fullDate = entry.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale))
        val title = entry.title.ifBlank { context.getString(R.string.agenda_untitled) }
        val kind = kindLabel(entry.kind)
        views.setTextViewText(R.id.agenda_text, context.getString(R.string.agenda_entry, shortDate, title))
        views.setContentDescription(
            R.id.agenda_row,
            context.getString(R.string.agenda_accessibility, fullDate, title, kind),
        )
        views.setTextColor(R.id.kind_dot, colorFor(entry.kind))
        views.setOnClickFillInIntent(
            R.id.agenda_row,
            Intent().apply {
                putExtra(WeeklightWidgetProvider.EXTRA_AGENDA_DATE, entry.date.toString())
                putExtra(WeeklightWidgetProvider.EXTRA_RENDER_TOKEN, renderToken)
            },
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.eventId ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        rows = emptyList()
    }

    private fun colorFor(kind: EventKind): Int =
        when (kind) {
            EventKind.HOLIDAY -> settings.holidayColor
            EventKind.EVENT -> settings.eventColor
            EventKind.TASK -> settings.taskColor
        }

    private fun kindLabel(kind: EventKind): String =
        when (kind) {
            EventKind.HOLIDAY -> context.getString(R.string.kind_holiday)
            EventKind.EVENT -> context.getString(R.string.kind_event)
            EventKind.TASK -> context.getString(R.string.kind_task)
        }
}
