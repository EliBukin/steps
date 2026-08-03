# Bukin's Split Step

A lightweight, fully offline Android app that counts daily steps and splits them into **workout
walking** and **incidental/everyday** movement. Hebrew + RTL is the default UI language, with
English as a fallback locale.

No account, no cloud backend, no ads, no analytics. Location is used only while you are actively
recording a manually started trip (see "Trip Route Recording" below) - never in the background,
and never for the automatic step tracking above.

## What it does

- Automatically collects all steps in the background via periodic sync - there is nothing to
  start or stop, and no way to explicitly record a session.
- Detects walking sessions retrospectively from imported step data and splits them into
  workout-walk steps vs. incidental steps using a transparent, adjustable heuristic.
- Shows today's totals, daily/weekly goal progress (uncapped - 120% displays as 120%, not clamped to 100%), and a 7-day history with a stacked bar chart.
- Lets you manually correct any detected session's classification (workout vs. incidental) after
  the fact - the only manual intervention the automatic step-tracking side of the app offers.
- Lets you manually record a GPS route for an occasional hike or trip - a completely separate,
  user-controlled feature (**Trips** tab) that never touches automatic step tracking. See "Trip
  Route Recording" below.
- Works fully offline. `ACTIVITY_RECOGNITION` is required for automatic step tracking; location
  permissions are requested only if/when you start a trip.

## Architecture

Single Gradle module (`app`), manual dependency injection (`di/AppContainer.kt`), unidirectional
data flow from Room/DataStore through a repository, into `StateFlow`-based ViewModels, into
Compose UI.

```
ui/            Compose screens (Today, History, Sessions, Trips, Settings), navigation, theme
di/            Hand-written AppContainer + ViewModelFactory (no DI framework)
domain/        Pure Kotlin, no Android deps: classification, aggregation, time, models, trip
data/
  local/       Room entities/DAOs (step_buckets, walk_bouts, session_overrides, trips,
               trip_points; manual_walks is deprecated - see Schema notes)
  settings/    Preferences DataStore (daily goal, thresholds, last sync time)
  stepsource/  StepSource interface + LocalRecordingStepSource + FakeStepSource
  repository/  StepRepository - the single place that imports, normalizes, classifies, merges
  trip/        TripRepository + TripLocationClient (Fused/Fake) + TripRecordingCoordinator -
               entirely separate from repository/ above
sync/          StepSyncWorker (CoroutineWorker) + WorkManager scheduling/factory
trip/service/  TripRecordingService - dedicated location foreground service, separate from sync/
debug/         Debug-only sample data seeder
```

The `domain` package has zero Android dependencies, so the classification and aggregation logic
is plain JVM unit-testable without Robolectric or a device.

## Step acquisition

Steps come from the **accountless Recording API on mobile** (`FitnessLocal.getLocalRecordingClient`,
`LocalDataType.TYPE_STEP_COUNT_DELTA`, `LocalRecordingClient`, `LocalDataReadRequest`) -
`com.google.android.gms:play-services-fitness:21.3.0`. This is deliberately **not** the deprecated
account-based Google Fit API (`Fitness.getRecordingClient`, Google Sign-In, History API).

- `LocalRecordingStepSource` (`data/stepsource/LocalRecordingStepSource.kt`) checks the
  `ACTIVITY_RECOGNITION` permission and the minimum Google Play services version using
  `LocalRecordingClient.LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE` - the steps-only floor,
  not the general `LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE` (documented as a higher requirement).
  Since this app never subscribes to any local fitness data type other than steps, using the
  general constant would reject devices that support local step recording but not every other
  local fitness data type.
- Subscription (`subscribe(TYPE_STEP_COUNT_DELTA)`) is idempotent and is **never** routinely
  unsubscribed - unsubscribing makes previously recorded data unavailable.
- Reads use `LocalDataReadRequest.Builder().aggregate(TYPE_STEP_COUNT_DELTA).bucketByTime(1, MINUTES)`,
  normalizing into one-minute buckets. The response's own `Status` is checked before any bucket is
  processed (`toRawIntervalsOrThrow()` in the same file): a completed `Task` does not by itself
  mean the read succeeded, and treating a non-success status as "zero buckets" would let
  `StepRepository` delete previously stored data in its reconciliation window based on that false
  emptiness. A non-success status throws `StepSourceReadException` instead, which surfaces as an
  ordinary failed sync - see "Synchronization health" below.
- `StepSource` is a small interface (`data/stepsource/StepSource.kt`) so a future Health Connect
  or sensor-based provider could be added without touching classification, aggregation, or UI.
  `FakeStepSource` is the interface's other implementation, used by unit tests and the debug-only
  sample data seeder.

### Recording API retention limitation

