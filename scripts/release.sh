#!/usr/bin/env bash
# Builds, verifies and signs a Google Play release of Steps GPS Route from one committed revision.
# See docs/RELEASING.md.
#
#   scripts/release.sh build <revision>
#       Exports exactly <revision> (a commit) into a fresh temporary directory, runs every release
#       gate there, checks what the bundle and the APK contain, and keeps them, still unsigned,
#       with the R8 mapping, reports and a build record in
#       dist/<package>-<version>-vc<code>-<commit>/ (never overwritten).
#
#   scripts/release.sh sign <release-dir> <upload-keystore> <key-alias>
#       Re-checks that release directory against its build record and signs its bundle with the
#       upload key. jarsigner prompts for the passwords; none are ever passed on a command line.
#
#   scripts/release.sh test-apk <release-dir>
#       Signs a copy of that directory's release APK with this computer's debug key, for testing
#       the release build on an emulator or a test phone. Never for upload.
#
# Provenance comes from the build itself, not from timestamps or the working tree: the bundle is
# produced from the exported commit, the gates run against that same export, and sign accepts only
# a directory written by build whose files still match the hashes recorded there. There is no way
# to hand either command a bundle from anywhere else.
#
# Every check reads a file or a captured variable, never `producer | grep -q`: under pipefail an
# early-exiting grep makes the producer fail with SIGPIPE, which would let a check fail open.
set -euo pipefail

# The application ID is permanent from the first upload.
PACKAGE=bukin.stepsgpsroute
# Google Play's target API level requirement for new apps and updates (checked 2026-10-21).
MIN_TARGET_SDK=36
# A superset of the everyday gate (assembleDebug testDebugUnitTest lintDebug), plus the release
# variant's tests and lint and both release artifacts.
GATES=(clean assembleDebug testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleRelease bundleRelease)
EMBEDDED_MAPPING=BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map
# Exactly the permissions the release requests: the app's own (app/src/main/AndroidManifest.xml)
# and the ones its libraries add. A change here changes what Play Console's permission and Data
# safety declarations must say, so a new one stops the release until it's added here on purpose.
EXPECTED_PERMISSIONS=(
  android.permission.health.READ_STEPS
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_FINE_LOCATION
  android.permission.FOREGROUND_SERVICE
  android.permission.FOREGROUND_SERVICE_LOCATION
  android.permission.POST_NOTIFICATIONS
  android.permission.INTERNET
  # Added by libraries: MapLibre (network and Wi-Fi state), WorkManager (wake lock), and
  # androidx.core (its own signature permission for non-exported dynamic receivers). WorkManager's
  # RECEIVE_BOOT_COMPLETED is removed by the Mobile Ads SDK's manifest; the app schedules no work.
  android.permission.ACCESS_NETWORK_STATE
  android.permission.ACCESS_WIFI_STATE
  android.permission.WAKE_LOCK
  bukin.stepsgpsroute.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  # Added by the Google Mobile Ads SDK, with or without ads in the build: the advertising ID, and
  # Android's Privacy Sandbox ad APIs. Play Console's advertising ID declaration must say so.
  com.google.android.gms.permission.AD_ID
  android.permission.ACCESS_ADSERVICES_AD_ID
  android.permission.ACCESS_ADSERVICES_ATTRIBUTION
  android.permission.ACCESS_ADSERVICES_TOPICS
)
# Google's sample AdMob publisher: its IDs are test IDs, which a release must never carry.
ADMOB_TEST_PUBLISHER=3940256099942544
# Debug builds' Settings section and sample data live in this package; R8 must drop all of it.
DEBUG_TOOLS_PACKAGE=com.example.stepsplit.debug
# Activities that the debugImplementation libraries (Compose tooling, ui-test-manifest) declare.
DEBUG_ONLY_COMPONENTS=(androidx.compose.ui.tooling.PreviewActivity androidx.activity.ComponentActivity)
# Android 15+ devices may use 16 KB memory pages, on 64-bit ABIs only; Play requires native code
# for those ABIs to support them.
PAGE_ALIGNMENT=16384
PAGE_ALIGNMENT_ABIS=(arm64-v8a x86_64)

