# Google Play publication checklist: Steps GPS Route

Status on **2026-10-21**, prepared on branch `play-console-prep` (from main `dc88b27`).

- **Nothing has been entered in Play Console.** No app has been created and no bundle uploaded.
- **Status:** ✅ done · ⬜ to do · 🔄 in progress elsewhere.
- **Who:** **Owner** = Eli, in Play Console or on the phone. **Claude** = a Claude session in the
  repo.
- The short version for the owner is `OWNER-NEXT-STEPS.md`.

## The files in this folder

| File | What it's for |
|---|---|
| `OWNER-NEXT-STEPS.md` | The short, plain to-do list for Play Console |
| `data-safety.md` | Data safety answers, the reason for each, and the uncertain points |
| `health-connect-declaration.md` | Health apps declaration, the Steps justification text, what reviewers check |
| `foreground-service-declaration.md` | Location foreground-service text, plus the video script and shot list |
| `content-and-audience.md` | Content rating, target audience, ads, app access, advertising ID, the other declarations, store settings |
| `store-graphics/` | 512 icon, 1024 × 500 feature graphic, 8 English + 4 Hebrew screenshots, how they were made |
| `../store-listing.md` | Name, short and full descriptions, EN + HE (the Open map session is editing it) |

## 1. Decisions and prerequisites

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 1.1 | Personal developer account "bukin", identity verified | ✅ 2026-10-12 | Owner | Shared with Bukin Mic Recorder. |
| 1.2 | App name **Steps GPS Route** | ✅ | Owner | 15 of 30 characters. |
| 1.3 | Final application ID **`bukin.stepsgpsroute`** | 🔄 | Claude (release-setup) | Main still builds `com.example.stepsplit`. **The ID is permanent after the first upload**, so upload only a build with the final ID. |
| 1.4 | Release signing: upload key and the release workflow (`docs/RELEASING.md`) | 🔄 | Claude builds, owner makes the key | The owner generates and keeps the upload key, and uses Play App Signing. Never commit or share the key or its passwords. |
| 1.5 | Support email `dev.mechanics998877@gmail.com` | ✅ | Owner | Public on the store page. |
| 1.6 | Privacy policy published at `https://elibukin.github.io/steps/privacy-policy.html` | ✅ effective 2026-10-19 | Claude + owner | **Re-publish whenever the policy changes**, since the app ships a copy (Open map will change it). The in-app copy must match the URL entered in Play. |
| 1.7 | Category **Maps & Navigation** | ✅ draft | Owner confirms | *Health & Fitness* would clash with "not a health app". |
| 1.8 | Audience: general adults, not for children | ✅ policy says so | Owner confirms | Play target age: **18+** (see `content-and-audience.md`). |
| 1.9 | **Closed test waits for the mic app's production access** | ✅ decided | Owner | The paid Testers Community plan (12 testers, 14 days) is bought only after Bukin Mic Recorder reaches production. Until then, all free steps (1-6 below) can be done. |
| 1.10 | Store graphics | ✅ 2026-10-21 | Claude | `store-graphics/`. Owner to review. |
| 1.11 | Form answers (Data safety, Health apps, FGS, rating, audience, …) | ✅ 2026-10-21 | Claude | This folder. Owner to review the ⚠ points. |

## 2. App readiness (code)

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 2.1 | Target SDK 36, min SDK 26 (Android 8.0) | ✅ | Claude | Meets Play's target-API rule. |
| 2.2 | Release build minified (R8) and resources shrunk | ✅ | Claude | |
| 2.3 | Map doesn't require Vulkan (MapLibre OpenGL ES build) | ✅ `dc88b27` | Claude | Otherwise Play would hide the app from phones without Vulkan. |
| 2.4 | Health Connect privacy-policy screen, in-app policy, "Open Health Connect" fallback | ✅ `2387809`, `b7a6ce6`, `cf27d1c` | Claude | Required for Health Connect review. |
| 2.5 | No background location. FGS type `location` only. No ads, analytics or `AD_ID`. | ✅ | Claude | Checked in the merged debug manifest. Re-check the release one. |
| 2.6 | Open map feature (GPX import / open map) | 🔄 map-gpx-import | Claude | Changes the policy and the listing. **Decide whether it's in the first upload.** If it is, re-check Data safety and the screenshots. |
| 2.7 | `versionCode 1` / `versionName 1.0` for the first upload | 🔄 release-setup | Claude | Each later upload needs a higher `versionCode`. |
| 2.8 | Suggested text and app tweaks (section 10) | ⬜ | Owner decides, Claude does | None of them blocks publishing. |

