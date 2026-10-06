# Weeklight

Weeklight is a native Android home-screen widget that shows a configurable calendar overview at a glance. It renders several weeks of local calendar events as colored dots, includes an agenda list, and provides previous/next navigation for nearby date ranges.

The companion setup screen lets users:

- Choose which device calendars to display.
- Mark selected calendars as task calendars.
- Optionally connect Google Tasks and select task lists for read-only task dots and agenda entries.
- Customize event, holiday, and task colors.
- Use system, light, or dark appearance.

The widget is implemented in Kotlin with Jetpack Compose for configuration and `RemoteViews` for the launcher widget. Calendar data comes from Android `CalendarContract`; Google Tasks uses Google Identity Services and the Google Tasks API. Widget data is cached so the last valid view can be shown when a refresh is unavailable.

## Requirements

- Android SDK 35
- JDK 17
- Docker, if using the container build

## Build locally

From the repository root:

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/weeklight.apk`.

Run the unit tests with:

```bash
./gradlew test
```

## Build the APK with Docker

The Docker build uses an Android SDK image with the required JDK and runs the checked-in Gradle wrapper. From the repository root:

```bash
docker run --rm \
  -v "$PWD:/workspace" \
  -w /workspace \
  ghcr.io/cirruslabs/android-sdk:35 \
  ./gradlew assembleDebug
```

On Windows PowerShell, use `${PWD}` as the volume source:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace ghcr.io/cirruslabs/android-sdk:35 ./gradlew assembleDebug
```

The generated APK remains in `app/build/outputs/apk/debug/weeklight.apk` on the host.

## Google Tasks setup

Google Tasks requires a Google Cloud project with the Google Tasks API enabled, an OAuth consent screen, and an Android OAuth client configured for package `com.weeklight`. The OAuth client must include the SHA-1 certificate used to sign the APK. The app requests the read-only `tasks.readonly` scope only when Google Tasks is connected.
