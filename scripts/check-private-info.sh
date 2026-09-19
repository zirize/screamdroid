#!/usr/bin/env bash
# Checks the tree that will be published for private information, and for leftover Korean.
#
# 🔑 **It looks only at what is tracked inside this directory** (an allowlist). Anything outside it
#    was never going to be published, so it cannot leak - which is why this script stays short
#    instead of growing a list of things to ignore.
#
#   bash scripts/check-private-info.sh          # exits 1 if it finds anything
#   bash scripts/check-private-info.sh --files  # lists the files that will be published
#
# 🔴 **Only tracked files are judged.** local.properties and keystore.properties are gitignored, so
#    they are not published even though they sit here - but that is only true if the final assembly
#    uses `git ls-files`. 🚫 Copying the directory with `rsync -a` takes them along.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
HERE="${PWD##*/}"

public_files() {
  git ls-files . | grep -v 'scripts/check-private-info.sh'
}

if [ "${1:-}" = "--files" ]; then
  public_files | sed "s|^|$HERE/|"
  echo; echo "$(public_files | wc -l) files"
  exit 0
fi

# name | regex | why it is blocked
PATTERNS=(
  "private IP|192\.168\.[0-9]+\.[0-9]+|somebody's LAN address. For an example, use RFC 5737's 192.0.2.0/24"
  "private git|ssh://git|a private server address"
  "device model|SM.A908N|the maintainer's actual phone"
  "device serial|RFCM801|a real device serial number"
  "home path|/home/[a-z]+|a path containing a real account name"
  "host nickname|(^|[^a-zA-Z])(hp|bill|spiral)([^a-zA-Z]|\$)|nicknames for the maintainer's machines"
  "private plan|PLAN\.md|the planning document lives in the private repository, not this one"
  "signing key path|keys-doldam|where the upload key sits on the maintainer's machine"
  "key store path|importants/android|the directory the signing keys live in, on the maintainer's machine"
)

total=0
mapfile -t FILES < <(public_files)
[ "${#FILES[@]}" -eq 0 ] && { echo "🔴 nothing would be published - check the path"; exit 1; }

for p in "${PATTERNS[@]}"; do
  name="${p%%|*}"; rest="${p#*|}"; re="${rest%%|*}"; why="${rest#*|}"
  hits=$(grep -nIE "$re" "${FILES[@]}" 2>/dev/null)
  n=$(printf '%s' "$hits" | grep -c . || true)
  if [ "$n" -gt 0 ]; then
    printf '\n🔴 %s - %d place(s)\n   why: %s\n' "$name" "$n" "$why"
    printf '%s\n' "$hits" | sed 's/^/     /'
    total=$((total + n))
  fi
done

# ── Korean outside res/values-ko/ ─────────────────────────────────────────────
# 🔑 Source and documentation here are English only. res/values-ko/ is the one place Korean
#    belongs: it is the translation that keeps the app in Korean for the people who use it.
kr=$(printf '%s\n' "${FILES[@]}" \
  | grep -v 'res/values-ko/' \
  | xargs grep -nIP '[가-힣]' 2>/dev/null)
kn=$(printf '%s' "$kr" | grep -c . || true)
if [ "$kn" -gt 0 ]; then
  printf '\n🔴 Korean text - %d line(s)\n   why: this tree is English-only outside res/values-ko/\n' "$kn"
  printf '%s\n' "$kr" | sed 's/^/     /'
  total=$((total + kn))
fi

echo
if [ "$total" -eq 0 ]; then
  echo "✅ clean - $(public_files | wc -l) files"
  exit 0
fi
echo "🔴 $total problem(s) remain."
exit 1
