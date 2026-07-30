#!/usr/bin/env bash
# Shared helpers for the Wits research scripts.
# Sourced, not executed.

set -uo pipefail

RESEARCH_DIR="${RESEARCH_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/research}"
TS="$(date +%Y%m%d-%H%M%S)"

c_red()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }
c_cyn()  { printf '\033[36m%s\033[0m\n' "$*"; }
hdr()    { printf '\n\033[1m===== %s =====\033[0m\n' "$*"; }

die() { c_red "ERROR: $*" >&2; exit 1; }

# --- adb plumbing ------------------------------------------------------------

ADB="${ADB:-adb}"
ADB_SERIAL="${ADB_SERIAL:-}"

adb_() {
  if [ -n "$ADB_SERIAL" ]; then "$ADB" -s "$ADB_SERIAL" "$@"; else "$ADB" "$@"; fi
}

# Run a shell command on the device. Read-only by convention.
ash() { adb_ shell "$@" 2>/dev/null; }

require_adb() {
  command -v "$ADB" >/dev/null 2>&1 || die "adb not found in PATH"
  local n
  n="$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
  if [ "$n" -eq 0 ]; then
    die "no adb device in state 'device'. Connect the head unit and enable USB debugging."
  fi
  if [ "$n" -gt 1 ] && [ -z "$ADB_SERIAL" ]; then
    "$ADB" devices | awk 'NR>1 && $2=="device"'
    die "multiple devices attached; set ADB_SERIAL=<serial>"
  fi
  c_grn "adb OK ($n device)"
}

device_tag() {
  local m
  m="$(ash getprop ro.build.display.id | tr -d '\r')"
  [ -n "$m" ] || m="unknown"
  echo "$m"
}

mkout() {
  mkdir -p "$RESEARCH_DIR"
  echo "$RESEARCH_DIR"
}

# --- redaction ---------------------------------------------------------------
# Applied to every captured stream. Firmware constants stay; identifiers do not.
redact() {
  sed -E \
    -e 's/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/<MAC-REDACTED>/g' \
    -e 's/\b[A-HJ-NPR-Z0-9]{17}\b/<VIN-REDACTED>/g' \
    -e 's/(ro\.(boot\.)?serialno|ro\.serialno|persist\.sys\.serial[a-z._]*)=.*/\1=<REDACTED>/Ig' \
    -e 's/(gsm\.|ril\.)([a-z._]*)(imei|imsi|iccid|meid|subscriber)([a-z._]*)=.*/\1\2\3\4=<REDACTED>/Ig' \
    -e 's/(ssid|psk|passphrase|password|passwd|pre-shared-key|wifi_ap_[a-z_]*)([=:"[:space:]]+).*/\1\2<REDACTED>/Ig' \
    -e 's/(bluetooth[a-z._]*(name|address|addr)|bt_name|bt_passwd|bt_address)([=:"[:space:]]+).*/\1\3<REDACTED>/Ig' \
    -e 's/(persist\.[a-z.]*(btmac|BTmac|mac))=.*/\1=<REDACTED>/Ig'
}

banner_readonly() {
  c_cyn "This script is READ-ONLY: it does not send broadcasts, change settings,"
  c_cyn "switch source, touch the MCU, or write any partition."
}
