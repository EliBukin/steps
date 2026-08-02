# StepSplit

A lightweight, fully offline Android app that counts daily steps and splits them into **workout
walking** and **incidental/everyday** movement. Hebrew + RTL is the default UI language, with
English as a fallback locale.

No account, no cloud backend, no ads, no analytics, no location permission.

## What it does

- Automatically collects all steps in the background via periodic sync - there is nothing to
  start or stop, and no way to explicitly record a session.
- Detects walking sessions retrospectively from imported step data and splits them into
  workout-walk steps vs. incidental steps using a transparent, adjustable heuristic.
- Shows today's totals, daily/weekly goal progress (uncapped - 120% displays as 120%, not clamped to 100%), and a 7-day history with a stacked bar chart.
- Lets you manually correct any detected session's classification (workout vs. incidental) after
  the fact - the only manual intervention the app offers.
- Works fully offline. The only permission requested is `ACTIVITY_RECOGNITION`.

## Architecture

Single Gradle module (`app`), manual dependency injection (`di/AppContainer.kt`), unidirectional
data flow from Room/DataStore through a repository, into `StateFlow`-based ViewModels, into
Compose UI.

```
ui/            Compose screens (Today, History, Sessions, Settings), navigation, theme
di/            Hand-written AppContainer + ViewModelFactory (no DI framework)
domain/        Pure Kotlin, no Android deps: classification, aggregation, time, models
data/
  local/       Room entities/DAOs (step_buckets, walk_bouts, session_overrides; manual_walks is
               deprecated - see Schema notes)
  settings/    Preferences DataStore (daily goal, thresholds, last sync time)
  stepsource/  StepSource interface + LocalRecordingStepSource + FakeStepSource
  repository/  StepRepository - the single place that imports, normalizes, classifies, merges
sync/          StepSyncWorker (CoroutineWorker) + WorkManager scheduling/factory
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

## Required permission

Only `android.permission.ACTIVITY_RECOGNITION`. No location, no internet is required for normal
operation (Play services calls are all on-device/local for this API).

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
  briefly.
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
throwing if anything doesn't match. `MigrationTestHelper` was not used directly to drive this: as
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
4. Launch **StepSplit**. Grant the activity-recognition permission when prompted (or via the
   banner on the Today screen).
5. Walk around - the activity should show up, detected and classified retrospectively, within the
   ~6-hour sync window, or the next time you foreground the app (which also triggers a sync).

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
- **Manual-override anchoring across classifier reruns is best-effort.** Overrides are keyed by a
  bout's start-time anchor. If a future classifier change merges or splits bouts differently such
  that a previously-overridden bout's start time shifts, that override becomes inactive (it is
  preserved in the database, never deleted, but stops applying) rather than being intelligently
  re-attached to the new boundary.
- **No Health Connect / accelerometer fallback.** If the Recording API is unavailable (old Play
  services, no Play services at all, etc.) the app shows an honest "unavailable" state rather than
  inventing a second step-counting mechanism, per the product constraints.
- Very long gaps between app opens (beyond the Recording API's retention window) mean the steps
  taken during that gap are permanently unrecoverable - this is a platform limitation, documented
  above, not something the app can work around.