## 3. Create the app in Play Console (free)

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 3.1 | **Create app**: name *Steps GPS Route*, default language **English (United States)**, App, **Free**; accept the declarations | ⬜ | Owner | Free can't become paid later. |
| 3.2 | **Store settings**: category, tags, contact email, website (optional) | ⬜ | Owner | Values in `content-and-audience.md`. |

## 4. Store listing (free)

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 4.1 | Main store listing (en-US): name, short and full description | ⬜ | Owner | Paste from `docs/store-listing.md` **after** the Open map session's edits land. |
| 4.2 | App icon, feature graphic, 8 phone screenshots | ⬜ | Owner | From `store-graphics/`, in numbered order. |
| 4.3 | Hebrew translation (iw-IL): texts and 4 Hebrew screenshots | ⬜ | Owner | Optional, but the app is in Hebrew. *Add translations → Hebrew*. |
| 4.4 | Tablet screenshots, promo video | ⬜ optional | Owner | Not required for phones. Skip for now. |

## 5. App content forms (free)

Some forms appear only after the first bundle is uploaded (step 6.2): Health Connect data types,
foreground service and advertising ID. Fill in the rest first.

| # | Form | Answer | Status | Who |
|---|---|---|---|---|
| 5.1 | Privacy policy | the URL above | ⬜ | Owner |
| 5.2 | App access | All functionality available, no login | ⬜ | Owner |
| 5.3 | Ads | No ads | ⬜ | Owner |
| 5.4 | Content rating (IARC) | Utility category, "No" throughout → Everyone / PEGI 3 | ⬜ | Owner |
| 5.5 | Target audience and content | 18 and over | ⬜ | Owner |
| 5.6 | News, COVID-19, government, financial | No / not applicable | ⬜ | Owner |
| 5.7 | Data safety | **No data collected or shared** (⚠ OpenFreeMap judgment call) | ⬜ | Owner |
| 5.8 | Health apps | Activity and fitness; Steps justification | ⬜ | Owner |
| 5.9 | Advertising ID | No | ⬜ | Owner |
| 5.10 | Foreground service: location | Text + **video link** | ⬜ | Owner (video: step 7) |

