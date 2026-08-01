# Backlog

Ideas and follow-ups captured during on-vehicle testing, not yet done. Roughly ordered by
how concrete they are.

## Volume

- **Pin Android media volume to 100% and never change it.** The effective volume is the
  head-unit stage (`wits_mcu:1`, ~67 % on the dial), which the user confirmed *does* change
  Spotify's loudness. Android's own `STREAM_MUSIC` should sit at maximum so it is a fixed
  gain stage and only the MCU/amplifier attenuates — one volume domain, not two.
  - The vendor `AudioService` ignores stream-volume changes from any caller except
    `com.wits.pms`, and `CenterService` already pins the stream to max, so it may already be
    at 100 %. **First verify** the live `STREAM_MUSIC` level (`dumpsys audio`), then decide
    whether anything is needed.
  - If active pinning is wanted, the gate blocks a normal setter; explore whether the
    platform signature lets us set it another way, or a watcher that re-asserts max. Do NOT
    add a second user-facing volume control — the user explicitly does not want two.

## Notification access

- **[DONE]** Self-grant notification access from the app (platform build). The vendor's system
  menu for notification access is unreachable on this unit, and the grant is lost on every
  reinstall, so it currently needs ADB. With the platform signature we can hold
  `WRITE_SECURE_SETTINGS` and append our listener component to
  `enabled_notification_listeners` ourselves. The Settings → Media button should do this
  when privileged, falling back to the system intent otherwise. (Manifest permission +
  `MediaSessionRepository.grantSelf()`.)

## Vendor integration to explore (offline)

- **"Car Device" source / transitions.** The vendor launcher has a "Car Device" tile and
  source transitions. Investigate what it switches to and whether a clean, safe entry point
  is worth surfacing (we removed the old OEM/Android buttons because they were broken).
- **Vendor dashboard with car metrics.** The default launcher has a dashboard that pulls
  some vehicle readings. Study which properties it reads and whether any are worth showing
  in the Cockpit (keeping to what is genuinely useful in motion — speed/doors are on the
  cluster/HUD already).

## Status bar / top strip

- **Hide / reveal the top bar.** The vendor 99 px top strip can be hidden and pulled back
  down. Unclear yet whether the Cockpit wants this (more screen for the map) — decide, then
  see if we can drive it (likely `SYSTEM_UI_FLAG_*` / WindowInsetsController from a
  full-screen Cockpit, or a vendor call).
- **Top bar colour.** The vendor's 99 px top strip (home / clock / recents / back) has its
  own colour that does not match the app or the Cockpit. See whether it can be themed
  (status-bar colour is a per-window property; with the platform signature there may be more
  reach) or at least made consistent with the panel.

## Hotspot

- **Toggling the hotspot closes the focused app.** Clicking hotspot on/off in the Cockpit
  turns off / hides whatever app was in focus (e.g. the map). Likely a side effect of
  `TetheringManager.start/stopTethering` on the vendor ROM (Wi-Fi station/AP transition or a
  focus/window change), not the button itself. Needs on-car logcat around the toggle. Until
  understood, consider re-asserting the layout after a toggle, or gating the toggle.

## Emulator-only (does not affect the head unit)

Kept here so we don't chase them as product bugs. All stem from the emulator using the
**unprivileged** launch path (`launchViaPlainStart` + `setLaunchBounds`), whereas the head
unit is platform-signed and uses `resizeTask`.

- **Switching the floating app cascades the new window ~150 px off the anchor** (and it
  carries an AOSP caption bar). `[RUNTIME]` 2026-08-01, dumpsys: with the anchor bounds
  `1656–2400`, Maps lands exactly there, Chrome at `1506–2250` (−150), Spotify at
  `1356–2100` (−300) — a fixed +150 px cascade per launch. Cause: the previous floating
  app's freeform task is still present (hidden) at the same bounds, so AOSP's freeform
  placement offsets the new one to avoid perfect overlap. On the head unit this can't happen:
  `parkStaleWindows` turns the previous app fullscreen (no freeform task left at those
  bounds) and `resizeTask` sets exact bounds with no caption. This is the "Chrome opens in a
  window and isn't positioned right" report from emulator testing — **not reproducible on the
  car** (freeform tiling there was verified flush and caption-less). Confirm with a glance
  next time in the car; no code change planned for the emulator path.

## Layout placement (needs on-car re-test)

- **Spotify stretches to full width after tiling.** Spotify launches at the given bounds
  then grows itself to full width, covering the layout. Fix applied but untested on the car:
  the retry pass now re-asserts geometry with `resizeTask` on the privileged path (it was a
  no-op there before), which should pull Spotify back. Verify, and if it still fights,
  consider a short watcher that re-resizes it until it settles.
