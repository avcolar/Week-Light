package com.weeklight

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

private val Context.googleTaskDataStore by preferencesDataStore(name = "google_task_snapshots")

internal data class TaskSnapshot(
    val account: String,
    val listIds: Set<String>,
    val first: LocalDate,
    val last: LocalDate,
    val rows: List<TaskItem>,
    val state: TaskState,
) {
    fun matches(settings: WidgetSettings, first: LocalDate, last: LocalDate): Boolean =
        account == settings.googleTaskAccount && listIds == settings.googleTaskListIds &&
            this.first == first && this.last == last
}

internal object GoogleTaskSnapshotStore {
    suspend fun read(context: Context, widgetId: Int): TaskSnapshot? {
        val text = context.googleTaskDataStore.data.first()[stringPreferencesKey("snapshot_$widgetId")] ?: return null
        return try {
            val json = JSONObject(text)
            val rows = json.getJSONArray("rows")
            if (rows.length() > 500) return null
            TaskSnapshot(
                json.getString("account"),
                json.getJSONArray("lists").let { array -> (0 until array.length()).map(array::getString).toSet() },
                LocalDate.parse(json.getString("first")),
                LocalDate.parse(json.getString("last")),
                (0 until rows.length()).map { index ->
                    rows.getJSONObject(index).let { row ->
                        TaskItem(row.getString("id"), row.getString("list"), row.getString("title"), LocalDate.parse(row.getString("due")))
                    }
                },
                TaskState.valueOf(json.getString("state")),
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    suspend fun write(context: Context, widgetId: Int, snapshot: TaskSnapshot) {
        require(snapshot.rows.size <= 500)
        val json = JSONObject().apply {
            put("account", snapshot.account)
            put("lists", JSONArray(snapshot.listIds.sorted()))
            put("first", snapshot.first.toString())
            put("last", snapshot.last.toString())
            put("state", snapshot.state.name)
            put("rows", JSONArray().also { array ->
                snapshot.rows.forEach { row ->
                    array.put(JSONObject().put("id", row.id).put("list", row.listId).put("title", row.title).put("due", row.due.toString()))
                }
            })
        }
        context.googleTaskDataStore.edit { it[stringPreferencesKey("snapshot_$widgetId")] = json.toString() }
    }

    suspend fun clear(context: Context, widgetId: Int) {
        context.googleTaskDataStore.edit { it.remove(stringPreferencesKey("snapshot_$widgetId")) }
    }
}