The Local Recording API only retains a limited rolling history on-device - documented as **10
days** for successfully subscribed local data. `StepRepository` treats it as a *feed*, not
storage: every successful read is immediately imported into Room, which is the durable source of
truth. On first subscription (or after a long gap with no successful sync) the repository reads
back up to that full 10-day retention floor; ordinary syncs instead re-read a 6-hour rolling
overlap window ending at the last known bucket, so late/corrected buckets reconcile without
re-requesting the full history every time. Data older than what the API still retains at first
launch can never be recovered - this is an inherent, documented limitation of the platform API,
not a bug.

## Required permissions

- `android.permission.ACTIVITY_RECOGNITION` - automatic step tracking. Requested at first launch.
- `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` - Trip Route Recording only. Requested only
  when you tap "Start trip", never at app launch and never just from opening the Trips tab. See
  "Trip Route Recording" below for the full permission flow.

No internet permission at all - the app is fully offline, including trip recording (no maps, no
map tiles, no upload).

## Data storage and privacy

Everything lives in a local Room database (`stepsplit.db`) and a local Preferences DataStore
file - nothing leaves the device. There is no account, no analytics/telemetry, no crash reporter,
and `android:allowBackup="false"` so step data is not swept into cloud backups. Release builds do
not log step/activity data (see `debug/DebugDataSeeder.kt` and the `BuildConfig.DEBUG` gates in
Settings for the only debug-only surface).

### Schema notes

- `step_buckets`: one row per active (steps > 0) minute, unique on `(source, startEpochSecond)`.
  Re-importing an interval **replaces** the existing row (`OnConflictStrategy.REPLACE`), so
  repeated or overlapping reads can never duplicate steps. Each row also stores the `zoneId` and
  precomputed `localDate` captured at import time, so a later device timezone change never
  retroactively changes which calendar day a past step belongs to.
- `walk_bouts`: the cached AUTO classification result. Fully regenerated inside a transaction
  every time the classifier reruns (see below) - it is a derived cache, never a source of truth.
  Every row is stamped with the `CLASSIFIER_VERSION` (`domain/classification/Classification.kt`)
  that produced it. If the algorithm changes, `CLASSIFIER_VERSION` is bumped and any row stamped
  with an older version is treated as stale: `StepRepository` recomputes it from the raw
  `step_buckets` history the next time a sync runs (even if that sync's remote read itself fails
  or the source is unavailable - the recompute never depends on it), and in the meantime
  `observeSessions()` filters stale rows out directly so nothing outdated is ever shown, even
  briefly. Independent of that version check, `StepRepository` also unconditionally recomputes
  once on the very first classification check of its own process lifetime (regardless of whether
  any row is version-stale, or whether `walk_bouts` is empty) - this restores a pending trailing-
  bout finalization deadline that a killed-and-restarted process would otherwise have no memory of
  (see "Automatic classification heuristic" below).
- `session_overrides`: manual reclassifications, keyed by the bout's stable start-time anchor,
  stored **separately** from `walk_bouts` so regenerating the AUTO cache can never discard a
  manual correction.
- `manual_walks`: **deprecated**. It backed an earlier explicit "Start walk / Finish walk" feature
  that has since been removed in favor of fully automatic, retrospective detection. No product
  code reads or writes it anymore. The table, its Room entity, and the version 1→2 migration that
  added columns to it are kept exactly as they were - not deleted - because safely dropping a
  table needs its own dedicated migration, and doing that opportunistically alongside an unrelated
  change would be riskier than just leaving inert, unused rows in place. Removing it is left to a
  future, dedicated migration. Any rows an earlier app version wrote there are simply ignored.
- No destructive-migration fallback. Room migrations are additive-only going forward
  (`StepSplitDatabase.MIGRATIONS`).
- Exported schema JSON (`app/schemas/com.example.stepsplit.data.local.StepSplitDatabase/{1,2}.json`,
  from `ksp { arg("room.schemaLocation", ...) }` in `app/build.gradle.kts`) is committed to the
  repository rather than git-ignored, so a schema change shows up as a reviewable diff and the
  exact version-1 `createSql` is available for migration tests to build from (see below) without
  reverse-engineering it from the entity source.

## Automatic classification heuristic (and its limits)

A phone cannot know user intent with certainty - a continuous walk to a store can look identical
to a walking workout. `domain/classification/WalkClassifier.kt` is a pure function with no
Android dependencies:

1. Keep only minutes with steps > 0.
2. Group consecutive active minutes into a *bout*: an idle gap of up to **2 minutes**
   (`maxGapMinutes`) stays inside the same bout; anything longer starts a new one.
