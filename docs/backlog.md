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
- [ ] **Eyeball the right-hand control rail** — Settings/switcher/Exit column: proportions on the
      real ~28 % panel, tap targets, that Exit sits at the bottom and Settings at the top (built
      2026-08-03, only seen clipped on the emulator).
- [ ] **Settings button opens the config UI cleanly** — was just flashing (the freeform map drew
      over MainActivity); now un-windows the tiles + finishes the panel first. Verify
      `setTaskWindowingMode` actually un-freezes on the head unit.
- [ ] **Exit un-windows the apps** — after Exit, navigation should open **fullscreen**, not in
      the Cockpit's windowed format (the bug: `resizeTask` kept tiles freeform; now
      `setTaskWindowingMode(FULLSCREEN)`).
- [ ] **Top bar hide/reveal** — how to hide the vendor 99 px strip and pull it back (§ Status bar);
      the panel content sometimes tucked under it.
- [~] **zlink resolution** — *root cause found 2026-08-03:* AA res = `panel × aaDensity/hiCarDensity`
      = `2400×900 × 160/300 = 1280×480`. **Lever: `persist.zj.dpi.aaDensity`** (unset→160); set
      240 → 1920×720. (`rw.zlink.resize=true` blanks the mirror — ruled out.) Next time: set it,
      reconnect AA, check `hu_AA_width` (§ zlink). Experiment only when a dropped mirror is OK.
- [ ] **Volume: read the live `STREAM_MUSIC` level** (`dumpsys audio`) before deciding whether
      any pinning is even needed (§ Volume).
- [ ] **Spotify no longer stretches to full width** after tiling (§ Layout placement).
- [ ] **No stale fullscreen app** on the first Cockpit open after churn (§ Layout placement).
- [x] **Media album art** — *verified 2026-08-03: cover art + progress + transport for a
      logged-in Spotify track ("Weak", Skunk Anansie), playing behind the map.*
- [ ] **Top bar** — decide hide/reveal, and whether the colour can be themed (§ Status bar).
- [ ] **Swap / split model** — settle the propagation behaviour interactively, on the car
      (§ Layout mental model).
- [~] **Switcher tiles toggle (hide, not only switch)** — *implemented 2026-08-06* (`LayoutEngine.hideFloatingApp`,
      `DashboardActivity.onSwitcherTap`). Tapping the **active** tile un-windows the floating app
      (freeform → fullscreen, drops behind) and grows the panel to fill the display; the panel's
      own `reservation()` paints the freed strip **black** and keeps the panel content at its
      **usual width** (decided 2026-08-06: panel does *not* stretch — right side stays "as usual",
      left just goes black). Tapping any tile floats an app again; no tile lit = hidden. On-car:
      confirm the panel actually resizes (privileged `bringAnchorToFront(full)` + `onConfigurationChanged`
      rebuild). Emulator can't exercise the privileged resize — only the toggle wiring ("App hidden"
      toast, no crash) was verified there. *(Reverse camera is not a concern for this: it is an
      MCU/hardware video overlay, independent of the Android app-window layer, so window changes
      cannot reach it — see [[reverse-camera-mcu-overlay]]. The `reverseGuard` stays as a cheap
      belt-and-suspenders anyway.)*
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
- [x] **Cockpit right-hand control column** — *implemented 2026-08-03* (`f3c6b3c`): main column
      (media + hotspot + brightness) + narrow rail with Settings (gear) top, the app switcher
      vertical, Exit (reset) pinned bottom; no more ScrollView/footer. Verified structurally on
      the emulator (clipped there by the freeform cascade). **Eyeball on the car** — see On-car
      checklist. Still to do: the "Cockpit" name check (§ UI).
- [ ] **Volume: read-only probe scaffolding** (verify-first), no active pinning yet (§ Volume).
- [x] **Refresh Brightness values** — *done 2026-08-06* (`faec0ab`): the label refreshes on
      resume and observes `SCREEN_BRIGHTNESS`, so a system day/night change is reflected live.
- [x] **Play and Pause** button styles — *done 2026-08-06* (`faec0ab`): fill is a desaturated,
      mid-tone `calm()` of the album/brand accent instead of the full (loud) colour.
