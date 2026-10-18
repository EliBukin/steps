# Steps GPS Route

An Android app with two jobs: it shows **how much you walk each day**, and it **records GPS routes**
of your trips so you can see them on a map and export them as GPX files.

- **Steps.** Your daily and weekly step counts, a daily goal, the last 7 days, and lifetime stats.
- **GPS trips.** Start a trip, and the app records your route until you finish it. Afterwards you
  can view the route on a map, and save or share it as a GPX file to use in other apps.

There's no account, no cloud backend, no ads and no analytics. Everything is stored on the phone.
The app is in English on an English phone, and in Hebrew (right to left) otherwise.

**Privacy policy:** <https://elibukin.github.io/steps/privacy-policy.html>

## Why this app

I built it for my own use:

- **Daily movement.** I want to see how much I move every day, at a glance.
- **Hikes and nature walks.** I record the route of a hike or a walk in nature with the GPS tracker.
- **4x4 trips.** I also use the GPS tracker on off-road trips with my jeep, so it keeps recording
  at driving speed too.
- **GPX for other apps.** Exporting a trip as GPX is the important part: it lets me open the route
  in other map and navigation apps.

## Not a health app

Steps GPS Route is **not** a health, fitness-coaching or medical app. It gives no health advice, and
nothing in it is meant to diagnose, treat or track any health condition.

It reads your step count from Android's Health Connect only because that's where Android keeps the
phone's step count.

Some stats are there just for fun and are deliberately rough. Don't rely on them:

- calories burned (a flat 0.04 kcal per step);
- burgers burned;
- distance estimated from steps (0.75 m per step);
- progress around the Earth and to the Moon.

The split into *workout* and *incidental* steps is a rule of thumb too.

## Screens

| Screen | What it shows |
|---|---|
| **Today** | Today's steps inside a ring that fills toward the daily goal, with the workout and incidental steps below it. This week's total against the weekly goal (daily goal × 7; weeks start on Sunday). When the steps were last updated, and a banner if step data isn't available or an update failed. |
| **History** | The last 7 days as a bar chart, each bar split into workout and incidental steps, with the daily goal as a line. Below it, the estimated distance per day. |
| **Stats** | Lifetime numbers in two sections. **Steps:** all steps ever, estimated distance, workouts and workout time, active days, average per active day, best day, marathons walked and the next marathon, progress around the Earth and to the Moon, estimated calories and burgers burned. **GPS trips:** a card for **Walks** and one for **Drives**, each with the number of trips, total time and distance, and average speed; walks also show average pace. A card with no trips is hidden. |
| **Trips** | Start, finish or resume a trip, with a live clock, the distance and the GPS signal while recording. *Mark spot* saves where you are with one tap (a spring, a viewpoint, a tricky passage) and confirms with "Spot 3 marked"; the recording notification has the same action, which also works from the lock screen. Below that, your finished trips, each with a small picture of its route, a walk or car icon for its type, and its name, or its day, date and start time if it has none. |
| **Trip detail** | The trip's type icon and its name, with the date under it (an unnamed trip is titled by its date). A card with when the trip started and ended, its duration and distance, and under those the total climb (↑) and descent (↓), the moving time (the duration without stops) and the top speed. Then the route on a map, colored by speed from red (slow) through yellow to green (fast), with a small legend giving the trip's own km/h at each end. Stretches with no recorded points are gray and dashed. The round button on the map shows the whole route again. Spots you marked are white discs with their number (where two overlap, the newer one is on top; zoom in to separate them); tapping one shows its name (or "Spot 3"), when it was marked and its height above sea level (Android 14 and newer), with *Rename* and *Delete*. Under the map, a small height chart along the trip, with the highest and lowest point above sea level next to its top and bottom (Android 14 and newer; older phones show the chart's shape only). The chart is left out on a short phone, where the map needs the room, and a trip recorded without heights has no climb, descent or chart. The screen doesn't scroll, so the map's gestures always work. At the top, the pencil opens *Edit trip* (a name, and Walk or Drive, with the app's suggestion marked) and the trash icon deletes the trip. *Share GPX* opens Android's share sheet: *Save file* (to a folder you pick) comes first, then WhatsApp, Gmail, Telegram, Drive or any other app that accepts files. |
| **Settings** | Daily step goal, the workout-detection limits (advanced), *Keep deleted trips in Stats*, and the permission and step-data status. Debug builds also show debug tools. |

## How it works

### Steps