3. The **trailing** (most recent) bout is retrospective, not live: more of it could still arrive
   on a later sync, so it is only classified and surfaced as a session once **3 minutes**
   (`idleFinalizeMinutes`, always greater than `maxGapMinutes`) worth of fully-elapsed minutes have
   passed since its last active minute. Until then it is withheld entirely - not shown on the
   Sessions screen, and its raw steps count as incidental in daily totals rather than being
   prematurely counted as a workout. Earlier, non-trailing bouts are never withheld. `WalkClassifier.classify()`
   takes the current instant as a required (no default) explicit parameter for this rather than
   reading a system clock itself, so it stays a pure function of its inputs - `StepRepository`
   supplies it from its own `Clock` on every classifier rerun.

   A withheld trailing bout does not have to wait for the next sync (periodic syncs are ~6 hours
   apart) to actually appear: `StepRepository.rescheduleFinalizationJob` schedules a single
   cancellable, one-shot local coroutine timer for the exact remaining delay every time the
   classifier reruns. When it fires, it reclassifies from the raw data already in Room - it never
   touches the step source again. New raw data or a threshold change (both of which rerun the
   classifier) replace any still-pending timer with a freshly computed one rather than stacking
   duplicates, and the timer's owning `CoroutineScope` lives exactly as long as `StepRepository`
   does (the process lifetime in production), so it can never outlive its owner.

   That timer is purely in-memory, so it does not survive the process being killed while a bout is
   still withheld. `StepRepository` does not need any separate persisted deadline to recover from
   this: on the very first classification check of a fresh process (see "Schema notes" above), it
   unconditionally reruns the classifier against the raw `step_buckets` already in Room - which
   re-derives the correct current state either way. If the deadline already passed while the
   process was dead, that recompute finalizes the bout immediately, in the same call; otherwise
   `rescheduleFinalizationJob` schedules a fresh timer for whatever delay actually remains. This
   recovery runs before `syncNowLocked` even checks step-source availability, so it happens
   regardless of whether the source is reachable.
4. Classify a finalized bout as a likely **workout** only if it clears *every* threshold:
   elapsed duration ≥ 10 min, active minutes ≥ 8, steps ≥ 600, cadence ≥ 60 steps/min. Otherwise
   it is **incidental**.
5. Every bout gets a confidence (0.5 at exactly-at-threshold, up to 1.0 as metrics clear the
   thresholds) and a structured reason code (localized in the UI, not hardcoded English).

All six thresholds are user-editable in Settings ("Advanced"), with a reset-to-defaults action.
They are an initial heuristic, not an objective truth - the Settings screen says so explicitly.
Regardless of `idleFinalizeMinutes`, every raw step is always counted somewhere:
`totalSteps == workoutSteps + incidentalSteps` holds whether or not the trailing bout has
finalized yet, since daily totals are aggregated from raw buckets, not from sessions.

### Manual reclassification

Any auto-detected session can be reclassified from the Sessions screen, between **Workout** and
**Incidental activity**. A reclassification is stored in `session_overrides`, separate from the
derived `walk_bouts` cache, and **always wins** over the automatic result when the UI/aggregation
layer merges them (`domain/model/SessionMerger.kt`). Rerunning the classifier (which happens on
every sync) fully regenerates `walk_bouts` but never touches `session_overrides`, so manual
corrections survive indefinitely - including the case where more data arrives later and a bout
that used to be too short for a workout grows into one.

This is the only manual intervention in the app. There is no way to explicitly start, stop, or
otherwise directly record a session - every session on the Sessions screen is detected
retrospectively from imported step data, and reclassifying one only changes how it is labeled,
never what raw steps it covers or how many steps count toward the day's total.

## How periodic synchronization works

`sync/StepSyncWorker.kt` is a `CoroutineWorker` scheduled as **one unique periodic WorkManager
job** (`SyncScheduler`, `enqueueUniquePeriodicWork` with `ExistingPeriodicWorkPolicy.KEEP`) every
~6 hours. `ExistingPeriodicWorkPolicy.KEEP` makes it safe to call on every app start - it never
resets the schedule or creates a duplicate job. A missing permission or unavailable API is treated
as a non-transient condition (`Result.success()` - retrying will not fix it); an unexpected
exception is treated as transient (`Result.retry()`, with exponential backoff). The worker is
constructed via a hand-written `WorkerFactory` (`StepSyncWorkerFactory`) wired through
`StepSplitApplication : Configuration.Provider`, so it receives the same `StepRepository` as the
rest of the app - no reflection-based construction.

A `Mutex` inside `StepRepository` serializes the import → normalize → store → reclassify
pipeline, so a periodic sync racing an app-resume sync cannot interleave writes; Room's
`withTransaction` blocks additionally guarantee the multi-statement writes (bucket upsert, and
separately, bout-cache replace) are each atomic.

## Synchronization health

