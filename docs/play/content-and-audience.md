# App content: every other form

Play Console → **Policy → App content**. The answers for every item not covered by its own file.
Prepared 2026-10-21 from main `dc88b27`.

The Data safety, Health apps and foreground service forms have their own files: `data-safety.md`,
`health-connect-declaration.md` and `foreground-service-declaration.md`.

Play shows the items still to do under **Needs attention**. Some appear only after the first bundle
is uploaded, such as foreground service, Health Connect data types and advertising ID. If Play
shows an item that isn't listed here, it's new: check it rather than guess.

## Privacy policy

`https://elibukin.github.io/steps/privacy-policy.html`. Required: the app requests location and
Health Connect access. The in-app copy (Settings, and Health Connect's privacy-policy link) must be
the same text.

## App access (sign-in details)

**All functionality is available without special access.** There is no account, login or
membership.

Optional note for reviewers, if the form offers a box:

```text
No login. Steps need Android 14+ with Health Connect: tap "Grant permission" on Today and allow Steps. Trips: on Trips tap "Start trip", allow precise location, and walk or drive; the recording notification has Mark spot and Finish. Open a finished trip to see its map, details panel and Share (GPX).
```

## Ads

**No, my app does not contain ads.** There is no ad SDK.

## Advertising ID

**Does your app use advertising ID? → No.**

- The merged manifest has no `com.google.android.gms.permission.AD_ID`.
- The only Google Play services library is `play-services-location`, which doesn't add it.
- Re-check the **release** merged manifest after the release-setup changes. If any library ever
  adds `AD_ID`, either remove it with `tools:node="remove"` or answer this form again.

## Content rating (IARC questionnaire)

1. **Email address** for IARC mail: the support address `dev.mechanics998877@gmail.com`, or the
   owner's choice.
2. **Category:** *All other app types* / **Utility, Productivity, Communication, or Other**. It's
   not a game, not social, not news or education. The category names may read slightly
   differently in the console; pick the utility or other one.
3. **Answers:** Play's exact wording varies. These are the questions it currently asks this
   category.

| Question | Answer | Reason |
|---|---|---|
| Violence, blood, fear or horror | **No** to all | |
| Sexuality, nudity, sexual references | **No** to all | |
| Profanity or crude humor | **No** | |
| Drugs, alcohol, tobacco (controlled substances) | **No** | |
| Gambling, simulated gambling | **No** | |
| Does the app natively allow users to interact or exchange content with other users (chat, voice, sharing images or audio)? | **No** | There's no messaging, no feed and no other users inside the app. |
| Does the app share the user's current physical location with other users? | **No** ⚠ | The app never sends anyone the user's location. A GPX file of a *finished* trip leaves only when the user picks an app in Android's share sheet, and that other app does the sending. This is the one judgment call; if the owner prefers to be cautious, "Yes" only adds a "Shares location" notice and doesn't change the age rating. |
| Does the app allow users to purchase digital goods? | **No** | |
| Is the app a web browser or search engine / unrestricted internet access? | **No** | It only loads map tiles from OpenFreeMap. |
| Swastikas or other hate symbols, Nazi references | **No** | |

**Expected result:** the lowest rating everywhere (IARC 3+, PEGI 3, ESRB Everyone, USK 0). Redo
the questionnaire if a feature changes these answers, such as in-app sharing or chat.

## Target audience and content

| Question | Answer | Reason |
|---|---|---|
| Target age groups | **18 and over** only | The app is for a general adult audience and "not specifically designed for children" (privacy policy). Google warns that 13-15 and 16-17 "may be considered to include children in some locales". Any group that includes children brings in the Families policy. That's a needless burden for an app that handles location and Health Connect data. |
| Restrict minor access | **Off** (owner's choice) | It would hide the app from users Google believes are minors. Choosing 18+ as the target doesn't require it. |
| Could your app or store listing unintentionally appeal to children? (if asked) | **No** | Plain utility screens, no cartoon characters or games. |
| Teacher Approved / store presence | **Not applicable** | Only for apps aimed at children. |

## News apps

**No, my app is not a news app.**

## COVID-19 contact tracing and status apps

**My app is not a publicly available COVID-19 contact tracing or status app** (if Play still
lists this item).

## Government apps

**No.** It isn't developed by or for a government.

## Financial features

**My app doesn't provide any financial features.**

## Health apps

**Health and fitness → Activity and fitness.** See `health-connect-declaration.md`.

## Data safety

See `data-safety.md`. Recommended: **no data collected or shared**.

## Foreground service permissions

**Location.** See `foreground-service-declaration.md`.

## Items that should *not* appear (if one does, something changed)

- **Location permissions / background location declaration.** It only appears with
  `ACCESS_BACKGROUND_LOCATION`, which the app doesn't request.
- **Sensitive permissions declarations** (SMS, call log, all files, `QUERY_ALL_PACKAGES`, exact
  alarms, full-screen intent, photo and video): none is in the merged manifest.
- **Account deletion:** the app has no accounts.

## Store settings (not App content, but on the same visit)

| Field | Value |
|---|---|
| App or game | App |
| Free or paid | **Free**. This can't be changed to paid later. |
| Category | **Maps & Navigation** (from `docs/store-listing.md`) |
| Tags | hiking, GPS tracking, step counter: pick from Play's list |
| Contact email | `dev.mechanics998877@gmail.com` (public) |
| Website | Optional: `https://elibukin.github.io/steps/` |
| External marketing | Owner's choice |

## Sources (checked 2026-10-21)

- [Prepare your app for review (App content page)](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Content ratings](https://support.google.com/googleplay/android-developer/answer/9898843) and
  [Fill out the content rating questionnaire](https://support.google.com/googleplay/android-developer/answer/9859655).
  Google's help doesn't print the IARC questions; the wording above follows the questionnaire's
  usual categories. Check it against the console.
- [Target audience and content](https://support.google.com/googleplay/android-developer/answer/9867159)
- [Advertising ID](https://support.google.com/googleplay/android-developer/answer/6048248)
