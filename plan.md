# Weeklight Android Calendar Widget — Implementation Plan

Status: recommended staged plan  
Source: current HTML prototype plus three independent Terra reviews

## Product decision

Build a glance-first Android home-screen widget that:

- Shows a configurable 1–8 week date grid.
- Uses a compact header: previous arrow, month title/subtitle, next arrow.
- Opens the selected date in Google Calendar when tapped.
- Lets users choose which device-synced calendars contribute event dots.
- Shows one dot color, capped at three dots per date.
- Mutes dates belonging to adjacent months.
- Supports widget resizing and light/dark/system appearance.
- Keeps calendar data on the device.

The MVP should not add an account backend, custom event database, event editing, agenda views, or Google OAuth.

## Recommended tech stack

| Area | Recommendation | Reason |
|---|---|---|
| Language | Kotlin | First-class Android support and concise date/widget code. |
| Configuration app | Jetpack Compose + Material 3 | Fast, accessible settings UI and native Android theming. |
| Home-screen widget | AppWidgetProvider + RemoteViews | Predictable fit for a dense grid with many independent tap targets, arrows, and launcher compatibility. |
| Calendar data | Android CalendarContract / CalendarContract.Instances | Reads Google calendars already synced to the device without OAuth, API keys, a server, or network permission. |
| Settings/cache | AndroidX DataStore Preferences | Small per-widget settings: calendar IDs, week count, anchor range, theme, and last successful refresh. |
| Refresh | Widget lifecycle callbacks, calendar/timezone/date broadcasts, plus WorkManager as a best-effort fallback | Android controls widget refresh timing; do not promise exact refresh intervals. |
| Date/time | java.time: LocalDate, ZoneId, ZonedDateTime | Correct month boundaries, DST, and local-date mapping. |
| Build | Gradle Kotlin DSL, AndroidX, version catalog | Standard dependency and build management. |
| Minimum Android | API 26 initially | Broad device coverage while retaining modern java.time APIs. Validate this against the final target-device matrix. |
| Permissions | READ_CALENDAR only for MVP | Read-only access is sufficient; do not request write access. |
| Network | None for MVP | Device-synced Google Calendar data is enough for the stated product. |

Use Glance only if a later prototype proves its widget limitations do not hurt the grid, resizing, or per-cell interactions. Do not introduce both Glance and RemoteViews.

## Stages

### Stage 0 — Prototype lock and feasibility spike

Goal: confirm the prototype’s interaction model on a real Android launcher before building data integration.

Deliverables:

- Capture the approved interaction rules from the HTML prototype:
  - 1–8 weeks.
  - Previous arrow, month title/subtitle, next arrow.
  - Previous/next navigation moves one displayed range.
  - Adjacent-month dates are muted.
  - One event dot color, maximum three dots.
  - Date tap opens Google Calendar.
  - Resizable widget with light/dark/system theme.
- Create a tiny native spike with a static grid and real RemoteViews.
- Test Pixel Launcher and at least one non-Pixel launcher.
- Record the smallest legible widget size and the maximum useful week count at each size.

Done when:

- The grid is readable at the smallest supported size.
- Arrows and date cells receive the correct PendingIntent.
- Resize callbacks can select a compact layout without crashing.
- The team accepts whether 8 weeks remains readable on small widgets.

Risk to resolve: the prototype’s 8-week option may be too dense on small launchers. Keep the user-facing range 1–8 unless real-device testing proves a size-specific ceiling is necessary.

### Stage 1 — Native app and widget shell

Goal: install the app, add the widget, configure it, and render static data.

Deliverables:

- Kotlin Android app with one Compose configuration activity.
- App widget metadata with resize support.
- WeeklightWidgetProvider.
- Static RemoteViews grid matching the prototype.
- Per-widget configuration keyed by appWidgetId.
- DataStore persistence for:
  - Selected calendar IDs.
  - Week count.
  - Display anchor/range.
  - Theme mode.
- Header arrows that page the displayed range.
- Date cells that launch a calendar date intent.
- Multiple widget instances with independent settings.

Done when:

- Two widget instances can have different week counts and calendar selections.
- Rebooting or recreating the launcher preserves settings.
- Arrow navigation updates only the tapped widget.
- The widget has no network dependency.

### Stage 2 — Calendar access, selection, and event dots

Goal: replace static mock dots with selected, device-synced calendar data while keeping all provider work off the launcher thread.

