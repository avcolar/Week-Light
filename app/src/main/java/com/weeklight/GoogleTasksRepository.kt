package com.weeklight

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

internal data class TaskItem(val id: String, val listId: String, val title: String, val due: LocalDate)
internal data class TaskListOption(val id: String, val title: String)
internal data class TaskPage<T>(val items: List<T>, val nextPageToken: String?)
internal data class TaskRecord(
    val id: String,
    val title: String,
    val due: String?,
    val status: String,
    val deleted: Boolean,
    val hidden: Boolean,
)

internal fun taskDueDate(value: String?): LocalDate? =
    try {
        value?.takeIf { it.length >= 10 && (it.length == 10 || it[10] == 'T') }
            ?.take(10)?.let(LocalDate::parse)
    } catch (_: RuntimeException) {
        null
    }

internal fun visibleTasks(items: List<TaskRecord>, listId: String, first: LocalDate, last: LocalDate): List<TaskItem> =
    items.mapNotNull { task ->
        val due = taskDueDate(task.due) ?: return@mapNotNull null
        if (due.isBefore(first) || due.isAfter(last) || task.status != "needsAction" ||
            task.deleted || task.hidden || task.id.isBlank()
        ) return@mapNotNull null
        TaskItem(task.id, listId, task.title, due)
    }

internal fun <T> collectPages(fetch: (String?) -> TaskPage<T>): List<T> {
    val result = mutableListOf<T>()
    val tokens = mutableSetOf<String>()
    var token: String? = null
    var pages = 0
    do {
        if (++pages > 20) error("Google Tasks result too large")
        val page = fetch(token)
        result += page.items
        token = page.nextPageToken?.takeIf(String::isNotBlank)
        if (result.size > 500 || (token != null && !tokens.add(token))) error("Google Tasks result too large")
    } while (token != null)
    return result
}

internal class TasksHttpException(val status: Int) : RuntimeException()

internal class GoogleTasksRepository(private val accessToken: String) {
    fun lists(): List<TaskListOption> = collectPages { page ->
        val json = get("https://tasks.googleapis.com/tasks/v1/users/@me/lists", mapOf("pageToken" to page))
        TaskPage(json.items().mapNotNull {
            val id = it.optString("id")
            if (id.isBlank()) null else TaskListOption(id, it.optString("title"))
        }, json.optString("nextPageToken").takeIf(String::isNotBlank))
    }

    fun tasks(listIds: Set<String>, first: LocalDate, last: LocalDate): List<TaskItem> {
        require(!last.isBefore(first))
        val rows = listIds.flatMap { listId ->
            collectPages { page ->
                val json = get(
                    "https://tasks.googleapis.com/tasks/v1/lists/" + Uri.encode(listId) + "/tasks",
                    mapOf(
                        "pageToken" to page,
                        "dueMin" to "${first}T00:00:00Z",
                        "dueMax" to "${last}T23:59:59Z",
                        "showCompleted" to "false",
                        "showDeleted" to "false",
                        "showHidden" to "false",
                        "maxResults" to "100",
                    ),
                )
                TaskPage(visibleTasks(json.items().map {
                    TaskRecord(
                        it.optString("id"), it.optString("title"), it.optString("due").takeIf(String::isNotBlank),
                        it.optString("status"), it.optBoolean("deleted"), it.optBoolean("hidden"),
                    )
                }, listId, first, last), json.optString("nextPageToken").takeIf(String::isNotBlank))
            }
        }
        if (rows.size > 500) error("Google Tasks result too large")
        return rows.sortedWith(compareBy<TaskItem> { it.due }.thenBy { it.listId }.thenBy { it.id })
    }

    private fun get(path: String, query: Map<String, String?>): JSONObject {
        val uri = Uri.parse(path).buildUpon().also { builder ->
            query.forEach { (key, value) -> if (value != null) builder.appendQueryParameter(key, value) }
        }.build()
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            if (connection.responseCode != 200) throw TasksHttpException(connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

private fun JSONObject.items(): List<JSONObject> {
    val array = optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}
