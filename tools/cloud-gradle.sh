#!/usr/bin/env bash
# Build and test locally in a cloud session (or any Linux box without Android Studio).
#
#   tools/cloud-gradle.sh :app:testDebugUnitTest
#   tools/cloud-gradle.sh :app:testDebugUnitTest --tests '*LocalSpellingTest*'
#   tools/cloud-gradle.sh lintDebug
#
# First run: installs the Android SDK pieces this project compiles against
# (about 470 MB, outside the repository) and points local.properties at them.
# Every run: passes its arguments to ./gradlew, and reruns it while the failure
# is a download that was rate-limited. Maven Central answers HTTP 429 to the
# shared addresses cloud sessions use; Gradle keeps what it already fetched, so
# each rerun gets further. A real compile or test failure is returned at once.
#
# See docs/agent-environment.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"

# Command-line tools 19.0, pinned by checksum.
TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
TOOLS_SHA256="7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"
# Keep in step with compileSdk in the module build files and with the build
# tools the Android Gradle Plugin version in gradle/libs.versions.toml expects.
PACKAGES=("platform-tools" "platforms;android-36.1" "build-tools;36.0.0")

installed() {
  [ -d "$SDK/platform-tools" ] && [ -d "$SDK/platforms/android-36.1" ] && [ -d "$SDK/build-tools/36.0.0" ]
}

install_sdk() {
  local sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then
    echo "cloud-gradle: installing Android command-line tools into $SDK"
    local tmp
    tmp="$(mktemp -d)"
    curl -fsSL --retry 4 --retry-all-errors -o "$tmp/tools.zip" "$TOOLS_URL"
    echo "$TOOLS_SHA256  $tmp/tools.zip" | sha256sum -c - >/dev/null
    mkdir -p "$SDK/cmdline-tools"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  echo "cloud-gradle: installing ${PACKAGES[*]}"
  # sdkmanager closes its input once the licences are accepted, which ends `yes`.
  (yes || true) | "$sdkmanager" --licenses >/dev/null
  "$sdkmanager" "${PACKAGES[@]}" >/dev/null
}

command -v java >/dev/null || { echo "cloud-gradle: a JDK (17 or newer) is required" >&2; exit 2; }
installed || install_sdk
installed || { echo "cloud-gradle: the Android SDK install is incomplete in $SDK" >&2; exit 2; }

# local.properties is git-ignored; it lets a plain ./gradlew find the SDK too.
if ! grep -qs "^sdk.dir=" "$ROOT/local.properties"; then
  echo "sdk.dir=$SDK" >> "$ROOT/local.properties"
fi

[ "$#" -gt 0 ] || { echo "cloud-gradle: SDK ready in $SDK. Pass Gradle tasks to run them."; exit 0; }

log="$(mktemp)"
trap 'rm -f "$log" "$log.missing" "$log.previous"' EXIT
max="${CLOUD_GRADLE_ATTEMPTS:-20}"
refresh=()
for attempt in $(seq 1 "$max"); do
  status=0
  (cd "$ROOT" && ./gradlew --console=plain "${refresh[@]}" "$@") 2>&1 | tee "$log" || status=$?
  [ "$status" -eq 0 ] && exit 0
  # Only a failed download is worth another go. Robolectric fetches its Android
  # runtime jars itself, on first use, and reports a 429 as a failed fetch.
  if ! grep -qE "status code 429|Too Many Requests|Could not (find|resolve|download|GET) |Failed to fetch maven artifact|was not found in any of the following sources" "$log"; then
    exit "$status"
  fi
  [ "$attempt" -lt "$max" ] || break
  # When throttled, Central also answers 404 for files that exist, and Gradle
  # remembers a 404 as "missing" for a day. If this attempt is stuck on exactly
  # the files the last one was, make the next one ask again.
  grep -E "Could not find .*\.(jar|pom|module|aar)|was not found in any" "$log" | sort -u > "$log.missing" || true
  refresh=()
  if [ -s "$log.missing" ] && cmp -s "$log.missing" "$log.previous"; then
    refresh=(--refresh-dependencies)
  fi
  mv "$log.missing" "$log.previous"
  echo "cloud-gradle: downloads were rate-limited (attempt $attempt of $max); retrying in 20 s"
  sleep 20
done
echo "cloud-gradle: still rate-limited after $max attempts; try again in a few minutes" >&2
exit "$status"