Scope boundary:

- Use Android CalendarContract only.
- Request READ_CALENDAR only after the user initiates calendar selection.
- Do not add Google OAuth, backend sync, event editing, event titles, or recurring background jobs.
- Persist selected calendar IDs per widget; do not persist event content.

Deliverables:

1. Permission and picker flow

- Add READ_CALENDAR to the manifest and request it from the Compose configuration flow only after the user taps Choose calendars or Show event dots.
- Explain that access is read-only and used to show event dots on this device.
- Query CalendarContract.Calendars on an IO thread using only:
  - _ID
  - CALENDAR_DISPLAY_NAME
  - ACCOUNT_NAME
  - ACCOUNT_TYPE
  - VISIBLE
- Show calendar name plus account context so duplicate names are distinguishable.
- Allow zero or more calendars to be selected.
- Revalidate saved IDs whenever the picker opens or the widget refreshes.
- Remove deleted or unavailable IDs from the usable selection.

2. Visible-range event query

- Derive the query range from the widget’s displayed weeks and the device system ZoneId.
- Query CalendarContract.Instances only for the visible range and selected calendar IDs.
- Project only fields needed for dots:
  - CALENDAR_ID
  - EVENT_ID
  - BEGIN
  - END
  - ALL_DAY
  - EVENT_TIMEZONE
- Run queries on Dispatchers.IO or an equivalent bounded background executor.
- Pass a ready date-to-count map into the existing RemoteViews renderer.
- Process each widget independently so one provider failure does not block other widgets.

3. Date bucketing

- Add a pure event bucketing helper that produces LocalDate to dot count.
- Use CalendarContract.Instances for expanded recurring occurrences; do not implement recurrence rules.
- Count a multi-day event once on each applicable local date.
- Treat timed-event END as exclusive so an event ending exactly at midnight does not mark the next day.
- Treat all-day events as date ranges using their provider date semantics; do not shift them accidentally through the device timezone.
- Handle month/year boundaries and DST transitions with calendar-date iteration, not fixed 24-hour millisecond increments.
- Clamp each date to a maximum of three rendered dots.
- Do not deduplicate separate overlapping events by title; the widget only needs presence/count.

4. Widget states and refresh behavior

- Refresh on widget creation/update, configuration save, arrow navigation, and permission grant.
- Refresh after date/time/timezone changes when the app receives those lifecycle signals.
- Keep the existing per-widget settings and page state isolated by appWidgetId.
- Render distinct states instead of treating every failure as “no events”:
  - Calendar access needed.
  - No calendars available on this device.
  - No calendars selected.
  - Calendar data unavailable.
- Preserve the last successful dot snapshot when a query fails, if a small per-widget snapshot is introduced.
- Do not start periodic WorkManager refresh in this stage; leave a clear refresh entry point for Stage 4.

Acceptance criteria:

- No permission prompt appears on first launch or widget placement.
- Permission is requested only when calendar selection/event dots are initiated.
- Denial and later revocation produce a clear retry or system-settings action.
- Users can select multiple calendars, save an empty selection, and change selections later.
- Selection persists independently for each widget.
- Only selected calendar IDs contribute dots.
- Each date shows zero to three dots.
- Recurring, all-day, multi-day, midnight-ending, DST, and month/year-boundary cases are correct.
- Calendar I/O never blocks onUpdate, arrow handling, or RemoteViews construction.
- Provider errors do not crash the launcher or silently look like an empty calendar.
- Calendar names, account names, titles, attendees, descriptions, and locations are not logged or stored.

Tests:

- JVM tests for selected-calendar filtering, empty selection, stale IDs, single-day events, midnight endings, multi-day events, recurring instances, all-day ranges, DST boundaries, month/year boundaries, and the three-dot cap.
- Instrumented tests for CalendarContract calendar listing, visible-range filtering, permission denied/granted/revoked, provider errors, removed calendars, and updating one widget without changing another.
- Manual device checks with Google-synced calendars, multiple accounts, all-day events, multi-day events, timezone changes, and widget redraw after changing selection.

Deferred from Stage 2:

- WorkManager periodic refresh.
- Boot/content-observer background scheduling.
- OAuth for calendars unavailable through device sync.
- Event titles, agenda views, event editing, and remote storage.
### Stage 3 — Date-grid, rendering, and interaction completion

