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

### Start / Finish / Resume, not continuous tracking

- Exactly one trip may be active at a time; tapping "Start trip" again while one is already active
  is a no-op (see `TripRepository.startTrip`'s idempotency below).
- **No automatic trip detection, no geofencing, no continuous daily location tracking, no
  background-location permission (`ACCESS_BACKGROUND_LOCATION`), no pause/resume mid-recording.**
  Start, Finish, and Resume-after-interruption are the only user-managed transitions in this MVP.
- While idle, the app performs **zero** location requests and runs no location foreground
  service - `TripLocationClient.locationUpdates()` is a cold `Flow`, only ever collected by
  `TripRecordingCoordinator` while a trip is actually being recorded.

### Permission flow

Requested after tapping "Start trip" **or** "Resume" on an interrupted trip - both go through the
exact same rationale-then-request-then-validate flow in `TripsScreen.kt`, since a trip can only
reach `INTERRUPTED` after fine location was already granted once, but it may have been revoked
since; Resume re-checks rather than assuming.

1. A rationale dialog is shown first, then `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`
   (+ `POST_NOTIFICATIONS` on API 33+) are requested together via a single
   `ActivityResultContracts.RequestMultiplePermissions()` launcher in `MainActivity`, as Android
   requires.
2. **Precise (`ACCESS_FINE_LOCATION`) granted** → the trip starts/resumes.
3. **Only approximate granted, or denied entirely** → **there is no "start anyway" path.**
   `RoutePointAcceptancePolicy` rejects any fix whose reported accuracy is worse than 50m, and
   Android's approximate location is normally far coarser than that - a coarse-only trip could
   silently record zero points and zero distance while still looking like it was "recording."
   Instead, a dialog explains that precise location is required to record a useful route, with a
   button straight to the app's system settings page to grant it.
4. **System location services disabled** → a dialog offers a button straight to
   `Settings.ACTION_LOCATION_SOURCE_SETTINGS`.
5. **Notification permission denied** (API 33+) → recording still proceeds (a foreground service
   does not require it to run), but the active-recording screen honestly notes that no persistent
   notification will be visible, rather than claiming one is shown.

### Foreground service (`trip/service/TripRecordingService.kt` + `TripRecordingCommandController.kt`)

A dedicated `Service`, entirely separate from `sync/StepSyncWorker` - no `WorkManager` involvement
in continuous GPS recording. `TripRecordingService` itself is a thin shell owning only
Android-specific mechanics: promote to foreground *immediately* in `onStartCommand` (before any
repository/coroutine work), build the ongoing notification, translate each incoming `Intent` into a
call. Every actual command-routing/race-prevention decision lives in
`TripRecordingCommandController` - a plain Kotlin class with no Service/Activity/ViewModel/composable
reference, fully unit-testable with fakes (`TripRecordingCommandControllerTest.kt`) without any
Robolectric service shadow involved.

Four distinct ways to arrive at the service, deliberately **not** treated identically:

- **`ACTION_START`** (tapping "Start trip") - may create a brand-new trip via the idempotent
  `TripRepository.startTrip()`.
- **`ACTION_RESUME`** + `EXTRA_TRIP_ID` (tapping "Resume" on an interrupted trip) - the UI has
  already re-validated precise-location permission and system location being enabled (see
  "Permission flow" above) before sending this. The service atomically verifies the trip is still
  `INTERRUPTED` and transitions it to `ACTIVE` via `TripRepository.resumeInterruptedTrip` - which
  returns `false` (a safe no-op) for a stale/duplicate Resume or one targeting a trip that has since
  finished elsewhere - and **never** calls `startTrip()`. If foreground promotion, the permission/
  location checks, the atomic transition, or the coordinator's own registration all failed, the
  trip is left (or returned) to `INTERRUPTED` rather than left stuck `ACTIVE` with nothing recording
  behind it.
- **`ACTION_FINISH`** (notification action or in-app button).
- **A null `Intent`** - Android itself restarting an already-running `START_STICKY` service after
  process death. May only *recover* an already-`ACTIVE` trip via `TripRepository.getActiveTripId()`
  - never calls `startTrip()` or `resumeInterruptedTrip()`. This matters because a null restart can
  race the app's own launch-time reconciliation (see "Honest recovery" below): if that reconciliation
  already marked the trip `INTERRUPTED` by the time a *delayed* restart finally arrives, there is no
  longer an `ACTIVE` trip to recover, and the restart correctly stops the service without creating
  or changing anything, rather than resurrecting or duplicating a trip.

Other reliability properties:

- The ongoing notification (`FOREGROUND_SERVICE_TYPE_LOCATION`) opens the app straight to the
  Trips tab on tap, and has a "Finish" action targeting the service directly
  (`PendingIntent.getService`) - both the notification action and the in-app Finish button reach
  the same `handleFinish`.
- Repeated Start commands never create a duplicate trip or a duplicate location subscription -
  `TripRepository.startTrip()` returns the existing `ACTIVE` trip's id if one already exists, and
  the controller always calls `coordinator.stop()` before `coordinator.start()` (itself idempotent
  either way) to guarantee a clean subscription. Repeated Finish commands are equally harmless.
- **Bounded, genuinely bounded, Finish - and the end time it persists is honest too.**
  `TripLocationClient.flush()` is wrapped in its own documented timeout (3s) rather than trusted to
  return promptly - a real flush is backed by a Play Services `Task` that can in principle never
  complete, and an earlier version of this code hung Finish indefinitely on exactly that. A further
  short, separately-bounded grace period (2s) then lets any fixes that flush *did* manage to deliver
  actually arrive through the still-active collector before the trip is marked finished - "flush
  when practical, never wait indefinitely, and cap the practical part too." The Finish *request*
  instant is captured exactly once, before either wait begins, and reused for two things that must
  agree: it is handed to `TripRepository.beginFinish` so `recordAcceptedBatch` rejects a live fix
  newly *captured* during that wait (not merely delivered late - see "Sampling and route-point
  acceptance" below), and it is the *exact* value later persisted as the trip's `endEpochSecond` via
  `TripRepository.finishTrip(tripId, endEpochSecond)` - never a fresh `clock.instant()` taken again
  after the wait, which would silently pad the stored duration by however long flush/grace actually
  took. `recordAcceptedBatch` is a further backstop regardless: a callback that arrives *after*
  `finishTrip` has already run finds the trip no longer `ACTIVE` and is silently dropped - it can
  never append a point or increase distance post-Finish.
- **A cancelled or superseded Finish releases only its own cutoff - never leaves a stale one behind.**
  `beginFinish`/`cancelFinish` are a *token-owned* pair (the token is the command's own generation,
  see below): cancelling a Finish that a newer command has superseded runs `cancelFinish` from a
  `NonCancellable` `finally` block, so it still executes even while that Finish's own coroutine is
  being cancelled, and it releases *only* the cutoff it itself installed - an older, already-abandoned
  Finish can never clear a different, newer Finish's still-active cutoff for the same trip. On top of
  that, `TripRecordingCommandController.startCollecting` - the single place Start, Resume, and
  restart recovery all funnel through before (re)starting live collection - proactively clears any
  cutoff still outstanding for the trip it is about to take over via
  `TripRepository.clearAbandonedFinishCutoff`, closing the narrow window between a cancelled Finish
  being dispatched and its own cancellation-triggered cleanup actually running. Without either half of
  this, an abandoned cutoff would silently reject every subsequent GPS sample for that trip, forever.
- **A command can never be torn down by an older, delayed one - not just via cancellation, and not
  just via a currency check.** `TripRecordingCommandController` assigns a monotonically increasing
  generation id to every command (`beginCommand()`, called synchronously in `onStartCommand` before
  any suspend work). Cancelling the previous command's coroutine (which the service still does too)
  is not sufficient on its own: cancellation is cooperative and only takes effect at a suspension
  point, so an older command that has already returned from its *last* suspend call keeps running
  its remaining, purely synchronous cleanup to completion even after being cancelled. Nor is a plain
  "check currency, then act" enough on its own: a concurrent `beginCommand()` can be accepted in the
  gap between the check and the act, and the stale command's action still runs afterward regardless.
  `CommandGenerationGate.runIfCurrent` closes that gap by making the check and the synchronous act
  (`coordinator.start`/`stop`, requesting service teardown) one atomic operation - the *only* two
  places that ever touch the coordinator or request teardown, `startCollecting` and `stopIfCurrent`,
  go through it, so once a newer generation is accepted, an older one can never start, stop, replace,
  or tear down a collector on its behalf again. Every `handleXxx` function also checks its own
  generation is still current *before* touching the repository at all; `handleFinish` checks a second
  time immediately before its own `finishTrip` mutation, since time passes during its wait. A
  collector's own failure callback is generation-guarded too: a stale collector's failure can never
  mark `INTERRUPTED` a trip a newer generation has since idempotently reclaimed - see "What counts as
  a failure" below for why this had to change. The final `stopSelfResult(startId)` call (instead of a
  bare `stopSelf()`) adds Android's own independent "don't stop if a newer start command has been
  delivered" guard on top - and `stopServiceIfOwned` only removes the foreground notification *after*
  confirming `stopSelfResult` actually honored this `startId`, never before: removing it first and
  then discovering the stop was refused would leave the service alive with no foreground notification.
- **A foreground-promotion failure never leaves a trip silently claiming to record.** Promoting to
  foreground can itself fail (a `SecurityException` or, on API 31+, a
  `ForegroundServiceStartNotAllowedException`) before any command-specific work even begins. When it
  does, `handleForegroundPromotionFailure` runs instead of the command that was actually requested:
  it never creates or resumes a trip and never fabricates a successful Finish, but if some *earlier*
  command left a trip `ACTIVE`, that trip just lost its only live collector along with this failed
  promotion, so it is honestly reconciled to `INTERRUPTED` immediately - the same terminal state
  `reconcileActiveTripOnLaunch` would eventually reach on the next app launch, just applied right away.
- Location updates are removed (`awaitClose` inside `FusedTripLocationClient`'s `callbackFlow`) on
  every terminal path: normal Finish, an error, or the service's own `onDestroy` as a safety net.
- **Never starts automatically after boot.** There is no boot receiver anywhere in the manifest.
- **A location-registration failure never leaves the app silently claiming to record.**
  `FusedLocationProviderClient.requestLocationUpdates()` can reject registration *asynchronously*
  (its returned `Task` failing, with no exception thrown at the call site) - `FusedTripLocationClient`
  attaches a failure listener to that `Task` and closes its flow with the resulting exception, so a
  rejected registration is never silent. `TripRecordingCoordinator.start()` takes a
  cancellation-transparent `onFailure` callback (via `Flow.catch`, which never fires for a normal
  `stop()`) that the controller uses to mark the trip `TripState.INTERRUPTED` and tear down the
  foreground notification/service - the same honest recovery UI described below, triggered by a
  live failure instead of a launch-time check. This is deliberately narrower than "GPS toggled off"
  - see "What counts as a failure" below.

### Honest recovery after process death or force-stop

`trips.state` (`ACTIVE` / `FINISHED` / `INTERRUPTED`) in Room is the durable source of truth - the
UI observes it directly and never assumes a ViewModel or Activity staying alive means recording is
still active. If Android restarts the service after the process dies, it recovers the existing
`ACTIVE` trip from Room via `TripRepository.getActiveTripId()` - never `startTrip()` (see
"Foreground service" above for exactly why that distinction matters) - so no duplicate is ever
created.

If the app was force-stopped (which cancels Android's own service-restart machinery) or the OS
simply never restarts the service, nothing pretends the missing interval was recorded. On the next
app launch, `TripRepository.reconcileActiveTripOnLaunch` checks whether the service's own
in-process liveness flag (`TripRecordingService.isRunning`) is true; if not, the trip is marked
`INTERRUPTED` (durably, in Room) rather than silently left `ACTIVE` or silently finished. The
Trips screen then lets you either:

- **Resume (with a gap)** - re-validates precise-location permission and system location exactly
  like a fresh Start (see "Permission flow" above), then sends `ACTION_RESUME` synchronously from
  the button's own click handler - not routed through a ViewModel/coroutine, so the command reaches
  the service even if the activity is backgrounded immediately afterward. The service is what
  atomically transitions the trip back to `ACTIVE` and starts its collector (see "Foreground
  service" above); the trip's existing distance/points are preserved either way.
- **Finish at last point** - ends the trip honestly at its `lastAcceptedPointEpochSecond` (or its
  start time, if it never received a single accepted point), never at "now."

This check has one inherent, accepted timing race: if Android is about to restart the service but
simply hasn't yet at the exact moment of the check, the trip is reported interrupted a little
prematurely. This needs real-device confirmation (see the device checklist below) rather than a
speculative heartbeat/timeout mechanism built for a case that may not matter in practice; the
generation-safe restart path (see above) means this race is merely *premature*, not *harmful* - the
delayed restart that eventually arrives simply stops itself rather than duplicating anything.

### What counts as a failure (and what deliberately doesn't)

`FusedTripLocationClient` does **not** override `LocationCallback.onLocationAvailability`. Google's
own documentation describes a `false` availability signal as a best-effort estimate that fresh
locations aren't currently obtainable (GPS toggled off, deep indoors) - not a terminal error, and
not necessarily followed by the flow ever failing. Treating it as a failure would be dishonest in
the *other* direction: a trip would be reported `INTERRUPTED` (requiring an explicit Resume) for a
transient condition that often resolves itself within seconds. Only two things actually close the
flow with an exception and interrupt a trip: a genuine registration failure (above), and an actual
unexpected exception surfacing from location-callback handling itself. Temporary unavailability
instead stays honestly reflected without any extra plumbing: no new points arrive, the existing
fix-recency check in `TripsViewModel` already surfaces `GpsStatus.SEARCHING`, the trip simply stays
`ACTIVE` throughout, and collection resumes on its own once location becomes available again. A more
elaborate *sustained*-unavailability policy (e.g. auto-interrupting after some longer bound with no
fixes at all) is deliberately out of scope for this MVP.

**A failure also only counts if the collector reporting it still owns the trip.** An earlier revision
had `handleRecordingFailure` mark its trip `INTERRUPTED` unconditionally, regardless of which
generation it belonged to, on the reasoning that the mutation was "scoped to a trip id that generation
actually owned" and therefore safe no matter what. That reasoning had a real gap: `TripRepository
.startTrip()` is idempotent, so a *newer* Start can legitimately reuse the very same `ACTIVE` trip id
and begin a fresh collector for it before an *older*, already-superseded collector's own delayed
failure callback gets a chance to run. The stale failure would then interrupt the trip the new
collector was still actively, successfully recording to - `recordAcceptedBatch` would start silently
dropping every one of its points, since the trip was no longer `ACTIVE`, while the collector itself
kept running with nothing telling it anything was wrong. `handleRecordingFailure` now checks its own
generation is still current before touching the repository at all, exactly like every other `handleXxx`
function - a stale collector's failure is now a complete no-op, and only a failure from the collector
that genuinely still owns the trip interrupts it.

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

Two further durable backstops run in `TripRepository.recordAcceptedBatch` itself, *before* a sample
ever reaches the (deliberately trip-agnostic) acceptance policy above:

- A sample captured before the trip's own `startEpochSecond` is rejected outright. Fused Location
  can deliver a cached, pre-Start fix immediately after registration; without this, such a fix could
  become the trip's very first "accepted" point.
- A sample captured unreasonably far in the future relative to now (5 minutes) is rejected outright,
  so a single bogus/corrupt timestamp can never become the last-accepted point and make every
  subsequent genuine fix look non-monotonic (and therefore rejected) forever after.

`RouteMath.haversineMeters()` clamps its intermediate squared-half-chord value to `[0, 1]` before
taking square roots, so it always returns a finite, non-negative distance - including for antipodal/
near-antipodal coordinate pairs, where floating-point rounding can otherwise push that value
fractionally outside `[0, 1]` and produce `NaN`. This is not just a display concern: the
implausible-jump check above compares `impliedSpeed > MAX_PLAUSIBLE_SPEED...`, and *any* comparison
against `NaN` is `false` in IEEE 754 - an unclamped `NaN` would have silently defeated that
rejection and let a GPS-teleport artifact through with a poisoned, non-finite persisted distance.

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
repository idempotency/recovery, cached-pre-start/future-skew rejection), the command-generation
races at the command/generation-ordering level (Start/Resume/Restart/Finish ordering, stale-command
and stale-failure guarding, token-owned Finish-cutoff cancellation, the bounded flush and its honest
end-time - see `TripRecordingCommandControllerTest.kt`), the atomic ownership primitive itself under
real thread concurrency (`CommandGenerationGateTest.kt`), and the notification-vs-`stopSelfResult`
ordering decision with fakes modeling Android's own contract (`ServiceStopCoordinatorTest.kt`). None
of this exercises a real `android.app.Service` lifecycle, a real permission dialog, a real GPS
receiver, or real process death - `TripRecordingService` itself (the thin shell around the tested
controller) has no Robolectric or instrumented coverage at all. The following needs a physical
device, and has not been run in this environment. **The trip recorder is not considered
field-validated until every item below has actually been exercised on a physical device with the
screen off and the app backgrounded, not merely compiled/unit-tested.**

- [ ] Precise location permission granted, and denied.
- [ ] Approximate-only location granted (confirm the "precise location required" dialog appears -
      not a silent degraded recording, and not the old "start anyway" path, which no longer exists).
- [ ] Permission revoked (via device Settings) *between* a trip becoming `INTERRUPTED` and tapping
      Resume - confirm Resume re-prompts/re-validates rather than assuming still-granted.
- [ ] System location services disabled *before* tapping Resume on an interrupted trip - confirm the
      same location-disabled dialog Start uses appears for Resume too.
- [ ] Screen off for at least 15 minutes while recording continues.
- [ ] Switch to another app while recording continues.
- [ ] Airplane mode / no data connection (recording is fully offline - GPS itself doesn't need a
      data connection, but worth confirming nothing degrades).
- [ ] Finish from the in-app button, and separately from the notification action.
- [ ] Process death (e.g. "don't keep activities" / low-memory kill) followed by `START_STICKY`
      service restoration - confirm the *same* trip continues, not a duplicate.
- [ ] Force-stop the app mid-trip, relaunch, and confirm the interrupted-trip recovery UI appears
      honestly, with both Resume and Finish-at-last-point working correctly.
- [ ] A *delayed* null-intent restart that arrives only after reopening the app (and therefore after
      launch-time reconciliation already marked the trip `INTERRUPTED`) - confirm the app never ends
      up with two trips or a silently-resurrected one. (Deterministically covered at the command-
      controller level by automated tests; this confirms the real `Service`/OS behavior matches.)
- [ ] Transient GPS unavailability and recovery: toggle system location services off then back on
      *while a trip is actively recording* - confirm the trip stays `ACTIVE` throughout (never
      `INTERRUPTED`), GPS status honestly shows "Searching", and point collection resumes on its own
      once location becomes available again.
- [ ] A real registration/provider failure, if reproducible (e.g. revoking location permission via
      `adb shell pm revoke` while actively recording, which does throw) - confirm this, unlike
      transient unavailability above, *does* honestly interrupt the trip.
- [ ] Rapid Finish immediately followed by a new Start (e.g. double-tapping, or tapping Start again
      right after the notification's Finish action) - confirm the new trip ends up genuinely
      recording, not silently torn down by the just-issued Finish. (Deterministically covered at the
      command-controller level by automated tests; this confirms real-world command timing matches.)
- [ ] Weak GPS signal or an outdoor→indoor transition (GPS status should show "Weak"/"Searching"
      honestly, not "Good").
- [ ] A real hike/walk: route shape looks plausible on the trace, distance is in a reasonable
      range for the actual distance covered, and battery use over an extended recording is
      acceptable.
- [ ] Tap Finish, then immediately tap Start again (or trigger a Resume) before Finish's ~5s
      flush/grace window has elapsed - confirm the trip that results is the *new* one, genuinely
      recording, and that no GPS point after that moment is silently rejected as though Finish's
      cutoff were still in effect. (Deterministically covered at the command-controller level by
      automated tests; this confirms real Android command/coroutine timing matches.)
- [ ] Trigger a foreground-service start restriction (e.g. a delayed `START_STICKY` restart arriving
      while the app is fully backgrounded on API 31+, or revoking the notification permission right
      before a restart on API 33+) - confirm any trip left `ACTIVE` is honestly reconciled to
      `INTERRUPTED` rather than silently staying `ACTIVE` with nothing recording behind it.
- [ ] Force-stop or kill the app in the few seconds between tapping Finish and it actually completing
      (mid flush/grace-period) - confirm the trip recovers through the ordinary interrupted-trip path
      on relaunch, not left in some intermediate state.

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
  start/stop, no `ACCESS_BACKGROUND_LOCATION`, no pause/resume *mid-recording* (Resume only applies
  to an already-`INTERRUPTED` trip, never a live pause), no waypoints/photos/notes, no cloud
  sync/accounts/analytics/telemetry, and no network-based
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