Source **availability** (`StepSourceAvailability` - permission granted, Play services present)
and sync/collection **health** (whether the most recent sync attempt actually succeeded) are
tracked as two separate, orthogonal states - a device can be fully available while sync attempts
still fail for other reasons, and the UI never conflates the two:

- Every failure `StepRepository.syncNowLocked()` can produce - a failed `ensureSubscribed()`, or
  an exception from the read/import/classify pipeline - is recorded as a structured
  `SyncFailure(category, atEpochSecond)` (`domain/model/SyncFailure.kt`) via `SettingsRepository`,
  **not** as raw exception text. `SyncFailureCategory` has three localizable values:
  `SUBSCRIPTION_FAILED`, `READ_FAILED` (a `StepSourceReadException`, e.g. the non-success `Status`
  case above), and `UNKNOWN` for anything else unexpected.
- This is persisted to Preferences DataStore, not held in transient ViewModel state, so a failure
  recorded by a background `StepSyncWorker` run is visible on the Today screen (and in Settings)
  the next time the app is opened, not only right after a foreground refresh.
- A failure is only ever cleared by a **genuinely successful** sync, via
  `SettingsRepository.recordSuccessfulSync(instant)` - a single DataStore `edit` transaction that
  sets the new timestamp *and* removes the failure keys together, not two separate writes. An
  observer of `settings` can therefore never see an emission combining the new success timestamp
  with a stale failure, and cancellation between two separate writes can never leave one applied
  without the other. It is never replaced with a fake "zero steps" success, and the previous
  successful data/timestamp keep showing throughout.
- The Today screen shows a `SyncFailureBanner` (`ui/common/CollectionStatusBanner.kt`) whenever a
  failure is recorded, independent of the availability banner above it. In Settings, "Permission
  status" still describes availability only; "Data collection status" now describes sync health
  specifically (a recorded failure's message, or "active"/the availability reason when there is
  none) instead of just echoing the same availability text under both headings.
- `StepSyncWorker`'s retry behavior is unchanged: any `SyncResult.Failed` (whatever its category)
  still requests `Result.retry()`, so WorkManager keeps retrying transient failures with backoff.

## Trip Route Recording (manual GPS trips)

A second, completely independent recording mechanism for occasional hikes/trips - manually
started, manually finished, and never confused with the automatic step tracking described above.
**A manually recorded trip and an automatically detected walking session are different concepts.**
They may overlap in time, but neither owns or mutates the other: a trip never inserts or edits
`walk_bouts`, never creates a `session_overrides` row, never forces a workout classification, and
never changes daily step totals or duplicates steps. This MVP does not read step data at all - it
only persists a trip's own timestamps, route, and distance; a read-only association with
already-synced step data is left for a future version.

There is no way to enable/disable the feature in Settings. The **Trips** tab (between Sessions and
Settings, `Icons.Filled.Place`) is always visible; "off" simply means no trip is currently
recording, and opening the tab alone requests no permission and starts no service.

### Start / Finish, not continuous tracking

