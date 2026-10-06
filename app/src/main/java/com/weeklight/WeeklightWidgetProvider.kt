package com.weeklight

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.io.IOException
import java.util.Locale

class WeeklightWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, WeeklightWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    RefreshScheduler.schedule(context)
                    ids.forEach { requestUpdate(context, manager, it) }
                }
            }
            ACTION_PAGE -> {
                val manager = AppWidgetManager.getInstance(context)
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (id !in manager.getAppWidgetIds(ComponentName(context, WeeklightWidgetProvider::class.java))) return
                val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val renderToken = intent.getLongExtra(EXTRA_RENDER_TOKEN, -1L)
                if (renderToken != preferences.getLong("render_$id", 0L)) return
                val step = intent.getIntExtra(EXTRA_STEP, 0)
                if (step != -1 && step != 1) return
                preferences.edit().putInt("page_$id", preferences.getInt("page_$id", 0) + step).apply()
                requestUpdate(context, manager, id)
            }
            ACTION_AGENDA_DATE -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val manager = AppWidgetManager.getInstance(context)
                if (id !in manager.getAppWidgetIds(ComponentName(context, WeeklightWidgetProvider::class.java))) return
                val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val token = intent.getLongExtra(EXTRA_RENDER_TOKEN, -1L)
                if (token != preferences.getLong("render_$id", 0L)) return
                val date = intent.getStringExtra(EXTRA_AGENDA_DATE)?.let(LocalDate::parse) ?: return
                try {
                    dateIntent(context, id, date, token).send()
                } catch (_: PendingIntent.CanceledException) {
                    requestUpdate(context, manager, id)
                }
            }
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val manager = AppWidgetManager.getInstance(context)
                manager.getAppWidgetIds(ComponentName(context, WeeklightWidgetProvider::class.java))
                    .forEach { requestUpdate(context, manager, it) }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.schedule(context)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) RefreshScheduler.schedule(context)
        ids.forEach { requestUpdate(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        RefreshScheduler.schedule(context)
        requestUpdate(context, manager, id, options)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (id in ids) {
                    WidgetSettingsStore.remove(context, id)
                    WidgetCacheStore.clear(context, id)
                    GoogleTaskSnapshotStore.clear(context, id)
                }
                val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                ids.forEach {
                    editor.remove("page_$it")
                    editor.remove("render_$it")
                    editor.remove("effective_weeks_$it")
                }
                editor.commit()
                if (AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, WeeklightWidgetProvider::class.java))
                        .isEmpty()
                ) RefreshScheduler.cancel(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val restored = buildList {
                    for ((oldId, newId) in oldWidgetIds.zip(newWidgetIds)) {
                        add(
                            Triple(
                                oldId,
                                newId,
                                WidgetSettingsStore.read(context, oldId) to
                                    preferences.getInt("page_$oldId", 0),
                            ),
                        )
                    }
                }
                for ((oldId, _, _) in restored) {
                    WidgetSettingsStore.remove(context, oldId)
                    WidgetCacheStore.clear(context, oldId)
                    GoogleTaskSnapshotStore.clear(context, oldId)
                }
                val editor = preferences.edit()
                for ((oldId, newId, state) in restored) {
                    WidgetSettingsStore.save(context, newId, state.first)
                    WidgetCacheStore.clear(context, newId)
                    GoogleTaskSnapshotStore.clear(context, newId)
                    editor.remove("page_$oldId").remove("render_$oldId").remove("effective_weeks_$oldId")
                    editor.putInt("page_$newId", state.second)
                    editor.remove("render_$newId").remove("effective_weeks_$newId")
                }
                editor.commit()
                if (restored.isNotEmpty()) RefreshScheduler.schedule(context)
                val manager = AppWidgetManager.getInstance(context)
                restored.forEach { (_, newId, _) -> requestUpdate(context, manager, newId) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun requestUpdate(context: Context, manager: AppWidgetManager, id: Int, options: Bundle? = null) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshWidget(context, manager, id, options)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun refreshWidget(context: Context, manager: AppWidgetManager, id: Int, options: Bundle? = null) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val token = preferences.getLong("render_$id", 0L) + 1L
        preferences.edit().putLong("render_$id", token).commit()
        try {
                if (!isCurrent(context, id, token)) return
                val settings = WidgetSettingsStore.read(context, id)
                if (settings.selectedCalendarIds.isNotEmpty() &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    WidgetCacheStore.clear(context, id)
                    renderStatus(context, manager, id, token, "Calendar access needed", "Open Weeklight to choose calendars.")
                    return
                }

                val page = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getInt("page_$id", settings.anchorPage)
                val currentOptions = options ?: manager.getAppWidgetOptions(id)
                val width = currentOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220)
                val height = currentOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 348)
                val compact = WidgetSizePolicy.maxWeeks(width, height) == 0
                val effectiveWeeks = WidgetSizePolicy.visibleWeeks(width, height, settings.weeks)
                val widgetPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                widgetPreferences.edit().putInt("effective_weeks_$id", effectiveWeeks).apply()

                val zone = ZoneId.systemDefault()
                val locale = Locale.getDefault()
                val dates = DateGrid.dates(LocalDate.now(zone), effectiveWeeks, page, locale)
                val selectedIds = settings.selectedCalendarIds.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
                val availableIds = if (selectedIds.isEmpty()) emptySet() else try {
                    CalendarRepository.calendars(context).mapTo(mutableSetOf()) { it.id }
                } catch (_: RuntimeException) {
                    null
                }
                val usableIds = if (availableIds == null) {
                    selectedIds
                } else {
                    usableCalendarIds(selectedIds, availableIds)
                }
                if (usableIds.isEmpty() && settings.googleTaskListIds.isEmpty()) {
                    val status = if (selectedIds.isEmpty()) "No calendars selected" else "Choose calendars"
                    renderStatus(context, manager, id, token, status, "Open Weeklight to update this widget.")
                    return
                }

                val key = RenderKey(usableIds, dates.first(), dates.last(), zone.id, true)
                val taskSnapshot = refreshTasks(context, id, settings, dates.first(), dates.last())
                try {
                    if (availableIds == null && usableIds.isNotEmpty()) throw RuntimeException("Calendar provider unavailable")
                    val instances = if (usableIds.isEmpty()) emptyList() else CalendarRepository.instances(context, usableIds, dates.first(), dates.last(), zone)
                    val calendarKinds = eventDotKinds(
                        instances,
                        usableIds,
                        settings.taskCalendarIds.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() },
                        dates.first(),
                        dates.last(),
                        zone,
                    )
                    val dotKinds = mergeTaskDots(calendarKinds, taskSnapshot?.rows.orEmpty())
                    val counts = dotKinds.mapValues { it.value.size }
                    if (!isCurrent(context, id, token)) return
                    WidgetCacheStore.write(
                        context,
                        id,
                        DotSnapshot(key, counts, System.currentTimeMillis()),
                    )
                    if (isCurrent(context, id, token)) {
                        render(context, manager, id, dates, counts, dotKinds, settings.theme, locale, zone, compact, false, token, settings.holidayColor, settings.eventColor, settings.taskColor, taskSnapshot?.state)
                    }
                } catch (_: SecurityException) {
                    WidgetCacheStore.clear(context, id)
                    renderStatus(context, manager, id, token, "Calendar access needed", "Open Weeklight to grant access.")
                } catch (_: RuntimeException) {
                    if (!renderCached(context, manager, id, token, key, dates, compact, settings.theme, locale, zone, settings.holidayColor, settings.eventColor, settings.taskColor)) {
                        renderStatus(context, manager, id, token, "Calendar unavailable", "Open Weeklight to retry.")
                    }
                }
            } catch (_: SecurityException) {
                WidgetCacheStore.clear(context, id)
                renderStatus(context, manager, id, token, "Calendar access needed", "Open Weeklight to grant access.")
            } catch (_: RuntimeException) {
                renderStatus(context, manager, id, token, "Calendar unavailable", "Open Weeklight to retry.")
            }
        }

    private suspend fun refreshTasks(
        context: Context,
        id: Int,
        settings: WidgetSettings,
        first: LocalDate,
        last: LocalDate,
    ): TaskSnapshot? {
        if (settings.googleTaskAccount.isBlank() || settings.googleTaskListIds.isEmpty()) return null
        val old = GoogleTaskSnapshotStore.read(context, id)?.takeIf { it.matches(settings, first, last) }
        val access = GoogleTasksAuthorization.silentAccess(context, settings.googleTaskAccount)
        val snapshot = if (access.token == null) {
            (old ?: TaskSnapshot(settings.googleTaskAccount, settings.googleTaskListIds, first, last, emptyList(), access.state))
                .copy(state = access.state)
        } else {
            try {
                val rows = GoogleTasksRepository(access.token).tasks(settings.googleTaskListIds, first, last)
                TaskSnapshot(settings.googleTaskAccount, settings.googleTaskListIds, first, last, rows, TaskState.READY)
            } catch (error: TasksHttpException) {
                val state = if (error.status == 401 || error.status == 403) TaskState.RECONNECT else TaskState.UNAVAILABLE
                (old ?: TaskSnapshot(settings.googleTaskAccount, settings.googleTaskListIds, first, last, emptyList(), state)).copy(state = state)
            } catch (_: IOException) {
                (old ?: TaskSnapshot(settings.googleTaskAccount, settings.googleTaskListIds, first, last, emptyList(), TaskState.OFFLINE)).copy(state = TaskState.OFFLINE)
            } catch (_: RuntimeException) {
                (old ?: TaskSnapshot(settings.googleTaskAccount, settings.googleTaskListIds, first, last, emptyList(), TaskState.UNAVAILABLE)).copy(state = TaskState.UNAVAILABLE)
            }
        }
        GoogleTaskSnapshotStore.write(context, id, snapshot)
        return snapshot
    }

    private suspend fun renderCached(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        token: Long,
        key: RenderKey,
        dates: List<LocalDate>,
        compact: Boolean,
        theme: String,
        locale: Locale,
        zone: ZoneId,
        holidayColor: Int,
        eventColor: Int,
        taskColor: Int,
    ): Boolean {
        val snapshot = WidgetCacheStore.read(context, id) ?: return false
        if (!snapshot.matches(key) || !isCurrent(context, id, token)) return false
        val dotKinds = snapshot.counts.mapValues { (_, count) ->
            List(count.coerceIn(0, 3)) { EventKind.EVENT }
        }
        render(context, manager, id, dates, snapshot.counts, dotKinds, theme, locale, zone, compact, true, token, holidayColor, eventColor, taskColor)
        return true
    }

    private suspend fun renderStatus(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        token: Long,
        title: String,
        subtitle: String,
    ) {
        if (!isCurrent(context, id, token)) return
        val themed = themedContext(context, WidgetSettingsStore.read(context, id).theme)
        val views = RemoteViews(context.packageName, R.layout.widget)
        applyColors(views, themed)
        views.setTextViewText(R.id.title, title)
        val action = configurationIntent(context, id)
        views.setOnClickPendingIntent(R.id.header, action)
        views.setContentDescription(R.id.header, "$title. $subtitle " + context.getString(R.string.configure_widget))
        views.setViewVisibility(R.id.previous, View.GONE)
        views.setViewVisibility(R.id.next, View.GONE)
        views.removeAllViews(R.id.weekdays)
        views.removeAllViews(R.id.weeks)
        views.setViewVisibility(R.id.agenda, View.GONE)
        manager.updateAppWidget(id, views)
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        dates: List<LocalDate>,
        counts: Map<LocalDate, Int>,
        dotKinds: Map<LocalDate, List<EventKind>>,
        theme: String,
        locale: Locale,
        zone: ZoneId,
        compact: Boolean,
        stale: Boolean,
        token: Long,
        holidayColor: Int,
        eventColor: Int,
        taskColor: Int,
        taskState: TaskState? = null,
    ) {
        val themed = themedContext(context, theme)
        val titleDate = dates[dates.size / 2]
        val today = LocalDate.now(zone)
        val views = RemoteViews(context.packageName, R.layout.widget)
        applyColors(views, themed)
        views.setTextViewText(
            R.id.title,
            titleDate.month.getDisplayName(TextStyle.FULL, locale) + " " + titleDate.year +
                when (taskState) {
                    TaskState.RECONNECT -> " (Tasks reconnect)"
                    TaskState.OFFLINE -> " (Tasks offline)"
                    TaskState.UNAVAILABLE -> " (Tasks unavailable)"
                    else -> if (stale) " (cached)" else ""
                },
        )
        views.setViewVisibility(R.id.previous, View.VISIBLE)
        views.setViewVisibility(R.id.next, View.VISIBLE)
        views.setOnClickPendingIntent(R.id.previous, pageIntent(context, id, -1, token))
        views.setOnClickPendingIntent(R.id.next, pageIntent(context, id, 1, token))
        views.setOnClickPendingIntent(R.id.header, configurationIntent(context, id))
        views.setContentDescription(R.id.header, context.getString(R.string.configure_widget))
        views.removeAllViews(R.id.weekdays)
        views.setViewVisibility(R.id.weekdays, if (compact) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.weeks, if (compact) View.GONE else View.VISIBLE)
        if (!compact) DateGrid.weekdayLabels(locale).forEach { label ->
            val weekday = RemoteViews(context.packageName, R.layout.weekday)
            weekday.setTextViewText(R.id.weekday, label)
            weekday.setTextColor(R.id.weekday, themed.getColor(R.color.widget_muted))
            views.addView(R.id.weekdays, weekday)
        }
        views.removeAllViews(R.id.weeks)
        if (!compact) dates.chunked(7).forEach { week ->
            val row = RemoteViews(context.packageName, R.layout.week_row)
            week.forEach { date ->
                val cell = RemoteViews(context.packageName, R.layout.day_cell)
                val kinds = dotKinds[date].orEmpty().take(3)
                val eventCount = kinds.size.coerceAtLeast(counts[date] ?: 0)
                val isToday = date == today
                val isAdjacentMonth = date.month != titleDate.month || date.year != titleDate.year
                val dayColor = if (isToday) R.color.widget_accent else if (isAdjacentMonth) R.color.widget_muted else R.color.widget_text
                cell.setTextViewText(R.id.day_number, date.dayOfMonth.toString())
                val dotIds = listOf(R.id.holiday_dot, R.id.event_dot, R.id.task_dot)
                val dotColors = listOf(holidayColor, eventColor, taskColor)
                dotIds.forEachIndexed { index, dotId ->
                    cell.setViewVisibility(dotId, if (index < kinds.size) View.VISIBLE else View.GONE)
                    if (index < kinds.size) cell.setTextColor(dotId, dotColors[kindIndex(kinds[index])])
                }
                cell.setTextColor(R.id.day_number, themed.getColor(dayColor))
                if (isToday) cell.setInt(R.id.day_cell, "setBackgroundColor", themed.getColor(R.color.widget_today))
                val eventsLabel = if (eventCount == 1) "1 event" else eventCount.toString() + " events"
                cell.setContentDescription(
                    R.id.day_cell,
                    date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", locale)) +
                        ", " + eventsLabel,
                )
                cell.setOnClickPendingIntent(R.id.day_cell, dateIntent(context, id, date, token))
                row.addView(R.id.week_row, cell)
            }
            views.addView(R.id.weeks, row)
        }
        val serviceIntent = agendaServiceIntent(context, id, token, dates.first(), dates.last())
        views.setRemoteAdapter(R.id.agenda, serviceIntent)
        views.setPendingIntentTemplate(R.id.agenda, agendaTemplateIntent(context, id, token))
        views.setViewVisibility(R.id.agenda, View.VISIBLE)
        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.agenda)
    }
    private fun applyColors(views: RemoteViews, themed: Context) {
        views.setInt(R.id.widget_root, "setBackgroundColor", themed.getColor(R.color.widget_surface))
        views.setTextColor(R.id.title, themed.getColor(R.color.widget_text))
        views.setTextColor(R.id.previous, themed.getColor(R.color.widget_accent))
        views.setTextColor(R.id.next, themed.getColor(R.color.widget_accent))
    }

    private fun themedContext(context: Context, theme: String): Context {
        if (theme == "system") return context
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (theme == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(configuration)
    }

    private fun configurationIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getActivity(
            context,
            id * 10 + 5,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pageIntent(context: Context, id: Int, step: Int, token: Long): PendingIntent {
        val intent = Intent(context, WeeklightWidgetProvider::class.java).apply {
            action = ACTION_PAGE
            data = Uri.parse("weeklight://page/$id/$step/$token")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_STEP, step)
            putExtra(EXTRA_RENDER_TOKEN, token)
        }
        return PendingIntent.getBroadcast(
            context,
            ("page:$id:$step:$token").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dateIntent(context: Context, id: Int, date: LocalDate, token: Long): PendingIntent {
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
        if (intent.resolveActivity(context.packageManager) == null) {
            intent.data = Uri.parse(
                "https://calendar.google.com/calendar/r/day/" + date.format(DateTimeFormatter.BASIC_ISO_DATE),
            )
        }
        val requestCode = ("date:$id:$date:$token").hashCode()
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun agendaServiceIntent(context: Context, id: Int, token: Long, first: LocalDate, last: LocalDate): Intent =
        Intent(context, AgendaRemoteViewsService::class.java).apply {
            data = Uri.parse("weeklight://agenda/$id/$token")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_RENDER_TOKEN, token)
            putExtra(EXTRA_FIRST_DATE, first.toString())
            putExtra(EXTRA_LAST_DATE, last.toString())
        }

    private fun agendaTemplateIntent(context: Context, id: Int, token: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ("agenda:$id:$token").hashCode(),
            Intent(context, WeeklightWidgetProvider::class.java).apply {
                action = ACTION_AGENDA_DATE
                data = Uri.parse("weeklight://agenda-date/$id/$token")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(EXTRA_RENDER_TOKEN, token)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun kindIndex(kind: EventKind): Int =
        when (kind) {
            EventKind.HOLIDAY -> 0
            EventKind.EVENT -> 1
            EventKind.TASK -> 2
        }

    private fun isCurrent(context: Context, id: Int, token: Long): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong("render_$id", 0L) == token

    companion object {
        private const val ACTION_PAGE = "com.weeklight.PAGE"
        internal const val ACTION_AGENDA_DATE = "com.weeklight.AGENDA_DATE"
        internal const val EXTRA_AGENDA_DATE = "agenda_date"
        internal const val EXTRA_RENDER_TOKEN = "render_token"
        internal const val EXTRA_FIRST_DATE = "first_date"
        internal const val EXTRA_LAST_DATE = "last_date"
        private const val EXTRA_STEP = "step"
        private const val PREFERENCES = "weeklight_spike"
    }
}
