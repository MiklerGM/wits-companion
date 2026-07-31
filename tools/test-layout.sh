#!/usr/bin/env bash
# Manually exercise the vendor window hook wits.intent.action.CHANGE_WINDOW.
#
# DEFAULT BEHAVIOUR IS DRY-RUN: it prints the exact adb commands and does not
# run them. Add --execute to actually send the broadcasts.
#
# Usage:
#   tools/test-layout.sh [preset] [options]
#
# Presets:
#   maps-spotify     Maps 65% left  | Spotify 35% right      (default)
#   maps-full        Maps fullscreen
#   spotify-full     Spotify fullscreen
#   three            Maps 65% | top-right + bottom-right      (experimental)
#   custom           use --pkg/--bounds (repeatable)
#
# Options:
#   --execute            actually send the broadcasts (default: dry-run)
#   --mode N             windowMode (default 5 = freeform; 1 = fullscreen)
#   --pkg PKG            with 'custom': package name
#   --bounds l,t,r,b     with 'custom': normalized 0..1 bounds for the last --pkg
#   --delay MS           delay between windows (default 350)
#   --inset-top PX       top inset to reserve (default: auto-detect the status bar)
#   --retries N          extra staggered passes after the first (default 0)
#   --burst              DIAGNOSTIC: fire every window back-to-back with no gap,
#                        reproducing the retry storm that broke tiling on 2026-07-31
#   --no-check           skip package presence check
#
# This script NEVER switches the video source and NEVER sends MCU commands.
# It refuses to run with --execute while reverse is engaged.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

PRESET="maps-spotify"
EXECUTE=0
MODE=5
DELAY_MS=350
CHECK=1
RETRIES=0
BURST=0
INSET_TOP=""
CUSTOM_PKGS=()
CUSTOM_BOUNDS=()

MAPS="com.google.android.apps.maps"
SPOTIFY="com.spotify.music"

while [ $# -gt 0 ]; do
  case "$1" in
    maps-spotify|maps-full|spotify-full|three|custom) PRESET="$1"; shift ;;
    --execute)  EXECUTE=1; shift ;;
    --mode)     MODE="$2"; shift 2 ;;
    --delay)    DELAY_MS="$2"; shift 2 ;;
    --inset-top) INSET_TOP="$2"; shift 2 ;;
    --retries)   RETRIES="$2"; shift 2 ;;
    --burst)     BURST=1; shift ;;
    --no-check) CHECK=0; shift ;;
    --pkg)      CUSTOM_PKGS+=("$2"); shift 2 ;;
    --bounds)   CUSTOM_BOUNDS+=("$2"); shift 2 ;;
    -h|--help)  sed -n '2,26p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_adb

# --- display geometry --------------------------------------------------------
SIZE="$(ash wm size | tr -d '\r' | grep -E 'Override|Physical' | tail -1 | sed 's/.*: //')"
W="${SIZE%x*}"; H="${SIZE#*x}"
[ -n "$W" ] && [ -n "$H" ] || die "could not read display size (wm size)"

# The vendor hook passes bounds straight to setLaunchBounds. The system raises `top`
# to below the status bar but preserves the requested height, so a rect asked for at
# y=0..H ends up at y=inset..H+inset and hangs off the bottom of the screen.
# Reserve the inset up front instead.
if [ -z "$INSET_TOP" ]; then
  # An existing freeform task shows the inset the system actually applies: the
  # winConfig line carries both mAppBounds and mWindowingMode=freeform.
  INSET_TOP="$(ash dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | grep 'mWindowingMode=freeform' \
    | grep -oE 'mAppBounds=Rect\([0-9]+, [0-9]+' | head -1 \
    | grep -oE '[0-9]+$')"
  if [ -n "$INSET_TOP" ]; then
    INSET_SRC="detected from an existing freeform task"
  else
    INSET_TOP=0
    INSET_SRC="NOT DETECTED (no freeform task yet) - pass --inset-top if windows hang off the bottom"
  fi
else
  INSET_SRC="given on the command line"
fi
USABLE_TOP="$INSET_TOP"
USABLE_H=$(( H - INSET_TOP ))

hdr "DISPLAY"
echo "  wm size    : ${W}x${H}"
echo "  wm density : $(ash wm density | tr -d '\r' | tail -1 | sed 's/.*: //')"
echo "  top inset  : ${INSET_TOP}px (${INSET_SRC})"
echo "  usable     : ${W}x${USABLE_H} at y=${USABLE_TOP}"

hdr "WINDOWING CAPABILITY"
FF="$(ash settings get global enable_freeform_support | tr -d '\r')"
FR="$(ash settings get global force_resizable_activities | tr -d '\r')"
PIP="$(ash pm list features | tr -d '\r' | grep -c picture_in_picture)"
printf '  enable_freeform_support   = %s\n' "$FF"
printf '  force_resizable_activities= %s\n' "$FR"
printf '  picture_in_picture feature= %s\n' "$([ "$PIP" -gt 0 ] && echo present || echo ABSENT)"
if [ "$FF" != "1" ]; then
  c_ylw "  WARNING: freeform is not enabled. windowMode=5 may be ignored."
  c_ylw "  See docs/v262-v263-diff.md §1 for the reversible experiment."
fi

# --- reverse guard -----------------------------------------------------------
REV="$(ash getprop wits.backcar | tr -d '\r')"
SRC="$(ash getprop wits.source  | tr -d '\r')"
hdr "SAFETY"
printf '  wits.backcar = %s\n' "${REV:-<unset>}"
printf '  wits.source  = %s%s\n' "${SRC:-<unset>}" "$([ "$SRC" = "11" ] && echo '  (BACKCAR!)')"
if [ "$EXECUTE" = "1" ] && { [ "${REV:-0}" != "0" ] && [ -n "${REV:-}" ] || [ "$SRC" = "11" ]; }; then
  die "reverse appears to be ACTIVE — refusing to change windows. Disengage reverse."
