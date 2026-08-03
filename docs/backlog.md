# Backlog

Ideas and follow-ups captured during on-vehicle testing. The two checklists below are the
quick index — where the *next* action happens. Full detail is in the per-topic sections
further down (linked by name).

## ☑ On-car checklist (needs the head unit)

Things whose next step needs the car — verify a fix, or run a probe that only works there.

- [x] **Cockpit panel-as-tile looks clean** — *verified 2026-08-03: seamless on the head unit,
      no caption, exact placement (panel `1560–2400`, map `0–1560`).* The map no longer
      disappears on control taps.
- [x] **Brightness actually drives the backlight** — *verified: the ± tiles change the panel
      (it read 100 %); the framework `SCREEN_BRIGHTNESS` path is the right one here.*
- [x] **Cockpit floating-app switch returns to Maps** — *fixed + verified 2026-08-03.* Two-tile
      mode had broken it: parking to fullscreen made the previous app cover the screen and the
      new one couldn't reach the front (§ Layout placement → floating-app switch).
- [ ] **zlink resolution test task** — enumerate `persist.zj.*`, try zlink developer options /
      the property route, measure `hu_AA_*` before/after (§ zlink, numbered steps).
- [ ] **Volume: read the live `STREAM_MUSIC` level** (`dumpsys audio`) before deciding whether
      any pinning is even needed (§ Volume).
- [ ] **Spotify no longer stretches to full width** after tiling (§ Layout placement).
- [ ] **No stale fullscreen app** on the first Cockpit open after churn (§ Layout placement).
- [x] **Media album art** — *verified 2026-08-03: cover art + progress + transport for a
      logged-in Spotify track ("Weak", Skunk Anansie), playing behind the map.*
- [ ] **Top bar** — decide hide/reveal, and whether the colour can be themed (§ Status bar).
- [ ] **Swap / split model** — settle the propagation behaviour interactively, on the car
      (§ Layout mental model).
- [ ] **One-line confirmations:** the emulator cascade is absent on the car (§ Emulator-only);
      `SensorManager` has no `TYPE_LIGHT` (§ Brightness).

## ☐ Offline checklist (emulator / code / study)

Things doable now, without the car.

- [ ] **Send `MainActivity` fully behind the Cockpit tiles** so it can't peek in uncovered
      strips (§ Cockpit: play hides the map — follow-ups).
- [ ] **Study the vendor "Car Device" source and the vendor dashboard** — what they switch to /
      which properties they read (§ Vendor integration).
- [ ] **Spotify top-left flicker** — is it a suppressible freeform caption/handle? (§ Cockpit /
      panel polish).
- [ ] **Cockpit right-hand control column** — user's proposal: a narrow rightmost column with
      Settings (icon) at the top, the floating-app switcher, and Exit/Reset pinned to the
      bottom; frees vertical space so the panel stops needing to scroll under the top bar
      (§ UI). Plus the "Cockpit" name check.
- [ ] **Volume: read-only probe scaffolding** (verify-first), no active pinning yet (§ Volume).

---

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

zlink (zjinnova) renders soft because it mirrors at a **low, forced resolution** and the head
unit upscales it. From the log the user captured (`research/zlink-log-1.txt`, wireless Android
Auto, Galaxy S24):

- The HU advertises the Android Auto display as **1280×480 @ 160 dpi**, forced:
  - `MESSAGE_INIT_INFO`: `hu_AA_width=1280  hu_AA_height=480  hu_AA_density=160`
  - `get_best_res … is_force_res=1`; content area `1280×480` inside a `1280×720` video frame
    (`height_margin=240`). The phone can do more — the HU pins it low.
  - zlink reads the system property **`persist.zj.dpi.aaDensity`** for the AA density (`zj` =
    zjinnova). Other `init_*` values in the log: `init_cp_dpi=180`, `init_video_res=0`.
- That `1280×480` is then scaled to zlink's `video_viewarea 1856×704`, and that onto the
  2400×900 panel — **two/three upscales**, hence the softness. `1280×480` and `2400×900` are
  the **same 8:3 aspect**, so this is purely resolution, not stretch.
