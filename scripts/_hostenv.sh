#!/usr/bin/env bash
# 🔑 Everything host-specific lives here and nowhere else - build.sh, doctor.sh and
#    verify-signing.sh all read it.
#    Moving to a new host means **adding one line** to the candidate lists below. The bodies of the
#    scripts stay untouched. Hosts with a system JDK and hosts that need the toolchain's own JDK
#    can coexist.
#
# usage:  . "$(dirname "$0")/_hostenv.sh";  hostenv_resolve "$APP_DIR"
# result: $JDK_HOME and $SDK_ROOT, each empty if not found
#         🔑 Nothing is exported: the caller decides what to do (doctor only reports).

JDK_CANDIDATES=(
  /mnt/sdb1/android_tools/jdk            # a host with no system JDK - use the toolchain's own
  /usr/lib/jvm/java-17-openjdk-amd64
  /usr/lib/jvm/java-17-openjdk
)
SDK_CANDIDATES=(
  /mnt/sdb1/android_tools                # where the SDK root is this folder itself
  /mnt/android/sdk
  "$HOME/Android/Sdk"
)

hostenv_resolve() {
  local app="${1:-}" c
  JDK_HOME=""; SDK_ROOT=""

  # First match wins: 1. environment variable, 2. local.properties, 3. the candidate lists.
  [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/javac" ] && JDK_HOME="$JAVA_HOME"
  if [ -z "$JDK_HOME" ]; then
    for c in "${JDK_CANDIDATES[@]}"; do [ -x "$c/bin/javac" ] && { JDK_HOME="$c"; break; }; done
  fi

  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [ -z "$SDK_ROOT" ] && [ -n "$app" ] && [ -f "$app/local.properties" ]; then
    SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$app/local.properties" | head -1)
  fi
  if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT/platforms" ]; then
    for c in "${SDK_CANDIDATES[@]}"; do [ -d "$c/platforms" ] && { SDK_ROOT="$c"; break; }; done
  fi
  [ -d "${SDK_ROOT:-/nonexistent}/platforms" ] || SDK_ROOT=""
  return 0
}
