#!/usr/bin/env bash
# Reports which key signed an artefact. 🔑 No password needed - a certificate is public.
#   bash scripts/verify-signing.sh <.aab|.apk>
# 🔴 Always run this before uploading: a debug-signed artefact will be rejected.
#    It is deliberately self-contained so it works on any host.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
APP="$PWD"
. "$PWD/scripts/_hostenv.sh"; hostenv_resolve "$APP"   # 🔑 the one place toolchain paths live
JH="$JDK_HOME"
[ -n "$JH" ] || { echo "🔴 no JDK found - run: bash scripts/doctor.sh"; exit 1; }
# 🔴 `apksigner` is a wrapper shell script and looks for `java` on PATH. On a host with no system
#    JDK it dies with "exec: java: not found", and this script would **misdiagnose** that as
#    "could not read the signature". ⇒ Put the JDK on PATH first.
export JAVA_HOME="$JH"; export PATH="$JH/bin:$PATH"
ART="${1:-}"
[ -f "$ART" ] || { echo "usage: bash $0 <.aab|.apk>"; exit 2; }

KS=$(sed -n 's/^storeFile=//p' "$APP/keystore.properties" 2>/dev/null | head -1)
ALIAS=$(sed -n 's/^keyAlias=//p' "$APP/keystore.properties" 2>/dev/null | head -1); ALIAS="${ALIAS:-upload}"
HEX='([0-9A-Fa-f]{2}:){19,}[0-9A-Fa-f]{2}'   # 🚫 never sed 's/.*: *//' - it eats the colons inside the fingerprint

REF=""
# 🔑 A certificate can be read out of a JKS without the password.
[ -n "${KS:-}" ] && [ -f "$KS" ] && REF=$(printf '\n' | "$JH/bin/keytool" -exportcert -rfc -alias "$ALIAS" \
  -keystore "$KS" 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null | grep -oE "$HEX" | head -1)

GOT=$(printf '\n' | "$JH/bin/keytool" -printcert -jarfile "$ART" 2>/dev/null \
      | grep -iE "SHA-?256:" | head -1 | grep -oE "$HEX" | head -1)
if [ -z "${GOT:-}" ]; then   # v2/v3-only signatures (APK) are not readable above
  SDK="$SDK_ROOT"
  AS=$(ls -1 "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
  if [ -n "${AS:-}" ]; then
    ASOUT=$("$AS" verify --print-certs "$ART" 2>&1); ASRC=$?
    GOT=$(printf '%s' "$ASOUT" | grep -oE '[0-9a-fA-F]{64}' | head -1 | sed 's/../&:/g; s/:$//' | tr 'a-f' 'A-F')
    # 🔑 "could not run it" and "ran it, found no signature" are different. Conflating them misdiagnoses.
    [ -z "$GOT" ] && [ "$ASRC" != 0 ] && { echo "  🔴 could not run apksigner:"; printf '     %s\n' "$ASOUT" | head -3; exit 1; }
  else
    echo "  ⚠️  apksigner not found (build-tools) - SDK: ${SDK:-none}"
  fi
fi

echo "  artefact : $ART"
echo "  expected : ${REF:-none - keystore.properties is not wired up}"
echo "  actual   : ${GOT:-could not read a signature}"
if [ -z "${GOT:-}" ]; then echo "  🔴 unsigned, or in a format this cannot read."; exit 1
elif [ -z "${REF:-}" ]; then echo "  ⚠️  no expected key to compare against - most likely the debug key."; exit 1
elif [ "$REF" = "$GOT" ]; then echo "  ✅ match - signed with the upload key. Safe to publish."; exit 0
else echo "  🔴 mismatch - signed with a different key, almost certainly the debug key."; exit 1; fi
