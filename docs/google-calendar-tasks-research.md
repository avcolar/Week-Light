# Google Calendar and Google Tasks research

Research date: 2026-09-28. Sources below are Google-owned primary documentation and API definitions.

## Decision

Neither Android `CalendarContract` nor the Google Calendar API exposes Google Tasks as a supported task resource. Use the **Google Tasks API** for task lists and tasks; keep `CalendarContract` for the device calendar events it already supplies.

- The Calendar API discovery document exposes `calendars`, `calendarList`, `events`, `freebusy`, `acl`, `settings`, `colors`, and `channels` resources—no task resource. Its `Event.eventType` values are regular event, birthday, focus time, Gmail, out of office, and working location; task is not one. [Calendar API discovery](https://www.googleapis.com/discovery/v1/apis/calendar/v3/rest)
- Android's official `CalendarContract` source exposes calendar/event-oriented nested types such as `Calendars`, `Events`, `Instances`, `Reminders`, and `CalendarAlerts`; it has no `Tasks` contract or task URI. [CalendarContract source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/CalendarContract.java) and [Android API reference](https://developer.android.com/reference/android/provider/CalendarContract)
- This is distinct from the Google Calendar UI displaying Tasks. A provider may happen to materialize something task-like as an event, but neither contract above makes that a portable Tasks API. Treat it as an implementation detail, not a sync mechanism.

## Google Tasks API: minimum read path

Enable **Google Tasks API** in the Google Cloud project, then use OAuth 2.0 with the narrowest scope:

| Need | Scope | REST call | Model fields needed by this widget |
| --- | --- | --- | --- |
| Read task lists and tasks | `https://www.googleapis.com/auth/tasks.readonly` | `GET https://tasks.googleapis.com/tasks/v1/users/@me/lists` | `TaskList.id`, `title`, `updated` |
| Read tasks for each selected list | same | `GET https://tasks.googleapis.com/tasks/v1/lists/{tasklist}/tasks` | `Task.id`, `title`, `due`, `status`, `completed`, `parent`, `position` |
| Create/edit/delete/reorder later | `https://www.googleapis.com/auth/tasks` | `tasks.insert`, `patch`/`update`, `delete`, `move` | Full mutable `Task` model as required |

The official API has two resource types: `TaskList` and `Task`; each user has at least one default list, and a task collection belongs to one list. [Tasks overview](https://developers.google.com/workspace/tasks/overview), [Task resource](https://developers.google.com/tasks/reference/rest/v1/Task), [TaskList resource](https://developers.google.com/tasks/reference/rest/v1/TaskList), [Tasks methods](https://developers.google.com/tasks/reference/rest/v1/tasks).

For a date-window widget, paginate `tasks.list` and use its `dueMin`, `dueMax`, `showCompleted`, `showDeleted`, and `showHidden` query parameters as appropriate. Do not infer task type from a Calendar ID. [tasks.list](https://developers.google.com/tasks/reference/rest/v1/tasks/list)

Constraint: `Task.due` is a scheduled **day**, not a deadline; Google discards its time component and the API cannot read or write a scheduled time. Render it as an all-day item for its due date. Assigned-task fields are output-only, and some assigned-task changes have source-surface restrictions. [Task resource](https://developers.google.com/tasks/reference/rest/v1/Task)

## Android OAuth requirements

1. In the same Cloud project, configure the OAuth consent screen, enable Google Tasks API, and add only `tasks.readonly` for this read-only widget. Google requires a consent screen for OAuth 2.0; external apps must declare scopes, and sensitive/restricted scopes can require additional review. [Consent-screen guide](https://developers.google.com/workspace/guides/configure-oauth-consent)
2. Create an **Android OAuth client** with this app's package ID, `com.weeklight`, and the SHA-1 of every signing certificate used to distribute it (debug and release/Play as applicable). [Android authorization guide](https://developer.android.com/identity/authorization)
3. Use Google Identity Services' `AuthorizationClient` and request the Tasks scope when the user enables Google Tasks, not during initial widget setup. Google explicitly recommends authorization at the action that needs the data and separate from authentication. [Android authorization guide](https://developer.android.com/identity/authorization)
4. `READ_CALENDAR` remains a separate Android runtime permission. It grants local Calendar Provider access only; it does not grant Google Tasks API access. Conversely, the OAuth scope does not grant `CalendarContract` access. [CalendarContract](https://developer.android.com/reference/android/provider/CalendarContract), [Tasks API discovery](https://www.googleapis.com/discovery/v1/apis/tasks/v1/rest)
5. Do not embed a client secret or a refresh token in the APK. If unattended six-hour widget refreshes must work after an access token expires, send the GIS server authorization code to a backend and exchange/store tokens there; Google's Android guide identifies `AuthorizationResult.getServerAuthCode()` for this server-side flow, and its installed-app OAuth guide specifies authorization-code exchange and offline refresh tokens. [Android authorization guide](https://developer.android.com/identity/authorization), [installed-app OAuth](https://developers.google.com/identity/protocols/oauth2/native-app)

## Map to `calwidget`

Current flow:

```text
CalendarRepository -> CalendarContract.Instances -> EventInstance
    -> eventDotKinds / allAgendaEntries -> widget and agenda
```

`CalendarRepository.kt` reads only local `CalendarContract.Calendars` and `Instances`. `WidgetSettings.taskCalendarIds` and `EventKind.TASK` merely classify selected calendar IDs for purple rendering. That can remain as a local-provider display preference, but it must not represent Google Tasks integration.

Recommended shape (smallest correct addition):

```text
Google Tasks OAuth authorization
    -> TaskRepository (tasklists.list, then tasks.list)
    -> map due-day Tasks to the existing dot/agenda input model
    -> merge with CalendarRepository output at render time
    -> cache a per-widget Tasks snapshot alongside the existing calendar snapshot
```

Keep the two source identities separate: Calendar event IDs are local provider IDs; Google Task IDs are opaque API strings. Add a selected Google Task-list ID setting rather than reusing `selectedCalendarIds`/`taskCalendarIds`. For v1, request only `tasks.readonly`, show open tasks due in the visible date window, and have taps open `Task.webViewLink` when supplied. Add write methods only when task editing is explicitly required.

The app has no backend today. Therefore choose deliberately: foreground/manual Tasks refresh can use the Android client authorization path; reliable periodic background Tasks synchronization needs the server-side authorization-code/refresh-token path above. This recommendation is an inference from Google's documented short-lived access-token and server-code flows, not a claim that CalendarContract can refresh Tasks.