- Exactly one trip may be active at a time; tapping "Start trip" again while one is already active
  is a no-op (see `TripRepository.startTrip`'s idempotency below).
- **No automatic trip detection, no geofencing, no continuous daily location tracking, no
  background-location permission (`ACCESS_BACKGROUND_LOCATION`), no pause/resume.** Start and
  Finish are the only two user-managed states in this MVP.
- While idle, the app performs **zero** location requests and runs no location foreground
  service - `TripLocationClient.locationUpdates()` is a cold `Flow`, only ever collected by
  `TripRecordingCoordinator` while a trip is actually being recorded.

### Permission flow

Requested only after tapping "Start trip", with a rationale shown first:

1. `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (+ `POST_NOTIFICATIONS` on API 33+) via a
   single `ActivityResultContracts.RequestMultiplePermissions()` launcher in `MainActivity`.
2. **Precise granted** → the trip starts.
3. **Only approximate granted** → a second dialog warns that the recorded route will be much less
   precise before letting you start anyway (honest, not silently degraded).
4. **Both denied** → no trip is created; a message explains location is required, with a button to
   open the app's system settings page.
5. **System location services disabled** → a dialog offers a button straight to
   `Settings.ACTION_LOCATION_SOURCE_SETTINGS`.
6. **Notification permission denied** (API 33+) → recording still proceeds (a foreground service
   does not require it to run), but the active-recording screen honestly notes that no persistent
   notification will be visible, rather than claiming one is shown.

### Foreground service (`trip/service/TripRecordingService.kt`)

A dedicated `Service`, entirely separate from `sync/StepSyncWorker` - no `WorkManager` involvement
in continuous GPS recording. Deliberately kept thin: it owns only foreground-service mechanics
(promote to foreground *immediately* in `onStartCommand`, before any repository/coroutine work;
build the ongoing notification; react to `ACTION_START`/`ACTION_FINISH`) and delegates the actual
recording logic to `data/trip/TripRecordingCoordinator.kt`, a plain, Robolectric-free-testable
class with no Service/Activity/ViewModel/composable reference held anywhere.

- The ongoing notification (`FOREGROUND_SERVICE_TYPE_LOCATION`) opens the app straight to the
  Trips tab on tap, and has a "Finish" action targeting the service directly
  (`PendingIntent.getService`) - both the notification action and the in-app Finish button call
  the same idempotent `TripRepository.finishTrip`.
- Repeated Start commands never create a duplicate trip or a duplicate location subscription -
  `TripRepository.startTrip()` returns the existing `ACTIVE` trip's id if one already exists, and
  `TripRecordingCoordinator.start()` no-ops if already collecting. Repeated Finish commands are
  equally harmless.
- On Finish, `TripLocationClient.flush()` (backed by `FusedLocationProviderClient.flushLocations()`)
  is requested and given a short, bounded grace period (2s) for any already-batched fixes to land
  before the trip is marked finished - "flush when practical, never wait indefinitely."
  `TripRepository.recordAcceptedBatch` is a further backstop regardless: a location callback that
  arrives *after* `finishTrip` has already run finds the trip no longer `ACTIVE` and is silently
  dropped - it can never append a point or increase distance post-Finish.
- Location updates are removed (`awaitClose` inside `FusedTripLocationClient`'s `callbackFlow`) on
  every terminal path: normal Finish, an error, or the service's own `onDestroy` as a safety net.
- **Never starts automatically after boot.** There is no boot receiver anywhere in the manifest;
  the service is only ever started by an explicit user action (Start/Finish) or by Android itself
  restarting an already-running `START_STICKY` instance after process death.
- **Explicit Start and an OS restart are handled by different code paths, deliberately.**
  `onStartCommand` branches on whether the triggering `Intent` is null: an explicit `ACTION_START`
  (always a non-null `Intent`, see `TripsScreen.kt`) may create a new trip via the idempotent
  `TripRepository.startTrip()`, but a null-`Intent` OS restart may only *recover* an already-`ACTIVE`
  trip via `TripRepository.getActiveTripId()` - it never calls `startTrip()`. This matters because
  the two can race: if the app's own launch-time reconciliation (see "Honest recovery" below) has
  already marked the trip `INTERRUPTED` by the time a *delayed* OS restart finally arrives, there is
  no longer an `ACTIVE` trip to recover - the old code called `startTrip()` unconditionally here,
  which would silently create and start recording a second, unrelated trip. The fixed restart path
  instead finds nothing to recover and stops the service without creating or changing anything.
- **A location-registration failure never leaves the app silently claiming to record.**
  `FusedLocationProviderClient.requestLocationUpdates()` can reject registration *asynchronously*
  (its returned `Task` failing, with no exception thrown at the call site) - `FusedTripLocationClient`
  attaches a failure listener to that `Task` and closes its flow with the resulting exception, so a
  rejected registration is never silent. `TripRecordingCoordinator.start()` takes a cancellation-transparent
  `onFailure` callback (via `Flow.catch`, which never fires for a normal `stop()`) that
  `TripRecordingService` uses to mark the trip `TripState.INTERRUPTED` and tear down the foreground
  notification/service - the same honest recovery UI described below, triggered by a live failure
  instead of a launch-time check.

### Honest recovery after process death or force-stop

`trips.state` (`ACTIVE` / `FINISHED` / `INTERRUPTED`) in Room is the durable source of truth - the
UI observes it directly and never assumes a ViewModel or Activity staying alive means recording is
still active. If Android restarts the service after the process dies, it recovers the existing
`ACTIVE` trip from Room via the same idempotent `startTrip()` call a fresh Start uses - no
duplicate is ever created.

If the app was force-stopped (which cancels Android's own service-restart machinery) or the OS
simply never restarts the service, nothing pretends the missing interval was recorded. On the next
app launch, `TripRepository.reconcileActiveTripOnLaunch` checks whether the service's own
in-process liveness flag (`TripRecordingService.isRunning`) is true; if not, the trip is marked
`INTERRUPTED` (durably, in Room) rather than silently left `ACTIVE` or silently finished. The
Trips screen then lets you either:

- **Resume (with a gap)** - `TripRepository.resumeInterruptedTrip` flips it back to `ACTIVE`,
  preserving its existing distance/points, and the UI restarts the service.
- **Finish at last point** - ends the trip honestly at its `lastAcceptedPointEpochSecond` (or its
  start time, if it never received a single accepted point), never at "now."

This check has one inherent, accepted timing race: if Android is about to restart the service but
simply hasn't yet at the exact moment of the check, the trip is reported interrupted a little
prematurely. This needs real-device confirmation (see the device checklist below) rather than a
speculative heartbeat/timeout mechanism built for a case that may not matter in practice.

### Sampling and route-point acceptance (`domain/trip/`)

`FusedTripLocationClient` requests a hiking-oriented, conservative, centralized, and documented
`LocationRequest` (`PRIORITY_HIGH_ACCURACY`, 10s desired interval, 5s minimum interval, 8m minimum
displacement, up to 15s of platform batching) - tunable after real-device trail testing without
touching any other layer.

`RoutePointAcceptancePolicy.evaluate()` is a pure function (no clock/IO of its own) deciding
accept/reject for each raw fix, documented and boundary-tested for every threshold:

- Invalid coordinates (out of range, or the `(0,0)` "no fix" sentinel).
- Accuracy worse than 50m, or non-positive.
- Non-monotonic/duplicate capture timestamps relative to the last *accepted* point.
- Samples older than 120s relative to when they were actually processed (stale/delayed delivery).
- An implied speed above ~12 m/s (43 km/h) between consecutive accepted points - well above any
  realistic hiking/trail-running pace, so it only ever rejects a clear GPS-teleport artifact, never
  ordinary imperfect GPS noise.

A batch of fixes (which the platform may deliver out of order when batching) is always processed
in ascending capture-time order. Distance accumulates only between *consecutive accepted* points
(the first accepted point of a trip always contributes zero), and each accepted point's insert plus
the trip's updated distance/last-point-timestamp commit together inside one Room transaction, so a
crash between them can never leave the two inconsistent.

### Trip detail: offline route trace and delete

The MVP detail screen is deliberately minimal: date, start/end times (in the trip's own stored
`startZoneId`, consistent with its date - see "Schema" below - not the device's *current* zone), duration, distance, the offline route
trace, and delete. Estimated steps and GPX export were both cut from this first version (see
"Known limitations") to keep trips completely independent of the step pipeline and reduce MVP
surface area; both are straightforward to reintroduce later since `trips`/`trip_points` already
retain everything (timestamps, points) either would need.

- The route trace (`ui/trips/RouteTraceCanvas.kt`) is a plain Compose `Canvas` polyline over a
  pure, unit-tested normalization function (`domain/trip/RouteTraceGeometry.kt`) - a simple
  degree-based linear fit, **not** a real map/projection, and safe for empty, one-point, and
  perfectly horizontal/vertical routes (all handled explicitly, not just assumed away). No Google
  Maps, no map tiles, no API key/billing, no Internet permission, no routing, no offline map
  downloads - the app's offline character is fully preserved.
- **Delete trip** cascades (`ON DELETE CASCADE` on `trip_points.tripId`) to remove every point with
  it, with an in-app confirmation first.

### Schema (v2 → v3)

Additive-only, like every migration in this project - see "Schema notes" above for the general
policy. `MIGRATION_2_3` adds exactly two new tables and touches nothing existing:

- `trips`: stable id, start/optional-end timestamps, the trip's **starting** `zoneId` (so its
  display date/time stay stable even across a later device timezone change), durable `state`,
  accumulated accepted distance, latest-accepted-point timestamp, creation timestamp.
- `trip_points`: trip id (foreign key, `ON DELETE CASCADE`), capture timestamp, latitude/longitude,
  accuracy, optional altitude/speed, indexed on `(tripId, capturedAtEpochSecond)` for chronological
  queries.

`StepSplitDatabaseMigrationTest` extends the same file-driven approach used for v1→v2 (see "Migration
testing" below) with a `v2 to v3 full schema migration` test: it builds a complete v2 database from
the committed `2.json`, seeds one representative row into *all four* v2 tables (including the
deprecated `manual_walks`), migrates, and asserts every existing row survives untouched *and* the
two new tables are genuinely usable (a real insert through them, not just schema presence).

## UI and localization

Hebrew (`res/values/strings.xml`) is the default resource set; English (`res/values-en/strings.xml`)
is the fallback. `android:supportsRtl="true"`, and layout direction is derived explicitly from the
current locale rather than left to resource-fallback guesswork. No text is hardcoded in
composables - everything goes through `stringResource(...)`.

## Build and run

Requirements: JDK 17+ to build, Android SDK with `platforms;android-36` and
`build-tools;36.1.0`, and an `ACTIVITY_RECOGNITION`-capable device running Play services (a real
device is strongly recommended - the Recording API is unlikely to behave meaningfully on an
emulator with no real step sensor).

```bash
# from the StepSplit/ directory
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device/emulator:

```bash
./gradlew installDebug
```

## Run tests

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

**Note:** `testDebugUnitTest` uses Robolectric against `compileSdk = 36`, and Robolectric's API 36
shadow specifically requires **JDK 21+** to build its sandbox (`assembleDebug`/`lintDebug` only
need JDK 17+, per AGP 8.13's minimum). If `testDebugUnitTest` fails with
`Android SDK 36 requires Java 21 (have Java 17)`, rerun with `JAVA_HOME` pointed at a JDK 21+
install.

`connectedDebugAndroidTest` requires a physical device or running emulator; none was available in
the environment this project was built in, so it has not been run.

### Migration testing

`StepSplitDatabaseMigrationTest` genuinely parses the committed
`app/schemas/.../1.json` at test time (`org.json.JSONObject`, not a hand-copied approximation) and
executes its exact `createSql`/`setupQueries` strings verbatim to build the *complete* version-1
schema (all four tables, their indices, and the `room_master_table` identity row). It then opens
that database through a real `StepSplitDatabase` via
`Room.databaseBuilder(...).addMigrations(*StepSplitDatabase.MIGRATIONS)` - the same call
`StepSplitDatabase.build()` makes in production. That forces Room's own open-time validation: it
runs the real migration, then introspects every table's actual on-disk shape (columns, types,
nullability, indices) and compares it field-by-field against what its compiled v2 entities expect,
throwing if anything doesn't match. The same approach is reused by the `v2 to v3 full schema migration` test for the Trip Route
Recording tables - see "Trip Route Recording" → "Schema (v2 → v3)" above.

`MigrationTestHelper` was not used directly to drive this: as
of Room 2.8.4 it has a database-path resolution issue under Robolectric with `applicationIdSuffix`
set (found while first building this test). Parsing the already-exported JSON directly and letting
a real `StepSplitDatabase` open the result gives the same validation guarantee without depending on
that helper's own internals; it relies on Gradle running `testDebugUnitTest` with the `app/` module
directory as the JVM working directory, which was confirmed empirically rather than assumed. The
test also asserts the pre-existing `manual_walks` rows (finished and ongoing) survive the upgrade
unchanged, with the two new columns defaulting correctly. (Both this rewrite and the original
hand-built version were verified with a negative control - temporarily removing one column from
`MIGRATION_1_2` and confirming the test fails with Room's own expected-vs-found diff - before being
reverted.)

## Debug fake data

Settings → "Debug tools" (only visible/reachable when `BuildConfig.DEBUG` is true) has a
"Generate sample data" button. It runs `debug/DebugDataSeeder.kt`, which builds a week of
synthetic incidental + workout step intervals via `FakeStepSource.withSampleData(...)` and imports
them through the exact same normalize → store → classify pipeline as a real sync. It is gated by
`BuildConfig.DEBUG` both at the UI entry point and again inside the seeder itself, so it is inert
dead code in release builds - and it contains no real user data, so there is nothing sensitive to
leak even if invoked.

## Installing and testing on a real Android phone

1. On the phone: **Settings → About phone**, tap "Build number" 7 times to enable Developer
   options, then enable **USB debugging** under **Settings → Developer options**.
2. Connect the phone via USB and accept the "Allow USB debugging" prompt on the phone.
3. From the `StepSplit/` directory: `./gradlew installDebug` (or copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone and install it manually with unknown
   sources allowed).
4. Launch **Bukin's Split Step**. Grant the activity-recognition permission when prompted (or via the
   banner on the Today screen).
