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

## zlink (CarPlay / Android Auto mirroring) — upscaled / low resolution

- **zlink renders soft/stretched because it negotiates a low resolution** — the user recalls
  it settling on ~**1280×480** while the panel is 2400×900, so the image is upscaled. Note
  1280×480 and 2400×900 are the **same 8:3 aspect**, so it is a resolution problem, not a
  stretch/aspect one — it just needs a higher negotiated mode.
- **What to find out (needs the logs the user captured + on-device):**
  - Where 1280×480 comes from: is it hard-coded in zlink, driven by the **surface/window
    size** zlink is given, or negotiated with the phone? If it follows the window size, giving
    zlink a full 2400×900 surface (fullscreen, or a correctly-sized freeform tile) may make it
    negotiate higher; if it is tiled *smaller* it may go *lower*, so tiling could hurt here.
  - Whether a zlink setting or a `wits_*` / system property pins the mirror resolution (use
    the signal recorder: change zlink's display/quality setting and watch what moves).
  - Whether zlink exposes a resolution/quality option in its own UI we simply have not set.
- **Ask the user** to share the logs they took (the resolution-negotiation lines) before
  designing anything. Offline there is nothing to build yet — this is research first.

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

- **[FIXED structurally — verify on car] Toggling the hotspot (and, it turned out, any panel
  control) pushed the floating map behind the panel.** Root cause was *not* tethering: the
  Cockpit panel was a **fullscreen** window with the map floating *over* it, so any tap on a
  panel control focused the panel task and the framework raised it in front of the map
  (`dumpsys`: companion `MOVE_TO_TOP`, map `TO_BACK`). Confirmed on the emulator with the
  brightness buttons; matches the on-car hotspot report.
  - First attempt was a debounced *re-raise* of the map after each tap — it worked but the map
    visibly blinked out and back (rejected: "очень заметно").
  - **Real fix:** make the panel a **freeform tile beside the map**, not a fullscreen anchor
    behind it. Two non-overlapping freeform tiles never occlude each other on focus, verified
    both with a Maps+Chrome pair and with the panel itself (`bringAnchorToFront` now launches
    `DashboardActivity` into freeform at the map's complement bounds; a distinct
    `taskAffinity` gives it its own task). On the emulator the map stays `visible=true` through
    every control tap — no flicker.
  - **Verify on car:** the privileged `resizeTask` should place the panel tile *exactly* and
    the vendor hides the freeform caption, so it looks seamless. On the **emulator** the panel
    tile shows the usual freeform caption + a cascade/size offset (same emulator-only quirk as
    the tile cascade), and MainActivity can peek in the uncovered strips — cosmetic, emulator
    only. Confirm the clean look on the head unit.

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

## Layout mental model (design discussion — pick up interactively)

The current model: the **proportion slider** rewrites the split for *all* presets, and
**"primary left/right"** (swap) flips which side the floating app takes.

**Audit (2026-08-01): the geometry math is correct, and now well covered by tests**
(`PresetKindAndCustomisationTest`: `withGeometry`/`mirrored`/`withSplit`, both kinds, swap,
clamping, reservation). So the reported "swap works strangely for everything" is **not** a
geometry bug — it is *propagation*, plus emulator limits:

- **Changing swap/split does not update an already-open Cockpit or an already-floating map.**
  Nothing re-applies on the change; the map stays put until the app is re-picked. This is the
  real "primary left/right didn't move the map" report. **This is the design decision to make
  interactively:** should changing the slider/side re-apply the current layout immediately, or
  should swap be a per-apply choice? (Leaning: re-apply on change — it matches "the slider
  changes all presets".) Not implemented pending that call.
- **On the emulator specifically, re-applying to an already-running app doesn't move it:**
  `setLaunchBounds` only positions a *new* task, so relaunching live Maps via the unprivileged
  path is a no-op on position. On the head unit the privileged `resizeTask` *does* move it, so
  this particular strangeness is largely emulator-only — another reason to settle the model on
  the car, not the emulator.
- **Changing the slider also changes how the Cockpit looks** — expected (the panel reserves
  the strip the floating app covers, which the split defines), but surprising. Decide whether
  to make that visibly obvious or decouple.

**Fixed along the way (not design):** the switcher's `anchored_<pkg>` presets are not in
`allPresets`, so after floating Chrome/Spotify the highlight fell back to the map on an
activity recreation (e.g. a day/night flip). Now the floating package is remembered directly
(`LayoutRepository.cockpitFloatingPackage`).

## Cockpit: play hides the map — [FIXED, same cause as hotspot]

- Same root cause as the hotspot item above (the fullscreen panel overlapping the map), and
  fixed by the same change (panel is now a freeform tile beside the map). Not the `play()`
  command — the earlier emulator theory about logged-out Spotify grabbing focus was a red
  herring; brightness, which talks to nothing, hid the map just as reliably. Empty-panel taps
  never triggered it because a non-interactive touch does not focus/raise the panel task.
- Follow-ups from the two-tile change (cosmetic, mostly emulator): the panel tile's exact
  placement + caption suppression need on-car confirmation; consider sending the launcher
  (`MainActivity`) fully behind the tiles so it cannot peek in uncovered strips.

## Brightness (Cockpit control)

- **[DONE, one on-car check left]** A brightness − / + control in the Cockpit that nudges
  ±20 % of the range. Built as `BrightnessController` + a tile in the panel; writes
  `Settings.System.SCREEN_BRIGHTNESS` after switching off auto-brightness, floors at ~5 %
  (never black in motion), caps at 100 %. Validated on the emulator (2026-08-01): +/− step by
  51/255, floor holds at 12, label tracks. `WRITE_SETTINGS` is already declared (granted by
  signature on the platform build; on the emulator granted via `appops set … WRITE_SETTINGS
  allow`). Covered by `BrightnessControllerTest`.
  - **On-car check that remains:** does this panel's backlight actually follow the framework
    `SCREEN_BRIGHTNESS`, or the vendor MCU (day/night tracks the illumination line)? Tap the
    tiles in the car — if the panel dims/brightens, done; if it snaps back on the next
    illumination event, the MCU owns it and we'd need the vendor channel instead.
- **No ambient-light sensor to auto-tune from.** These units have no photodiode; day/night
  comes from the car's illumination (headlight) line over CAN, which is why the theme flips
  with the headlights, not the clock. `SensorManager` has no `TYPE_LIGHT` on the hardware
  (the emulator's "Goldfish Light sensor" is virtual and irrelevant). Worth a one-line
  `getSensorList` confirmation on the car, but the absence is expected — which is exactly why
  a manual ± control is the right call rather than an auto-brightness curve.

## Cockpit / panel polish

- **[DONE]** Floating-app switcher shows which app is active — a filled pill with an outline
  and a bold label, the other tiles dimmed so the active one is obvious at a glance; the
  tapped tile pops in so the focus visibly moves onto it. Labels are centre-aligned (were
  drifting sideways). 2026-08-01.
- **[DONE]** Media reworked into a single rounded card tinted by what is playing: album-art
  accent (`AlbumAccent`), brand-colour fallback (Spotify/YouTube/…) + the player's icon when a
  source is live but artless, a filled accent play button, centred transport. Real album art
  needs a logged-in player (validate on the car with Spotify). 2026-08-01.

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