fail() {
  echo "error: $*" >&2
  exit 1
}
usage() {
  sed -n '5,17p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}
sha256() { sha256sum "$1" | cut -d' ' -f1; }
# The value of "key: value" in a record file (first match), or nothing.
record_value() { sed -nE "s/^$2:[[:space:]]+(.*)$/\1/p" "$1" | sed -n 1p; }

require_tools() {
  local tool
  for tool in git tar unzip zip sha256sum cmp diff readelf "$@"; do
    command -v "$tool" >/dev/null || fail "$tool not found"
  done
  build_tools=$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)
  build_tools=${build_tools%/}
  aapt2=$build_tools/aapt2
  [ -n "$build_tools" ] && [ -x "$aapt2" ] || fail "aapt2 not found under \$ANDROID_HOME/build-tools"
}

# Packages the bundle's base manifest and resources as $2/manifest/base-proto.apk, which aapt2 can
# read like an APK.
bundle_proto_apk() {
  local bundle=$1 tmp=$2
  mkdir -p "$tmp/manifest"
  rm -f "$tmp/manifest/AndroidManifest.xml" "$tmp/manifest/resources.pb" "$tmp/manifest/base-proto.apk"
  unzip -q -j -DD "$bundle" base/manifest/AndroidManifest.xml base/resources.pb -d "$tmp/manifest"
  (cd "$tmp/manifest" && zip -q -X base-proto.apk AndroidManifest.xml resources.pb)
}

# Sets package, version_code, version_name, min_sdk, target_sdk from a badging file.
read_badging() {
  local badging
  badging=$(cat "$1")
  package=$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<<"$badging")
  version_code=$(sed -nE "s/^package: .* versionCode='([0-9]+)'.*/\1/p" <<<"$badging")
  version_name=$(sed -nE "s/^package: .* versionName='([^']*)'.*/\1/p" <<<"$badging")
  min_sdk=$(sed -nE "s/^minSdkVersion:'([0-9]+)'.*/\1/p" <<<"$badging")
  target_sdk=$(sed -nE "s/^targetSdkVersion:'([0-9]+)'.*/\1/p" <<<"$badging")
  [ -n "$package" ] && [ -n "$version_code" ] && [ -n "$version_name" ] ||
    fail "could not read the package and version from $1"
}

# Fails unless the badging in $1 (of artifact $2) is a release this app may ship: the right
# package, not debuggable, a current target SDK, exactly the expected permissions, and no Vulkan
# requirement (Play would hide the app from phones without Vulkan). Sets permissions and features.
check_badging() {
  local file=$1 label=$2 badging expected
  badging=$(cat "$file")
  read_badging "$file"
  [ "$package" = "$PACKAGE" ] || fail "$label has package $package, expected $PACKAGE"
  if grep -q '^application-debuggable' <<<"$badging"; then fail "$label is debuggable"; fi
  [ -n "$target_sdk" ] && [ "$target_sdk" -ge "$MIN_TARGET_SDK" ] ||
    fail "$label targets SDK ${target_sdk:-?}; Google Play requires $MIN_TARGET_SDK or later"
  permissions=$(sed -nE "s/^uses-permission(-sdk-23)?: name='([^']+)'.*/\2/p" <<<"$badging" | sort -u)
  expected=$(printf '%s\n' "${EXPECTED_PERMISSIONS[@]}" | sort -u)
  [ "$permissions" = "$expected" ] || fail "$label requests other permissions than expected:
$(diff <(echo "$expected") <(echo "$permissions") | grep '^[<>]' | sed 's/^</  missing:/; s/^>/  new:    /')"
  features=$(sed -nE "s/^[[:space:]]*uses-feature(-not-required)?: name='([^']+)'.*/\2\1/p" <<<"$badging" | sort -u)
  if grep -qi 'vulkan' <<<"$features"; then fail "$label requires a Vulkan feature: $features"; fi
}