5. Walk around - the activity should show up, detected and classified retrospectively, within the
   ~6-hour sync window, or the next time you foreground the app (which also triggers a sync).

### Trip Route Recording device checklist

Automated tests cover the pure logic (acceptance policy, distance, route geometry, migration,
repository idempotency/recovery, location-failure/interrupted-trip handling) but **cannot**
exercise real permission dialogs, a real GPS receiver, or real process death - the following needs
a physical device, and has not been run in this environment. **The trip recorder is not considered
field-validated until every item below has actually been exercised on a physical device with the
screen off and the app backgrounded, not merely compiled/unit-tested.**

- [ ] Location permission granted, and denied.
- [ ] Approximate-only vs. precise location granted.
- [ ] System location services disabled, then re-enabled via the in-app deep link.
- [ ] Start a trip from the visible Trips screen.
- [ ] Screen off for at least 15 minutes while recording continues.
- [ ] Switch to another app while recording continues.
- [ ] Airplane mode / no data connection (recording is fully offline - GPS itself doesn't need a
      data connection, but worth confirming nothing degrades).
- [ ] Weak GPS signal or an outdoor→indoor transition (GPS status should show "Weak"/"Searching"
      honestly, not "Good").
- [ ] Finish from the in-app button, and separately from the notification action.
- [ ] Process death (e.g. "don't keep activities" / low-memory kill) followed by service
      restoration - confirm the *same* trip continues, not a duplicate.