- **Cockpit shows a stale fullscreen app on the first open after churn.** After messy rapid
  taps, opening the Cockpit first showed a leftover fullscreen Spotify; second try was
  correct. Needs extra stale-task cleanup before/while applying an anchored preset (park or
  fullscreen any freeform task not in the new layout, not just the last-applied set).

## Layout mental model (needs formalization — user is still deciding)

The current model: the **proportion slider** rewrites the split for *all* presets, and
**"primary left/right"** (swap) flips which side the floating app takes. Two rough edges the
user hit on 2026-08-01, flagged as "to think about", not yet a decided change:

- **Changing the slider also changes how the Cockpit looks.** Expected in hindsight (the
  Cockpit panel reserves the strip the floating app covers, which the split defines), but
  surprising in the moment. Consider decoupling, or making it visibly obvious that the slider
  drives the Cockpit reservation too.
- **Changing "primary left/right" does not re-position an already-floating map in the
  Cockpit.** The Cockpit's reservation and the floating map come from the *last-applied*
  anchored preset (baked bounds); flipping the global swap doesn't re-float the current map,
  so it stays on its old side until the app is re-picked. Decide the intended behaviour:
  either re-apply the anchored preset when swap changes, or make swap a per-apply choice.
- Broader: the user finds swap "works strangely for everything" and wants a simpler mental
  model. Gather how they'd prefer to think about it (pick layout first, then apps? one split
  that means the same thing everywhere?) before changing code.

## Brightness (Cockpit control — feasibility to verify on car)

- **A brightness control in the Cockpit: one or two tiles that nudge ±~15–25%.** For when
  the auto night level is too bright or the day level too dim. Relative nudges (−/+) from the
  current value fit driving better than a slider. **Blocked on one on-car check:** does this
  head unit's panel backlight follow Android's `Settings.System.SCREEN_BRIGHTNESS`, or is it
  driven by the vendor MCU (day/night tracks the car's illumination line)? Change brightness
  manually in vendor settings and watch `SCREEN_BRIGHTNESS` + the signal recorder to see
  which moves. If it's the framework setting, we can write it (platform signature /
  `WRITE_SETTINGS`); if it's the MCU, we'd need the vendor channel and the change may be
  re-asserted on the next illumination event.
- **No ambient-light sensor to auto-tune from.** These units have no photodiode; day/night
  comes from the car's illumination (headlight) line over CAN, which is why the theme flips
  with the headlights, not the clock. `SensorManager` has no `TYPE_LIGHT` on the hardware
  (the emulator's "Goldfish Light sensor" is virtual and irrelevant). Worth a one-line
  `getSensorList` confirmation on the car, but the absence is expected — which is exactly why
  a manual ± control is the right call rather than an auto-brightness curve.

## Cockpit / panel polish

- **[DONE]** Floating-app switcher shows which app is active (rounded highlight + bold
  label) and the labels are centre-aligned (were drifting sideways). 2026-08-01.

- **Flicker artifact in Spotify's top-left, just under the status bar** — seen only with
  Spotify tiled, not Maps. Likely a freeform caption/handle or a Spotify overlay redrawing.
  Investigate whether the freeform tile shows a caption bar we can suppress.
- **Cockpit app switching flickers slightly** on change. Acceptable now; revisit if it
  bothers.
- **Blue top strip in Cockpit** — reported, then found the panel is actually black and not
  showing through. Re-check only if it reappears.
- **[DONE]** Panel reservation is now side-aware (was: only a left-anchored map). With the map swapped to the right,
  the reserve-left logic returns 0 and the panel goes full-width (content then risks sitting
  under the map). Make the reservation side-aware, or fix the map to one side.

## UI

- **[DONE]** Reset on Home. Add a reset entry to the Home tab (currently only in Settings) so the
  "return everything to the vendor launcher" action is one tap from the landing screen.
  (`LayoutEngine.resetToVendorState()` already exists — just surface it as a Home tile/card.)
- **[DONE]** "Your layouts" cards flow into the same adaptive grid as Home (currently a
  single column).
- **Cockpit layout** — user mentioned wanting to rearrange the blocks (clock / media / apps
  / hotspot); gather specifics.
- **Name check** — "Cockpit" is the working name for Mode B; confirm or change.

## Verified working on the vehicle (for reference, not backlog)

- Side-by-side tiling via the privileged `resizeTask` / freeform-launch path, offset correct
  under the 99 px status bar.
- Cockpit opens with the map floating and in-panel app switching.
- Dark theme follows the system setting.
