# Steps GPS Route

An Android app that shows your daily steps and lets you record walking routes with GPS.

- **Steps** come from the phone's own step counter, through Health Connect. The app shows them
  per day and per week, against a goal, and splits them into *workout* and *incidental* steps.
- **Trips** are GPS routes you record yourself with Start / Finish. You can view one on a map and
  export it as a GPX file.

There's no account, no cloud backend, no ads and no analytics. Everything is stored on the phone.
The UI follows the phone's language: English on an English phone, otherwise Hebrew (right to
left).

> The project and its package are still called **StepSplit** (`com.example.stepsplit`). Only the
> name shown to users changed.

## Screens

| Screen | What it shows |
|---|---|
| **Today** | Today's total, workout and incidental steps. Progress toward the daily goal, this week's total and the weekly goal (daily goal × 7; weeks start on Sunday). The time of the last sync, plus a banner when step data isn't available or a sync failed. |
| **History** | The last 7 days as a stacked bar chart (workout + incidental) with the daily goal line, plus an estimated distance per day. |
| **Stats** | Lifetime totals: all steps ever, estimated distance (0.75 m per step, not GPS), progress around the Earth and to the Moon, marathon equivalents, active days, average per active day, and best day. |
| **Trips** | Start, finish or resume a GPS trip, with live time, distance and GPS signal while recording. Below that, the list of finished trips. |
| **Trip detail** | Start/end time, duration and distance, the route on a map, and the buttons *Show whole route*, *Export GPX* and *Delete trip*. |
| **Settings** | Daily step goal, the workout-detection thresholds (advanced), and the permission and data-collection status. Debug builds also show debug tools (see below). |

## How it works

### Step counting

- **Source.** Health Connect's on-device step counting. It needs Android 14 or later with Health
  Connect's step counting available (SDK extension 20+). This is the only step source, and there's
  no fallback: on an unsupported phone the app says step tracking isn't available.
- **Collecting.** The phone counts steps all the time, whether the app is open or not. The app
  only *imports* the counts while it's in the foreground: when it opens or resumes, about once a
  minute while Today is on screen, and right after you grant permission. There's no background
  job.
- **Catch-up.** Each sync re-reads the last 6 hours, so late or corrected counts are picked up.
  The first sync, or one after a long break, reaches back **30 days**. If the app goes unopened
  for more than 30 days, steps from before that are never imported.
- **Storage.** Steps are stored as one row per minute that had steps. A sync only adds or
  corrects minutes and never deletes any, so a short or empty read can't erase history.
- **Workout vs. incidental.** This is a rule of thumb, not a measurement:
  1. Minutes with steps are grouped into walks. A pause of up to 2 minutes stays inside the walk.
  2. A walk counts only once it has been idle for 3 minutes.
  3. It's a **workout** if it lasts at least 10 minutes, has at least 8 minutes with steps, at
     least 600 steps, and averages at least 60 steps per minute. Otherwise it's **incidental**.

  All of these limits can be changed in Settings. Labelling never changes the total: every
  counted step is either workout or incidental.

### GPS trips

- **Recording.** You start a trip in the app. Recording then runs in a foreground service with an
  ongoing notification, so it continues with the screen off or in another app. You can finish
  from the notification or from the app.
- **Interruptions.** If Android stops the app during a trip, the next launch shows the trip as
  *interrupted*. You can resume it (leaving a gap in the route) or finish it at its last point.
- **Fixes the app keeps.** Location is requested every 10 seconds at high accuracy. A fix is
  rejected if it's less accurate than 30 m, older than 2 minutes, implies more than 6 m/s
  (≈ 22 km/h), contradicts the phone's reported speed, or is only drift while standing still.
- **Displayed route.** Before display, distance and GPX export, the stored points are cleaned of
  outliers and smoothed. All three use the same processed route, so they always agree.
