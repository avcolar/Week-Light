package com.weeklight

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        setResult(Activity.RESULT_CANCELED)

        setContent {
            MaterialTheme {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Text(getString(R.string.add_widget_hint), modifier = Modifier.padding(24.dp))
                } else {
                    ConfigurationScreen(
                        widgetId = widgetId,
                        onSave = { settings ->
                            lifecycleScope.launch {
                                val previous = WidgetSettingsStore.read(this@MainActivity, widgetId)
                                if (previous.googleTaskAccount != settings.googleTaskAccount ||
                                    previous.googleTaskListIds != settings.googleTaskListIds) {
                                    GoogleTaskSnapshotStore.clear(this@MainActivity, widgetId)
                                }
                                WidgetSettingsStore.save(this@MainActivity, widgetId, settings)
                                RefreshScheduler.schedule(this@MainActivity)
                                sendWidgetUpdate(widgetId)
                                setResult(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                                )
                                finish()
                            }
                        },
                        onDisconnect = {
                            lifecycleScope.launch {
                                GoogleTaskSnapshotStore.clear(this@MainActivity, widgetId)
                                WidgetSettingsStore.save(this@MainActivity, widgetId,
                                    WidgetSettingsStore.read(this@MainActivity, widgetId).copy(
                                        googleTaskAccount = "", googleTaskListIds = emptySet(),
                                    ))
                                sendWidgetUpdate(widgetId)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun sendWidgetUpdate(widgetId: Int) {
        sendBroadcast(
            Intent(this, WeeklightWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            },
        )
    }
}

@Composable
private fun ConfigurationScreen(
    widgetId: Int,
    onSave: (WidgetSettings) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var weeks by remember { mutableIntStateOf(4) }
    var selectedCalendarIds by remember { mutableStateOf(emptySet<Long>()) }
    var taskCalendarIds by remember { mutableStateOf(emptySet<Long>()) }
    var googleAccount by remember { mutableStateOf("") }
    var googleListIds by remember { mutableStateOf(emptySet<String>()) }
    var googleLists by remember { mutableStateOf(emptyList<TaskListOption>()) }
    var taskState by remember { mutableStateOf(TaskState.RECONNECT) }
    var taskLoading by remember { mutableStateOf(false) }
    var holidayColor by remember { mutableStateOf(DEFAULT_HOLIDAY_COLOR) }
    var eventColor by remember { mutableStateOf(DEFAULT_EVENT_COLOR) }
    var taskColor by remember { mutableStateOf(DEFAULT_TASK_COLOR) }
    var theme by remember { mutableStateOf("system") }
    var calendars by remember { mutableStateOf(emptyList<CalendarOption>()) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var authorizationAccount by remember { mutableStateOf<String?>(null) }

    fun loadTaskLists(token: String) {
        scope.launch {
            taskLoading = true
            try {
                googleLists = withContext(Dispatchers.IO) { GoogleTasksRepository(token).lists() }
                googleListIds = googleListIds.intersect(googleLists.mapTo(mutableSetOf()) { it.id })
                taskState = TaskState.READY
            } catch (_: Exception) {
                taskState = TaskState.UNAVAILABLE
            } finally {
                taskLoading = false
            }
        }
    }

    val taskResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            taskState = TaskState.RECONNECT
        } else {
            val account = authorizationAccount
            val data = result.data
            if (account.isNullOrBlank() || data == null) {
                taskState = TaskState.UNAVAILABLE
            } else {
                try {
                    val access = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
                    val token = access.accessToken
                    if (access.hasResolution() || token.isNullOrBlank()) taskState = TaskState.RECONNECT
                    else loadTaskLists(token)
                } catch (_: RuntimeException) {
                    taskState = TaskState.UNAVAILABLE
                }
            }
        }
    }

    fun loadCalendars() {
        if (!permissionGranted) return
        scope.launch {
            isLoading = true
            message = null
            try {
                calendars = withContext(Dispatchers.IO) {
                    CalendarRepository.calendars(context)
                }
                val availableIds = calendars.mapTo(mutableSetOf()) { it.id }
                selectedCalendarIds = selectedCalendarIds.intersect(availableIds)
                if (calendars.isEmpty()) {
                    message = "No calendars available on this device."
                }
            } catch (_: SecurityException) {
                permissionGranted = false
                message = "Calendar access is required to choose calendars."
            } catch (_: RuntimeException) {
                message = "Calendar data is unavailable. Try again."
            } finally {
                isLoading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            loadCalendars()
        } else {
            message = "Calendar access was denied. You can try again or open system settings."
        }
    }

    LaunchedEffect(widgetId) {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val settings = WidgetSettingsStore.read(context, widgetId)
            weeks = settings.weeks
            selectedCalendarIds = settings.selectedCalendarIds.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
            taskCalendarIds = settings.taskCalendarIds.mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
            googleAccount = settings.googleTaskAccount
            googleListIds = settings.googleTaskListIds
            taskState = GoogleTaskSnapshotStore.read(context, widgetId)?.state ?: TaskState.RECONNECT
            holidayColor = settings.holidayColor
            eventColor = settings.eventColor
            taskColor = settings.taskColor
            theme = settings.theme
        }
        if (permissionGranted) loadCalendars()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Weeklight widget", style = MaterialTheme.typography.headlineSmall)
        Text("Choose calendars to show event dots.", style = MaterialTheme.typography.bodyMedium)

        Text("Weeks visible: $weeks", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = weeks.toFloat(),
            onValueChange = { weeks = it.roundToInt().coerceIn(1, 8) },
            valueRange = 1f..8f,
            steps = 6,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("1")
            Text("8")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CALENDAR,
                ) == PackageManager.PERMISSION_GRANTED
                if (permissionGranted) {
                    loadCalendars()
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            },
        ) {
            Text(if (permissionGranted) "Refresh calendars" else "Choose calendars")
        }

        if (!permissionGranted) {
            Text("Calendar access is read-only and stays on this device.")
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                },
            ) {
                Text("Open system settings")
            }
        }

        if (isLoading) {
            Text("Loading calendars…")
        } else {
            calendars.forEach { calendar ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = calendar.id in selectedCalendarIds,
                        onCheckedChange = { checked ->
                            selectedCalendarIds = if (checked) {
                                selectedCalendarIds + calendar.id
                            } else {
                                taskCalendarIds = taskCalendarIds - calendar.id
                                selectedCalendarIds - calendar.id
                            }
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(calendar.name.ifBlank { "Unnamed calendar" })
                        Text(
                            calendar.account.ifBlank { calendar.accountType },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (calendar.id in selectedCalendarIds) {
                        Checkbox(
                            checked = calendar.id in taskCalendarIds,
                            onCheckedChange = { checked ->
                                taskCalendarIds = if (checked) taskCalendarIds + calendar.id else taskCalendarIds - calendar.id
                            },
                        )
                        Text("Task")
                    }
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Google Tasks", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = googleAccount,
            onValueChange = {
                googleAccount = it.trim()
                googleLists = emptyList()
                googleListIds = emptySet()
                taskState = TaskState.RECONNECT
            },
            label = { Text("Google account email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(when (taskState) {
            TaskState.READY -> if (taskLoading) "Loading task lists…" else "Connected. Choose task lists."
            TaskState.RECONNECT -> "Reconnect Google Tasks to refresh."
            TaskState.OFFLINE -> "Tasks offline. Saved tasks may be stale."
            TaskState.UNAVAILABLE -> "Google Tasks unavailable. Try connecting again."
        })
        OutlinedButton(
            enabled = googleAccount.contains('@') && !taskLoading,
            onClick = {
                val requestedAccount = googleAccount.trim()
                authorizationAccount = requestedAccount
                taskLoading = true
                Identity.getAuthorizationClient(context).authorize(GoogleTasksAuthorization.request(requestedAccount))
                    .addOnSuccessListener { access ->
                        taskLoading = false
                        val token = access.accessToken
                        if (access.hasResolution()) {
                            taskResolutionLauncher.launch(IntentSenderRequest.Builder(access.pendingIntent!!.intentSender).build())
                        } else if (!token.isNullOrBlank()) {
                            loadTaskLists(token)
                        } else {
                            taskState = TaskState.RECONNECT
                        }
                    }
                    .addOnFailureListener {
                        taskLoading = false
                        taskState = TaskState.UNAVAILABLE
                    }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (googleListIds.isEmpty()) "Connect Google Tasks" else "Reconnect Google Tasks") }
        if (googleAccount.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    googleAccount = ""
                    googleListIds = emptySet()
                    googleLists = emptyList()
                    taskState = TaskState.RECONNECT
                    onDisconnect()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect Google Tasks") }
        }
        googleLists.forEach { list ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = list.id in googleListIds,
                    onCheckedChange = { selected ->
                        googleListIds = if (selected) googleListIds + list.id else googleListIds - list.id
                    },
                )
                Text(list.title.ifBlank { "Untitled task list" })
            }
        }

        Text("Dot colors", style = MaterialTheme.typography.titleMedium)
        val palette = listOf(
            "Blue" to DEFAULT_EVENT_COLOR,
            "Orange" to DEFAULT_HOLIDAY_COLOR,
            "Purple" to DEFAULT_TASK_COLOR,
            "Green" to 0xFF35B779.toInt(),
        )
        listOf(
            "Holiday" to holidayColor,
            "Event" to eventColor,
            "Task" to taskColor,
        ).forEach { (label, current) ->
            Text(label)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                palette.forEach { (name, color) ->
                    OutlinedButton(
                        onClick = {
                            when (label) {
                                "Holiday" -> holidayColor = color
                                "Event" -> eventColor = color
                                else -> taskColor = color
                            }
                        },
                    ) {
                        Text(if (current == color) "[$name]" else name)
                    }
                }
            }
        }

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        listOf("system" to "System", "light" to "Light", "dark" to "Dark")
            .forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = theme == id, onClick = { theme = id })
                    Text(label)
                }
            }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onSave(
                    WidgetSettings(
                        weeks = weeks,
                        selectedCalendarIds = selectedCalendarIds.mapTo(mutableSetOf()) { it.toString() },
                        taskCalendarIds = taskCalendarIds.mapTo(mutableSetOf()) { it.toString() },
                        googleTaskAccount = googleAccount,
                        googleTaskListIds = googleListIds,
                        theme = theme,
                        holidayColor = holidayColor,
                        eventColor = eventColor,
                        taskColor = taskColor,
                    ),
                )
            },
        ) {
            Text("Save widget")
        }
    }
}