# Fails unless the artifact $1 carries the privacy policy at entry $2, identical to the source $3.
check_privacy_policy() {
  local artifact=$1 entry=$2 source=$3 tmp=$4 entries
  entries=$(unzip -Z1 "$artifact")
  grep -qxF "$entry" <<<"$entries" || fail "$artifact has no $entry"
  unzip -p "$artifact" "$entry" > "$tmp/privacy-policy.html"
  cmp -s "$tmp/privacy-policy.html" "$source" ||
    fail "$entry in $artifact differs from docs/privacy-policy.html of this commit"
}

# Fails if any class of the debug-tools package survived R8 in mapping $1, or the manifest of the
# APK-like file $2 declares a debug-only component.
check_no_debug_tools() {
  local mapping=$1 apk=$2 survivors names component
  survivors=$(grep -E "^${DEBUG_TOOLS_PACKAGE//./\\.}\.[^ ]+ -> " "$mapping" || true)
  [ -z "$survivors" ] || fail "debug tools are in the release build:
$survivors"
  names=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk" | sed -nE 's/.*android:name\(0x01010003\)="([^"]+)".*/\1/p')
  [ -n "$names" ] || fail "could not read the manifest of $apk"
  for component in "${DEBUG_ONLY_COMPONENTS[@]}"; do
    if grep -qxF "$component" <<<"$names"; then fail "$apk declares the debug-only $component"; fi
  done
}

# Fails unless the APK-like file $1 has ads as the build meant ($2: "on" or "off"): with ads, an
# AdMob app ID that isn't Google's test one; without, no app ID and no Mobile Ads start-up provider
# (see app/src/adsOff). Prints the app ID, or "none".
check_ads() {
  local apk=$1 expected=$2 manifest app_id
  manifest=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")
  [ -n "$manifest" ] || fail "could not read the manifest of $apk"
  app_id=$(grep -A1 'android:name(0x01010003)="com.google.android.gms.ads.APPLICATION_ID"' <<<"$manifest" |
    sed -nE 's/.*android:value\(0x01010024\)="([^"]*)".*/\1/p' | sed -n 1p || true)
  case "$expected" in
    on)
      [ -n "$app_id" ] || fail "$apk has no AdMob app ID, though admob.properties was there"
      case "$app_id" in *"$ADMOB_TEST_PUBLISHER"*) fail "$apk carries Google's test AdMob app ID" ;; esac
      echo "$app_id" ;;
    off)
      [ -z "$app_id" ] || fail "$apk has an AdMob app ID ($app_id), though there was no admob.properties"
      if grep -qF '"com.google.android.gms.ads.MobileAdsInitProvider"' <<<"$manifest"; then
        fail "$apk declares the Mobile Ads start-up provider without an app ID; it would stop the app at launch"
      fi
      echo "none" ;;
  esac
}

# Prints a summary of the bundle's native libraries; fails if one for a 64-bit ABI has a LOAD
# segment aligned to less than 16 KB.
native_libraries() {
  local bundle=$1 tmp=$2 libs lib abi align count=0 checked=0 abis segments
  libs=$(unzip -Z1 "$bundle" | grep -E '^base/lib/[^/]+/[^/]+\.so$' || true)
  if [ -z "$libs" ]; then echo "none"; return; fi
  while read -r lib; do
    count=$((count + 1))
    abi=$(sed -E 's#^base/lib/([^/]+)/.*#\1#' <<<"$lib")
    case " ${PAGE_ALIGNMENT_ABIS[*]} " in *" $abi "*) ;; *) continue ;; esac
    unzip -p "$bundle" "$lib" > "$tmp/lib.so"
    segments=$(readelf -lW "$tmp/lib.so" | awk '$1 == "LOAD" { print $NF }')
    [ -n "$segments" ] || fail "could not read the LOAD segments of $lib"
    while read -r align; do
      [ $((align)) -ge "$PAGE_ALIGNMENT" ] || fail "$lib has a LOAD segment aligned to $align, less than 16 KB"
    done <<<"$segments"
    checked=$((checked + 1))
  done <<<"$libs"
  abis=$(sed -E 's#^base/lib/([^/]+)/.*#\1#' <<<"$libs" | sort -u | paste -sd' ')
  echo "$count libraries ($abis); all $checked for ${PAGE_ALIGNMENT_ABIS[*]} are 16 KB aligned"
}