- `is_developer_options: 0` — zlink's developer options are OFF; they often expose a
  resolution/quality picker.

**The companion cannot fix this by tiling** — the low resolution is decided inside zlink's
negotiation, driven by the HU config / `persist.zj.*` properties, not by the window we give
it. Giving zlink a bigger surface only improves the *second* upscale, not the `1280×480` core;
tiling it *smaller* could make it negotiate even lower. So this is a property/settings change,
plus the companion's signal recorder + property reader as the tools to find and test it.

### Test task (on the car)

1. **Enumerate the knobs.** With zlink running (or just installed), read its properties:
   `getprop | grep -iE 'persist\.zj|zlink|\.aa'`. Expect `persist.zj.dpi.aaDensity` and likely
   companions for AA width/height or a resolution/quality index. Record the current values.
2. **Baseline.** Confirm the live values match the log: capture a fresh zlink log (or logcat)
   and note `hu_AA_width/height/density` + `is_force_res`.
3. **Try zlink's own developer options** first (least invasive): enable them in zlink's UI if
   possible and look for a resolution/quality setting; set it higher; reconnect AA; re-read the
   log to see whether `hu_AA_width/height` rose.
4. **Try the property route.** Raise the AA resolution/lower the density via the `persist.zj.*`
   props found in step 1 (e.g. a higher AA width/height, or `aaDensity`). Note: these are
   `/data` persistent properties, **not** a vendor-partition change, so within the "don't touch
   system/vendor" rule — but the SELinux context may block a plain `setprop`; try `adb setprop`
   and, if needed, the platform-signed app. Reconnect AA and re-read `hu_AA_*`.
5. **Use the signal recorder** around each change: Start → change the setting/prop → Stop, so we
   have a before/after of any `Settings`/property that moved.
6. **Judge by the numbers, not just the eye:** success = `hu_AA_width/height` (and `get_best_res`
   content size) go up and the picture sharpens. If nothing moves the forced `1280×480`, the cap
   is baked into the HU's zlink config and the realistic options are (a) zlink developer options,
   or (b) accept it. Capture whatever worked here for reference.

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

- **[FIXED + verified 2026-08-03] Cockpit floating-app switch could not return to Maps; the
  previous app covered the screen full-size.** A consequence of the panel-as-tile change:
  `parkStaleWindows` parked the previous floating app by resizing it to the *full display*
  (still freeform), which the old fullscreen anchor used to hide but the new panel *tile* does
  not — so it filled the screen; and the newly-selected app, already a freeform task behind it,
  was only `resizeTask`-ed (never reordered), so it stayed hidden. Fix: for anchored (Cockpit)
  layouts, park stale apps to the **floating tile's bounds** (not fullscreen), and place the
  selected app with **`bringToFront`** (a launch that reorders, then the retry re-asserts the
  bounds). On the car: switching Maps→Spotify→Maps returns to Maps, no full-size cover, Spotify
  keeps playing behind. `WitsWindowController.applyWindow(bringToFront)` /
  `PrivilegedWindowController.place(bringToFront)` / `LayoutEngine.parkStaleWindows(parkBounds,
  parkMode)`.
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
- **Cockpit right-hand control column** (user proposal, 2026-08-03). Move the controls into a
  narrow rightmost column so the media block has the full height and the panel no longer needs
  to scroll under the vendor top bar:
  - top: **Settings** as an icon;
  - middle: the **floating-app switcher** (the app tiles), maybe smaller icons or vertical;
  - bottom (pinned): **Exit / Reset**.
  The media card + clock keep the rest of the panel width. This also sidesteps the "panel goes
  under the header / scroll" issue reported on the car (the panel content was taller than the
  tile). Doable offline; size it once we look at the panel width the map leaves (~28 %).
- **Name check** — "Cockpit" is the working name for Mode B; confirm or change.

## Verified working on the vehicle (for reference, not backlog)

- Side-by-side tiling via the privileged `resizeTask` / freeform-launch path, offset correct
  under the 99 px status bar.
- Cockpit opens with the map floating and in-panel app switching.
- Dark theme follows the system setting.
