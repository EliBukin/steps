# Health apps declaration and Health Connect access

Play Console → **Policy → App content → Health apps**. This one form covers both:

- the Health apps declaration, which every app must fill in;
- the Health Connect data-type declaration. Its data-type list appears once a bundle with Health
  Connect permissions has been uploaded.

Prepared 2026-10-21 from main `dc88b27`.

**What the app uses:**

- `android.permission.health.READ_STEPS` only.
- No `WRITE_*`, no `READ_HEALTH_DATA_IN_BACKGROUND`, no `READ_HEALTH_DATA_HISTORY`, and no other
  health data types.

## Page 1: health features

| Question | Answer | Reason |
|---|---|---|
| Health features your app offers | **Health and fitness → Activity and fitness** only | Google: select the features matching the Health Connect data types the app reads. "My app doesn't provide any health features" is for apps that access no health or fitness data. Steps belong to Activity and fitness ("apps that monitor and record physical activities"). Declaring no features while requesting `READ_STEPS` would contradict the manifest. Health Connect would then refuse the app ("can't access Health Connect"). |
| Medical features (Clinical decision support, Medical device apps, …) | **None** | The app gives no health advice and isn't a medical device. |
| Human subjects research | **None** | |

⚠ "Activity and fitness" sits under the heading *Health and fitness*, while the store listing has a
section called "Not a health app". The two aren't really in conflict: the app counts steps but
gives no health advice. Still, a reviewer may read "not a health app" literally. See *Suggested
changes* in `PUBLICATION-CHECKLIST.md`.

## Page 2: the Steps justification

Paste this into the text box for **Steps** (Activity and fitness data types). It's about 1,000
characters. If the field is shorter, use the short version below.

```text
Steps GPS Route shows the user how much they walk each day. It reads Steps from Health Connect, read-only, and only while the app is open on screen; it never reads in the background and never writes to Health Connect. The steps are used only for these user-facing features: today's steps against the user's daily goal and this week's total; the last 7 days as a chart; lifetime step statistics (total steps, best day, active days, estimated distance); and a split of each day's steps into brisk walks of 10+ minutes ("workout") and other steps ("incidental"). The app asks for Steps only, no other data type, and reads at most the last 30 days, so it needs no history or background permission. Step counts are stored only on the device, in the app's private database. They are never sent off the device, shared, sold, used for advertising, or used for any other purpose. The app has no account, ads or analytics. The user can revoke the permission in Health Connect at any time.
```

Short version (about 400 characters):

```text
Reads Steps (read-only, only while the app is open) to show today's steps against the user's goal, this week's total, a 7-day chart, lifetime step stats, and a split into brisk walks and other steps. Steps are stored only on the device, never sent off it, shared, sold or used for ads. No other data types, no background or history access.
```

## What Google's reviewers check, and where the app stands

| Requirement | Status in `dc88b27` |
|---|---|
| **An approved use case.** "Fitness, wellness and coaching" covers apps that help users "track, monitor, analyze, manage, and improve their physical fitness". | Showing daily steps against a goal fits *track and monitor*. |
| **Minimum data.** Request only what the features need, with a clear justification per permission. Generic reasons ("needed for app functionality") are rejected. | `READ_STEPS` only. The justification above names each feature. |
| **Privacy-policy screen in the app.** An activity for `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`, plus on Android 14+ an `activity-alias` for `VIEW_PERMISSION_USAGE` / `HEALTH_PERMISSIONS` guarded by `START_VIEW_PERMISSION_USAGE`. It must show "the same privacy policy you provide for your app in the Google Play Console". | Done: `ui/PermissionsRationaleActivity.kt` and the alias in the manifest (commits 2387809, b7a6ce6). Every build copies `docs/privacy-policy.html` into the app. **Before each release, publish the policy whenever the app's copy changed**, or the in-app screen and the Play URL drift apart. |
| **The privacy policy.** It says which app is asking, which data types, how they're used and shared, and how to delete them. It's reachable from the app and the store page. | Done: the policy's section "Step data from Health Connect" covers what, when, why, storage, withdrawal and deletion. It's in Settings and on the rationale screen. |
| **Limited Use.** Use only for the user-facing features. No transfer without explicit consent. No human access. No ads, data brokers or credit decisions. | Done: steps never leave the phone. The policy carries Google's own line: "The use of information received from Health Connect adheres to the Health Connect Permissions policy, including the Limited Use requirements." |
| **No headless access.** A visible app icon, no reading without a UI. | Done: steps are read only while the app is open. Nothing is scheduled. |
| **Security.** The policy asks for "encryption at rest and in transit" as a minimum. | Steps are never transmitted. At rest they sit in app-private storage, which Android's file-based encryption protects (mandatory on the Android 14+ phones that have Health Connect step counting). ⚠ The policy currently says the data "is not encrypted beyond Android's own protection of app storage". That's accurate, but a reviewer could read it as "not encrypted". See *Suggested changes*. |
| **Consistency with Data safety.** | Data safety answers "No data collected". Steps never leave the phone, so it's consistent. |

## When the review happens

- The declaration is reviewed with the app.
- Google: "This process must be completed for all publishing requests". Approval is tied to the
  package name. Later versions need a new declaration only when the data types change.
- If Health Connect access isn't declared or approved, users get a dialog that the app can't access
  Health Connect.
- ⚠ Not verified: whether that block already applies to installs from the **internal testing**
  track. After the first internal release, install it from Play and tap **Grant permission** on
  Today. If Health Connect refuses, the declaration has to be approved first.

## Sources (checked 2026-10-21)

- [Provide information for the Health apps declaration form](https://support.google.com/googleplay/android-developer/answer/14738291)
- [Health Connect: Declare access to data types (Publish your health app)](https://developer.android.com/health-and-fitness/guides/health-connect/publish/declare-access)
- [Health permissions: approved use cases, justification, review](https://support.google.com/googleplay/android-developer/answer/12991134)
- [Permissions and APIs that access sensitive information, Health Connect section (Limited Use)](https://support.google.com/googleplay/android-developer/answer/9888170)
- [Get started with Health Connect: the privacy-policy activity](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started)
- [Read data: the 30-day default and the background/history permissions](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)