- [ ] Force-stop the app mid-trip, relaunch, and confirm the interrupted-trip recovery UI appears
      honestly with both Resume and Finish-at-last-point working correctly.
- [ ] Process death (or force-stop) mid-trip followed by a *delayed* restart that arrives after
      reopening the app (and therefore after launch-time reconciliation already marked the trip
      `INTERRUPTED`) - confirm the app never ends up with two trips or a silently-resurrected one.
- [ ] A location provider/registration failure mid-trip (e.g. toggling location services off while
      recording) - confirm the app shows the interrupted-trip recovery UI rather than continuing to
      claim recording is active.
- [ ] A real hike/walk: route shape looks plausible on the trace, distance is in a reasonable
      range for the actual distance covered, and battery use over an extended recording is
      acceptable.

## Known limitations

- **Not tested on a real device or emulator** in this environment (none was available) - only
  `testDebugUnitTest`, `lintDebug`, and `assembleDebug` were run. The Local Recording API call
  surface was written from official documentation and verified to compile against the actual
  `play-services-fitness:21.3.0` AAR, but its real runtime behavior (permission dialogs, actual
  step data, Play services error paths) has not been exercised end to end.
- **compileSdk/targetSdk is 36 (Android 16), not 37.** API 37 platform stubs exist, but its
  build-tools were still release-candidate only (`build-tools;37.0.0-rc*`) at the time this
  project was built, and several current-stable androidx libraries (core-ktx 1.19.0, lifecycle
  2.11.0+) already require compileSdk 37 / AGP 9.1+. To keep the project fully on stable,
  non-preview tooling, AGP was pinned to the 8.13.x line and those specific androidx libraries to
  the latest versions still compatible with compileSdk 36 - see the version comments in
  `gradle/libs.versions.toml`.