fi

# --- build the window list ---------------------------------------------------
# entries: "pkg l t r b"  with normalized floats
WINDOWS=()
case "$PRESET" in
  maps-spotify)
    WINDOWS+=("$MAPS 0.00 0.00 0.65 1.00")
    WINDOWS+=("$SPOTIFY 0.65 0.00 1.00 1.00")
    ;;
  maps-full)    WINDOWS+=("$MAPS 0.00 0.00 1.00 1.00") ;;
  spotify-full) WINDOWS+=("$SPOTIFY 0.00 0.00 1.00 1.00") ;;
  three)
    WINDOWS+=("$MAPS 0.00 0.00 0.65 1.00")
    WINDOWS+=("$SPOTIFY 0.65 0.00 1.00 0.50")
    WINDOWS+=("io.github.miklergm.witscompanion 0.65 0.50 1.00 1.00")
    c_ylw "  'three' is EXPERIMENTAL (docs/known-unknowns.md §1)."
    ;;
  custom)
    [ "${#CUSTOM_PKGS[@]}" -gt 0 ] || die "custom preset needs at least one --pkg"
    [ "${#CUSTOM_PKGS[@]}" -eq "${#CUSTOM_BOUNDS[@]}" ] || die "each --pkg needs a matching --bounds"
    for i in "${!CUSTOM_PKGS[@]}"; do
      IFS=',' read -r l t r b <<< "${CUSTOM_BOUNDS[$i]}"
      WINDOWS+=("${CUSTOM_PKGS[$i]} $l $t $r $b")
    done
    ;;
esac

# --- validation --------------------------------------------------------------
hdr "VALIDATION"
declare -A SEEN
FAIL=0
for w in "${WINDOWS[@]}"; do
  read -r pkg l t r b <<< "$w"
  if [ -n "${SEEN[$pkg]:-}" ]; then
    c_red "  duplicate package '$pkg' — the hook reuses the first task; only one window per package"
    FAIL=1
  fi
  SEEN[$pkg]=1
  awk -v l="$l" -v t="$t" -v r="$r" -v b="$b" 'BEGIN{ if (l<0||t<0||r>1||b>1||l>=r||t>=b) exit 1 }' \
    || { c_red "  invalid bounds for $pkg: $l,$t,$r,$b"; FAIL=1; }
  if [ "$CHECK" = "1" ]; then
    if [ -z "$(ash pm path "$pkg" | tr -d '\r')" ]; then
      c_red "  package NOT INSTALLED: $pkg"; FAIL=1
    else
      c_grn "  package present: $pkg"
    fi
  fi
done
[ "$FAIL" = "0" ] || die "validation failed; nothing was sent"

# --- render commands ---------------------------------------------------------
hdr "COMMANDS ($([ "$EXECUTE" = "1" ] && echo 'WILL EXECUTE' || echo 'DRY RUN'))"
CMDS=()
for w in "${WINDOWS[@]}"; do
  read -r pkg l t r b <<< "$w"
  PL=$(awk -v v="$l" -v s="$W" 'BEGIN{printf "%d", v*s}')
  PT=$(awk -v v="$t" -v s="$USABLE_H" -v o="$USABLE_TOP" 'BEGIN{printf "%d", o + v*s}')
  PR=$(awk -v v="$r" -v s="$W" 'BEGIN{printf "%d", v*s}')
  PB=$(awk -v v="$b" -v s="$USABLE_H" -v o="$USABLE_TOP" 'BEGIN{printf "%d", o + v*s}')
  cmd="am broadcast -a wits.intent.action.CHANGE_WINDOW --es packageName $pkg --ei windowMode $MODE --ei left $PL --ei top $PT --ei right $PR --ei bottom $PB"
  CMDS+=("$cmd")
  printf '  %-42s %s,%s,%s,%s\n' "$pkg" "$PL" "$PT" "$PR" "$PB"
  echo "    adb shell '$cmd'"
done

if [ "$EXECUTE" != "1" ]; then
  echo
  c_ylw "DRY RUN — nothing was sent. Re-run with --execute to apply."
  exit 0
fi

# --- execute -----------------------------------------------------------------
hdr "EXECUTING"
SLEEP_S="$(awk -v m="$DELAY_MS" 'BEGIN{printf "%.3f", m/1000}')"

send_pass() {
  local label="$1" gap="$2"
  for cmd in "${CMDS[@]}"; do
    echo "  [$label] -> ${cmd##*packageName }"
    ash "$cmd" >/dev/null 2>&1
    [ "$gap" = "0" ] || sleep "$SLEEP_S"
  done
}

if [ "$BURST" = "1" ]; then
  c_red "  --burst: sending every window with NO gap (diagnostic only)"
  send_pass "burst" 0
else
  send_pass "initial" "$SLEEP_S"
fi

i=1
while [ "$i" -le "$RETRIES" ]; do
  sleep 0.6
  if [ "$BURST" = "1" ]; then send_pass "retry$i" 0; else send_pass "retry$i" "$SLEEP_S"; fi
  i=$((i + 1))
done

hdr "RESULT"
echo "  wits.top.package = $(ash getprop wits.top.package | tr -d '\r')"
echo
echo "  Running tasks (top 8):"
ash dumpsys activity activities 2>/dev/null | tr -d '\r' \
  | grep -E "Task\{|topActivity|mWindowingMode" | head -24 | sed 's/^/    /'
echo
c_cyn "Record the outcome in docs/window-management.md §8."