- **Map.** Tiles come from [OpenFreeMap](https://openfreemap.org) (free, no API key) via
  MapLibre. The map supports pan, pinch zoom, double-tap zoom and rotation; tilt is off. The scale
  bar is always metric. Without internet, the screen shows an offline outline of the route
  instead, with a *Retry map* button.

### The two features are separate

Step counting and GPS trips share only the database file, the app process and navigation. Trip
code never reads or writes step data, and step code never uses location. A trip doesn't affect
your step counts, and deleting one never removes steps. Two test files enforce this:
`ArchitectureInvariantsTest` scans the code for cross-imports, and `StepGpsSeparationTest` runs
both features together.

## Permissions

| Permission | Used for | When it's asked |
|---|---|---|
| Health Connect *Read steps* | Step counting | When you tap the grant button on Today |
| Fine and coarse location | GPS trips (precise location is required) | When you tap *Start trip* or *Resume* |
| Notifications | The recording notification | With location, on Android 13+. Recording still works if you deny it. |
| Foreground service (location) | Recording with the screen off | Granted automatically |
| Internet | Map tiles on the trip detail screen only | Granted automatically |

The app never asks for background location.

## Privacy and data

- Everything stays in a local Room database (`stepsplit.db`) and local settings files.
  `allowBackup` is off, so this data isn't included in Android's cloud backup.
- The only network traffic is map-tile requests, made while a trip's detail screen is open. They
  don't include your GPS points, but like any map they reveal which area you're viewing (plus the
  usual IP address and similar request details) to OpenFreeMap.
- Step data is never written back to Health Connect.

## Project structure

Single module (`app`), Kotlin + Jetpack Compose, with dependencies wired by hand
(`di/AppContainer.kt`, no DI framework). Data flows from Room/DataStore through a repository into
ViewModels with `StateFlow`, and from there to the Compose screens.

```
app/src/main/java/com/example/stepsplit/
  ui/               Compose screens (today, history, stats, trips, settings), navigation, theme
  di/               AppContainer and ViewModelFactory
  domain/           Pure Kotlin, no Android: walk classification, aggregation, time,
                    lifetime stats, and the trip route logic (point acceptance, cleanup, smoothing)
  data/
    stepsource/     Health Connect step source (+ a fake for tests and debug data)
    repository/     StepRepository: imports, stores and classifies steps
    trip/           TripRepository, GPS location client, recording coordinator
    local/          Room database, entities and DAOs
    settings/       DataStore settings (goal, thresholds, last sync)
  trip/service/     The trip recording foreground service and its command handling
  sync/             Cancels a background job left by older app versions (nothing is scheduled)
  debug/            Debug-only sample data and device diagnostics
```

## Build, install, test

You need **JDK 21** (the unit tests need it; building alone works with JDK 17) and the Android SDK
with platform 36.

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # installs on every connected device and emulator
./gradlew testDebugUnitTest    # unit tests (JVM + Robolectric)
./gradlew lintDebug
```

- **Tests.** About 450 unit tests. Business logic is pure Kotlin; Room and the screens' ViewModels
  run under Robolectric. There are no instrumented (on-device) tests.
- **Real phone.** Step counting needs a real phone with Health Connect step counting, since an
  emulator reports step tracking as unavailable.
- **Emulator.** Trips can be tested on an emulator by feeding simulated positions with
  `adb emu geo fix <longitude> <latitude>`.
- **Debug tools.** Debug builds (`com.example.stepsplit.debug`) add a *Debug tools* section in
  Settings: *Generate sample data* (a week of fake steps, stored under a separate source so they
  never count toward lifetime stats), a step source check, and device diagnostics. None of this
  exists in release builds.

## Database

- **Version.** Room schema version **7**. Each version's schema is exported to `app/schemas/` and
  committed. Migrations never delete steps or change step counts; the 5→6 migration did drop the
  old manual-relabelling table (`session_overrides`).
- **Migration tests.** Each step from 1→7 has a test in `StepSplitDatabaseMigrationTest`. It
  builds the old schema from its exported JSON, migrates, and lets Room validate the result.
- **Unused leftovers.** Some tables and columns from removed features are kept but unused:
  `manual_walks`, `activity_intervals`, `motion_evidence`, `temporal_continuity_state`,
  `step_counter_samples`, and the validation columns in `step_buckets`. Dropping them would need
  its own migration.
- **Step source id.** Steps from Health Connect are stored under the source id
  `local_recording_api`, the old source's id, so earlier history stayed in place without a
  migration.

## Known limitations

- **Steps only update while the app is open.** Counting itself never stops; after more than 30
  days without opening the app, older steps are never imported.
- **Android 14+ only for steps.** Older phones get no step counting and no fallback.
- **Workout vs. incidental is a guess.** A long brisk walk to a shop counts as a workout.
- **Fast movement stalls GPS recording.** Fixes are only compared with the last accepted one. So
  after fast movement (bus, bike) or one bad fix that got through, new fixes keep being rejected
  until enough time passes.
- **GPS can read "Searching" while you stand still.** This happens after 30 seconds without
  movement, because no new points are accepted then.
- **Killed trips stop recording.** If Android stops the app mid-trip, recording stops until you
  resume.
- **A permission answer can be lost.** If the screen rotates while a permission dialog is open,
  the answer is lost and you need to tap *Start trip* again.
- **Brief "invalid goal" text on launch.** On a cold start, Today shows zeros and "Enter a valid
  step count" for a moment until data loads.