Goal: finish the user-visible calendar grid behavior that Stage 2 partially implemented, without duplicating its CalendarContract/event-query work.

Already covered by Stage 2:

- CalendarContract calendar and instance queries.
- Selected-calendar filtering.
- Local date bucketing for timed, multi-day, all-day, midnight-ending, and DST cases.
- Three-dot cap.
- Basic RemoteViews rendering, header arrows, date actions, and adjacent-month muting.

Remaining work:

1. Date-grid rules

- Keep the grid pure and testable: exactly 1–8 consecutive seven-day weeks.
- Decide and document the week-start rule. Recommended: use the device locale’s first day of week; keep a user-configurable week start deferred.
- Localize weekday labels instead of hardcoding English initials.
- Make page movement explicit: each arrow moves exactly the number of currently displayed weeks.
- Decide whether the page anchor is recalculated from today or retained as a fixed date anchor; avoid surprising jumps after resize or midnight.
- Define header semantics for ranges crossing months and years. Include years in subtitles when endpoints are in different years.
- Derive adjacent-month muting, header title, and subtitle from the same visible-range rule.
- Add a visual today state that remains understandable without color alone.

2. RemoteViews rendering

- Pass one explicit render model containing visible dates, dot counts, size bucket, theme, and status instead of mixing query and layout decisions.
- Replace height-only sizing with tested compact, standard, and tall buckets using available width and height.
- Ensure every supported bucket either renders the configured week count or applies a documented, tested size-specific rule.
- Prevent clipping at the smallest supported widget size; validate the 2-week minimum against actual layout height.
- Honor per-widget System, Light, and Dark settings in RemoteViews, including background, text, muted dates, arrows, and dots.
- Keep the three-dot cap and same-color event treatment.

3. Interaction and failure states

- Keep arrow and date PendingIntent identity unique to widget ID, direction, and target date.
- Make rapid arrow taps and resize/configuration updates race-safe so an older async query cannot overwrite a newer page.
- Add TalkBack content descriptions to every date cell, including full date and event count.
- Keep arrow labels actionable and distinct.
- Make permission-needed, no-calendars-selected, no-calendars-available, and provider-unavailable states distinguishable from “no events.”
- Give status states a clear route back to configuration or system settings.
- Validate the calendar date handoff across month/year boundaries and installed-calendar fallback cases.

Acceptance criteria:

- Every supported widget size renders without clipping.
- Grid order, page movement, header, subtitle, muted adjacent dates, and today state follow one documented rule.
- Weekday labels follow the device locale.
- System theme follows device theme; explicit Light and Dark remain stable when the device theme changes.
- Rapid navigation leaves the widget on the latest requested page.
- Date cells announce their date and event count with TalkBack.
- Each failure state is actionable and never silently looks like an empty calendar.
- Separate widget instances retain independent settings, pages, themes, and dot results.

Tests:

- JVM tests for 1/8-week bounds, seven-day rows, locale week starts, leap day, month/year crossings, page offsets, header/subtitle rules, adjacent-month status, today status, and resize/week-count behavior.
- Preserve Stage 2 event-dot tests and add fall-back DST, boundary clipping, invalid/zero-length event, and more-than-three-event cases.
- Instrumented tests for two-widget settings isolation, arrow navigation, date PendingIntent identity, permission/error states, and configuration refresh.
- Accessibility checks for date labels, event counts, arrows, and non-color event meaning.

Manual checks:

- Pixel Launcher plus one OEM launcher.
- Smallest, intermediate, widest, and tallest widget bounds.
- 1, 2, 4, and 8 configured weeks.
- Light, dark, and explicit theme modes.
- Sunday- and Monday-start locales, long month names, leap day, and December/January.
- Rapid arrow taps during resize.
- Permission revoked, no selected calendars, provider failure, and no calendar app installed.

Deferred:

- WorkManager/boot/calendar-change background refresh.
- Cached last-good results after failed refresh.
- Full privacy/release hardening and launcher matrix.
### Stage 4 — Refresh, resilience, and launcher reliability

Goal: make the widget dependable after calendar changes, process restarts, resize operations, launcher differences, and background execution limits.

1. Refresh scheduling

