# Releasing Steps GPS Route to Google Play

How to build, sign, record and upload a release. Read it all before the first
upload: the application ID and the upload key are permanent decisions.

The process follows the one Bukin Mic Recorder already uses
(`/home/anxel/stmp/WavRecorder/docs/RELEASING.md`).

## App identity

| | Release | Debug |
|---|---|---|
| Application ID | `bukin.stepsgpsroute` | `bukin.stepsgpsroute.debug` |
| Name on the phone | Steps GPS Route | Steps GPS Route |

- **The application ID can never change after the first upload.** Play and
  Android identify the app by it alone. A different ID is a different app,
  with its own listing, data and permissions.
- **The Kotlin package stays `com.example.stepsplit`** (`namespace` in
  `app/build.gradle.kts`). It only names the code and the `R` class. Users
  never see it, and renaming it would touch every source file for no gain.
  It does show up in crash stack traces.
- **What follows the application ID automatically:** the GPX FileProvider
  authority (`${applicationId}.fileprovider`, which `GpxShare` reads from
  `context.packageName`), the androidx-startup provider authority, and the
  Debug tools' package row.
- **Per-app state starts empty** under a new ID: the database, settings,
  notification channels, and the Health Connect and location permissions,
  which have to be granted again.
- **Unchanged on purpose:** the Health Connect declarations (the rationale
  activity and the `ViewPermissionUsageActivity` alias are found by their
  intent filters, not by the package), and the intent action strings
  `com.example.stepsplit.*`, which only travel in explicit intents inside
  the app.
