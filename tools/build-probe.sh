#!/usr/bin/env bash
#
# Builds the privilege probe and signs it with the AOSP platform test key.
#
# The probe is read-only (see probe/src/main/java/.../ProbeActivity.kt). Signing it with
# the platform key is what lets us find out whether this firmware actually grants
# signature-level permissions to a matching certificate — the head unit's framework-res is
# signed with this same public test key (fingerprint C8:A2:E9:...:2A:B8).
#
# This does NOT flash anything. It produces an APK you install with `adb install`.
#
# Usage:
#   tools/build-probe.sh                 # build + sign -> artifacts/priv-probe.apk
#   tools/build-probe.sh --install SER   # also `adb -s SER install -r`
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY_DIR="$ROOT/tools/platform-key"
OUT="$ROOT/artifacts/priv-probe.apk"
GRADLE_DIR="$ROOT/wits-companion"

PK8="$KEY_DIR/platform.pk8"
PEM="$KEY_DIR/platform.x509.pem"

if [[ ! -f "$PK8" || ! -f "$PEM" ]]; then
  echo "!! platform key missing under $KEY_DIR" >&2
  echo "   fetch it from AOSP (public test key):" >&2
  echo "   platform/build/target/product/security/{platform.pk8,platform.x509.pem}" >&2
  exit 1
fi

APKSIGNER="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" -name apksigner 2>/dev/null | sort -V | tail -1)"
[[ -n "$APKSIGNER" ]] || { echo "!! apksigner not found in build-tools" >&2; exit 1; }

echo "== building probe (unsigned) =="
( cd "$GRADLE_DIR" && ./gradlew :probe:assembleRelease -q )

UNSIGNED="$GRADLE_DIR/probe/build/outputs/apk/release/probe-release-unsigned.apk"
[[ -f "$UNSIGNED" ]] || { echo "!! unsigned APK not found at $UNSIGNED" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"

echo "== signing with the AOSP platform key =="
"$APKSIGNER" sign \
  --key "$PK8" --cert "$PEM" \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$UNSIGNED"

echo "== verify =="
"$APKSIGNER" verify --print-certs "$OUT" | grep -iE "signer|SHA-256" | head -4
echo "-> $OUT"

if [[ "${1:-}" == "--install" ]]; then
  SER="${2:?usage: --install <adb-serial>}"
  echo "== installing on $SER =="
  # Uninstall first: switching signing identity blocks an in-place update.
  adb -s "$SER" uninstall io.github.miklergm.privprobe >/dev/null 2>&1 || true
  adb -s "$SER" install -r "$OUT"
  echo "launch: adb -s $SER shell am start -n io.github.miklergm.privprobe/.ProbeActivity"
fi