# Fails unless $1 embeds an R8 mapping identical, byte for byte, to the file $2.
check_embedded_mapping() {
  local bundle=$1 mapping=$2 tmp=$3 entries
  entries=$(unzip -Z1 "$bundle")
  grep -qxF "$EMBEDDED_MAPPING" <<<"$entries" ||
    fail "$bundle has no embedded R8 mapping ($EMBEDDED_MAPPING); this app is minified, so a release must carry one"
  unzip -p "$bundle" "$EMBEDDED_MAPPING" > "$tmp/embedded-mapping.txt"
  [ -s "$tmp/embedded-mapping.txt" ] || fail "the R8 mapping embedded in $bundle is empty"
  cmp -s "$tmp/embedded-mapping.txt" "$mapping" ||
    fail "the R8 mapping embedded in $bundle differs from $mapping; they come from different builds"
}

# "N tests, F failures, E errors, S skipped" over a directory of JUnit XML results; fails if any
# test failed or none ran.
test_summary() {
  local dir=$1 tests=0 failures=0 errors=0 skipped=0 file line n f e s
  for file in "$dir"/TEST-*.xml; do
    [ -f "$file" ] || continue
    line=$(sed -n '/<testsuite /{p;q}' "$file")
    n=$(sed -nE 's/.* tests="([0-9]+)".*/\1/p' <<<"$line"); f=$(sed -nE 's/.* failures="([0-9]+)".*/\1/p' <<<"$line")
    e=$(sed -nE 's/.* errors="([0-9]+)".*/\1/p' <<<"$line"); s=$(sed -nE 's/.* skipped="([0-9]+)".*/\1/p' <<<"$line")
    tests=$((tests + ${n:-0})); failures=$((failures + ${f:-0})); errors=$((errors + ${e:-0})); skipped=$((skipped + ${s:-0}))
  done
  [ "$tests" -gt 0 ] || fail "no unit test results in $dir"
  [ "$failures" -eq 0 ] && [ "$errors" -eq 0 ] || fail "unit tests failed in $dir: $failures failures, $errors errors"
  echo "$tests tests, $failures failures, $errors errors, $skipped skipped"
}

lint_summary() {
  local report=$1 issues errors warnings
  [ -f "$report" ] || fail "lint report missing: $report"
  issues=$(grep -o 'severity="[A-Za-z]*"' "$report" || true)
  errors=$(grep -cE '"(Error|Fatal)"' <<<"$issues" || true)
  warnings=$(grep -c '"Warning"' <<<"$issues" || true)
  [ "$errors" -eq 0 ] || fail "lint reported $errors errors ($report)"
  echo "$errors errors, $warnings warnings"
}

# Checks a release directory written by build against its record; sets record.
check_release_dir() {
  local dir=$1 file
  [ -d "$dir" ] || fail "not a directory: $dir (it must be a release directory written by '$0 build')"
  record=$dir/build-record.txt
  for file in "$record" "$dir/app-release-unsigned.aab" "$dir/app-release-unsigned.apk" "$dir/mapping.txt"; do
    [ -f "$file" ] || fail "$dir is not a release directory written by '$0 build' (no $(basename "$file"))"
  done
  [ "$(record_value "$record" status)" = "verified, unsigned" ] || fail "$record does not record a verified build"
  # The files must be exactly the ones the verified build recorded.
  [ "$(sha256 "$dir/app-release-unsigned.aab")" = "$(record_value "$record" 'unsigned bundle SHA-256')" ] ||
    fail "app-release-unsigned.aab is not the bundle this build recorded"
  [ "$(sha256 "$dir/app-release-unsigned.apk")" = "$(record_value "$record" 'unsigned APK SHA-256')" ] ||
    fail "app-release-unsigned.apk is not the APK this build recorded"
  [ "$(sha256 "$dir/mapping.txt")" = "$(record_value "$record" 'mapping.txt SHA-256')" ] ||
    fail "mapping.txt is not the mapping this build recorded"
}