- [x] **Floating apps** tile style — *done 2026-08-06* (`faec0ab`): dropped the selected-tile
      outline (soft pill + bold label + dimmed neighbours already mark the active app); the
      "Floating app" category label was already removed in the rail refactor.

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

### Why 1280×480 — and the lever (2026-08-03)

**The HU computes the AA resolution as `panel × (aaDensity / hiCarDensity)`:**

```text
1280 × 480  =  2400 × 900  ×  (160 / 300)
```

- `persist.zj.dpi.hiCarDensity = 300` (the panel's density for mirror modes),
- `persist.zj.dpi.aaDensity` is **unset → 160**,
- 160 / 300 = 0.533, so the panel is shrunk to 1280×480 and then upscaled back → the softness.

So **`persist.zj.dpi.aaDensity` is the safe resolution lever** (unlike `rw.zlink.resize`, which
blanked the mirror). Same ratio, higher number = more pixels at the same logical UI size:

- `aaDensity = 240` → **1920×720** (≈1.5× sharper) — try this first.
- `aaDensity = 300` → **2400×900** native — may be too heavy for wireless AA / rejected.

**Scope — AA-mirror only.** `persist.zj.*` is zjinnova's (zlink) namespace and `aa` = Android
Auto, so this only affects the AA mirror session. It does NOT touch the head unit's own UI
density (`wm density` / `ro.sf.lcd_density` — the system-wide knob that would resize the launcher,
the companion, vendor apps), nor CarPlay (`init_cp_dpi=180`), nor HiCar (`hiCarDensity=300`). Each
mirror mode has its own density.

**Expected effect — sharper, not resized UI.** The HU sends `aaDensity` to the phone as *both* the
density and the resolution divisor (log: `hu_AA_density=160` = the default), so the logical dp size
stays constant: `1920px / (240/160) = 1280 dp`, same as `1280px / (160/160)`. So AA keeps the same
layout / element sizes and just renders more pixels. Watch during the test: wireless
bandwidth/latency/heat (more pixels — 300 may be refused), the phone accepting the resolution, and
touch mapping staying aligned.

**On-car test:** `setprop persist.zj.dpi.aaDensity 240`, restart zlink, reconnect AA, and check
`hu_AA_width` in `/sdcard/zlinklog/zlink_log-1.txt` (expect ~1920). Judge sharpness by eye too.
`[UNVERIFIED]`: couldn't confirm `wm density`=300 live (head unit went offline), but the numbers
match exactly and `hiCarDensity=300` exists — verify the panel density when back on the car.

### On-car investigation (2026-08-03) — what we learned

- **The 1280×480 comes from the head unit, not zlink.** The HU (com.wits side) sends it to zlink
  in `MESSAGE_INIT_INFO` (`hu_AA_width=1280 hu_AA_height=480 hu_AA_density=160`), and zlink then
  forces it (`is_force_res=1`). So the cap is HU-side — but it is derived from `aaDensity` (above),
  which we can set.
- **Properties found:** `persist.zj.dpi.aaDensity` is **unset** (so it defaults to the 160 we
  see); `persist.zj.dpi.hiCarDensity=300` (the HiCar analog — shows the naming pattern);
  `rw.zlink.resize` was `false`; `rw.zlink.disable.features=dce`; `ak.af.carplay.package=
  com.zjinnova.zlink`. No property carries the AA width/height.
- **`rw.zlink.resize=true` — RULED OUT.** Setting it (then restarting zlink) gave a **black
  screen** on the mirror. Reverted to `false` and it recovered. Do not retry.
- **zlink's own config is in an encrypted MMKV** (`files/mmkv/ZlinkAppStore`) — not readable, so
  no settings to edit directly there.
- **zlink logs** to `/sdcard/zlinklog/zlink_log-1.txt` (and `-2`), and also to logcat (tag
  `btopt`). Effect of any change shows in the next `MESSAGE_INIT_INFO` / `get_best_res`.
- **CAUTION:** poking `rw.*`/`persist.zj.*` props on the *live* head unit disrupts the mirror
  (black screen). Only experiment when a dropped mirror is acceptable, and revert promptly.

### Next steps (when we return to zlink, carefully)

1. **Set `persist.zj.dpi.aaDensity=240`** (the lever above → 1920×720), restart zlink, reconnect
   AA, and read `hu_AA_width` from the log — expect ~1920, and judge sharpness. It is a `persist`
   `/data` prop (not a vendor-partition change). Start at 240; if stable and the phone accepts it,
   try 300 (native). Revert to unset (`setprop persist.zj.dpi.aaDensity ""` won't unset — use the
   platform app or note the default is 160) if anything misbehaves. Do it when a dropped mirror
   is acceptable.
2. **First verify the panel density** (`wm density`) matches the 300 assumption before trusting
   the formula blindly.
3. **zlink developer options (fallback).** `is_developer_options=0` in the log — enabling them in
   zlink's UI (often a hidden gesture on a version/logo) may expose a resolution/quality toggle.
4. Note the phone offered `get_best_res` up to 1280×720 in the *old* log; whether it will accept
   1920×720 depends on the negotiation once the HU advertises the larger `hu_AA_*` — the log will
   tell.

## Status bar / top strip

- **Hide the top bar — lever is `FORCE_FULLSCREEN`, but the app can't write it.** `[RUNTIME]`
  2026-08-05: `settings put system FORCE_FULLSCREEN 1` from **shell** hides the whole vendor top
  strip (clock / home / recents / back) and the map fills top-to-bottom — verified. BUT the
  companion **cannot** write it:
  - The vendor added `FORCE_FULLSCREEN` to the framework's `MOVED_TO_SECURE`, so every app-side
    write into the *system* table is rejected with `IllegalArgumentException: "You cannot keep
    your settings in the secure settings"` — tried `Settings.System.putInt`, the provider
    `call("PUT_system")`, and a raw `ContentResolver.insert`; all three throw. Shell's `settings`
    bypasses it (different UID/path).
  - Writing `Settings.Secure` **is** allowed (we hold `WRITE_SECURE_SETTINGS`) but does **not**
    hide the bar — the vendor reads it from the *system* table, not secure.
  - So: **auto-hide via this setting is not doable from the companion.** The non-working attempt
    was removed. Alternatives to explore (offline): (a) request signature-level
    `android.permission.STATUS_BAR` and hide via `IStatusBarService` (but `disable*` hides bar
    *contents*, not the strip itself — may not be enough); (b) a vendor broadcast (none found so
    far — `com.wits.systemui.*` only has show_volume / SET_WALLPAPER); (c) accept the bar.
  - The user wanted to check whether **navigation has no top bar** anyway — worth confirming what
    the bar looks like over Maps before investing more.
  - Related: post-boot the tiles land at `top=0` (under the bar) not `top=99` — the `top` inset
    (`status_bar_height`) may read 0 in the freeform tile; the panel content then tucks under the
    bar. Fixing that inset is the smaller, doable win if we keep the bar.
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

## Freeform isn't ready at autostart (boot) — Cockpit auto-opens fullscreen

`[RUNTIME]` 2026-08-05, clarified by the user: freeform windowing is **not up yet when the
Cockpit auto-starts on boot**, so the auto-started Cockpit comes up **fullscreen** (panel over the
map, no tiles). The user considers this normal-ish — **after one manual tap on an app tile**
(floatApp re-applies) freeform establishes and the two tiles appear correctly.

- So it's an **autostart *timing*** problem, not "freeform doesn't survive a reboot": the engine
  applies the layout before the platform freeform path is ready, and the launches land fullscreen.
- `am start --windowingMode 5 …` works even then, so freeform is available — the companion's very
  first apply is just too early / the display is still `mDisplayWindowingMode=fullscreen`.
- **Fix ideas:** on autostart, wait for / poll until a freeform launch actually sticks before
  applying (or retry the anchored apply until the panel task is really `mode=freeform`); or have
  the autostart do a throwaway freeform "warm-up" launch first. Low urgency (a tap recovers it),
  but worth smoothing so the auto-open isn't broken-looking.

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