- **Where steps come from.** Android's Health Connect. It holds the step count from the phone's
  built-in step counter, plus steps from any other app or watch you've connected to Health Connect.
  The app reads Health Connect's combined total and never writes anything back.
- **Requirements.** Android 14 or later, with Health Connect's step counting available. On an older
  or unsupported phone, the app says step tracking isn't available; trips still work.
- **When steps update.** The phone counts steps all the time, whether the app is open or not. The
  app copies the counts in only while it's open: when you open it, about once a minute while
  *Today* is on screen, and right after you grant permission. It never runs in the background.
- **Catching up.** Each update re-reads the last 6 hours, so late or corrected counts are picked
  up. The first update, or one after a long break, reaches back **30 days**. Steps older than that
  are never copied in, so open the app at least once a month.
- **Storage.** The app keeps one record per minute that had steps. An update only adds or corrects
  minutes and never deletes any, so a bad or empty read can't erase your history.
- **Workout vs. incidental.**
  1. Minutes with steps are grouped into walks. A pause of up to 2 minutes stays inside the walk.
  2. A walk is labelled once you've been still for 3 minutes.
  3. It's a **workout** if it lasted at least 10 minutes, had at least 8 minutes with steps and at
     least 600 steps, and averaged at least 60 steps per minute. Otherwise it's **incidental**.

  You can change these limits in Settings. The labels never change the total: every step counts
  as either workout or incidental.

### GPS trips

- **Recording.** You start a trip yourself. While it records, Android shows an ongoing
  notification, and recording continues with the screen off or while you use other apps. You can
  mark spots and finish from the notification or from the app.
- **Interruptions.** If Android stops the app during a trip, the next time you open it the trip is
  marked *interrupted*. You can resume it (the route will have a gap) or finish it at its last
  point.
- **Which GPS points are kept.** The app asks for a location every 10 seconds at high accuracy. It
  skips a point that is less accurate than 30 m, older than 2 minutes, contradicts the speed the
  phone reports, or is just GPS drift while you stand still. Each point is compared with the last
  point the app kept:
  - **Standing still** (the phone reports 0.3 m/s or less for this point and the last kept one), a
    point that is closer to the last kept one than their two accuracies added together is drift,
    and is skipped. The first point after you stop is still kept, so the route reaches where you
    stopped.
  - **On foot** (the phone reports 6 m/s or less, or no speed at all), a point that implies moving
    faster than 6 m/s (about 22 km/h) is skipped.
  - **In a vehicle** (the phone reports more than 6 m/s for this point or the last kept one), the
    limit becomes twice the reported speed plus 3 m/s, and never more than 55 m/s (about
    200 km/h). So a drive records a continuous route, braking included.

  A GPS jump that the phone's own speed can't explain is skipped either way. Phones normally
  measure speed from the satellite signal itself, separately from the position, which is why the
  app trusts it to tell a real drive from a jump.
- **The route you see.** Before the route is drawn, measured or exported, stray points are removed
  and the line is smoothed. The map, the distance and the GPX file all use this same route, so
  they always agree. On foot this includes short GPS excursions: one to three points that jump
  away from your path and back faster than the phone says you were moving (say, about 80 m away
  and back within a minute while the phone reports 5 km/h) are left out, so they add no spur, no
  distance and no false top speed. A real detour or turnaround at your own pace stays, and so does
  everything recorded without the phone's speed or at driving speed. Nothing stored changes, so
  trips recorded before this are cleaned up too.
- **Sharing and saving.** *Share GPX* writes the trip as a GPX file and opens Android's share
  sheet. *Save file* saves it to a folder you choose; any other choice sends it to that app. A
  named trip's name goes into the file too (as the GPX file's and track's name), so other apps
  show it. Each marked spot becomes a waypoint (`<wpt>`) with its height, time and name; an
  unnamed spot is called "Spot 3" in the app's language, as the app shows it, so the other app
  calls it the same. Each point's and spot's height (`<ele>`) is above sea level, as GPX means it,
  on Android 14 and newer; see *Heights* below.
