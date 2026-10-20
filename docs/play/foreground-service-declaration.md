# Foreground service declaration: location

Play Console → **Policy → App content → Foreground service permissions**. Prepared 2026-10-21 from
main `dc88b27`.

The app declares one foreground service type: **location**.

- Service: `TripRecordingService`, with `android:foregroundServiceType="location"`. Not exported.
- Permissions: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`.
- There is no other `FOREGROUND_SERVICE_*` permission, and no `ACCESS_BACKGROUND_LOCATION`.

**What Google asks for each type:**

- a description of the feature;
- the user impact if the task is **deferred** and if it's **interrupted**;
- a **video link** "demonstrating the steps the user needs to take in your app in order to trigger
  the feature";
- a **use case** from a preset list, or your own if none fits.

## Answers to paste

**Foreground service type:** Location

**Use case:** Google's preset list for location has three entries:

- *Background Location Updates: User-initiated location sharing* ("Find My Friend feature, vehicle
  activity tracking");
- *Background Location Updates: Navigation* ("continuing driving navigation in maps, ride tracking
  for ride share");
- *Geofencing*.

None is exact. Google: "if you do not see your use case listed, you can enter your use case
manually."

- **If the form lets you type one (recommended):**
  `User-initiated GPS route recording of a trip (hike, walk or off-road drive)`
- **If you must pick a preset:** *Background Location Updates: Navigation*. Recording the user's
  own trip is closest to "ride tracking". Don't pick *location sharing*: the app shares no location
  with anyone.

**Description of the feature**

```text
Steps GPS Route records the GPS route of a trip the user starts, such as a hike, a nature walk or an off-road drive. Recording starts only when the user taps "Start trip" (or "Resume" on an interrupted trip) on the Trips screen, after an in-app explanation and the location permission (precise, while using the app). The app then runs a foreground service of type location with an ongoing "Recording your trip" notification, which has Mark spot and Finish actions and also shows on the lock screen. The service gets a GPS position about every 10 seconds and stores the route only on the device, so recording continues with the screen off or while the user uses other apps (for example a navigation app on a 4x4 drive). It stops when the user taps "Finish trip" in the app or "Finish" in the notification. The app never starts recording by itself: nothing starts the service at boot, and the app does not request background location. If Android restarts the app during a trip, the service only continues that same user-started trip.
```

**User impact if the task is deferred**

```text
The route is recorded live and positions can't be recorded after the fact. If the start were deferred, the beginning of the trip (the trailhead, or the start of the drive) would never be recorded: the map, distance, duration, moving time and the exported GPX file would all be missing that part.
```

**User impact if the task is interrupted**

```text
Interrupting the service stops recording the user's position. Everything from the interruption until the user notices is lost for good: the route shows a gap drawn as a straight gray line, and the distance, moving time and top speed come out too low. The next time the app opens, the trip is marked "interrupted" and the user can resume it (with a gap) or finish it at the last point, but the missing part of the route can't be recovered.
```

**Video link:** an unlisted video, recorded following the script below. Check that it plays
without signing in, then paste its URL.

## Location policy check (no background-location declaration)

Google's location policy:

- The background-location declaration is triggered by `ACCESS_BACKGROUND_LOCATION`, which the app
  doesn't have.
- A location foreground service "must be initiated as a continuation of an in-app, user-initiated
  action" and must stop when that task is done. The app does both: **Start trip** starts it, and
  **Finish** stops it.
- The app shows its own explanation dialog before Android's permission prompt.

---

## Video script and shot list (about 60-75 seconds)

**What Google needs to see:** the user starting the feature in the app, the service working while
the app is in the background, and the user stopping it.

**Prepare:**

- **Phone:** the A55, with the app **installed from Play's internal testing track**. That build
  (`bukin.stepsgpsroute`) is a separate app from the debug build (`com.example.stepsplit.debug`),
  so it starts with no trips and no permissions. That's ideal: nothing private shows, and
  Android's permission prompts appear.
- **Place:** outdoors, a short walk somewhere that isn't near home. The video shows a small map of
  where it was recorded, and an unlisted video can be watched by anyone with the link.
- **Phone settings:** turn on **Do Not Disturb**, and close chats and other apps with private
  content. Set battery use for the app to *Unrestricted* only if you want to show that tip. It isn't
  needed.
- **Screen recorder:** *Quick panel → Screen recorder*. Choose **No sound**. "Show taps" is
  optional but helps.

| # | Time | What to do on the phone | What the reviewer sees |
|---|---|---|---|
| 1 | 0:00-0:05 | Start recording the screen with the app already open on **Trips**. | "Route recording is off" and the **Start trip** button. |
| 2 | 0:05-0:20 | Tap **Start trip** → the app's "Location needed for this trip" dialog → **Continue** → Android's prompt: keep **Precise**, tap **While using the app** → then **Allow** notifications. | A user-initiated start, the in-app explanation, then the runtime permissions. |
| 3 | 0:20-0:30 | Walk. Wait for **GPS: Good**. Let the clock and the distance count up a little. | The green **Recording route** card is working. |
| 4 | 0:30-0:40 | Press **Home**, open another app (Settings is fine), then pull down the notification shade. | Recording continues in the background, and the ongoing **Recording your trip** notification shows its **Mark spot** and **Finish** buttons. |
| 5 | 0:40-0:47 | Tap **Mark spot** in the notification. | The notification briefly says "Spot 1 marked". |
| 6 | 0:47-0:57 | Turn the screen **off** for 5 seconds, turn it on: the notification is on the lock screen. Unlock (**cut the PIN entry** in the editor if the recorder shows it) and return to the app. | The service survived screen-off. The clock and distance kept counting. |
| 7 | 0:57-1:07 | Tap **Finish trip** → confirm. | The notification disappears and the card returns to "Route recording is off". The trip appears under **Previous trips**. |
| 8 | 1:07-1:12 (optional) | Tap the new trip. | The recorded route on the map. |

**After recording:**

- Watch it once. Check that no private notification, chat or home address shows. Trim the start
  and end.
- Upload to YouTube as **Unlisted**, or to Google Drive with "Anyone with the link".
- Open the link in a private browser window to check that it plays **without signing in**.

The video is only for the declaration. It isn't the store's promo video.

## Consistency checks before submitting

- The release bundle's merged manifest has exactly one type, `location`, and no other
  `FOREGROUND_SERVICE_*` permission. Check again after the release-setup and Open map changes.
- The description matches what the video shows: start in the app, notification, screen off, Finish.
- The privacy policy and Data safety describe the same thing: location only during a trip the user
  started, stored only on the phone.

## Sources (checked 2026-10-21)

- [Understanding foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Location permissions policy (background vs foreground, FGS)](https://support.google.com/googleplay/android-developer/answer/9799150)
- The app: `AndroidManifest.xml`, `trip/service/TripRecordingService.kt` (`START_STICKY` only
  while recording), and the README's *Recording a trip* section
