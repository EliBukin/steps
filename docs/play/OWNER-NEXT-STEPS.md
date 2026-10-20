# Steps GPS Route: what you do in Play Console, and when

Prepared 2026-10-21. Nothing has been entered in Play Console yet. Everything to paste is in this
folder. The full list with statuses is `PUBLICATION-CHECKLIST.md`.

## Now: free steps, any time

Start once the release-setup and Open map work is merged and the first release bundle exists.

1. **Create the app.**
   - *Play Console → Create app*: name **Steps GPS Route**, language **English (United States)**,
     **App**, **Free**.
   - The bundle you upload later must have the final ID **`bukin.stepsgpsroute`**. It can never
     change after that.
2. **Store settings.** Category **Maps & Navigation**, contact email, tags
   (`content-and-audience.md`, bottom).
3. **Main store listing.**
   - Paste the English texts from `docs/store-listing.md`.
   - Upload `store-graphics/icon-512.png`, `store-graphics/feature-graphic-1024x500.png` and the 8
     files in `store-graphics/screenshots/en/`, in order.
   - Optional: **Add translation → Hebrew**, with the Hebrew texts and `screenshots/he/`.
4. **App content forms.** In this order:
   - Privacy policy;
   - App access;
   - Ads;
   - Content rating;
   - Target audience;
   - News, government, financial;
   - Data safety;
   - Health apps.

   Answers are in `content-and-audience.md`, `data-safety.md` and
   `health-connect-declaration.md`. Before Data safety, decide the one ⚠ point in `data-safety.md`
   (OpenFreeMap map tiles): "No" is recommended.
5. **Internal testing.**
   - *Testing → Internal testing → Create release*: upload the signed `.aab` and add your own
     Google account as a tester. It's free and has no 12-tester rule.
   - After this upload, three more forms appear under App content: **Foreground service**,
     **Health Connect data types** (inside Health apps) and **Advertising ID** ("No").
6. **Install from Play on the A55.**
   - Open the internal testing link and install. It installs next to the debug app; your current
     trips stay where they are.
   - Try it:
     - **Grant permission** on Today;
     - a short trip with **Mark spot**;
     - the trip's map and details;
     - **Share** and **Save file**.
   - If Health Connect says the app can't access it, the Health apps declaration has to be
     approved first. Tell the coordinating session.
7. **Record the foreground-service video** with that Play-installed app.
   - Follow the shot list in `foreground-service-declaration.md`: about 1 minute, outdoors, away
     from home, Do Not Disturb on.
   - Upload it as **Unlisted** and check that it plays without signing in.
   - Paste the link and the texts into *App content → Foreground service*. Submit the forms.

## Later: only after Bukin Mic Recorder reaches production

The mic app's 14 days end about **2026-10-30**. Then it applies for production, and Google usually
answers within 7 days.

8. **Buy the Testers Community plan** for Steps GPS Route.
9. **Closed testing.**
   - Create the track, promote the internal release (or upload a newer one), add the testers and a
     feedback channel, and send it for review.
   - **At least 12 testers must stay opted in for 14 days in a row.** Internal testers don't count.
10. **Apply for production** from the Dashboard. You'll answer questions about the test, the
    feedback and the app. Google usually decides within 7 days.
11. **Release to production.** Pick countries, and use a staged rollout.

## Before you submit anything, decide these

- **Data safety:** "No data collected" (recommended), or the cautious "approximate location"
  version. Both are in `data-safety.md`.
- **Store listing:** keep the "Not a health app" heading, or say "Not a medical app" (see
  `PUBLICATION-CHECKLIST.md` section 10). The Health apps form has to declare *Activity and
  fitness*.
- **Content rating:** the GPX share answer, "doesn't share location with other users" (see
  `content-and-audience.md`).
- **Target age:** 18 and over (recommended).
