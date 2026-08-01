#!/usr/bin/env bash
#
# Brings up an emulator where the companion's window tiling actually works, for UI
# iteration off the vehicle.
#
# Why this is needed: a stock "Google Play" system image is a locked user build, so freeform
# windowing and the hidden ActivityOptions.setLaunchWindowingMode are unavailable. A
# **google_apis** image is a userdebug build where both can be enabled. (The head unit gets
# tiling a different way — the platform signature grants MANAGE_ACTIVITY_TASKS and
# resizeTask — but Google's emulator images are signed with Google's key, not the public
# AOSP test key, so that path does not work on an emulator. The freeform-launch path does.)
#
# Prerequisites: the AVD "HeadUnitGA" (google_apis, 2400x900) — created by the setup agent;
# see ~/.android/avd/HeadUnitGA.avd. And a debug APK built from wits-companion.
#
# Usage: tools/setup-tiling-emulator.sh [serial]   (default serial: emulator-5556)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
SERIAL="${1:-emulator-5556}"
A="$ADB -s $SERIAL"
APK="$ROOT/wits-companion/app/build/outputs/apk/debug/app-debug.apk"

echo "== waiting for $SERIAL =="
$A wait-for-device
until [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done

echo "== enabling freeform + relaxing the hidden-API blocklist =="
# Freeform windowing is read at boot, so this needs a reboot to take effect the first time.
$A shell settings put global enable_freeform_support 1
$A shell settings put global force_resizable_activities 1
# Lets the app's reflective ActivityOptions.setLaunchWindowingMode resolve. Without this the
# tiles launch fullscreen instead of freeform. (1 = disable enforcement.)
$A shell settings put global hidden_api_policy 1

echo "== installing the DEBUG build (unprivileged; tiles via the freeform-launch path) =="
# Must be debug, not platform: the platform key does not match this image's framework key,
# so the privileged resizeTask path is unavailable here; the debug build's launchPackage
# with setLaunchBounds + setLaunchWindowingMode is what tiles.
[ -f "$APK" ] || { echo "!! build it first: (cd wits-companion && ./gradlew :app:assembleDebug)"; exit 1; }
$A uninstall io.github.miklergm.witscompanion >/dev/null 2>&1 || true
$A install "$APK"

echo
echo "If this is the first run after boot, reboot once for freeform to take hold:"
echo "  $ADB -s $SERIAL reboot   # then re-run this script"
echo
echo "Then open the app, tap a side-by-side layout on Home, and it tiles."
echo "Note: freeform windows show an AOSP caption bar (min/max/close) that the head unit hides."