- **Old installs.** The debug app installed before this change,
  `com.example.stepsplit.debug`, is a separate app now. It stays on the
  phone next to the new one, with its data. To move its trips over, see
  [Moving the old debug app's data](#moving-the-old-debug-apps-data).

## Keys: Play App Signing and the upload key

The app uses **Play App Signing**, which Google requires for new apps:

- **App signing key.** Google generates and keeps it. It signs the APKs that
  Play delivers to phones. You never hold it.
- **Upload key.** You generate and keep it. You sign each upload with it,
  and Play checks it before accepting the bundle. If it's lost or leaked,
  you can ask Google to reset it from Play Console. That's possible because
  the app signing key stays safe with Google.

### What happens at the first upload

1. You create the app in Play Console and upload the first signed bundle to
   a testing track.
2. Play Console asks how the app should be signed. Keep the default,
   **Google-generated app signing key**. The other choices (reuse another
   app's signing key, or upload your own) are for apps that must share a
   signature, which this one doesn't.
3. Play records the certificate of the key that signed this upload as the
   app's **upload key**. Every later upload must be signed with the same
   key.
4. Both certificates' fingerprints then show on Play Console's **App
   integrity** page, under Play app signing. Note down both.

Phones only ever see Google's app signing certificate. An APK you signed
yourself (with the upload key or the debug key) therefore can't be updated
from Play, and the other way round: uninstall one before installing the
other.

### Choosing the upload key

Play lets one upload key serve several apps, so there are two options:

| | Reuse the mic app's upload key | A new key for this app |
|---|---|---|
| To keep safe | Nothing new | One more keystore and password |
| If the key leaks | Both apps need an upload key reset | Only this app does |
| If you ever transfer one app to another account | The new owner would need a key that also uploads your other app, so you'd reset it first | Hand over (or reset) this key alone |

**Recommendation: a new key for this app.** It costs one more keystore and
password to back up. In return, a leak or an app transfer affects one app
only. You decide.

If you reuse the mic app's key, skip the next section, and in the `sign`
command below use that keystore and its alias.

### Generating the upload key (the owner does this)

Rules for the upload key:

- Generate it yourself, on a computer you control. Nothing in this repository
  generates it or chooses its certificate details.
- Keep the keystore **outside this repository**. The `sign` command refuses a
  keystore inside the working tree, and `.gitignore` ignores keystore file
  types anywhere in the repo as a second line of defence.
- Keep the password in a password manager. Don't email it or put it in chat,
  tickets, shell commands, scripts, `gradle.properties` or this document.
- Use a 4096-bit RSA key, valid for about 27 years (10,000 days). Google
  asks for at least 25.

1. Make a private folder for it:

   ```bash
   mkdir -p ~/secure/stepsgpsroute && chmod 700 ~/secure/stepsgpsroute
   ```

2. Generate the key. `keytool` (part of the JDK) asks for the password and
   the certificate details at its prompts, so nothing secret goes on the
   command line. The keystore is PKCS12, so the key has the same password as
   the keystore, and keytool asks for one password only (twice):

   ```bash
   keytool -genkeypair -v -keystore ~/secure/stepsgpsroute/upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   The certificate details (name, organization, country) are visible to
   anyone who inspects a bundle. Use the publisher identity you want to be
   associated with, for example the one on the mic app's key.

3. Note the certificate's **SHA-256 fingerprint**. Play Console shows it
   after the first upload, and you'll need it to recognize the key later.
   keytool asks for the password:

   ```bash
   keytool -list -v -keystore ~/secure/stepsgpsroute/upload-keystore.jks -alias upload
   ```

4. **Back it up** to `\\192.168.20.5\f\TMP\`, as for the mic app's release
   records. For example, from a file manager on this computer, copy
   `~/secure/stepsgpsroute/upload-keystore.jks` to
   `smb://192.168.20.5/f/TMP/`.
   - The keystore is encrypted with its password. Keep the password only in
     the password manager, never next to the keystore.
   - A `TMP` folder may get cleaned up, so keep a second copy somewhere
     durable too, such as the password manager's file attachment or a USB
     drive.
   - Check that the backup opens: run the `keytool -list` command above on
     the copy, and compare the fingerprint.

## Build variants and what is signed

| Artifact | How it's produced | Signed with | Use |
|---|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `./gradlew assembleDebug` | Gradle's local debug key | Development (`bukin.stepsgpsroute.debug`). Never upload. |
| `app/build/outputs/bundle/release/app-release.aab` | `./gradlew bundleRelease` | **Unsigned** | Diagnostic only: inspection, size checks. Never signed or uploaded. |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `./gradlew assembleRelease` | **Unsigned** | Diagnostic only. Can't be installed. |
| `dist/<release>/app-release-unsigned.aab` and `.apk` | `scripts/release.sh build` | **Unsigned** | A verified release candidate, built from one commit. |
| `dist/<release>/<package>-<version>-vc<code>-upload-signed.aab` | `scripts/release.sh sign` | Your **upload key** | The only file to upload to Play. |
| `dist/<release>/device-test/…-DEBUG-KEY-not-for-upload.apk` | `scripts/release.sh test-apk` | This computer's debug key | Testing the release build on an emulator or a test phone. |

The `release` build type deliberately has **no signing config**. Gradle never
signs a release build, and it never falls back to the debug key. Debug builds
and every unit test work without any production secret.

Two release settings matter for Play:

- **R8.** The release is minified and its resources shrunk. The R8 mapping
  that decodes its stack traces is embedded in the bundle (Play uses it for
  crash reports) and archived next to it.
- **Languages.** `bundle { language { enableSplit = false } }` keeps every
  language in the base APK, as in the release APK that gets tested. With
  language splits, Play would install only the phone's languages, so an app
  language picked later in Android's settings (Settings → Apps → Steps GPS
  Route → Language) could be missing its strings. It adds about 0.4 MB to
  the installed app and 70 KB to the download (measured with bundletool).
  ABI and screen-density splits stay on.

## Ads (AdMob IDs)

The app shows Google AdMob ads in three places only: a banner at the bottom
of the Stats and History tabs, and an ad card in the Trips list. Their IDs
are never in the code:

| Build | AdMob app ID and ad units |
|---|---|
| Debug | Google's published test IDs, always. Only test ads. |
| Release, with `admob.properties` | The real IDs from that file. |
| Release, without it | None: no ads, no consent form, and the Mobile Ads SDK never starts. |

The IDs aren't secret: every installed copy of the app carries them. Keeping
them out of the code means a debug build can never show real ads (clicking
your own real ads can get the AdMob account suspended), and a release can
never show test ads.

**Where the real IDs go.** A file named `admob.properties` at the repository
root, next to `app/`. Git ignores it. Create it once AdMob has the app and
its two ad units:

```properties
# AdMob -> Apps -> Steps GPS Route -> App settings: the App ID (with "~").
appId=ca-app-pub-0000000000000000~0000000000
# AdMob -> Apps -> Steps GPS Route -> Ad units: the Banner unit (with "/").
bannerUnitId=ca-app-pub-0000000000000000/0000000000
# The Native advanced unit.
nativeUnitId=ca-app-pub-0000000000000000/0000000000
```

- The build checks the file. Each ID must have AdMob's form, and none may be
  one of Google's test IDs. Otherwise the release build stops with a message
  naming the wrong line.
- `scripts/release.sh build` copies the file into its clean checkout and
  writes in the build record whether that release has ads.
- Before testing real ads on a phone, add it as a test device in AdMob
  (Settings -> Test devices), so its taps never count.

**Testing Google's consent form.** The form only shows where the consent
rules apply (the EEA, the UK and Switzerland). A debug build can pretend to
be there:

```bash
./gradlew assembleDebug -Pstepsplit.umpDebugGeography=EEA -Pstepsplit.umpTestDeviceHashedId=<id>
```

then install it with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.
A phone's `<id>` is printed by the consent SDK in logcat ("Use new
ConsentDebugSettings.Builder().addTestDeviceHashedId(...)"). Emulators need
none. The answer is remembered: clear the app's data to see the form again.
Settings shows "Privacy options for ads" wherever the form applies.

## Version codes

- `versionCode` (in `app/build.gradle.kts`) must increase with **every**
  upload to Play, on any track (internal, closed, open or production). Play
  permanently rejects a version code it has already seen for this app, even
  if that upload was deleted or never rolled out.
- Nothing has been uploaded yet (checked 2026-10-21: no tags, no GitHub
  releases, `versionCode` 1 since the first commit). So the **first upload is
  `versionCode 1`, `versionName "1.0"`**.
- After that, raise `versionCode` by 1 for each upload, even one that only
  goes to internal testing. Raise `versionName` (shown to users) as you like:
  `1.0.1` for fixes, `1.1` for new features, as the mic app does.
- A bundle that's already uploaded moves to another track by promoting it in
  Play Console. It isn't built or uploaded again.
- Commit the bump on its own, for example "Release 1.0.1 (versionCode 2)".
  Releases are only ever built from a commit.

## Building and signing an upload

A release goes through `scripts/release.sh`. It never signs a bundle it didn't
build and verify itself. It needs `JAVA_HOME` (JDK 21) and `ANDROID_HOME`:

```bash
export JAVA_HOME=/home/anxel/tools/jdk-21.0.12+8 ANDROID_HOME=/home/anxel/Android/Sdk
```

1. **Build and verify one commit.** Name the commit (or a branch or tag that
   points at it) explicitly. It takes about 15 minutes:

   ```bash
   scripts/release.sh build main
   ```

   - **Isolated checkout.** It exports exactly that commit (`git archive`)
     into a fresh temporary directory, so uncommitted edits, untracked files
     and earlier build outputs can't reach the release.
   - **Gates.** It runs, in that directory:
     `./gradlew --no-daemon --continue clean assembleDebug testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleRelease bundleRelease`.
     That's the everyday gate plus the release variant's tests and lint, and
     both release artifacts. If anything fails, it stops: nothing is kept or
     signed, and the isolated checkout and its log are left for inspection.
   - **Contents.** It checks the bundle and the release APK, and stops on any
     difference:
     - the package is `bukin.stepsgpsroute`, not debuggable, and targets SDK
       36 or later (Google Play's current requirement);
     - the permissions are exactly the list in the script
       (`EXPECTED_PERMISSIONS`). A new one, for example from a library
       update, changes what Play Console's permission and Data safety forms
       must say, so add it to the list only on purpose;
     - no `vulkan` feature is required, so Play doesn't hide the app from
       phones without Vulkan;
     - the bundled privacy policy (`assets/privacy-policy.html`) is identical
       to `docs/privacy-policy.html` of that commit;
     - no debug tools: no class of the `com.example.stepsplit.debug` package
       survives R8, and the debug-only Compose tooling activities aren't
       declared;
     - the native libraries for 64-bit ABIs (MapLibre and two androidx ones)
       support 16 KB memory pages.
   - **Mapping.** The R8 mapping embedded in the bundle
     (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) must
     be byte-for-byte identical to the mapping that build wrote.
   - **Release directory.** It keeps the verified outputs together in a new,
     git-ignored directory, `dist/<package>-<versionName>-vc<versionCode>-<commit>/`.
     It refuses if one already exists for that commit, and makes every file
     read-only:
     - `app-release-unsigned.aab`, `app-release-unsigned.apk` and
       `mapping.txt`;
     - `build.log`, the lint reports, the unit test results, and the
       `aapt2 dump badging` of the bundle and the APK;
     - `build-record.txt`: the full commit and its tree, the toolchain
       versions, test and lint results, package, version, SDK levels,
       permissions, native libraries, and the sizes and SHA-256 of the bundle,
       the APK and the mapping.

2. **Sign that release directory.** `jarsigner` asks for the keystore
   password at the prompt:

   ```bash
   scripts/release.sh sign dist/<release> ~/secure/stepsgpsroute/upload-keystore.jks upload
   ```

   `sign` takes only a release directory written by `build`, never a loose
   bundle. Before signing, it re-checks that:
   - the bundle, APK and mapping still match the SHA-256 hashes in
     `build-record.txt`;
   - the recorded commit exists here with the recorded tree;
   - the bundle's embedded mapping is identical to the archived `mapping.txt`;
   - the bundle isn't already signed, and its package, permissions and SDK
     levels still pass;
   - the keystore lives outside the repository.

   It then signs, verifies the signature, checks the signed bundle still
   embeds the same mapping, refuses a debug key, and writes `sign-record.txt`:
   the upload certificate's owner and SHA-256 fingerprint, and the signed
   bundle's SHA-256 and size. A directory is never signed twice.

3. Before uploading, check that the fingerprint `sign` prints matches your
   upload key (from `keytool -list` above). From the second upload on, it
   must also match the upload certificate on Play Console's App integrity
   page.

4. **Back up the release directory** (see below), then upload
   `dist/<release>/…-upload-signed.aab` in Play Console.

## Testing the release build on a device

`test-apk` signs a copy of the verified release APK with this computer's
debug key. No password is asked: the SDK's debug keystore has a well-known
one.

```bash
scripts/release.sh test-apk dist/<release>
```

It writes `dist/<release>/device-test/<package>-<version>-vc<code>-DEBUG-KEY-not-for-upload.apk`
and a `test-apk-record.txt`. Install it on an emulator or a test phone
(`adb -s <serial> install …`).

- It is the exact release build (R8, no debug tools, `bukin.stepsgpsroute`),
  as a single APK with every ABI.
- It installs next to the debug apps, but it's a separate app with empty
  data.
- Play can't update it (different signature). Uninstall it, which deletes its
  data, before installing the app from Play.
- The real test of what users get is the **internal testing** track: Play
  delivers its own split APKs, signed with Google's key. After the first
  internal-test install, check the language switch too: change the app's
  language in Android's settings and check that the text follows.

## Keep a release record for every upload

**Copy the whole `dist/<release>/` directory of each upload to
`\\192.168.20.5\f\TMP\`**, as for the mic app (for example from a file
manager to `smb://192.168.20.5/f/TMP/`). `dist/` is ignored by Git.

| Item | Why |
|---|---|
| Source commit and tree (full hashes) | Rebuild or audit exactly what shipped |
| Toolchain versions, test and lint results | Know how it was built and verified |
| `versionCode` / `versionName` | Match Play Console and crash reports to source |
| SHA-256 of the unsigned and signed bundles | Prove which file was uploaded |
| Upload certificate SHA-256 fingerprint | Confirm the right key was used |
| R8 `mapping.txt` (and its SHA-256) | Decode obfuscated stack traces |

## Publishing the web page and the privacy policy

The `gh-pages` branch is the GitHub Pages site,
<https://elibukin.github.io/steps/>. Its source lives on `main`:

| File on `gh-pages` | What it is | Source on `main` |
|---|---|---|
| `index.html` | The app's web page (the home page; Play's optional *Website* field) | `docs/site/index.html` |
| `privacy-policy.html` | The privacy policy. The app, the README and Play Console link this URL, so it never moves | `docs/privacy-policy.html` |
| `icon-192.png`, `screenshots/*.webp` | The page's images: only the screenshots it shows | `docs/site/icon-192.png`, `docs/screenshots/` |
| `.nojekyll` | Serve the files as they are | |

Publishing needs the owner's OK.

- **Publish the policy whenever a release ships a changed copy.** The app
  bundles `docs/privacy-policy.html`, and Health Connect requires the policy in
  the app to be the one on Play.
- **Publish the page** when `docs/site/` or the screenshots it shows change.
- Both pages must stay self-contained: no scripts, fonts, stylesheets,
  analytics or images from other sites. A unit test checks the policy;
  `scripts/build-site.sh` checks the page.

`scripts/build-site.sh` copies the page, the policy, the icon and the
screenshots the page uses into a folder, and removes screenshots it no longer
uses:

```bash
git worktree add ../steps-pages gh-pages     # once
git -C ../steps-pages pull --ff-only
scripts/build-site.sh ../steps-pages
git -C ../steps-pages status                  # check what changed
git -C ../steps-pages add -A
git -C ../steps-pages commit -m "Publish the web page and privacy policy (<date>)"
git -C ../steps-pages push origin gh-pages
```

A minute or two after the push, open both URLs and check that
`privacy-policy.html` is byte for byte main's `docs/privacy-policy.html`
(for example `curl -s <url> | md5sum`).

To preview the site before publishing:

```bash
scripts/build-site.sh /tmp/steps-site
python3 -m http.server --bind 127.0.0.1 --directory /tmp/steps-site 8000   # http://127.0.0.1:8000/
```

## Native code and 16 KB pages

Google Play requires apps that target Android 15+ and ship native code to
support 16 KB memory pages. That concerns the 64-bit ABIs (`arm64-v8a`,
`x86_64`). This app ships native libraries: MapLibre (`libmaplibre.so`, the
trip map) and two small androidx ones. All of their 64-bit builds are 16 KB
aligned, and `build` checks this on every release. MapLibre's 32-bit builds
are 4 KB aligned, which is allowed. Re-check after updating MapLibre: `build`
stops if a 64-bit library loses its alignment.

## Moving the old debug app's data

A one-time step for the owner's phone. The old debug app,
`com.example.stepsplit.debug`, holds the real trips. The new debug app,
`bukin.stepsgpsroute.debug`, starts empty. Both are debuggable, so `run-as`
can read the old app's files and write the new app's. Only do this with the
owner's OK.

What moves: the Room database (`databases/stepsplit.db` and its
`stepsplit.db-wal`; steps, trips, spots, deleted-trip totals) and the settings
(`files/datastore/stepsplit_settings.preferences_pb`: goals, thresholds, keep
deleted trips). The Health Connect sync status
(`stepsplit_step_source_health`) isn't moved; it rebuilds at the first sync.
Both apps must run the same database version (10 today); if the new one is
newer, Room migrates the copy on first open, as on any update.

1. **Prepare.** Find the phone's serial (`adb devices -l`, model `SM_A556E`),
   and pin every command to it. No trip may be recording in the old app.
   Stop both apps, so the files don't change while they're copied:

   ```bash
   P=<serial>; OLD=com.example.stepsplit.debug; NEW=bukin.stepsgpsroute.debug
   ```

   ```bash
   adb -s $P shell am force-stop $OLD
   ```

2. **Back up the old app's files** to the computer, and check them:

   ```bash
   B=~/stmp/claudecode/stepsplit-a55-backups/move-$(date +%Y%m%d-%H%M) && mkdir -p $B
   ```

   ```bash
   adb -s $P shell run-as $OLD ls -l databases files/datastore
   ```

   ```bash
   for f in databases/stepsplit.db databases/stepsplit.db-wal files/datastore/stepsplit_settings.preferences_pb; do adb -s $P exec-out run-as $OLD cat $f > $B/$(basename $f); done; ls -l $B
   ```

   The sizes must match the listing. If the listing has no `stepsplit.db-wal`
   or no settings file, skip that file in the steps below. Never skip an
   existing WAL: most of the data can be in it (in the rehearsal, the `.db`
   file was 4 KB and the WAL 338 KB). Check a scratch copy, never the backup
   itself, since opening it merges the WAL:

   ```bash
   T=$(mktemp -d) && cp $B/stepsplit.db $B/stepsplit.db-wal $T/ && sqlite3 $T/stepsplit.db "PRAGMA integrity_check; SELECT COUNT(*) FROM trips;"
   ```

3. **Install the new debug app** (`./gradlew assembleDebug`, then
   `adb -s $P install app/build/outputs/apk/debug/app-debug.apk`). It's a
   separate app, so the old one and its data stay. Don't open it yet. If it
   was opened before, the next step replaces its empty database.

4. **Copy the files in**, with the new app stopped. Its old `-shm` file is
   deleted so SQLite rebuilds it from the copied WAL:

   ```bash
   adb -s $P shell am force-stop $NEW
   ```

   ```bash
   adb -s $P shell "run-as $NEW sh -c 'mkdir -p databases files/datastore && rm -f databases/stepsplit.db databases/stepsplit.db-wal databases/stepsplit.db-shm databases/stepsplit.db-journal'"
   ```

   ```bash
   for f in stepsplit.db stepsplit.db-wal; do adb -s $P exec-in run-as $NEW sh -c "cat > databases/$f" < $B/$f; done
   ```

   ```bash
   adb -s $P exec-in run-as $NEW sh -c "cat > files/datastore/stepsplit_settings.preferences_pb" < $B/stepsplit_settings.preferences_pb
   ```

5. **Check the copies before opening the app.** `adb exec-in` returns before
   the phone has finished writing, so a file checked straight away can still
   be short. Wait a few seconds, then compare the phone's checksums with the
   backup's:

   ```bash
   adb -s $P shell run-as $NEW sha256sum databases/stepsplit.db databases/stepsplit.db-wal files/datastore/stepsplit_settings.preferences_pb
   ```

   ```bash
   (cd $B && sha256sum stepsplit.db stepsplit.db-wal stepsplit_settings.preferences_pb)
   ```

   All three must match. If one differs, wait and check again; if it still
   differs, copy that file again. Then give the files the app's own private
   mode (run-as creates them readable by everyone):

   ```bash
   adb -s $P shell run-as $NEW chmod 600 databases/stepsplit.db databases/stepsplit.db-wal files/datastore/stepsplit_settings.preferences_pb
   ```

6. **Open the new app and check it.** Both apps are called Steps GPS Route;
   the new one's Settings → Debug tools shows `bukin.stepsgpsroute.debug`.
   Trips (open one, so its route loads), History, Stats and the daily goal
   should match the old app. Grant Health Connect step access and location
   again: they belong to the package. Trips recorded in the old app after the
   copy stay there.

7. **Keep the old app** until the owner has checked the new one. Uninstalling
   it deletes its data; the backup in `$B` stays.

This procedure was rehearsed on an emulator (Android 16, userdebug image) with
a made-up trip and goal, 2026-10-21: the trip, its route and the goal arrived
in the new app, and Room opened the copy without a migration. On the owner's
phone, a user build, `run-as` behaves the same for debuggable apps, but it
hasn't been tried there.