- Create one shared WorkManager periodic worker for all active widgets, never one worker per widget.
- Use a six-hour best-effort periodic refresh with no network constraint and no expedited work.
- Trigger immediate refreshes for widget add/update, resize, configuration save, permission grant, and arrow navigation.
- Refresh all active widgets after date, time, timezone, and locale changes.
- Reconcile the periodic schedule after app replacement and cancel it when the last widget is removed.
- Do not add exact alarms, polling services, or a persistent calendar observer.
- Treat periodic refresh as eventual consistency, not real-time synchronization.

2. Per-widget cache and render key

- Store only app-private per-widget data:
  - Effective visible date range.
  - Sorted selected calendar IDs.
  - Timezone ID.
  - Permission state.
  - Refresh timestamp.
  - LocalDate to 0–3 dot counts.
- Never cache event titles, descriptions, attendees, locations, account names, or other event metadata.
- Treat a snapshot as valid only when its selection, effective range, timezone, and permission state match the current widget.
- Invalidate on configuration save, arrow navigation, resize-driven range changes, timezone/date changes, and permission grant/revocation.
- Replace snapshots atomically after successful queries.
- On provider failure, preserve and render only a matching last-good snapshot; never convert a failed query into zero events.
- Permission revocation must hide cached dots immediately.

3. Concurrency and widget state

- Keep the existing monotonically increasing render generation per widget.
- Before writing cache or updating RemoteViews, confirm the request is still the newest and its render key still matches current settings.
- Keep widget settings, page, cache, refresh status, and PendingIntent identity isolated by appWidgetId.
- Render explicit states:
  - Calendar access needed.
  - No calendars selected.
  - Calendar temporarily unavailable.
  - Matching cached data is stale.
- Do not block launcher callbacks on CalendarContract or cache I/O.

4. Launcher-safe resize contracts

Use both width and height to select deterministic buckets:

- Compact: 1–2 weeks when bounds are tight.
- Standard: 3–5 weeks at normal bounds.
- Tall: 6–8 weeks when height supports it.

Do not silently remove configured weeks. If the widget is below the minimum usable bounds for its configured count, show a compact “Resize taller/wider” state while preserving the saved week preference. Persist the effective rendered bucket separately from the configured count.

Validate compact → standard → tall → compact transitions, missing size options, rapid resize during refresh, and launcher restore. Adjust thresholds from measured Pixel/OEM bounds rather than assumptions.

5. Interaction reliability

- Keep arrow PendingIntent identity unique to widget ID, direction, rendered page size, and render generation.
- Keep date PendingIntent identity unique to widget ID, exact date, and render generation.
- Discard stale arrow actions instead of paging from outdated bounds.
- Confirm date taps still open the intended date after navigation, resize, refresh, process death, and launcher restart.
- Prefer Google Calendar when installed, then fall back to another calendar app or a browser handler.

Acceptance criteria:

- One periodic worker exists regardless of widget count.
- Date/time/timezone/locale changes refresh all active widgets without changing their settings.
- Widgets retain independent matching last-good snapshots across process restarts.
- Provider failures do not crash the launcher or look like “no events.”
- Permission loss hides calendar dots and shows an actionable state.
- No supported size clips, loses dots, or silently changes the saved week count.
- Two widgets never affect each other’s page, settings, theme, cache, or refresh.
- Rapid arrows and resize leave the widget on the latest requested state.

Tests:

- Unit tests for render-key equality, cache validity/invalidation, snapshot isolation, 3-dot persistence, stale-result rejection, permission revocation, and refresh-reason routing.
- Instrumented tests for worker uniqueness, widget add/remove, two-widget refresh isolation, provider failure with/without matching cache, permission changes, and app-update reconciliation.
- Manual tests on Pixel Launcher, Samsung One UI, and one additional OEM launcher:
  - 1, 2, 4, 6, and 8 configured weeks.
  - Minimum, intermediate, and maximum bounds.
  - Rapid resize/navigation.
  - Process kill, launcher restart, reboot, date/time/timezone/locale changes.
  - Doze/battery restrictions.
  - Calendar edit while the app is closed.
  - Google Calendar installed, another calendar app only, and no calendar handler.

Deferred:

- Full privacy/release hardening, battery profiling, release APK inspection, beta rollout, crash monitoring, and store publication.
### Stage 5 — Release qualification and native hardening

Goal: make the current MVP reproducible, private, accessible, and safe to validate on real launchers. Do not add calendar features or start beta work until this stage passes.

#### 5.0 Reproducible green build

