package com.weeklight

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private val Context.widgetCacheDataStore by preferencesDataStore(name = "widget_cache")

data class RenderKey(
    val calendarIds: Set<Long>,
    val first: LocalDate,
    val last: LocalDate,
    val zoneId: String,
    val permissionGranted: Boolean,
)

data class DotSnapshot(
    val key: RenderKey,
    val counts: Map<LocalDate, Int>,
    val refreshedAt: Long,
) {
    fun matches(renderKey: RenderKey): Boolean = key == renderKey
}

object WidgetCacheStore {
    suspend fun read(context: Context, widgetId: Int): DotSnapshot? {
        val preferences = context.widgetCacheDataStore.data.first()
        val first = preferences[stringPreferencesKey("first_$widgetId")]?.let(LocalDate::parse) ?: return null
        val last = preferences[stringPreferencesKey("last_$widgetId")]?.let(LocalDate::parse) ?: return null
        val zoneId = preferences[stringPreferencesKey("zone_$widgetId")] ?: return null
        val calendarIds = preferences[stringSetPreferencesKey("calendars_$widgetId")]
            ?.mapNotNullTo(mutableSetOf(), String::toLongOrNull)
            ?: return null
        val counts = preferences[stringPreferencesKey("counts_$widgetId")]
            ?.split(',')
            ?.mapNotNull { item ->
                val parts = item.split('=')
                if (parts.size == 2) LocalDate.parse(parts[0]) to (parts[1].toIntOrNull() ?: 0) else null
            }
            ?.toMap()
            ?: emptyMap()
        return DotSnapshot(
            key = RenderKey(
                calendarIds = calendarIds,
                first = first,
                last = last,
                zoneId = zoneId,
                permissionGranted = preferences[booleanPreferencesKey("permission_$widgetId")] ?: false,
            ),
            counts = counts,
            refreshedAt = preferences[longPreferencesKey("refreshed_$widgetId")] ?: 0L,
        )
    }

    suspend fun write(context: Context, widgetId: Int, snapshot: DotSnapshot) {
        context.widgetCacheDataStore.edit { preferences ->
            preferences[stringPreferencesKey("first_$widgetId")] = snapshot.key.first.toString()
            preferences[stringPreferencesKey("last_$widgetId")] = snapshot.key.last.toString()
            preferences[stringPreferencesKey("zone_$widgetId")] = snapshot.key.zoneId
            preferences[stringSetPreferencesKey("calendars_$widgetId")] =
                snapshot.key.calendarIds.map(Long::toString).toSet()
            preferences[booleanPreferencesKey("permission_$widgetId")] = snapshot.key.permissionGranted
            preferences[longPreferencesKey("refreshed_$widgetId")] = snapshot.refreshedAt
            preferences[stringPreferencesKey("counts_$widgetId")] =
                snapshot.counts.entries.joinToString(",") { entry ->
                    entry.key.toString() + "=" + entry.value.coerceIn(0, 3)
                }
        }
    }

    suspend fun clear(context: Context, widgetId: Int) {
        context.widgetCacheDataStore.edit { preferences ->
            listOf(
                stringPreferencesKey("first_$widgetId"),
                stringPreferencesKey("last_$widgetId"),
                stringPreferencesKey("zone_$widgetId"),
                stringSetPreferencesKey("calendars_$widgetId"),
                booleanPreferencesKey("permission_$widgetId"),
                longPreferencesKey("refreshed_$widgetId"),
                stringPreferencesKey("counts_$widgetId"),
            ).forEach { key -> preferences.remove(key) }
        }
    }
}

