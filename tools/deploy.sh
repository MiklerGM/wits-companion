#!/usr/bin/env bash
#
# Build and install the wits-companion — one command instead of hunting for the gradle
# invocation and the keystore path every time.
#
#   tools/deploy.sh platform [serial]   # platform-signed → head unit (privileged: resizeTask,
#                                       #   MANAGE_ACTIVITY_TASKS, WRITE_SECURE_SETTINGS, …)
#   tools/deploy.sh debug    [serial]   # debug → emulator (unprivileged, freeform-launch path)
#   tools/deploy.sh build    <variant>  # build only, no install (variant = platform|debug)
#   tools/deploy.sh                     # defaults to: platform
#
# Device selection when no serial is given:
#   platform → the first NON-emulator device (the head unit)
#   debug    → the first emulator
#
# The platform and debug builds have different signatures and cannot update each other in
# place; on a signature/downgrade clash this uninstalls and reinstalls. (App data is lost, but
# the platform build re-grants notification access itself, so media comes back on its own.)
#
# Platform keystore: WITS_PLATFORM_KEYSTORE, else tools/platform-key/platform.keystore.p12.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
GRADLE="$ROOT/wits-companion/gradlew"
KEYSTORE="${WITS_PLATFORM_KEYSTORE:-$ROOT/tools/platform-key/platform.keystore.p12}"
PKG="io.github.miklergm.witscompanion"

# `build <variant>` = build only.
INSTALL=1
if [ "${1:-}" = "build" ]; then INSTALL=0; shift; fi

VARIANT="${1:-platform}"
SERIAL="${2:-}"

build() {
  case "$VARIANT" in
    platform)
      [ -f "$KEYSTORE" ] || { echo "!! platform keystore not found: $KEYSTORE"; exit 1; }
      (cd "$ROOT/wits-companion" && "$GRADLE" :app:assemblePlatform -PplatformKeystore="$KEYSTORE")
      APK="$ROOT/wits-companion/app/build/outputs/apk/platform/app-platform.apk" ;;
    debug)
      (cd "$ROOT/wits-companion" && "$GRADLE" :app:assembleDebug)
      APK="$ROOT/wits-companion/app/build/outputs/apk/debug/app-debug.apk" ;;
    *) echo "usage: $0 [build] [platform|debug] [serial]"; exit 1 ;;
  esac
  echo "== built $VARIANT: $APK =="
}

pick_device() {
  local list; list=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
  if [ "$VARIANT" = debug ]; then
    SERIAL=$(echo "$list" | grep '^emulator-' | head -1 || true)
  else
    SERIAL=$(echo "$list" | grep -v '^emulator-' | head -1 || true)
  fi
  [ -n "$SERIAL" ] || { echo "!! no matching device for '$VARIANT'; pass a serial."; "$ADB" devices; exit 1; }
}

install() {
  [ -n "$SERIAL" ] || pick_device
  # Fail fast when the serial is not actually connected. A typo'd IP (or a unit that
  # dropped off wifi) otherwise sails all the way through to "done" while nothing was
  # installed, and the next test silently runs the OLD build — this cost several
  # on-car test cycles before it was spotted.
  if ! "$ADB" devices | awk 'NR>1 && $2=="device"{print $1}' | grep -qx "$SERIAL"; then
    echo "!! device '$SERIAL' is not connected. Connected devices:"
    "$ADB" devices
    exit 1
  fi
  echo "== installing $VARIANT → $SERIAL =="
  local out
  out=$("$ADB" -s "$SERIAL" install -r -d "$APK" 2>&1) || true
  echo "$out"
  if ! echo "$out" | grep -q Success; then
    # Uninstall ONLY for the failures a reinstall actually fixes. This wipes the app's data
    # (layouts, presets, recorded sessions), so a disk-full, transport or corrupt-APK failure
    # must not trigger it — those used to take out the working install and its data as well.
    if echo "$out" | grep -qE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES|INSTALL_FAILED_VERSION_DOWNGRADE|INSTALL_FAILED_SHARED_USER_INCOMPATIBLE'; then
      echo "== signature/downgrade clash — reinstalling clean (THIS ERASES APP DATA) =="
      "$ADB" -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
      out=$("$ADB" -s "$SERIAL" install "$APK" 2>&1) || true
      echo "$out"
    else
      echo "!! install failed for a reason an uninstall would not fix — leaving the existing"
      echo "!! installation and its data alone. Fix the cause and retry:"
      echo "$out" | sed 's/^/!!   /'
    fi
  fi
  # Never report success we did not get: "done" must mean the APK is on the device.
  echo "$out" | grep -q Success || { echo "!! install FAILED on $SERIAL — the device still runs the previous build"; exit 1; }
  echo "== done: $VARIANT on $SERIAL =="
}

build
[ "$INSTALL" = 1 ] && install || true