- **AGP 9's built-in Kotlin compilation / new DSL was not adopted.** It is very new (AGP 9.0
  shipped ~January 2026) and broke on first contact with this project's straightforward setup;
  the traditional `org.jetbrains.kotlin.android` + `org.jetbrains.kotlin.plugin.compose` + KSP
  plugin combination is used instead, which is still fully supported.
- **Manual-override anchoring across classifier reruns is reconciled, but only for unambiguous
  matches.** Overrides are keyed by a bout's start-time anchor; a classifier rerun that shifts the
  same walking session's boundary (a corrected/removed first minute, an earlier minute extending it
  backward, a threshold change, ...) would otherwise silently orphan the override. Every recompute
  (`StepRepository.reconcileOverrideAnchors`) now looks for a single newly computed bout that
  overlaps an orphaned override's previous interval by a strong majority (>=50%) in both
  directions, and re-keys the override to it atomically with the bout replacement. If a session
  ambiguously splits or merges (no candidate clears that majority, or more than one orphaned
  override would otherwise claim the same new bout), the override is deliberately left exactly as
  it was - preserved, never deleted, but inactive - rather than guessing.
- **No Health Connect / accelerometer fallback.** If the Recording API is unavailable (old Play
  services, no Play services at all, etc.) the app shows an honest "unavailable" state rather than
  inventing a second step-counting mechanism, per the product constraints.
- Very long gaps between app opens (beyond the Recording API's retention window) mean the steps
  taken during that gap are permanently unrecoverable - this is a platform limitation, documented
  above, not something the app can work around.
- **Trip Route Recording is intentionally an MVP.** By design, this first version has no real map
  or map tiles, no Internet permission, no navigation/route planning, no automatic trip
  start/stop, no `ACCESS_BACKGROUND_LOCATION`, no pause/resume (only Start/Finish), no
  waypoints/photos/notes, no cloud sync/accounts/analytics/telemetry, and no network-based
  elevation correction (altitude, when shown, is whatever the location provider itself supplied).
  **Estimated steps and GPX export were both cut from this first version too** - a trip currently
  reads no step data at all and can only be viewed in-app, not exported - to keep trips completely
  independent of the step pipeline while the recording mechanism itself was hardened; the schema
  already retains everything (timestamps, route points) either would need to be reintroduced later.
  None of these are bugs to fix incidentally - they are explicit scope exclusions for this change.
- **Trip Route Recording has not been exercised on a real device or GPS receiver** in this
  environment - see the device checklist above. It is not considered field-validated - in
  particular for screen-off/backgrounded recording, process-death/restart recovery, and a
  location-registration failure mid-trip - until it actually has been. The interrupted-trip-recovery
  liveness check also has one documented, accepted timing race (see "Trip Route Recording" →
  "Honest recovery" above) that needs real-device confirmation.
