package com.weeklight

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.widgetSettingsDataStore by preferencesDataStore(name = "widget_settings")

internal val DEFAULT_HOLIDAY_COLOR = 0xFFFFB020.toInt()
internal val DEFAULT_EVENT_COLOR = 0xFF4F8CFF.toInt()
internal val DEFAULT_TASK_COLOR = 0xFFB56CFF.toInt()

data class WidgetSettings(
    val weeks: Int = 4,
    val selectedCalendarIds: Set<String> = emptySet(),
    val taskCalendarIds: Set<String> = emptySet(),
    val googleTaskAccount: String = "",
    val googleTaskListIds: Set<String> = emptySet(),
    val theme: String = "system",
    val anchorPage: Int = 0,
    val holidayColor: Int = DEFAULT_HOLIDAY_COLOR,
    val eventColor: Int = DEFAULT_EVENT_COLOR,
    val taskColor: Int = DEFAULT_TASK_COLOR,
)

internal fun normalizeWeeks(weeks: Int): Int = weeks.coerceIn(1, 8)

object WidgetSettingsStore {
    suspend fun read(context: Context, widgetId: Int): WidgetSettings {
        val preferences = context.widgetSettingsDataStore.data.first()
        return WidgetSettings(
            weeks = normalizeWeeks(preferences[intPreferencesKey("weeks_$widgetId")] ?: 4),
            selectedCalendarIds = preferences[stringSetPreferencesKey("calendars_$widgetId")]
                ?: emptySet(),
            taskCalendarIds = preferences[stringSetPreferencesKey("tasks_$widgetId")]
                ?: emptySet(),
            googleTaskAccount = preferences[stringPreferencesKey("google_account_$widgetId")] ?: "",
            googleTaskListIds = preferences[stringSetPreferencesKey("google_lists_$widgetId")] ?: emptySet(),
            theme = preferences[stringPreferencesKey("theme_$widgetId")] ?: "system",
            anchorPage = preferences[intPreferencesKey("anchor_$widgetId")] ?: 0,
            holidayColor = preferences[intPreferencesKey("holiday_color_$widgetId")] ?: DEFAULT_HOLIDAY_COLOR,
            eventColor = preferences[intPreferencesKey("event_color_$widgetId")] ?: DEFAULT_EVENT_COLOR,
            taskColor = preferences[intPreferencesKey("task_color_$widgetId")] ?: DEFAULT_TASK_COLOR,
        )
    }

    suspend fun save(context: Context, widgetId: Int, settings: WidgetSettings) {
        context.widgetSettingsDataStore.edit { preferences ->
            preferences[intPreferencesKey("weeks_$widgetId")] = normalizeWeeks(settings.weeks)
            preferences[stringSetPreferencesKey("calendars_$widgetId")] = settings.selectedCalendarIds
            preferences[stringSetPreferencesKey("tasks_$widgetId")] =
                settings.taskCalendarIds.intersect(settings.selectedCalendarIds)
            preferences[stringPreferencesKey("google_account_$widgetId")] = settings.googleTaskAccount
            preferences[stringSetPreferencesKey("google_lists_$widgetId")] = settings.googleTaskListIds
            preferences[stringPreferencesKey("theme_$widgetId")] = settings.theme
            preferences[intPreferencesKey("anchor_$widgetId")] = settings.anchorPage
            preferences[intPreferencesKey("holiday_color_$widgetId")] = settings.holidayColor
            preferences[intPreferencesKey("event_color_$widgetId")] = settings.eventColor
            preferences[intPreferencesKey("task_color_$widgetId")] = settings.taskColor
        }
    }

    suspend fun remove(context: Context, widgetId: Int) {
        context.widgetSettingsDataStore.edit { preferences ->
            preferences.remove(intPreferencesKey("weeks_$widgetId"))
            preferences.remove(stringSetPreferencesKey("calendars_$widgetId"))
            preferences.remove(stringSetPreferencesKey("tasks_$widgetId"))
            preferences.remove(stringPreferencesKey("google_account_$widgetId"))
            preferences.remove(stringSetPreferencesKey("google_lists_$widgetId"))
            preferences.remove(stringPreferencesKey("theme_$widgetId"))
            preferences.remove(intPreferencesKey("anchor_$widgetId"))
            preferences.remove(intPreferencesKey("holiday_color_$widgetId"))
            preferences.remove(intPreferencesKey("event_color_$widgetId"))
            preferences.remove(intPreferencesKey("task_color_$widgetId"))
        }
    }
}