- Add the standard Gradle wrapper and document JDK 17 plus Android SDK platform 35.
- Repair WidgetSnapshotTest.kt to use the current RenderKey and DotSnapshot API; remove obsolete cache codec expectations.
- Add only the dependencies needed for JVM and widget instrumentation tests.
- Require these clean-checkout checks:
  - .\gradlew.bat :app:testDebugUnitTest
  - .\gradlew.bat :app:lintDebug
  - .\gradlew.bat :app:assembleDebug

#### 5.1 Lifecycle, privacy, and state safety

- Make the periodic worker execute and await the shared refresh operation instead of broadcasting and reporting success early.
- Keep one six-hour WorkManager job for all widgets; schedule with widget lifecycle and cancel after the final widget is removed.
- Handle package replacement and onRestored(oldIds, newIds) without cross-contaminating widget settings, page state, or cache.
- Make deletion cleanup completion-bound; do not rely on an unowned coroutine surviving process death.
- Exclude app-private settings/cache from backup, or disable backup for this prototype.
- Remove unused CalendarContract projection fields.
- Preserve no event titles, descriptions, attendees, locations, account names, or IDs in logs or cache payloads.

#### 5.2 Calendar handoff and provider semantics

- Prefer an explicit Google Calendar date intent when installed.
- Fall back to another calendar handler, then a browser URL; show an actionable no-handler state.
- Distinguish no calendars on device, no calendars selected, saved calendars unavailable, and temporary provider failure.
- Preserve selected IDs during temporary provider failure; remove stale IDs only after a successful calendar-list query.
- Keep all-day UTC handling, exclusive timed-event ends, recurring events, DST, and month/year boundary behavior covered by tests.

#### 5.3 Accessibility, localization, and rendering

- Move visible and accessibility strings into localized resources, including arrow labels, status states, event plurals, and the date handoff action.
- TalkBack must announce the full date and event count without exposing event metadata; setup and failure states must have an actionable settings path.
- Ensure Compose calendar-selection rows have labels and correct focus order.
- Replace text asterisks with a capped dot representation that remains legible in light/dark themes; keep event count in content descriptions.
- Preserve rounded widget backgrounds when applying explicit themes.
- Extract one pure size policy from the current thresholds. Keep the saved week count unchanged; show resize guidance when the host cannot fit it.
- Require measured minimum bounds for 48dp touch targets before claiming the 8-week layout is fully accessible.

#### 5.4 Verification gate

Automated:

- JVM: date grid, size policy, render-key/cache invalidation, stale-result rejection, permission revocation, calendar handoff selection, and snapshot isolation.
- Instrumented: widget add/remove/restore, package update, two-widget isolation, WorkManager uniqueness, permission changes, provider failure, resize/navigation races, and PendingIntent date handoff.
- Lint and release manifest inspection: exported components, permissions, backup policy, no INTERNET, and no secrets.

Manual launcher matrix:

- Pixel Launcher, Samsung One UI, and one additional OEM launcher.
- 1, 2, 4, 6, and 8 weeks at compact, normal, and tall bounds.
- Resize transitions, rapid arrows, process kill, launcher restart, reboot, locale/timezone changes, permission revoke, Google Calendar installed, alternate handler only, and no handler.
- Record device, Android version, launcher version, measured bounds, result, and known limitations.

Go only when the wrapper build is green, P0 privacy/lifecycle/accessibility checks pass, all launcher evidence is recorded, and a signed artifact passes manifest inspection. Otherwise remain NO-GO.

Deferred:

- OAuth, backend sync, analytics/crash SDKs, event titles/details, agenda/editing, per-calendar colors, content observers, exact alarms, foreground services, and per-widget workers.
### Stage 6 — Test, beta, and release

Goal: validate behavior on real devices and ship a small reliable MVP.

Automated tests:

- Week-grid generation.
- Previous/next range navigation.
- Adjacent-month styling.
- Month/year boundaries.
- One-to-three-dot cap.
- Recurring events.
- All-day and multi-day events.
- Timezone and DST boundaries.
- Missing/deleted selected calendars.
- Permission state transitions.

Instrumented/manual tests:

- Add, resize, configure, and remove the widget.
- Multiple widget instances with different settings.
- Pixel Launcher plus one major OEM launcher.
- Light, dark, and system appearance.
- Launcher restart and device reboot.
- Calendar permission denied, granted, and revoked.
- No synced Google account.
- Offline device.
- Google Calendar installed and unavailable.
- Date handoff at month/year boundaries.

