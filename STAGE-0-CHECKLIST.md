# Stage 0 device check

Open this project in Android Studio with Android SDK 35, then add Weeklight from the launcher widget picker. The widget deliberately shows sample dots; it does not read calendars.

- On a Pixel Launcher and one OEM launcher, check the compact header, muted adjacent-month dates, and 1-3 sample dots.
- Tap both arrows repeatedly, including across December/January. Check that each widget instance keeps its own page.
- Resize vertically: below 220 dp expect 2 weeks, from 220-399 dp expect 4, and at 400 dp or taller expect 8. Check clipping and touch targets.
- Tap a date with Google Calendar installed, then without it. Check the selected date opens in Google Calendar, another calendar app, or the browser fallback.
- Switch system light/dark mode and restart the launcher. Check colors, rendering, and that paging still works.

Run unit tests with `gradle :app:testDebugUnitTest` when Gradle and the Android SDK are available. This environment has neither installed.

