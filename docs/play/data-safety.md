# Data safety form: answers for Steps GPS Route

Play Console → **Policy → App content → Data safety**. Prepared 2026-10-21 from main `dc88b27`
(code, merged manifest and the privacy policy effective 2026-10-19). Google's definitions were
checked the same day (sources at the end).

**Re-check these answers** whenever the app gains a new SDK or network use, or another way to send
data off the phone. The Open map work that's in progress counts, as do any changes to OpenFreeMap
or the map library.

## The answers

| # | Question | Answer | Reason |
|---|---|---|---|
| 1 | Does your app collect or share any of the required user data types? | **No** | The app never sends steps, trips, locations or anything else about the user to the developer or anyone else. See the table below. |
| 2 | Is all of the user data collected by your app encrypted in transit? | *Not asked after "No"* | Play only asks this after "Yes". |
| 3 | Do you provide a way for users to request that their data is deleted? | *Not asked after "No"* | The same. Everything is stored on the phone, and the user deletes it in the app or by uninstalling. |
| 4 | Privacy policy | `https://elibukin.github.io/steps/privacy-policy.html` | Entered under **App content → Privacy policy**. It's required even with "No". |

Play then shows **"No data collected"** and **"No data shared with third parties"** on the store
page.

## Why "No": each data flow, checked against Google's definitions

Google's definitions:

- **"Collect"** means "transmitting data from your app off a user's device".
- Data "only processed locally on the user's device and not sent off device" doesn't need to be
  disclosed.
- Data sent by "libraries and/or SDKs used in your app" counts, whether it goes to the developer or
  to a third party.
- **"Sharing"** includes an on-device transfer to another app. A transfer "based on a specific
  user-initiated action, where the user reasonably expects the data to be shared" is an exception.

| Data flow | What it is | How it counts |
|---|---|---|
| **Steps (Health Connect)** | Read-only `READ_STEPS`, only while the app is open. Stored in the app's private database. Never written back to Health Connect, never sent. | Processed on the device only: **not collected**. |
| **Precise location (trips)** | GPS points while a trip the user started is recording. Stored only on the device. | Processed on the device only: **not collected**. |
| **Google Play services location** | The app gets positions from Google Play services' fused location provider (`play-services-location`). Depending on the phone's location settings, Play services may use Wi-Fi and mobile networks, under Google's own privacy policy. | That traffic belongs to the phone's location service, not to this app. The app's own code and its client library send nothing. **Not declared.** ⚠ Google doesn't say this outright; see *Uncertain points*. |
| **Map tiles (OpenFreeMap)** | While a trip's screen is open, MapLibre loads the map's style, fonts and vector tiles over HTTPS from `tiles.openfreemap.org`. The requests carry the tile numbers of the area shown (zoom 14 at most), plus the IP address and the usual request headers. They never carry GPS points, times or trip data. | **Not treated as collection** (recommended). ⚠ This is the one real judgment call; see below. |
| **GPX share and Save file** | Sent only when the user taps **Share** on a trip and picks an app, or **Save file** and picks a folder. | A specific user-initiated action where the user expects the data to go there: the sharing exception applies. The app itself transmits nothing, so it isn't collection either. **Not declared.** |
| **Phone-to-phone transfer** | Cloud backup is off (`allowBackup="false"`), but Android's device-to-device transfer may copy app data. | Done by Android at the user's request. **Not declared.** |
| **Play install stats and crash reports** | Google collects these about every Play app, if the user agreed. | Not the app's own collection. **Not declared.** |
| **Ads, analytics, crash reporting, advertising ID** | None. The merged manifest has no `AD_ID` permission and no such SDK. | Nothing to declare. |

## ⚠ Uncertain points: the owner decides

### 1. OpenFreeMap map tiles

**Why I recommend "No":**

- The app doesn't send the user's location. It asks for map pictures of an area, the way a browser
  showing a map does.
- Google's own disclosure for its **Maps SDK for Android**, which loads map tiles the same way,
  lists IP address, request metadata and map interactions, but **not location**.
- OpenFreeMap says its access logs keep **no IP addresses by default**. Error logs may hold the IP
  address and the URL, and all logs are deleted after 7 days (incident logs after at most 30).
- The privacy policy already describes this traffic in plain words.

**Why it's arguable:**

- The area loaded is the area of a trip, which usually is where the user was.
- A zoom-14 tile in Israel covers about 4.2-4.5 km². That's above Google's 3 km² line between
  *approximate* and *precise*.

**Strictly conservative alternative.** Use it if the owner prefers to over-declare. Nothing else
changes.

| Question | Alternative answer |
|---|---|
| Collect or share required data types? | **Yes** |
| Encrypted in transit? | **Yes**: every request goes to `https://tiles.openfreemap.org`. |
| Users can request deletion? | **No**. The developer holds no data to delete. OpenFreeMap deletes its logs itself, after 7 days. |
| Data types | **Location → Approximate location** only |
| Collected / shared | Collected: **Yes**. Shared: **No**. OpenFreeMap only sends the map back to the app. |
| Processed ephemerally? | **No**. OpenFreeMap's error logs can keep the URL for up to 7 days. |
| Required or optional? | **Required**. Opening a trip's screen always loads its map. |
| Purpose | **App functionality** |

The store page would then say "This app may collect approximate location". That's less
attractive, but it can't be called under-declaring.

### 2. Google Play services location

Google's Data safety help doesn't mention Play services by name. Before submitting, open the
**Google Play SDK Index** entry for `com.google.android.gms:play-services-location`, which Play
Console links from the Data safety page. If it says the SDK collects data for which the app must
declare something, follow that. When checked on 2026-10-21, the SDK Index page didn't render
outside a browser.

## Keep consistent with

- **Privacy policy:** it describes every flow above, including OpenFreeMap and Play services
  location.
- **Store listing:** "Your data stays on your phone … The only internet use is loading the map
  (from OpenFreeMap)". That matches "No data collected".
- **Health apps declaration and Health Connect:** see `health-connect-declaration.md`. Steps
  never leave the phone.
- **Advertising ID:** "No". See `content-and-audience.md`.

## Sources (checked 2026-10-21)

- [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Maps SDK for Android: Google Play data disclosure](https://developers.google.com/maps/documentation/android-sdk/play-data-disclosure)
- [OpenFreeMap privacy policy](https://openfreemap.org/privacy/), and its TileJSON at
  `https://tiles.openfreemap.org/planet` (`"maxzoom": 14`)
- The app: `AndroidManifest.xml`, the merged debug manifest (`processDebugManifest`), and
  `ui/trips/TripRouteMap.kt` (HTTPS style URLs)