cmd_build() {
  [ $# -eq 1 ] || usage
  local revision=$1 repo commit tree existing work src log status out_dir toolchain
  require_tools
  [ -n "${JAVA_HOME:-}" ] || fail "set JAVA_HOME (JDK 21)"
  repo=$(git rev-parse --show-toplevel)
  commit=$(git -C "$repo" rev-parse --verify --quiet "$revision^{commit}") || fail "not a commit: $revision"
  tree=$(git -C "$repo" rev-parse "$commit^{tree}")
  existing=$(ls -d "$repo"/dist/*-"${commit:0:12}" 2>/dev/null || true)
  [ -z "$existing" ] || fail "$existing already exists; release records are never overwritten"

  work=$(mktemp -d "${TMPDIR:-/tmp}/stepsgpsroute-release.XXXXXX")
  src=$work/src
  log=$work/build.log
  mkdir -p "$src"
  # Exactly the committed tree: nothing from the working tree, untracked files or earlier builds.
  git -C "$repo" archive --format=tar "$commit" | tar -xf - -C "$src"
  # The one exception: the real AdMob IDs, which Git ignores (see docs/RELEASING.md, "Ads"). Without
  # them the release has no ads.
  local ads=off
  if [ -f "$repo/admob.properties" ]; then
    cp "$repo/admob.properties" "$src/admob.properties"
    ads=on
  fi
  echo "Ads: $ads ($([ "$ads" = on ] && echo "IDs from admob.properties" || echo "no admob.properties"))"

  echo "Building $commit in $src"
  echo "Gates: ./gradlew --no-daemon --continue ${GATES[*]} (about 15 minutes; log: $log)"
  status=0
  (cd "$src" && ./gradlew --no-daemon --console=plain --continue "${GATES[@]}") > "$log" 2>&1 || status=$?
  if [ "$status" -ne 0 ]; then
    tail -n 30 "$log" >&2
    fail "the release gates failed (exit $status); nothing was kept or signed. Isolated checkout and log left in $work"
  fi

  local bundle=$src/app/build/outputs/bundle/release/app-release.aab
  local apk=$src/app/build/outputs/apk/release/app-release-unsigned.apk
  local mapping=$src/app/build/outputs/mapping/release/mapping.txt
  local policy=$src/docs/privacy-policy.html
  [ -f "$bundle" ] || fail "the build produced no bundle"
  [ -f "$apk" ] || fail "the build produced no release APK"
  [ -f "$mapping" ] || fail "the build produced no R8 mapping; the release build must be minified"
  check_embedded_mapping "$bundle" "$mapping" "$work"

  bundle_proto_apk "$bundle" "$work"
  "$aapt2" dump badging "$work/manifest/base-proto.apk" > "$work/badging-bundle.txt"
  "$aapt2" dump badging "$apk" > "$work/badging-apk.txt"
  local apk_package apk_version_code apk_version_name apk_permissions apk_features
  check_badging "$work/badging-apk.txt" "the release APK"
  apk_package=$package apk_version_code=$version_code apk_version_name=$version_name
  apk_permissions=$permissions apk_features=$features
  check_badging "$work/badging-bundle.txt" "the bundle"
  [ "$apk_package $apk_version_code $apk_version_name" = "$package $version_code $version_name" ] ||
    fail "the release APK ($apk_package $apk_version_code $apk_version_name) and the bundle ($package $version_code $version_name) differ"
  [ "$apk_permissions" = "$permissions" ] && [ "$apk_features" = "$features" ] ||
    fail "the release APK and the bundle request different permissions or features"
  check_privacy_policy "$bundle" base/assets/privacy-policy.html "$policy" "$work"
  check_privacy_policy "$apk" assets/privacy-policy.html "$policy" "$work"
  check_no_debug_tools "$mapping" "$work/manifest/base-proto.apk"
  check_no_debug_tools "$mapping" "$apk"
  local ads_app_id bundle_ads_app_id
  ads_app_id=$(check_ads "$apk" "$ads")
  bundle_ads_app_id=$(check_ads "$work/manifest/base-proto.apk" "$ads")
  [ "$bundle_ads_app_id" = "$ads_app_id" ] || fail "the release APK and the bundle have different AdMob app IDs"
  local libraries
  libraries=$(native_libraries "$bundle" "$work")

  local tests_debug tests_release lint_debug lint_release
  tests_debug=$(test_summary "$src/app/build/test-results/testDebugUnitTest")
  tests_release=$(test_summary "$src/app/build/test-results/testReleaseUnitTest")
  lint_debug=$(lint_summary "$src/app/build/reports/lint-results-debug.xml")
  lint_release=$(lint_summary "$src/app/build/reports/lint-results-release.xml")

  toolchain=$(cd "$src" && ./gradlew --no-daemon -q --console=plain buildEnvironment 2>/dev/null || true)
  local gradle_version agp_version kotlin_version java_version aapt2_version
  gradle_version=$(sed -nE 's#^distributionUrl=.*gradle-([0-9.]+)-(bin|all)\.zip#\1#p' "$src/gradle/wrapper/gradle-wrapper.properties")
  agp_version=$(grep -oE 'com\.android\.tools\.build:gradle:[0-9][^ ]*' <<<"$toolchain" | sed -n '1s/.*://p')
  kotlin_version=$(grep -oE 'org\.jetbrains\.kotlin:kotlin-gradle-plugin:[0-9][^ ]*' <<<"$toolchain" | sed -n '1s/.*://p')
  java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 1p)
  aapt2_version=$("$aapt2" version 2>&1 | sed -n 1p)

  out_dir=$repo/dist/$package-$version_name-vc$version_code-${commit:0:12}
  [ ! -e "$out_dir" ] || fail "$out_dir already exists; release records are never overwritten"
  mkdir -p "$out_dir"
  cp "$bundle" "$out_dir/app-release-unsigned.aab"
  cp "$apk" "$out_dir/app-release-unsigned.apk"
  cp "$work/embedded-mapping.txt" "$out_dir/mapping.txt"
  cp "$log" "$out_dir/build.log"
  cp "$work/badging-bundle.txt" "$work/badging-apk.txt" "$out_dir/"
  cp "$src/app/build/reports/lint-results-debug.xml" "$src/app/build/reports/lint-results-release.xml" "$out_dir/"
  tar -czf "$out_dir/unit-test-results.tar.gz" -C "$src/app/build/test-results" testDebugUnitTest testReleaseUnitTest
  {
    echo "status:                  verified, unsigned"
    echo "source commit:           $commit"
    echo "source tree:             $tree"
    echo "requested revision:      $revision"
    echo "checkout:                git archive of the commit into a fresh directory"
    echo "built at (UTC):          $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "gates:                   ./gradlew --no-daemon --continue ${GATES[*]}: passed"
    echo "unit tests, debug:       $tests_debug"
    echo "unit tests, release:     $tests_release"
    echo "lint, debug:             $lint_debug"
    echo "lint, release:           $lint_release"
    echo "package:                 $package"
    echo "versionCode:             $version_code"
    echo "versionName:             $version_name"
    echo "minSdk / targetSdk:      $min_sdk / $target_sdk"
    echo "debuggable:              no"
    echo "permissions:             $(paste -sd' ' <<<"$permissions")"
    echo "uses-features:           $(paste -sd' ' <<<"$features")"
    echo "Vulkan required:         no"
    echo "native libraries:        $libraries"
    echo "privacy policy asset:    identical to docs/privacy-policy.html, SHA-256 $(sha256 "$policy")"
    echo "debug tools:             none (no $DEBUG_TOOLS_PACKAGE class after R8, no ${DEBUG_ONLY_COMPONENTS[*]})"
    if [ "$ads" = on ]; then
      echo "ads:                     on, AdMob app ID $ads_app_id (admob.properties SHA-256 $(sha256 "$src/admob.properties"))"
    else
      echo "ads:                     off (no admob.properties)"
    fi
    echo "Gradle:                  $gradle_version"
    echo "Android Gradle plugin:   ${agp_version:-unknown}"
    echo "Kotlin Gradle plugin:    ${kotlin_version:-unknown}"
    echo "JDK:                     $java_version"
    echo "aapt2:                   $aapt2_version"
    echo "unsigned bundle size:    $(stat -c %s "$out_dir/app-release-unsigned.aab") bytes"
    echo "unsigned bundle SHA-256: $(sha256 "$out_dir/app-release-unsigned.aab")"
    echo "unsigned APK size:       $(stat -c %s "$out_dir/app-release-unsigned.apk") bytes"
    echo "unsigned APK SHA-256:    $(sha256 "$out_dir/app-release-unsigned.apk")"
    echo "mapping.txt SHA-256:     $(sha256 "$out_dir/mapping.txt")"
    echo "mapping source:          embedded in the bundle, identical to the build's mapping.txt"
  } > "$out_dir/build-record.txt"
  chmod a-w "$out_dir"/*
  rm -rf "$work"

  echo
  cat "$out_dir/build-record.txt"
  echo
  echo "Verified release candidate: $out_dir"
}

cmd_sign() {
  [ $# -eq 3 ] || usage
  local dir=$1 keystore=$2 alias_name=$3 repo keystore_abs
  require_tools jarsigner keytool
  repo=$(git rev-parse --show-toplevel)
  [ -d "$dir" ] || fail "not a directory: $dir (sign takes a release directory written by '$0 build')"
  dir=$(realpath "$dir")
  check_release_dir "$dir"
  [ ! -e "$dir/sign-record.txt" ] || fail "$dir is already signed; never sign a release twice"
  local commit tree
  commit=$(record_value "$record" 'source commit')
  tree=$(record_value "$record" 'source tree')
  [ "$(git -C "$repo" rev-parse --verify --quiet "$commit^{tree}" || true)" = "$tree" ] ||
    fail "source commit $commit is not in this repository, or its tree doesn't match the record"

  # Global, not local: the EXIT trap runs after this function has returned.
  work=$(mktemp -d)
  trap 'rm -rf "${work:-}"' EXIT
  local bundle=$dir/app-release-unsigned.aab
  check_embedded_mapping "$bundle" "$dir/mapping.txt" "$work"
  if grep -qE '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' <<<"$(unzip -Z1 "$bundle")"; then
    fail "$bundle is already signed"
  fi
  bundle_proto_apk "$bundle" "$work"
  "$aapt2" dump badging "$work/manifest/base-proto.apk" > "$work/badging.txt"
  check_badging "$work/badging.txt" "the bundle"

  [ -f "$keystore" ] || fail "keystore not found: $keystore"
  keystore_abs=$(realpath "$keystore")
  case "$keystore_abs/" in
    "$repo"/*) fail "the keystore is inside the repository ($keystore_abs); keep it outside, see docs/RELEASING.md" ;;
  esac

  local signed=$dir/$package-$version_name-vc$version_code-upload-signed.aab
  echo "Signing $bundle (commit $commit)"
  echo "  with alias '$alias_name' from $keystore_abs"
  echo "jarsigner will now ask for the keystore password (and the key password if different)."
  jarsigner -keystore "$keystore_abs" -sigalg SHA256withRSA -digestalg SHA-256 \
    -signedjar "$signed" "$bundle" "$alias_name"

  # Not -strict: an upload certificate is self-signed by design (Play accepts it), and -strict turns
  # that warning into a failure. What matters is that the signature verifies and covers every entry.
  local verify_output cert_info cert_sha256 cert_owner
  verify_output=$(jarsigner -verify "$signed") || fail "signature verification failed for $signed"
  grep -q '^jar verified\.' <<<"$verify_output" || fail "signature verification failed for $signed"
  if grep -q 'unsigned entries' <<<"$verify_output"; then
    fail "$signed has unsigned entries"
  fi
  check_embedded_mapping "$signed" "$dir/mapping.txt" "$work"
  cert_info=$(keytool -printcert -jarfile "$signed")
  cert_sha256=$(sed -nE 's/^[[:space:]]*SHA256: (.*)$/\1/p' <<<"$cert_info" | sed -n 1p)
  cert_owner=$(sed -nE 's/^Owner: (.*)$/\1/p' <<<"$cert_info" | sed -n 1p)
  [ -n "$cert_sha256" ] || fail "could not read the signing certificate from $signed"
  if [ "$cert_owner" = "C=US, O=Android, CN=Android Debug" ]; then
    rm -f "$signed"
    fail "that is a debug key ($cert_owner); Play refuses debug-signed uploads. Use the upload key"
  fi

  {
    echo "status:                  signed for upload"
    echo "source commit:           $commit"
    echo "signed at (UTC):         $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "key alias:               $alias_name"
    echo "upload cert owner:       $cert_owner"
    echo "upload cert SHA-256:     $cert_sha256"
    echo "unsigned bundle SHA-256: $(sha256 "$bundle")"
    echo "signed bundle SHA-256:   $(sha256 "$signed")"
    echo "signed bundle size:      $(stat -c %s "$signed") bytes"
    echo "signed bundle:           $(basename "$signed")"
    echo "mapping.txt SHA-256:     $(sha256 "$dir/mapping.txt") (embedded in the signed bundle too)"
  } > "$dir/sign-record.txt"
  chmod a-w "$signed" "$dir/sign-record.txt"

  echo
  cat "$record" "$dir/sign-record.txt"
  echo
  echo "Upload: $signed"
  echo "Copy $dir to durable storage outside the repository before uploading."
}

cmd_test_apk() {
  [ $# -eq 1 ] || usage
  local dir=$1 keystore=$HOME/.android/debug.keystore apksigner zipalign out apk cert_info cert_sha256
  require_tools
  apksigner=$build_tools/apksigner
  zipalign=$build_tools/zipalign
  [ -x "$apksigner" ] && [ -x "$zipalign" ] || fail "apksigner or zipalign not found in $build_tools"
  [ -d "$dir" ] || fail "not a directory: $dir (test-apk takes a release directory written by '$0 build')"
  dir=$(realpath "$dir")
  check_release_dir "$dir"
  read_badging "$dir/badging-apk.txt"
  [ -f "$keystore" ] || fail "no debug keystore at $keystore (any debug build creates it)"
  out=$dir/device-test
  [ ! -e "$out" ] || fail "$out already exists"
  mkdir "$out"
  apk=$out/$package-$version_name-vc$version_code-DEBUG-KEY-not-for-upload.apk

  # The Android SDK's debug keystore has the well-known password "android"; it's no secret. apksigner,
  # not jarsigner: targetSdk 30+ apps need APK Signature Scheme v2 or later to install.
  "$apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey --ks-pass stdin \
    --in "$dir/app-release-unsigned.apk" --out "$apk" <<<"android"
  rm -f "$apk.idsig"
  cert_info=$("$apksigner" verify --print-certs "$apk") || fail "signature verification failed for $apk"
  grep -q 'CN=Android Debug' <<<"$cert_info" || fail "$apk is not signed with the debug key"
  cert_sha256=$(sed -nE 's/^.*Signer.* certificate SHA-256 digest: ([0-9a-f]+)$/\1/p' <<<"$cert_info" | sed -n 1p)
  [ -n "$cert_sha256" ] || fail "could not read the signing certificate of $apk"
  "$zipalign" -c -P 16 4 "$apk" > /dev/null || fail "$apk is not zip-aligned for 16 KB pages"
  {
    echo "status:                  signed with this computer's debug key, for device tests only"
    echo "source commit:           $(record_value "$record" 'source commit')"
    echo "from:                    app-release-unsigned.apk, SHA-256 $(sha256 "$dir/app-release-unsigned.apk")"
    echo "signed at (UTC):         $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "debug cert SHA-256:      $cert_sha256"
    echo "APK SHA-256:             $(sha256 "$apk")"
  } > "$out/test-apk-record.txt"
  chmod a-w "$apk" "$out/test-apk-record.txt"

  cat "$out/test-apk-record.txt"
  echo
  echo "Test APK: $apk"
  echo "Never upload it. A phone that has it must uninstall it before installing $package from Play."
}

[ $# -ge 1 ] || usage
command=$1
shift
case "$command" in
  build) cmd_build "$@" ;;
  sign) cmd_sign "$@" ;;
  test-apk) cmd_test_apk "$@" ;;
  *) usage ;;
esac
