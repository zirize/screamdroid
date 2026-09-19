#!/usr/bin/env bash
# Answers "can this host build the project" before you try.
# 🔑 It changes nothing. It only names what is missing.
#    Run it first on a new host - cheaper than decoding a build error.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"; APP="$ROOT"
ok(){ printf "  ✅ %s\n" "$*"; }
no(){ printf "  🔴 %s\n" "$*"; FAIL=1; }
warn(){ printf "  ⚠️  %s\n" "$*"; }
hd(){ printf "\n\033[1m%s\033[0m\n" "$*"; }
FAIL=0

# 🔑 Where the toolchain lives is scripts/_hostenv.sh's business; this only reports.
. "$ROOT/scripts/_hostenv.sh"; hostenv_resolve "$APP"

hd "1. JDK 17"
JH="$JDK_HOME"
if [ -n "$JH" ] && [ -x "$JH/bin/javac" ]; then
  ok "$("$JH/bin/javac" -version 2>&1) ($JH)"
  case "$("$JH/bin/javac" -version 2>&1)" in *" 17."*) ;; *) warn "not 17 - the Gradle config requires 17";; esac
else no "JDK 17 not found - add this host's path to JDK_CANDIDATES in scripts/_hostenv.sh"; fi

hd "2. Android SDK"
SDK="$SDK_ROOT"
if [ -n "$SDK" ] && [ -d "$SDK/platforms" ]; then
  ok "SDK: $SDK"
  [ -d "$SDK/platforms/android-36" ] && ok "platform android-36" || no "platform android-36 missing (compileSdk=36)"
  [ -x "$SDK/platform-tools/adb" ] && ok "adb" || warn "no adb - it will build, but nothing can be checked on a device"
  BT=$(ls -1 "$SDK"/build-tools 2>/dev/null | sort -V | tail -1)
  [ -n "$BT" ] && ok "build-tools $BT" || no "no build-tools"
else no "Android SDK not found (ANDROID_SDK_ROOT, sdk.dir in local.properties, or SDK_CANDIDATES in _hostenv.sh)"; fi

# 🔑 There is no NDK section on purpose: the receiver uses AudioTrack, so there is no native code
#    and no CMake. If Oboe/AAudio is ever adopted, this is where the check goes.

hd "3. Signing"
if [ -f "$APP/keystore.properties" ]; then
  ok "keystore.properties present - releases are signed with that key"
  KS=$(sed -n 's/^storeFile=//p' "$APP/keystore.properties" | head -1)
  [ -f "$KS" ] && ok "keystore present: $KS" \
    || no "keystore.properties points at a key that is not there: $KS"
else
  warn "no keystore.properties - releases are signed with the debug key (fine for installing on a device)"
fi

hd "4. A device to install on"
if [ -n "${SDK:-}" ] && [ -x "$SDK/platform-tools/adb" ]; then
  N=$("$SDK/platform-tools/adb" devices | grep -cw device || true)
  [ "$N" -gt 0 ] && ok "$N device(s) connected" || warn "no device - build and unit tests still work"
fi

hd "Verdict"
[ "$FAIL" = 0 ] && echo "  ✅ ready to build - bash scripts/build.sh" \
                || echo "  🔴 fix the items marked 🔴 above first."
exit $FAIL
