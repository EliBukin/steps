# How Steps GPS Route works

The details behind the app's numbers and maps: where steps come from, which GPS points are kept,
how a route is cleaned up and measured, and how the map, the offline maps and the compass work.
For using the app, see the [README](../README.md).

## Steps

- **Where steps come from.** Android's Health Connect. It holds the step count from the phone's
  built-in step counter, plus steps from any other app or watch you've connected to Health Connect.
  The app reads Health Connect's combined total, minute by minute, and never writes anything back.
- **Requirements.** Android 14 or later, with Health Connect's step counting available. On an older
  or unsupported phone, the app says step tracking isn't available; trips still work.
- **When steps update.** The phone counts steps all the time, whether the app is open or not. The
  app copies the counts in only while it's open: when you open it, about once a minute while
  *Today* is on screen, and right after you grant permission (in the dialog, or in Health
  Connect's own page and back). It never runs in the background.
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

## GPS trips

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
  everything recorded without the phone's speed or at driving speed. The stored points never
  change; the cleanup runs each time a trip is shown or shared.
- **GPX files.** The file is GPX 1.1, created as "Steps GPS Route". A named trip's name goes into
  the file twice (as the file's and the track's name), since apps differ in which one they show.
  Each marked spot becomes a waypoint (`<wpt>`) with its height, time and name; an unnamed spot is
  called "Spot 3" in the app's language, as the app shows it. Each point's and spot's height
  (`<ele>`) is above sea level on Android 14 and newer; see *Heights above sea level* below.
- **Marked spots.** *Mark spot* saves where you are at once, so it works from a moving jeep. Spots
  are numbered per trip (Spot 1, 2, 3...); a number stays with its spot, so deleting Spot 2
  doesn't turn Spot 3 into Spot 2. Which GPS point a spot uses:
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
    second tap the phone can't have moved 40 m since lands on that same point: nothing new is
    saved, and the app says "Spot 3 is already marked here". Once you've moved on, a tap is a new
    spot.

  The spot keeps that point's time and height, so they match its place.
- **The recording notification.** It makes no sound and doesn't vibrate, but it isn't a "silent"
  notification in Android's sense (those are hidden on the lock screen by default), so it shows
  there in full, actions included. Its *Mark spot* doesn't ask you to unlock the phone. The answer
  ("Spot 3 marked", or "No GPS fix yet") shows as a short message and, for a few seconds, as the
  notification's text.
- **Walk or drive.** Every finished trip is a *walk* (walks, hikes, jogs) or a *drive*, so Stats can
  keep hikes and jeep drives apart. The app suggests one from the route's speed: each stretch's
  speed, the same one the speed colors use, and the trip is a drive when at least a tenth of its
  stretches are faster than 15 km/h. That is faster than anyone walks or jogs, and slower than a
  jeep on a dirt road. Stops don't count against a drive, since a stop is one long stretch. A trip
  with fewer than 5 stretches is a walk. You can change the type in *Edit trip*; your choice always
  wins, and picking the suggested type again goes back to the suggestion. The suggestion isn't
  stored: it's worked out from the route each time, so an improved rule also applies to old trips.
  It can be wrong: a 4x4 crawl that never goes over 15 km/h is suggested as a walk.
- **Names.** A trip can have a name of up to 60 characters, like "Wadi Qelt hike". Without one,
  the trip is titled by its date.
- **Deleting a trip.** Deleting removes the trip, its route and its marked spots. If *Keep deleted
  trips in Stats* is on (it's off by default), the app first keeps the trip's date, duration,
  distance and type (walk or drive), with no location, no name and no spots, and Stats keeps
  counting it in the right card. Turning the setting off hides those trips from Stats until you
  turn it on again. Trips deleted while it's off are gone from Stats for good.
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
  with Android's built-in converter each time a trip is shown or shared, so every trip is
  converted, old ones included. Android 13 and older have no converter: there the trip shows
  climb, descent and the chart's shape, which don't depend on it, but no highest or lowest
  point, and the GPX file gets the heights as stored.
- **Map.** The app's own outdoor style (`app/src/main/assets/map/outdoor-style.json`), drawn by
  MapLibre on [OpenFreeMap](https://openfreemap.org)'s vector tiles (OpenStreetMap data in the
  OpenMapTiles schema, zoom 14 at most), with hill shading from
  [Mapterhorn](https://mapterhorn.com)'s terrain tiles, asked for only up to zoom 12 (squares
  about 8 km across in Israel) and stretched for closer zooms. The style ships inside the app; its
  tiles, fonts and icons are loaded as needed and cached. It's the same light style in dark mode,
  and the route, gaps and dots keep their light-map colors. Labels use the Hebrew or English name
  to match the app's language, else the local name. You can pan, zoom and rotate the map; tilting
  is off. The scale bar is always in meters and kilometers. Without internet the style still
  loads, so the map shows what's cached on its plain land color. Only if the map can't load at
  all does a trip's screen show a plain outline of the route, with a *Retry map* button.
- **Offline maps.** A pack is two [PMTiles](https://docs.protomaps.com/pmtiles/) files: one vector
  file (OpenMapTiles layers z0-14, plus the app's own `outdoor_transportation`,
  `outdoor_waterway`, `trail` and `contour` layers) and one terrain file (Mapterhorn z0-11). They
  live in the app's own folder on the phone's storage (`Android/data/<app>/files/maps`), which
  Android deletes with the app. While the camera's center is inside a pack's area (from zoom 7, or
  at any zoom once the online map failed), the map uses the pack style (`MapPackStyle`): the
  outdoor style pointed at the two files, with fonts and icons from the app's assets and copies of
  its track, path, road and wadi layers reading the outdoor layers at low zoom. Nothing in it goes
  to the network. `scripts/offline-maps/` builds the packs and their list, `maps.json`; the app
  reads that list from the repo's `maps` GitHub release when Settings opens, and downloads with
  Android's download manager (resume, Wi-Fi only, notification, no extra permission), then checks
  each file's size and SHA-256 before using it.
- **The Map tab.** The same outdoor map, with your location from Google Play services'
  location service, asked for every 2 seconds at high accuracy, only while the tab is on screen and
  the app is in the foreground; switching tabs or leaving the app stops it at once. Nothing it gets is
  stored. If the map can't load at all, it switches to a style that is a single background color,
  with no tiles, fonts or icons to download, so your dot and the trip's route still draw on it. While a
  trip records with the map open, Android shares fixes between the two requests, so the recording
  may get a point every 5 seconds instead of every 10; its own rules still decide which it keeps.
- **The compass.** Android's rotation vector sensor (or its magnetometer-only version), read about
  15 times a second only while the Map tab is on screen with the app in front, and only while
  there's a dot or the map turns with it. The heading is where the screen's top edge and the
  phone's back point, so it doesn't jump as the phone tilts from flat to upright, and it follows
  the screen in landscape. It's lightly smoothed, and turned from magnetic to true north with
  Android's model of the Earth's magnetic field at your position (about 5 degrees in Israel).
  *Map turns with me* uses the GPS direction of travel instead from 15 km/h, from a fix under 5
  seconds old, and the compass again below 10 km/h; the gap keeps it from flipping at one speed.

## Steps and trips are separate

Recording a trip doesn't change your step counts, and deleting a trip never removes steps. Trip
code never reads step data, and step code never uses location. The two share only the app's
database file and its screens.
