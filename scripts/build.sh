#!/usr/bin/env bash
# One line to build.
#   bash scripts/build.sh            # release APK - the everyday one
#   bash scripts/build.sh aab        # store bundle, plus a check of which key signed it
#   bash scripts/build.sh debug      # debug APK, only when attaching a debugger
#   bash scripts/build.sh test       # unit tests only, no device needed
#   bash scripts/build.sh install    # release build, then push it to a device and launch it
#
# 🔑 **Always go through this script rather than calling `./gradlew` directly.** The wrapper needs
#    JAVA_HOME, and a host where the JDK lives inside the Android toolchain (no system `java`) has
#    none - `./gradlew` then dies with "JAVA_HOME is not set". This script resolves it through
#    scripts/_hostenv.sh first. Anything after the mode is passed straight to gradle.
#
# 🔴 **Release is the everyday build.** A debug build is `debuggable`, so ART gives up
#    optimisations. This app runs two audio threads that must not be late; do not measure on debug.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"; APP="$ROOT"          # 🔑 this script's parent is the Gradle root

# 🔑 Toolchain paths live in exactly one place, scripts/_hostenv.sh.
. "$ROOT/scripts/_hostenv.sh"; hostenv_resolve "$APP"
[ -n "$JDK_HOME" ] || { echo "🔴 no JDK 17 - run: bash scripts/doctor.sh"; exit 1; }
[ -n "$SDK_ROOT" ] || { echo "🔴 no Android SDK - run: bash scripts/doctor.sh"; exit 1; }
export JAVA_HOME="$JDK_HOME"
SDK="$SDK_ROOT"; export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"

cd "$APP" || exit 1
G=./gradlew
PKG=io.github.zirize.screamdroid
MODE="${1:-release}"
case "$MODE" in
  test)  shift 2>/dev/null; exec "$G" :app:testDebugUnitTest "$@" ;;
  debug) TASK=:app:assembleDebug;   OUT=app/build/outputs/apk/debug/app-debug.apk ;;
  release|install) TASK=:app:assembleRelease; OUT=app/build/outputs/apk/release/app-release.apk ;;
  aab)   TASK=:app:bundleRelease;    OUT=app/build/outputs/bundle/release/app-release.aab ;;
  *) echo "usage: bash scripts/build.sh [release|debug|aab|test|install]"; exit 2 ;;
esac

shift 2>/dev/null || true
echo "▸ $TASK ${*:+$*}"
# 🔴 A pipeline's exit code is the *last* command's, so a failing gradle behind `tail` still gives
#    0 and a stale APK left by an earlier run then reads as success. Gradle's own code is read
#    separately through PIPESTATUS. Presence of an artefact is not evidence on its own.
"$G" $TASK -q "$@" 2>&1 | tail -5
GRADLE_RC=${PIPESTATUS[0]}
[ "$GRADLE_RC" = 0 ] || { echo "🔴 build failed (gradle exit $GRADLE_RC) - any artefact below is stale."; exit 1; }
[ -f "$OUT" ] || { echo "🔴 no artefact: $OUT"; exit 1; }
[ -z "$(find "$OUT" -newermt '-5 minutes' 2>/dev/null)" ] && \
  echo "  ⚠️  $OUT was not created just now - it may be stale."
echo "✅ $OUT  ($(du -h "$OUT" | cut -f1))"

# 🔴 A store bundle is worth nothing if it went out under the debug key, and that is invisible
#    from the file itself. The check is free and a certificate needs no password, so it is not
#    optional - see scripts/verify-signing.sh.
[ "$MODE" = "aab" ] && bash "$ROOT/scripts/verify-signing.sh" "$APP/$OUT"

if [ "$MODE" = "install" ]; then
  ADB="$SDK/platform-tools/adb"
  # 🔴 **A failed install looks exactly like a good one here.** `adb install` prints its error
  #    and the pipe below hides its exit code, so the line at the end of this block was printed
  #    over a package that had not changed - a signature mismatch against what is already on the
  #    device reads as success. The code is read on its own, and the text has to say Success.
  INSTALL_OUT=$("$ADB" install -r "$OUT" 2>&1); INSTALL_RC=$?
  echo "$INSTALL_OUT" | tail -1
  { [ "$INSTALL_RC" = 0 ] && echo "$INSTALL_OUT" | grep -q "Success"; } || {
    echo "🔴 install failed - the app on the device is unchanged."; exit 1; }
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "✅ installed and launched"
fi