Release checklist:

- Build a release APK/AAB and inspect the manifest.
- Confirm no INTERNET permission unless a future feature requires it.
- Confirm no Google OAuth credentials are present.
- Confirm no event content appears in logs or crash reports.
- Document tested Android versions and launcher limitations.
- Publish the privacy policy before beta distribution.

### Stage 7 — Width-adaptive agenda and semantic dot colors

Goal: make width changes meaningful, show every visible calendar item in a scrollable widget list, and let each widget customize Holiday, Event, and Task dot colors.

#### 7.1 Sizing contract

- Read the host's current width and height options on update and resize.
- Use one pure size policy for the seven-column grid plus a remaining list viewport; do not reject a supported widget solely because its width changed.
- Preserve the configured week count. Below the minimum usable bounds, show an actionable resize state instead of silently shrinking or clipping the grid/list.
- Test missing options, min/max bounds, narrow-to-wide transitions, and compact/standard/tall transitions.

#### 7.2 Complete scrollable agenda

- Replace the capped inline agenda and "+N more" suffix with a native ListView backed by RemoteViewsService/RemoteViewsFactory.
- Include every selected-calendar item in the widget's visible date range; no arbitrary two-row cap and no pagination.
- Sort by date, all-day first, start time, then event ID. Keep multi-day and midnight-ending semantics consistent with the existing event engine.
- Show an empty-state row only when there are no visible items and enough space exists.
- Keep event titles in widget memory/rendering only; do not add titles to the app-private cache.

#### 7.3 Explicit item kinds and settings

- Use provider-neutral classification:
  - Task: item belongs to a calendar the user marked as a task calendar.
  - Holiday: all-day item not in a task calendar.
  - Event: remaining timed item.
- Do not infer kind from titles, account names, or provider-specific heuristics.
- Add per-widget task-calendar selection for selected calendars, defaulting all calendars to Event.
- Add three per-widget ARGB color settings with a small accessible preset palette: Holiday, Event, Task.
- Apply the selected kind color to capped grid dots and list-row markers; expose kind in content descriptions so color is not the only signal.
- Preserve isolation through save, restore, delete, and configuration of multiple widgets.

#### 7.4 Implementation surface

- Modify WidgetSizePolicy, widget.xml, day_cell.xml, agenda_row.xml, WeeklightWidgetProvider, EventDots, WidgetSettingsStore, WidgetCache, MainActivity, colors resources, and AndroidManifest.
- Add AgendaRemoteViewsService and its manifest registration with BIND_REMOTEVIEWS.
- Use collection fill-in PendingIntents scoped by widget ID and row date.
- Do not add Glance, a color-picker dependency, OAuth, network sync, or a separate task provider.

#### 7.5 Acceptance and verification

- Width resizing changes the rendered layout without clipping or a false width-only resize failure.
- The list scrolls and contains every visible item; "+N more" is absent.
- Holiday, Event, and Task dots/list markers use independently saved colors.
- Two widgets can have different task calendars, colors, ranges, pages, and themes without cross-update.
- TalkBack announces date, title, time, and kind; empty and resize states remain actionable.
- JVM tests cover size boundaries, kind precedence, three-dot cap/order, full agenda ordering, multi-day rows, settings/cache persistence, and render-key invalidation.
- Run a fresh testDebugUnitTest and assembleDebug; manually smoke-test Pixel Launcher plus one OEM launcher at narrow/wide/tall bounds and 1/4/8 weeks.

Deferred: Google Tasks/OAuth, provider-specific task adapters, free-form color picking, event editing, agenda pagination, analytics, and backend sync.
## Deferred scope

Add only after the MVP proves useful:

- Google OAuth for calendars that are not synced to the device.
- Per-calendar dot colors.
- Event counts or titles.
- Agenda/detail screen inside the app.
- Event creation/editing.
- Today jump.
- Configurable week-start day.
- Remote sync, backend services, analytics, or push notifications.

## Recommended first implementation slice

1. Build Stage 1 with static data.
2. Test the widget on two launchers and three sizes.
3. Implement Stage 2 permission/calendar selection.
4. Add the pure date/event-dot engine from Stage 3.
5. Do not add OAuth or a backend unless the device Calendar Provider fails a documented requirement.