- **Marked spots.** While a trip records, *Mark spot* on the Trips screen or in the notification
  saves where you are, at once and with no questions, so it works from a moving jeep. Spots are
  numbered per trip (Spot 1, 2, 3...); a number stays with its spot, so deleting Spot 2 doesn't
  turn Spot 3 into Spot 2. A name is optional and can be set later by tapping the spot on the map.
  Which GPS point a spot uses:
  - Any point the phone delivered from within 30 seconds of the tap (before or after), with an
    accuracy of 50 m or better, counts, including points the route itself skips, such as drift
    while standing still. The spot uses the one closest in time to the tap.
  - The newest point is used at once, unless the phone may have moved more than 40 m since it,
    judged by the speed it reported. On foot any point from the last 30 seconds will do; in a jeep
    at 40 km/h only one from the last 3-4 seconds, since the newest point is often 5-25 seconds
    old there, which would put the spot a few hundred meters back down the track.
  - Otherwise the app asks the GPS for a fresh point and also takes the recording's next one,
    whichever comes first, for up to 10 seconds; with the GPS already on that normally takes a
    second. This is also what makes a spot work while you stand still, when the recording gets no
    new points at all. If nothing closer comes, it uses the best point from those 30 seconds.
  - With no point at all, nothing is saved and the app says there's no GPS fix yet.
  - One point, one spot. The point the last spot used counts as one of those points too, so a
    second tap the phone can't have moved 40 m since (a double tap, a bump in the jeep) lands on
    that same point: nothing new is saved, and the app says "Spot 3 is already marked here". Once
    you've moved on, a tap is a new spot.

  The spot keeps that point's time and height, so they match its place. The notification's
  *Mark spot* doesn't ask you to unlock the phone. The recording notification makes no sound and
  doesn't vibrate, but it isn't a "silent" notification in Android's sense (those are hidden on
  the lock screen by default), so it shows there in full, actions included. The answer ("Spot 3 marked", or "No GPS fix yet") shows as a short message and,
  for a few seconds, as the notification's text.
- **Walk or drive.** Every finished trip is a *walk* (walks, hikes, jogs) or a *drive*, so Stats can
  keep hikes and jeep drives apart. The app suggests one from the route's speed: each stretch's
  speed, the same one the speed colors use, and the trip is a drive when at least a tenth of its
  stretches are faster than 15 km/h. That is faster than anyone walks or jogs, and slower than a
  jeep on a dirt road. Stops don't count against a drive, since a stop is one long stretch. A trip
  with fewer than 5 stretches is a walk. You can change the type in *Edit trip*; your choice always
  wins, and picking the suggested type again goes back to the suggestion. The suggestion isn't
  stored: it's worked out from the route each time, so an improved rule also applies to old trips.
  It can be wrong: a 4x4 crawl that never goes over 15 km/h is suggested as a walk.
- **Names.** A trip can have a name of up to 60 characters, like "Wadi Qelt hike". Leave it empty
  and the trip is titled by its date, as before.