## 6. Internal testing (free, any testers, no 12/14 rule)

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 6.1 | Build and sign the first release bundle (final ID, `versionCode 1`) | ⬜ | Claude builds, owner signs | Follow `docs/RELEASING.md` (release-setup session). |
| 6.2 | **Internal testing → Create release**: upload the `.aab` and add testers (the owner's own Google account) | ⬜ | Owner | This makes the remaining App content items appear: FGS, Health Connect data types, advertising ID. |
| 6.3 | Roll out to internal testing; open the opt-in link on the A55; install from Play | ⬜ | Owner | The Play build (`bukin.stepsgpsroute`) installs **next to** the debug app (`com.example.stepsplit.debug`), and the debug app's data stays untouched. |
| 6.4 | Smoke test the Play-signed build: Health Connect permission on Today, a short trip with Mark spot, map, details, Share and Save file | ⬜ | Owner (Claude can write a short test plan) | First time the minified, Play-signed build runs on real hardware. **If Health Connect refuses access, the Health apps declaration must be approved first.** |
| 6.5 | If Play won't roll out the internal release until the FGS form has a video: record it first (step 7) with a release APK sideloaded next to the debug app, then roll out | ⬜ only if needed | Owner + Claude | Not verified whether Play requires this for internal testing. |

## 7. Foreground-service video (free)

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 7.1 | Record the 60-75 s video on the A55 with the **Play** build, outdoors, away from home, Do Not Disturb on | ⬜ | Owner | Script: `foreground-service-declaration.md`. |
| 7.2 | Upload as **Unlisted** (YouTube) or Drive "anyone with link"; check it plays signed out | ⬜ | Owner | |
| 7.3 | Paste the link and texts into the FGS declaration; submit the App content forms | ⬜ | Owner | |

## 8. Closed test: **paid; only after Bukin Mic Recorder reaches production**

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 8.1 | **Wait:** the mic app's 14-day closed test ends about 2026-10-30; then it applies for production (review usually ≤ 7 days) | 🔄 | Owner | Decision 1.9. |
| 8.2 | Buy the Testers Community plan for Steps GPS Route | ⬜ | Owner | Same provider as the mic app. |
| 8.3 | **Closed testing → Create track**, promote the internal release (or upload a newer one), set up the testers list or Google Group and the feedback channel, and send it for review | ⬜ | Owner | Closed releases are reviewed. All App content must be complete. |
| 8.4 | **≥ 12 testers opted in for 14 days in a row** | ⬜ | Testers | Opt-outs break the run. Internal testers don't count. |
| 8.5 | Collect real feedback; fix and upload new versions as needed | ⬜ | Owner + Claude | The production application asks about it. |

## 9. Production

| # | Step | Status | Who | Notes |
|---|---|---|---|---|
| 9.1 | **Dashboard → Apply for production**: three sections (closed test, the app, production readiness) | ⬜ | Owner | Questions cover tester recruitment and engagement, feedback, audience, value, expected installs, and changes made. Review "usually takes seven days or less". |
| 9.2 | Create the production release; choose countries; roll out (a staged rollout is safest) | ⬜ | Owner | |
| 9.3 | Watch Android vitals and reviews | ⬜ | Owner + Claude | |

## 10. Suggested changes (not made here: other sessions own these files)

1. **Store listing, "Not a health app" heading** (`docs/store-listing.md`).
   - The Health apps form must declare *Activity and fitness*, listed under "Health and fitness".
   - To avoid a reviewer reading a contradiction, consider "Not a medical app" or "No health
     advice", keeping the same text ("not a medical, health-advice or fitness-coaching app…").
   - Owner's call. The current wording isn't false.
2. **Store listing credit** (optional). Add "Map data © OpenStreetMap contributors" at the end of
   the full description. The map's own credit is behind its (i) button.
3. **Privacy policy, "Other information stored on your device"** (`docs/privacy-policy.html`).
   - Now: "It is not encrypted beyond Android's own protection of app storage."
   - Health Connect's policy expects "encryption at rest and in transit".
   - Suggest: "It is protected by Android's storage encryption and app sandbox; the app adds no
     encryption of its own."
   - Then re-publish the policy, since the app ships it.
4. **App: the pre-permission dialog** (`Location needed for this trip`).
   - Now: "never in the background otherwise".
   - Suggest saying plainly that recording continues with the screen off or in other apps, as the
     recording card does: "Location is used only while a trip you started is recording, including
     when the screen is off or you use other apps."
   - That matches the FGS declaration word for word. A small string change in EN + HE.
5. **App, dependency cleanup** (optional, release-setup).
   - WorkManager is kept only to cancel a job left by old development builds, which never reached
     Play users.
   - It adds `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` to the store's permission list. Its boot
     receiver is disabled, but the permission still shows.
   - Dropping it for the Play build would make the list match the README.
6. **README permissions table.** It lists 5 permissions. The merged manifest also has
   `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (MapLibre / Play services), `WAKE_LOCK` and
   `RECEIVE_BOOT_COMPLETED` (WorkManager). Mention them, or remove them per item 5.

## 11. Open questions and uncertain points

- **Data safety and OpenFreeMap.** "No data collected" is recommended. A conservative "approximate
  location, collected" alternative is spelled out in `data-safety.md`. Owner decides.
- **Data safety and Play services location.** Check the SDK Index entry for `play-services-location`
  in Play Console before submitting.
- **Health Connect on internal testing.** Not verified whether Health Connect blocks the app before
  the declaration is approved. Test in step 6.4.
- **FGS form for internal testing.** Not verified whether an internal release needs the FGS video
  first. See step 6.5.
- **IARC wording.** Google's help doesn't print the questionnaire. Answers are by topic; read each
  question in the console.

## Sources (all checked 2026-10-21)

Each file lists the pages it used. The main ones:

- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
  (12 testers, 14 days, production application)
- [Prepare your app for review: App content](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Health apps declaration](https://support.google.com/googleplay/android-developer/answer/14738291)
  and [Health Connect: declare access](https://developer.android.com/health-and-fitness/guides/health-connect/publish/declare-access)
- [Foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