- **Deleting a trip.** Deleting removes the trip, its route and its marked spots. If *Keep deleted trips in Stats*
  is on (it's off by default), the app first keeps the trip's date, duration, distance and type
  (walk or drive), with no location, no name and no spots, and Stats keeps counting it in the right card.
  Turning the setting off hides those trips from Stats until you turn it on again. Trips deleted
  while it's off are gone from Stats for good.
- **Speed colors.** Each stretch between two points of that same route is colored by its speed:
  distance over time, averaged over about 30 seconds so the color doesn't flicker. Each trip has
  its own scale. Its slowest 5% of stretches are red and its fastest 5% green, so a walk at
  2-6 km/h and a jeep drive at 10-60 km/h both use the full range. The legend shows the trip's
  km/h at the red and green ends, worked out from the same stretches the top speed may come from.
  Slow sits on the left in English and on the right in Hebrew.
  Standing still is just slow, so it's red. A stretch where more than a minute passed *and* you
  moved more than 100 m has no points showing how you got there. That happens when recording was
  interrupted and resumed, or the GPS lost its fix. Such a stretch is drawn gray and dashed, with
  no speed. The GPX file, the trip list's route pictures and the offline outline stay one color.
- **Moving time.** The duration counts everything from start to finish, a 20-minute break
  included. Moving time counts only the stretches whose speed (the same 30-second average the
  colors use) is above 1 km/h on a walk or 3 km/h on a drive. Standing still sends no new points,
  so a stop is one long, nearly motionless stretch, and GPS drift during a break stays well under
  1 km/h, while even a slow scramble is faster. A drive's limit is higher because the stretch
  holding a stop also holds the drive-off up to the next point. Pauses shorter than about half a
  minute blend into the walking around them and count as moving. A gap (gray and dashed on the
  map) counts as moving when its straight line, the least ground you covered, is faster than the
  limit: a short GPS dropout counts, a long interruption spent mostly standing doesn't.
- **Top speed.** The fastest of those 30-second average speeds, never a single GPS reading or the
  phone's own reported speed, so one bad point can't produce a silly number. Gaps have no speed.
  It is never below the speed legend's fast end. A stretch far faster than the phone's own speed
  says (more than twice it plus 1 m/s, such as the GPS position catching up in one jump after
  lagging behind) can't be the top speed, and neither can the stretches whose 30-second average
  takes it in; the route, its colors and its distance stay as they are. The phone measures its
  speed separately from the position, so it doesn't share the lag. At driving speed the phone
  reports a high speed, so a real fast stretch always counts, and so does anything recorded
  without the phone's speed.
- **Heights.** GPS heights scatter 10-20 m up and down, so adding up every rise would turn a flat
  4 km walk into more than a kilometer of climb. The app first smooths the heights along the
  distance (a weighted average in which the heights within about 65 m count most on a walk, and
  more on a drive, whose points are farther apart), then counts climb and descent from valley to
  peak and back, ignoring any rise or fall under 10 m. The highest and lowest point are those of
  the smoothed heights. A trip where fewer than half of the points (or fewer than 10) have a
  height, or whose heights cover less than 100 m of the route, shows no heights at all.
- **Heights above sea level.** The phone reports height above the WGS84 ellipsoid, a mathematical
  shape of the Earth, not above sea level. In Israel that is about 17-20 m higher (Android's own
  model gives about 19-20 m at Jerusalem, 19 m at Tel Aviv and 17 m at Arad and the Dead Sea). The
  app stores what the phone reports and, on Android 14 and newer, converts it to above sea level
  with Android's built-in converter each time a trip is shown or shared, so trips recorded before
  this existed are converted too. Android 13 and older have no converter: there the trip shows
  climb, descent and the chart's shape, which don't depend on it, but no highest or lowest
  point, and the GPX file gets the heights as stored.
- **Map.** Map tiles come from [OpenFreeMap](https://openfreemap.org). You can pan, zoom and
  rotate the map; tilting is off. The scale bar is always in meters and kilometers. Without
  internet, the screen shows a plain outline of the route instead, with a *Retry map* button.

### Steps and trips are separate

Recording a trip doesn't change your step counts, and deleting a trip never removes steps. Trip
code never reads step data, and step code never uses location. The two share only the app's
database file and its screens.

## Permissions

| Permission | Used for | When it's asked |
|---|---|---|
| Health Connect: *Read steps* | Your step counts | When you tap the grant button on *Today* |
| Precise location | Recording a trip's route | When you tap *Start trip* or *Resume* |
| Notifications | The notification shown while a trip records | Together with location, on Android 13 and later. Recording still works if you say no. |
| Foreground service (location) | Recording with the screen off | Granted automatically |
| Internet | Loading the map on a trip's screen | Granted automatically |

The app never asks for background location. It uses location only while a trip is recording.

## Privacy and data

The full privacy policy is at <https://elibukin.github.io/steps/privacy-policy.html>. In short:

- Steps, trips and settings are stored only on the phone. Android's cloud backup is turned off for
  this app.
- The app sends nothing to the developer or anyone else. Its only internet traffic is loading map
  tiles from OpenFreeMap while a trip's screen is open. Those requests don't include your GPS
  points, but like any map they show OpenFreeMap which area you're looking at, and your IP address.
- A GPX file leaves the app only when you save or share it yourself.
- Step data is never written back to Health Connect.

## Known limitations

- **Driving needs the phone's speed.** Above about 22 km/h, points are kept only while the phone
  reports its speed. GPS points normally include it; if the phone stops reporting it, points are
  skipped until you slow down, and the route shows a straight jump.
- **GPS jumps are harder to catch while driving.** The faster the phone says you're going, the
  bigger the gap between two points the app allows, and the out-and-back cleanup above is for
  walking only (at driving speed a hairpin bend has the same shape). While driving, a stray point
  a few hundred meters to the side of the road can stay in the route.
- **"Searching" while standing still.** The GPS status can show *Searching* after 30 seconds
  without moving, because no new points are kept then.
- **Heights are only as good as GPS.** Even after smoothing, the climb and descent are estimates,
  good to about 10-20%, and the highest and lowest point are often 5-10 m off. A slow drift of the
  GPS height over minutes looks just like a gentle hill, so a flat walk can still show some climb,
  and hills smaller than about 10-15 m are missed. Telling the two apart would need a barometer,
  which the app doesn't use.
- **Steps update only while the app is open.** The phone keeps counting, but if you don't open
  the app for more than 30 days, the steps from before that are never copied in.
- **Android 14+ only for steps.** Older phones get no step counting.
- **Workout vs. incidental is a guess.** A long, brisk walk to the shop counts as a workout.
- **A trip stops if Android closes the app.** It stays *interrupted* until you resume it.
- **Spots on the map only.** Spots show on the online map, not on the offline outline, and
  TalkBack can't reach a single spot on the map: the map's description only says how many there
  are. Their heights show only on Android 14 and newer.
- **A permission answer can be lost.** If the screen rotates while a permission dialog is open,
  tap *Start trip* again.

## For developers

> The project and its package are still called **StepSplit** (`com.example.stepsplit`). Only the
> name shown to users changed.

### Project structure

A single `app` module in Kotlin and Jetpack Compose. Dependencies are wired by hand in
`di/AppContainer.kt` (no DI framework). Data flows from Room and DataStore through repositories into
ViewModels (`StateFlow`), and from there to the Compose screens.

```
app/src/main/java/com/example/stepsplit/
  ui/               Compose screens (today, history, stats, trips, settings), navigation, theme
  di/               AppContainer and ViewModelFactory
  domain/           Pure Kotlin, no Android: walk classification, aggregation, time,
                    lifetime stats, and the trip route logic (point acceptance, cleanup, smoothing,
                    speed colors, walk/drive, moving time and top speed, elevation, which GPS
                    point a marked spot uses)
  data/
    stepsource/     Health Connect step source (+ a fake for tests and debug data)
    repository/     StepRepository: imports, stores and classifies steps
    trip/           TripRepository, GPS location client, recording coordinator, spot marker,
                    sea-level heights
    local/          Room database, entities and DAOs
    settings/       DataStore settings (goal, thresholds, last sync, keep deleted trips)
  trip/service/     The trip recording foreground service and its command handling
  sync/             Cancels a background job left by older app versions (nothing is scheduled)
  debug/            Debug-only sample data and device diagnostics
```

The privacy policy's source is `docs/privacy-policy.html`. It's published on GitHub Pages from the
`gh-pages` branch, as `index.html` and `privacy-policy.html`.

### Build, install, test

You need **JDK 21** (the unit tests need it; building alone works with JDK 17) and the Android SDK
with platform 36.

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # installs on every connected device and emulator
./gradlew testDebugUnitTest    # unit tests (JVM + Robolectric)
./gradlew lintDebug
```

- **Tests.** About 670 unit tests. Business logic is pure Kotlin; Room and the screens' ViewModels
  run under Robolectric. There are no instrumented (on-device) tests.
- **Step separation.** `ArchitectureInvariantsTest` scans the code to make sure step and trip code
  don't import each other, and `StepGpsSeparationTest` runs both features together.
- **Real phone.** Step counting needs a real phone with Health Connect step counting. An emulator
  reports step tracking as unavailable.
- **Emulator.** Trips can be tested on an emulator by feeding it positions with
  `adb emu geo fix <longitude> <latitude>`.
- **Debug tools.** Debug builds (`com.example.stepsplit.debug`) add a *Debug tools* section in
  Settings:
  - *Generate sample data*: a week of fake steps, stored under a separate source so they never
    count toward lifetime stats;
  - a step source check;
  - device diagnostics.

  None of this exists in release builds.

### Database

- **Version.** Room schema version **10**. The 9→10 migration added the empty `trip_spots` table
  for marked spots, which are deleted with their trip. The 8→9 migration added trip names and
  types: existing trips get no name and keep the suggested type, and deleted trips kept in Stats
  from before then get their type from their average speed (10 km/h or more is a drive; their
  route is gone). Each version's schema is exported to `app/schemas/` and
  committed. Migrations never delete steps or change step counts; the 5→6 migration did drop the
  old manual-relabelling table (`session_overrides`).
- **Migration tests.** Each step from 1→10 has a test in `StepSplitDatabaseMigrationTest`. It
  builds the old schema from its exported JSON, migrates, and lets Room validate the result.
- **Unused leftovers.** Some tables and columns from removed features are kept but unused:
  `manual_walks`, `activity_intervals`, `motion_evidence`, `temporal_continuity_state`,
  `step_counter_samples`, and the validation columns in `step_buckets`. Dropping them would need
  its own migration.
- **Step source id.** Steps from Health Connect are stored under the source id
  `local_recording_api`, the old source's id, so earlier history stayed in place without a
  migration.
