# Backlog

Ideas and follow-ups captured during on-vehicle testing. The two checklists below are the
quick index — where the *next* action happens. Full detail is in the per-topic sections
further down (linked by name).

## ☑ On-car checklist (needs the head unit)

Things whose next step needs the car — verify a fix, or run a probe that only works there.

- [x] **N1 — the Cockpit's floating app (map) doesn't reach the front of its left tile** — ✅ *BOTH
      modes fixed + verified on-car 2026-08-17.* The panel (our own activity) tiled fine, but the
      foreign app stayed behind the launcher. Surfaced once the config stopped masking it
      (`20e138c`, 2026-08-11). The two modes turned out to have **different** root causes — the
      "freeform placement is unreliable" theory was only half right, and wrong for (b).
  - **(a) Raise after the vendor Home button / an app switch** — ✅ *FIXED + verified on-car
    2026-08-14 (`4a03eba`): home→app fronts the map (logcat `START …apps.maps from uid 10163`).
    Gotcha: an earlier deploy showed no change because the build was **stale** — force a clean
    rebuild after this kind of one-line engine change.* On a route-safe reassert the anchored app was
    preserved/resized in place (no reorder) and never fronted, so the map stayed behind the launcher.
    **Probed on the head unit** (cockpit up, map z-behind the launcher, `visible=false`):
    - `am stack move-task <t> <ownRoot> true` → **no-op** (moving a task into its own root does not
      reorder the display); `am stack move-stack` → not on this build; `startActivityFromRecents`
      (tried `79bd869`, reverted `446daa1`) → **EXCLUSIVE**, hid the panel (`visible=false`).
    - **A plain `am start` of the map → brought the existing task to the front over the launcher
      (`visible=true`) and left the panel `visible=true`** — non-exclusive, logged *"brought to the
      front"* (task reused, no relaunch → Maps route intact). That is the primitive.
    - **Fix:** front the anchored app *always* — drop the `!preserve` from the engine's `bringToFront`;
      `place()`'s bring-to-front is already that same plain `startActivity` (`launchIntoFreeform`).
      One line. Verify on-car: home→app / hide→show front the map with no launcher peek, route intact.
  - **(b) Cold boot** — ✅ *FIXED + verified on-car 2026-08-17 (`6795a71`).* **The freeform-readiness
    race was a red herring.** Real cause: tapping an app in the Cockpit rail stores
    `last_preset = anchored_<pkg>`, an **on-the-fly** preset that is never saved, so
    `lastAppliedPreset()` resolved to **null** and the autostart applied *no layout at all* — it just
    opened the panel. Hence "app tile lit but no app" (`cockpitLeft` persists fine; the preset did
    not). Also explains the intermittency: after opening the Cockpit from Home the id was the
    built-in `maps65_anchored`, which resolves. Proof in logcat: `autostart … -> open Cockpit (no
    last layout)` → after the fix `autostart … preset=anchored_com.google.android.apps.maps ->
    applied`, both tiles freeform+visible. Follow-ons: boot delay cut 12 s → 5 s (`88fa716`, the long
    wait had been guarding against this bug), and the post-apply verification (`8c023f2`) stays as
    the safety net for a genuine readiness race — it needed no correction at 5 s.
    *Historic symptom description:* autostart fired at ~12 s, the panel came up **fullscreen** and
    the floating app wasn't placed → panel content on the right with a **black left strip** (its
    reservation), no map. Reproduced on 2 of 2 cold boots
    (`[RUNTIME]` 2026-08-11 + 2026-08-14; 2nd: Spotify session came up but no map, panel right / black
    left — milder than the earlier launcher-peek). ⏳ **IMPLEMENTED `8c023f2` — verify on-car** (two
    cold boots in a row should come up map + panel; watch logcat `LayoutEngine: verify:`).
    Built as a **post-apply verification**, not a standing watchdog: each apply captures what it
    intended to place and re-checks at +3 s / +8 s, correcting only on a real mismatch. The yardstick
    comes from the apply itself, so the `Hidden` state (panel legitimately full-screen) and the
    panel-in-a-tiled-preset case need no special casing. Design (agreed):
    - **Event-triggered, not a free poll** — check ~1–1.5 s after the Cockpit is shown
      (`DashboardActivity.onResume`), and again a couple of times with backoff.
    - **Detect** via `getAllRootTaskInfos`: the intended floating app should be a *visible freeform*
      task at ~the left tile and the panel a *freeform* right tile. Panel `fullscreen`, or the app
      missing / behind / fullscreen → mismatch.
    - **Correct** by re-running the whole Cockpit apply (same as the "2nd tap", which is known to
      work) — not a single front primitive (those all dead-ended: recents is exclusive, move-task a
      no-op).
    - **Guards** (this is why it's not race-hell): only when the Cockpit is the intended foreground
      (`cockpitLeft` App/Default), never while reversing, and generation-gated + debounced so it
      can't fight an in-flight apply.
    - **Bounded**: 2–3 attempts then stop — each re-apply relaunches the app, so an unbounded loop
      would reset the Maps route and fight the vendor. Stop as soon as the state matches.
- [x] **Cockpit panel-as-tile looks clean** — *verified 2026-08-03: seamless on the head unit,
      no caption, exact placement (panel `1560–2400`, map `0–1560`).* The map no longer
      disappears on control taps.
- [x] **Brightness actually drives the backlight** — *verified: the ± tiles change the panel
      (it read 100 %); the framework `SCREEN_BRIGHTNESS` path is the right one here.*
- [x] **Cockpit floating-app switch returns to Maps** — *fixed + verified 2026-08-03.* Two-tile
      mode had broken it: parking to fullscreen made the previous app cover the screen and the
      new one couldn't reach the front (§ Layout placement → floating-app switch).
- [ ] **Bind a hardware button to the Cockpit via the vendor `NaviApp` slot** — *zero-code
      experiment, found 2026-08-19.* The vendor keeps its navigation app in
      `Settings.System."NaviApp"` as a **package name** (decompiled: `String naiPackge =
      Settings.System.getString(cr, "NaviApp")`), and its picker already enumerates us —
      logcat: `ScanNaviList: findAppListByPackage: packageName=io.github.miklergm.witscompanion`.
      So any control wired to "navigation" (the launcher shortcut, a steering `Map_key`, the
      physical map/menu button) could land on the companion:
      `adb shell settings put system NaviApp io.github.miklergm.witscompanion`
      (note the current value first, e.g. `settings get system NaviApp`, to restore it).
      It opens `MainActivity`, so it only lands in the Cockpit when **"Open the last layout
      when the app is opened"** is on — then the autostart brings the tiles up and the config
      yields to the back (the path fixed in `20e138c`). Caveat: this takes the navi slot away
      from Maps, so the launcher's own navi card/guide may change behaviour — try it, and set
      the package back if anything vendor-side depends on it.
      **Probably the better slot: `defPlayApp`** (the music app). Same mechanism —
      `LauncherViewModel.openMusic()` reads it and calls `getLaunchIntentForPackage(pkg)` — but it
      does *not* take the navigation slot away from Maps, and the panel genuinely is a media
      surface. Both slots are settable from the **vendor's own settings UI**, no adb:
      `DialogViews` writes them from lists built by `ScanDevList`/`ScanNaviList`, which enumerate
      every app with a LAUNCHER activity — so the companion shows up in both pickers.
- [ ] **Screenshots of the Cockpit for the public README** — grab a few on the head unit (`adb exec-out screencap -p > shot.png`): two-tile Cockpit with the map, the hidden/full-panel state, the rail with Settings lit. Check them for anything personal before publishing — a map centred on home, a track title, a visible SSID. They make the public repo far more legible than prose.
- [x] **Eyeball the right-hand control rail** — *verified 2026-08-07: proportions/tap-targets look
      right on the real panel; Settings top, Exit bottom.*
- [x] **Cockpit hide-toggle** — *verified 2026-08-07: two-tile → tap the active tile → app hidden
      (black left, panel right, no tile lit) → tap any tile → app back. No crash.*
- [x] **Settings opens as the Cockpit's LEFT TILE** — *verified on-car 2026-08-08* (`ad7aa5b` +
      `ed82d78`). The gear launches MainActivity freeform into the app/left tile (self-resizing its
      own task via `ensureConfigTileBounds`), panel stays right, top bar present, nothing
      un-windowed/finished. Open app → Cockpit; gear → config left + panel right; tap Maps → map
      returns. *(Replaced the full-screen-config approach, which raced with the ROM's missing
      `setTaskWindowingMode` + autostart `reassert` and closed the app to the vendor launcher.)*
      Minor: switching Maps back sometimes needs a second tap (the pre-existing autostart placement
      timing, not Settings-specific).
- [ ] **Exit un-windows the apps** — *`5671e0a` (verify).* Same root cause; Exit now clears freeform
      tiles then goes home. After Exit, re-opening navigation should be **fullscreen**, not windowed.
- [x] **Panel is a real right-hand tile, not full-screen** — *verified 2026-08-08* (`04e5e88`):
      cockpit task is `~[1560,99][2400,900]`, not `[0,0][2400,900]`; app-switching is smooth, offset gone.
- [x] **Hidden state hides the top strip** — *verified 2026-08-08* (`76ab0c6`): in the hidden/full-screen
      panel the vendor strip is gone (like the speedometer dashboard), swipe-from-top reveals it; two-tile keeps it.
- [x] **Top bar hide/reveal (general)** — *closed as understood, not as "do it".* Hiding works only
      when we are exclusively full-screen (the Cockpit's hidden state, verified 2026-08-08). In the
      two-tile state the bar belongs to the display, not to our window, and the system-owned
      `FORCE_FULLSCREEN` is the only global lever — an app cannot set it (§ Status bar). So the
      two-tile bar stays, by constraint rather than by choice.
- [~] **zlink resolution** — *root cause found 2026-08-03:* AA res = `panel × aaDensity/hiCarDensity`
      = `2400×900 × 160/300 = 1280×480`. **Lever: `persist.zj.dpi.aaDensity`** (unset→160); set
      240 → 1920×720. (`rw.zlink.resize=true` blanks the mirror — ruled out.) Next time: set it,
      reconnect AA, check `hu_AA_width` (§ zlink). Experiment only when a dropped mirror is OK.
- [x] **Volume: read the live `STREAM_MUSIC` level** — *done 2026-08-08:* at max (15/15), unmuted; no pinning needed (§ Volume).
- [ ] **Spotify no longer stretches to full width** after tiling (§ Layout placement).
- [ ] **No stale fullscreen app** on the first Cockpit open after churn (§ Layout placement).
- [x] **Media album art** — *verified 2026-08-03: cover art + progress + transport for a
      logged-in Spotify track ("Weak", Skunk Anansie), playing behind the map.*
- [ ] **Top bar** — decide hide/reveal, and whether the colour can be themed (§ Status bar).
- [ ] **Swap / split model** — settle the propagation behaviour interactively, on the car
      (§ Layout mental model).
- [x] **Switcher tiles toggle (hide, not only switch)** — *verified 2026-08-07; implemented 2026-08-06* (`LayoutEngine.hideFloatingApp`,
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

- [ ] **Verify the 2026-08-26 offline batch on the vehicle** — *nothing in it has been driven
      with; it should not be tagged until it has.* Most of it is invisible when it works, so
      the checks are mostly "nothing got worse":
  - **The split slider reads 65 / 35** on opening the layout settings, and still does after
      opening it three times without touching the slider. This is the one with a visible
      symptom and the easiest to confirm.
  - **Hiding the floating app still works** — tap the lit rail tile, the panel fills the
      display. It now goes through the reverse guard, so it can refuse; a "Refused:" toast
      while stationary would mean the guard is reading something wrong.
  - **The Cockpit still comes up and stays up.** `layout/verify -> ok` in the log as before,
      and no new `skipped:unverifiable:*` lines — one would mean task observation is failing
      on this ROM, which the old code hid by reporting an empty screen.
  - **The Debug screen still lists root tasks** with a count, not `UNREADABLE (...)`.
  - **Simulation mode moves nothing.** Turn it on from the Debug screen with the engine
      running: the dashboard should animate, and no layout should be applied and no hotspot
      switched on. Turning it off should leave the real state reading correctly.
  - **The rail has no Cockpit tile** (only reachable once the NaviApp slot points at us, so
      really a check for later).
  - **The layout refactor changed no behaviour** — the riskiest thing in the batch, since
      `apply()` was restructured around `LayoutPlanner`. The things to watch, in order:
    - **A tiled preset still lands as two tiles** with the same stagger; watch for one tile
      replacing the other, which is what a collapsed schedule would look like.
    - **A route survives a reassert.** Start navigation, trigger an automatic restore
      (ACC off/on), and confirm Maps keeps the route — the preserved-live path must still
      reposition rather than relaunch. `preserve_live -> no_relaunch` in the log.
    - **Switching the floating app still parks the old one** rather than leaving it floating
      over the new layout.
    - **A *tiled* preset whose apps are all missing** refuses without disturbing what is on
      screen. An **anchored** preset with a missing app deliberately does not refuse — the
      panel is the Cockpit and comes up either way, so it reports one tile rather than two.
      That asymmetry is the reason the old code refused twice, the second time after it had
      already started tearing the previous layout down.
- [ ] **Verify the audit fixes on the vehicle** — *deployed + partly confirmed on-car
      2026-08-20.* Build installed 09:46:57; capture in `capture-20260820-postaudit/`.
  - [x] **Reverse freshness — the 5 s window is safe.** The worry was that `wits.backcar`
      polls unreliably, which would start refusing automatic restores. Sampled 30×: `"0"`
      every time, **zero empty reads**, so the reading never goes stale in normal running.
      Confirmed end-to-end too — the post-update autostart restored the Cockpit and logged
      `layout/verify -> ok tiles=2`, and **22 log entries since the update contain no
      `blocked`, `refused`, `stale`, `rate_limited` or `error` results at all.**
  - [x] **Split/swap decoration reaches the UI** — every preset card renders "65/35", and the
      applied geometry measured `[0,99][1560,900]` + `[1560,99][2400,900]` = 0.65 exactly.
  - [x] **Preset identity survives** — the restored preset id is
      `anchored_com.google.android.apps.maps`, with no `_mirrored` suffix.
  - [x] **The night-mode "unset" case is real, not hypothetical** — `wits_night_mode` reads
      `null` on this unit, which is exactly the backup state whose undo used to fail silently.
  - [ ] **Reverse engage/release** — still unverified; needs the car actually put into reverse.
      Confirm the guard blocks on engage and unblocks within a poll interval on release.
  - [ ] **Layout cleanup gating** — not exercised. Note `lockWhilePlacing()` disables the cards
      for `PLACING_LOCK_MS` after a tap, so the rapid A-then-B switch the generation gate
      protects against may not be reachable from the UI at all; provoking it probably needs
      two applies driven programmatically rather than by tapping.
  - [ ] **Hotspot-only boot restore** — needs a reboot with the layout opt-in *off*.
- [x] **Verify the Cockpit panel after the ViewModel refactor** — *verified on-car 2026-08-20.*
      Panel came up as a tile at `[1560,99][2400,900]`, top strip present, no double padding,
      rail highlight correct on Maps, and the transport rules right (play enabled with no live
      session while prev/next greyed). The `ComponentActivity` base-class change caused none of
      the decor problems it risked. Original checklist below, kept for the next time it matters.
- [x] ~~**Verify the Cockpit panel after the ViewModel refactor**~~ — *offline-verified only;
      this is the one refactor that changes the panel's window handling.* `DashboardActivity`
      now extends `androidx.activity.ComponentActivity` instead of plain `Activity`, and its
      state comes from `CockpitViewModel` collected with `repeatOnLifecycle`.
      `ComponentActivity` is a thin subclass of `Activity` and adds no decor behaviour of its
      own, but the panel's insets, immersive handling and freeform self-resize are the most
      on-car-tuned code in the project and none of it can be exercised offline. Check, in this
      order, and stop at the first thing that looks wrong:
  - the panel comes up as a **tile** beside the map, not full-screen (the failure mode that
    took several sessions to get right the first time);
  - the vendor top strip is present beside a tile and hidden in the hidden/full state;
  - content is not double-padded at the top (the "everything slid too far down" report);
  - the rail highlight is correct immediately after a day/night flip and after a freeform
    resize — this is what the ViewModel is *meant* to improve, since the state now survives
    the recreation instead of being rebuilt;
  - media transport still enables/greys correctly, especially **play with no live session**,
    which must stay tappable so the media-key fallback is reachable.
- [x] **Day/night is a single switch — settled 2026-08-23.** A dark garage during the day
      (headlights on auto) brought the UI up in night mode and it went day about a minute
      after leaving, which looked like a head-unit ambient sensor. It is not: the sensor is in
      the *car*. Auto headlights drive `wits.ill`, which drives the backlight **and** the
      launcher skin together, and the minute of lag is auto-headlight hysteresis. A brief
      counter-report that the UI does not repaint with the lights was withdrawn — when the
      brightness drops automatically the launcher goes dark with it. See § docs/night-mode.md
      3 and 3.1; the history is in section 8. No launcher decompile needed.
- [ ] **Next-manoeuvre row: confirm the field mapping on-car** — *built 2026-08-23, extraction
      unverified.* The Cockpit now shows the next navigation instruction above the media card,
      read from the navigating app's own ongoing notification (there is no other route on this
      platform — see § docs/security.md 3.9). The plumbing, the panel rules and the parsing are
      unit-tested, but **which extra actually carries the manoeuvre is a guess** until seen on
      the device: it varies by app and by version, and some navigators post a fully custom
      layout with no usable text at all.
      With Maps actively navigating, capture what it really posts:

      ```sh
      adb -s <ip:5555> shell dumpsys notification --noredact \
        | grep -A 30 'com.google.android.apps.maps'
      ```

      Compare against `NavigationRepository.parse()`: the assumption is title=distance,
      text=manoeuvre, sub_text=ETA, with the title used as the instruction when it does not
      look like a distance. `NavigationRepository.lastRawExtras` holds the same data at runtime
      for the Debug screen, so the mapping can be corrected without a rebuild-and-guess loop.
      Also worth checking: whether the row updates often enough to be useful, and whether it
      survives the Cockpit's freeform resizes.
- [x] **A fullscreen app cannot be placed into its tile** — *fixed 2026-08-25, needs an
      on-car check.* Two defects, one screen. `place()` now removes a live task that is not
      freeform before launching, so the relaunch takes the bounds; and the post-apply
      verification compares tile *size* as well as centre, so it can no longer report `ok`
      over a full-display window.
      The verifier bug was arithmetic, not a missing case: expected centre 780, actual 1200,
      slack 780 — the 420 px difference fitted inside the tolerance. Notably it only affected
      the *wider* tile; the narrower panel was already caught by the centre test, which is why
      the log showed two tiles with one of them plainly wrong.
      **Verified on-car 2026-08-25.** A reinstall — the exact trigger, since installing kills
      the app and leaves Maps fullscreen — placed both tiles correctly with no intervention:
      Maps `[0,99,1560,900]`, panel `[1560,99,2400,900]`. Thursday the same sequence put Maps
      at full display over the panel.

      **The route-loss trade-off is accepted, not outstanding.** Removing the task ends what it
      was doing, a live route included — but it only fires when the task is not freeform, which
      in practice means start-up: after a boot or a reinstall, when there is rarely a route in
      progress. Decision 2026-08-25: leave it. If it does backfire in real use the trigger can
      be narrowed then, with an actual case to narrow it against rather than an imagined one.
- [x] **Save-to-collection button — done and verified on-car 2026-08-25.** The panel's ♡
      fires Spotify's own MediaSession custom action; tapping it flipped the session from
      "Add to collection" to "Remove from collection", i.e. the track was really saved.
      Mechanism, the state-dependent ids and the two wrong guesses are written up in
      § docs/media-session.md 5.1. No posture change: the notification route (and its cost)
      was avoided because MediaSession carried the action after all.
- [ ] **Capture the engine-off brightness jump as a live transition** — both endpoints are
      measured but the transition is not. The 2026-08-20 attempt failed by design error: the
      sequence returned the lights to **auto** before switching the engine off, and in daylight
      auto means off, so `wits.ill` was already `0`. Redo with the lights left **always-on** and
      the engine switched off directly, sampling `wits.ill` + `screen_brightness` throughout.
- [x] **Day/night: mechanism (3) settled 2026-08-20** — `ID8UG_SKIN_MODEL` flips
      `daytime` <-> `night` with the headlights, in the same second as `screen_brightness`
      moves 255 <-> 75. `wits_skin` is never written on this profile, which is why the
      decompiled SystemUI code pointed at the wrong key. See docs/night-mode.md 3.
      *(Original entry and its capture script below, kept as the method worked.)*
- [x] ~~**Day/night: settle mechanism (3), the launcher skin**~~ — *needs the car; everything
      else here is already answered.* "Night mode" on this unit is three independent things
      (§ docs/night-mode.md 3.2a): the **theme** is locked on and never moves, the **backlight**
      follows the headlights (confirmed both directions: `wits.ill=1` → `screen_brightness=75`,
      `wits.ill=0` → `255`), and the **launcher skin** is *reported* to follow them but was
      never sampled in both states. Run this with the engine on, once with the headlights on
      and once off, and diff the two:

      ```sh
      D=<ip:5555>          # head unit
      for state in lights-on lights-off; do
        read -r -p "Set headlights $state, then press enter..."
        { date -Is
          adb -s $D shell getprop wits.ill
          adb -s $D shell getprop wits.acc
          for k in screen_brightness screen_brightness_day screen_brightness_night \
                   screen_brightness_mode wits_skin ID8UG_SKIN_MODEL ID8_skin \
                   wits_night_mode UiSettings UiName; do
            echo "$k=$(adb -s $D shell settings get system $k | tr -d '\r')"
          done
          adb -s $D shell dumpsys uimode
        } > "daynight-$state.txt"
      done
      diff daynight-lights-on.txt daynight-lights-off.txt
      ```

      Whatever differs *is* the mechanism. `ID8UG_SKIN_MODEL` is the prime suspect — it read
      `daytime` with the lights off.
- [ ] **Engine-off brightness jump** — *cause identified offline, fix untested.* Engine off →
      headlights drop → `wits.ill=0` → BacklightControl writes
      `screen_brightness = screen_brightness_day` (**255**), so the screen goes to full
      brightness at night while you are still in the car. Not a `wits_night_mode` problem —
      that governs the locked theme. Levers in § docs/night-mode.md 3.3; the promising one is
      `wits_backlight_control_mode = 1` (documented time-based backlight control), whose
      `wits_backlight_start/end_hour` keys are **absent** here and may need writing as a set.
      Try lowering `screen_brightness_day` first as a one-line sanity check that the endpoint
      is really what gets written.
- [ ] **Cockpit brightness adjustments do not survive a headlight change** — *consequence of
      the above, not a bug in our writer.* `BrightnessController` writes `screen_brightness`;
      so does BacklightControl on every headlight transition, from `screen_brightness_day/
      _night`. Writing those endpoints instead would make an adjustment stick, but they are
      vendor-owned and the vendor UI edits them too — probe before adopting. Worth a line in
      the panel UI either way, so the behaviour is not mistaken for a failed write.
- [ ] **docs/night-mode.md §2 and §3.1 are stale** — §3.2/§3.2a/§3.2b record the
      re-measurement. The ILL branch is documented as gated on `UiSettings == "witstek8" &&
      wits_night_mode == 0`, both unset here; §3.1's force-night lock does still hold post-OTA.
      Two unexplained facts to chase: what sets `mNightModeLocked=true`, and what wrote
      `customStart=22:00 customEnd=06:00` into `UiModeManager` when the `wits_backlight_*`
      keys are absent. Re-derive from the v2.6.3 SystemUI rather than the pre-OTA decompile.

## ☐ Offline checklist (emulator / code / study)

Things doable now, without the car.

- [x] **Send `MainActivity` fully behind the Cockpit tiles** — *done + verified on-car
      2026-08-17.* Every path is covered: applying a layout from the config finishes it
      (`openCockpit` / `applyFromHome` / `applyPreset`), and a standalone open whose autostart
      brings the Cockpit up yields with `moveTaskToBack` (`20e138c`, MainActivity.yieldToCockpit).
      This is what removed the "full-screen settings visible behind the tiles" overlap.
- [x] **Study the vendor dashboard** — *done 2026-08-06, see § Status bar → "Source study".*
      The strip is a standard AOSP status bar, re-skinned; the dashboard does **not** hide it
      dynamically — every WitsLauncher activity just sets `FLAG_FULLSCREEN`. We do the modern
      equivalent, and it works **only where we are exclusively full-screen** (the Cockpit's
      hidden state). Beside a freeform app the bar is not ours to hide, and `FORCE_FULLSCREEN`
      is system-owned, so there is no app-side lever for the two-tile case.
- [x] **Study the vendor "Car Device" source** — *done 2026-08-19, from the decompiled launcher.*
      It is **not an app**: the launcher card is `Id8UgCarDevicesFragment`, whose click calls
      `LauncherViewModel.openCar()` → `UtilExport.sendKey(context, -78, 0)` — an **MCU key that
      switches the head unit to the OEM/car source**, not an activity launch. Same file confirms
      the settings entry we now use: `openSettings()` opens
      `com.wits.settings/com.wits.settings.SettingsActivity`.
      Wiring a Cockpit button for it is cheap — `WitsSourceController.switchToOem()` already
      exists, guarded and rate-limited, just unused by any UI. Two cautions before doing it:
      it hands the panel to the OEM image entirely (an Exit-class action, so it belongs beside
      Exit in the rail, not in the quick row where a mis-tap costs you the map), and § Source
      switching records an unresolved "OEM bounce" — Android → OEM → Android jumping around.
      Verify on-car before trusting it.
- [x] **Spotify top-left flicker** — *dropped 2026-08-09: no longer observed by the user, closing.*
- [x] **Cockpit right-hand control column** — *implemented 2026-08-03* (`785dd33`): main column
      (media + hotspot + brightness) + narrow rail with Settings (gear) top, the app switcher
      vertical, Exit (reset) pinned bottom; no more ScrollView/footer. Verified structurally on
      the emulator (clipped there by the freeform cascade). **Eyeball on the car** — see On-car
      checklist. Still to do: the "Cockpit" name check (§ UI).
- [x] **Second audit — the remaining findings, plus four smaller ones** — *fixed offline
      2026-08-26; **none of it has been driven with yet**.* Findings #4, #5 and #6 of the
      second review, which had been carried in conversation rather than written down, and the
      small items that had accumulated beside them. What each one was:
  - **A screen that was never read is not an empty screen** (`f75fe7a`, #4). `rootTasks()`
      returned an empty list for three different answers — cannot observe, reflection threw,
      genuinely nothing there. `verify()` read the first two as the third, so a reflection
      failure reported every tile missing and re-applied the layout, tearing down a correct
      Cockpit and any live route on a reading that never happened. `TaskObservation` and
      `LayoutVerdict` make the answers distinct; the three sites that legitimately degrade to
      empty say so by name (`tasksOrEmpty`).
  - **The other half of the Cockpit's app toggle had no guard** (`f35b943`, #5). `floatApp`
      goes through the engine "so the reverse guard, the rate limiter and the two-phase
      ordering all still apply"; `hideFloatingApp` moved the window and grew the panel to the
      full display past all three. Now a preflight refusal ahead of `cancelPending()`, and
      the panel records itself hidden only once the engine agreed. Same commit: the companion
      is filtered out of its own rail.
  - **Bounded ingestion for the exported probe** (`1f99ade`, #6). `BroadcastProbe` must be
      EXPORTED to hear the vendor at all, and dumps whatever it is sent. The walk had no
      limit on depth, count or size, so one broadcast near the Binder ceiling could expand
      into megabytes of text inside `onReceive` with 2000 events retained. Three axes now,
      all far above any real vendor payload, and a truncated capture says so.
  - **The bulk `getprop` timeout bounded nothing** (`7147351`). `waitFor(timeout)` sat in a
      `finally` around the read loop, so it could only run once the child closed stdout —
      the one thing a wedged child never does. `probe()` runs this from the constructor on
      the main thread, on a unit whose watchdog wipes to recovery at 80 s. The read moved to
      a throwaway thread with the deadline on the caller.
  - **Simulated telemetry was treated as evidence** (`d138f76`). `CarState.simulated` was
      read in exactly one place, a banner. A fabricated ACC OFF→ON edge switched on a real
      Wi-Fi hotspot (`restoreHotspotIfEnabled` consults no guard), and the simulator's
      11-in-90-seconds reverse phase blocked the user's own taps. The guards and the recovery
      coordinator now refuse it.
  - **The split slider lost a percent per visit** (`bffc63c`). `(0.65f - 0.25f) * 100` is
      39.999996, truncated to step 39 — so the settings showed "64 / 36" for a stored 0.65 and
      releasing the slider wrote 0.64 back.
  - **The release pinned a subject line, not a certificate** (`3d0d61c`). `grep CN=Android`
      passes for any self-signed key with that subject; a wrong keystore secret would have
      published an APK with none of the privileges the release claims. Now the full SHA-256,
      verified against the local key and a real signed build.
- [x] **Code audit — critical and high-priority findings** — *fixed offline 2026-08-20; **all
      five want on-car confirmation**, see On-car checklist.* An external review of the app,
      docs and deployment tooling. What it found and what was done:
  - **Stale telemetry authorised automatic actions** (`b869543`). `STALE` readings stayed
      "known", the polling merge retains the last value when a read fails, and the guard
      accepted any known `false` — so a last-seen `reverse=false` kept authorising automatic
      applies indefinitely after telemetry stopped. Split displayability from control-grade
      trust: positive reverse evidence counts however old, negative evidence decays to
      *unknown*. Same commit fixes a genuine lost-update race (polling on the worker thread vs.
      broadcasts on main, both read-copy-writing the snapshot) and stops an unauthenticated
      broadcast clearing a fresh property-backed positive.
  - **Superseded layout callbacks damaged the next layout** (`d5dc15a`). `parkStaleWindows`
      posted its `removeTask`/`applyWindow` bare — no generation check, no `RETRY_TOKEN` — so
      applying A then B let A's cleanup tear down B's tiles. Also: `apply()` reported the
      preset's window count as applied even when every window was skipped.
  - **Session recordings did not have the redaction docs promised** (`3d602ce`). Writes
      regexed the *serialized* JSON, so the key-aware list (ssid, password, phoneName, title)
      never ran — and a `CapturedExtra` hides its real key in a `name` field, which defeats a
      plain key walk too. Now a recursive, shape-aware walk. `stop()` is also transactional
      (it leaked a thread per session and could be exported mid-write).
  - **Assorted honesty bugs** (`f5545c5`). The New-layout Apply ignored the split and
      swap controls entirely; `withGeometry` renamed the preset it decorated, orphaning the
      stored last-applied id on a swap toggle; `openCockpit` recorded success on a guard
      refusal; night mode and brightness reported `Written` for writes that never landed;
      night-mode undo could not restore the original unset state; hotspot-only boot restore
      never ran; media progress extrapolated from the wrong timestamp; `deploy.sh` uninstalled
      the app (and its data) after *any* failed install.
  - **Lint** (`c62773a`). Both modules now clean. The ten API-30 `WindowMetrics` errors never
      affected the vehicle (Android 13; minSdk 29 is for the emulator) but the version handling
      was implicit — now an explicit gate with a real API-29 fallback.
  - **The architectural refactor — done, in four passes.** The review also recommended
      splitting `LayoutEngine` into planner/scheduler/executor/verifier, replacing the
      privileged boolean with per-operation capability interfaces, and a richer apply result.
      Deliberately deferred at the time as rewrites rather than fixes, then taken one at a
      time: capability interfaces (`WindowCapabilities.kt`), the verifier
      (`LayoutVerification.kt` — which was where the blind post-apply check turned out to be
      hiding), and finally the planner, scheduler and geometry (`6e754c7`). The engine is
      1009 → 847 lines and the part that shrank is the part that was reasoning; `LayoutPlanner`
      returns what an apply has decided as a value, so it can be asserted instead of watched
      on a vehicle. The richer apply result is **not** done and is not currently wanted:
      `Applied/Invalid/Refused` is what the callers act on.
- [ ] **Volume: read-only probe scaffolding** (verify-first), no active pinning yet (§ Volume).
- [x] **Refresh Brightness values** — *done 2026-08-06* (`25f9fe6`): the label refreshes on
      resume and observes `SCREEN_BRIGHTNESS`, so a system day/night change is reflected live.
- [x] **Play and Pause** button styles — *done 2026-08-06* (`25f9fe6`): fill is a desaturated,
      mid-tone `calm()` of the album/brand accent instead of the full (loud) colour.
- [x] **Floating apps** tile style — *done 2026-08-06* (`25f9fe6`): dropped the selected-tile
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

> **User note 2026-08-14:** tested informally, **unconvincing** — zlink seems to just **upscale**
> rather than negotiate a higher AA resolution. Verify the `aaDensity` lever *actually* changes
> `hu_AA_width` in the log before investing; if it doesn't move, the resolution is fixed elsewhere
> and this is a dead end. Low priority.

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
  - **[FIXED 2026-08-06 — content no longer tucks under the strip] (`38ad026`, DashboardActivity
    inset guard).** The intermittent zero top-inset was letting our own content slide under the
    bar — reproducible as "open the Cockpit, come back to Settings, and Settings flies under the
    top bar". Both fullscreen windows now floor the top with the framework `status_bar_height`:
    `MainActivity` (via `setDecorFitsSystemWindows(false)` + an insets listener that maxes the
    top with the dimen) and the Cockpit panel when `fillsDisplay()` (the hidden/full-screen
    state — a tile already sits below the bar, so it is left alone). Verify on-car; separate from
    whether we *hide* the bar (immersive) at all.
- **Source study 2026-08-06 (how the vendor dashboard treats the strip).** Read the decompiled
  vendor apps in `analysis/jadx/` + `research/diff/launcher-263`:
  - The strip **is a standard AOSP status bar**, just heavily re-skinned:
    `SystemUI/.../statusbar/phone/PhoneStatusBarView` (inset type `ITYPE_STATUS_BAR`, ~99 px).
    It observes ~20 `Settings.System` keys (`wits_night_mode`, `wits_skin`,
    `statusbar_right_bg_hidden`, `wits_hide_status_bar_volume_icon`, …) — those tweak its
    *contents/skin*, none hides the whole strip.
  - **The dashboard does NOT dynamically hide it.** Every WitsLauncher activity sets
    `getDecorView().setSystemUiVisibility(1280)` = `LAYOUT_FULLSCREEN|LAYOUT_STABLE` — i.e. it
    lays out **edge-to-edge under** the bar, it does **not** hide it. No vendor app (launcher,
    ZLink, MiniAA) uses `FLAG_FULLSCREEN` / `WindowInsetsController.hide(statusBars())`. So what
    reads as "the dashboard has no top bar" is the launcher drawing its own background under the
    strip (it blends in), and/or a per-car `FORCE_FULLSCREEN` factory config — not a trick we can
    borrow.
  - **`FORCE_FULLSCREEN` is a system-owned factory/per-car config**, confirmed by the sources:
    `CenterService` (`ConfigM701` sets it =1 for a car type, `ConfigYA82` reads it), `MiscService`
    (`App.isFullScreenConfig()` uses it + `PROP_CAR_TYPE` to pick the camera/carinfo layout),
    `WitsSettings` factory screen writes it. It is applied by system services, not per-app — and
    a normal app is framework-blocked from writing it (above). **Dead end for the companion.**
  - **The right lever, if we want it: standard per-window immersive on our OWN activity.** Since
    the strip is a normal `ITYPE_STATUS_BAR`, calling `setDecorFitsSystemWindows(window, false)`
    then `WindowInsetsControllerCompat(window, decor).hide(Type.statusBars())` (or the legacy
    `SYSTEM_UI_FLAG_FULLSCREEN`) hides it for our window — **no permission, no system write.**
    Caveat: per-window immersive typically only removes the bar when our activity is the *single
    fullscreen top* window; in the two-tile / freeform Cockpit the bar belongs to the whole
    display and immersive usually won't drop it. So it fits the **hidden/full-panel state** (the
    new hide-toggle → panel fullscreen) — request immersive there for a clean look — but not the
    normal two-tile layout. **On-car:** confirm immersive actually hides the strip in the
    fullscreen-panel state (freeform-vs-fullscreen bar policy varies by ROM).
- **[CONFIRMED on-car 2026-08-07 — the vendor car-dashboard proves the lever works.]** The
  speedometer/odometer "dashboard" the user meant is `com.wits.launcher/.launcher.view.DashboardActivity`
  (a `BaseThemeActivity` subclass — same package I studied before, but a different activity than the
  home fragments). It runs **with the top strip hidden**, full-screen. Mechanism, from the decompiled
  `BaseThemeActivity`: home screens call `setStatusBarTranslucent()` → `setSystemUiVisibility(1280)`
  (draw under, bar visible); the dashboard calls `setActivityFull()`/`setFullActivity(true)` →
  **`getWindow().setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)`** (the `1024` flag). Same base class,
  different method — that is why home shows the bar and the dashboard hides it. At runtime the
  `StatusBar` window carries an `insets_animation` leash and a top-swipe reveals it transiently then
  it auto-hides (transient-by-swipe) — i.e. modern Android renders `FLAG_FULLSCREEN` via the insets
  controller. **So the per-window immersive lever is confirmed to work on this exact unit.** For our
  panel: apply `FLAG_FULLSCREEN` (or `WindowInsetsControllerCompat.hide(statusBars())` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) **only in the hidden/full-screen state** (single top
  window). Depends on the panel-resize fix landing first so the hidden state is genuinely one
  full-screen window.
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

- **[FIX IMPLEMENTED 2026-08-07 `5671e0a` — verify on car] `setTaskWindowingMode` does not exist
  on this ROM (broke BOTH Settings and Exit).** Logcat on the head unit: `PrivWindowController: setTaskWindowingMode
  failed: NoSuchMethodException` → `privileged place failed ... setTaskWindowingMode returned false`
  for every tile. Confirmed in the decompiled framework: `android/app/IActivityTaskManager.java`
  has **no** `setTaskWindowingMode` — its task/windowing verbs are `moveTaskToRootTask(taskId,
  rootTaskId, toTop)`, `moveRootTaskToDisplay`, `removeRootTasksInWindowingModes(int[])`,
  `getRootTaskInfo(windowingMode, activityType)`, `getAllRootTaskInfos`. (`resizeTask(int,Rect,int)`
  *does* resolve — that is why placement works but un-freeform never did.) Consequences:
  - **Exit** (`unwindowTiles`) can't take tiles out of freeform → "window positions not reset";
    next app launch is still windowed.
  - **Settings** — the failed un-window leaves the freeform tiles drawing over `MainActivity`, and
    `place()`'s fallback (task exists but not visible → `launchIntoFreeform`) actually **launches an
    app** (observed: Spotify came to the front) → "everything flashes, apps change, Settings never
    opens".
  **Fix (offline):** replace the `setTaskWindowingMode` reflection with a verb that exists here.
  Candidate: `moveTaskToRootTask(taskId, <fullscreen rootTaskId>, toTop=true)` — get a fullscreen
  root task from `getAllRootTaskInfos()`/`getRootTaskInfo(WINDOWING_MODE_FULLSCREEN, …)`. Also make
  `place()`'s "not visible" branch **not** fall through to a launch when the intent was to un-window
  (pass the intent through, or guard the fallback). Re-verify Settings + Exit after. Same privileged
  layer as the panel-resize fix — do them together.
- **[FIX IMPLEMENTED 2026-08-07 `04e5e88` — verify on car] The Cockpit panel stayed a FULL-screen
  window instead of shrinking to the right complement tile.** Fix: `DashboardActivity` resizes its
  own task (`getTaskId()` + `resizeTaskTo` → `LayoutEngine.cockpitPanelBounds`) in `onResume` /
  `onConfigurationChanged`, since a relaunch's `setLaunchBounds` is ignored once the task exists.
  Original analysis: On the head unit (`dumpsys`): the panel
  task is `bounds=[0,0][2400,900]` while the floating app (Maps) is `[0,99][1560,900]`. The panel
  only lands at the complement (`1560–2400`) on a **fresh** launch (task does not exist yet →
  `ActivityOptions.setLaunchBounds` places it). Once the panel task already exists — after the
  autostart-fullscreen open, or after the hide-toggle grows it to full — `bringAnchorToFront`
  re-launches `DashboardActivity` with `setLaunchBounds`, but **launch bounds are ignored for an
  existing (singleTask) task**, so it keeps its previous (full) size. It still *renders* two-tile
  (the panel reserves its left strip, the app shows through), which is why it looked fine — but the
  full transparent-left panel drawing over everything is almost certainly the cause of:
  - **"apps switch strangely" (map/Spotify flicker)** — z-order/focus churn under the full panel;
  - **"Settings just flashes the map"** — the full panel + task-ambiguous un-window (two
    `witscompanion` tasks: MainActivity and the cockpit; `findTask(pkg)` can't tell them apart).
  **Fix (offline, careful):** reposition the panel via the privileged `resizeTask` path, not a
  relaunch — i.e. find the cockpit task specifically (add the top-activity *class* to
  `TaskSnapshot` so `DashboardActivity` can be told apart from `MainActivity`) and
  `setTaskWindowingMode(FREEFORM)` + `resizeTask(panelBounds)` it. Then the panel becomes a real
  `1560–2400` tile and the switch/flicker jank should go. Verify Settings/Exit again after.
  *(Inset + content-offset were fixed on-car 2026-08-07, `3632f47`; those were separate.)*
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
- **[ROOT CAUSE found on-car 2026-08-08 — "two-app tiled presets work very strangely"] Stale
  freeform tasks pile up when switching presets.** `dumpsys` after a few Maps+Spotify / Maps+Chrome
  switches: FIVE freeform tasks coexisting (maps, spotify, chrome, MainActivity, cockpit panel);
  logcat over ~40 s showed maps launched 4×, spotify 5×, chrome 2×. `parkStaleWindows` parks stale
  apps to **FULLSCREEN** for a tiled layout, but `setTaskWindowingMode` is absent on this ROM (and
  the stale task is often `visible=false`, so `place()` skips even trying) → the fallback
  `launchIntoFreeform` **re-launches** the app as yet another freeform tile instead of clearing it.
  Every switch accumulates windows → z-order/focus chaos. **Fix (offline):** on a tiled/preset
  apply, **remove** the freeform tasks not in the new layout — add `PrivilegedWindowController.removeTask(taskId)`
  (`IActivityTaskManager.removeTask(int)` exists here) and call it for stale tasks, instead of the
  park-to-fullscreen path that relaunches. Same ROM limitation as the Settings/Exit un-freeform.
  Logs saved to scratch `x-twoapps.log`.

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
  - **On-car check — ANSWERED 2026-08-20.** The framework `SCREEN_BRIGHTNESS` is the right
    channel: `screen_brightness` measurably tracks the headlights (75 with them on, 255 off),
    so the vendor writes the same key we do rather than owning a private MCU path. But the
    second half of the old question was the real one — it *does* "snap back on the next
    illumination event", because `BacklightControl` overwrites `screen_brightness` from
    `screen_brightness_day`/`_night` on every headlight transition. Our writes are correct and
    land; they simply do not survive the next transition. See § docs/night-mode.md 5.
- **No ambient-light sensor to auto-tune from.** These units have no photodiode; day/night
  comes from the car's illumination (headlight) line over CAN, not the clock. *(Correction
  2026-08-20: what the headlights move is the **backlight**, not the theme — the theme is
  locked on night on this unit and never flips. See § docs/night-mode.md.)* `SensorManager` has no `TYPE_LIGHT` on the hardware
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
- **Media widget doesn't attach to Spotify** — ⏳ *FIXED `196c8b8`, deployed, **button itself not yet
  tapped on-car***. Diagnosed 2026-08-17: nothing to do with notification access (the listener is
  enabled and bound). `dumpsys media_session` reported **0 sessions** — until the player has run
  there is no MediaSession, so `playPause()` returned early *and* the button was disabled anyway
  (a session-less player advertises no actions), which made any fallback unreachable. Now a media
  key is dispatched instead, and play stays enabled exactly in that case. Probed live: with 0
  sessions a `KEYCODE_MEDIA_PLAY` woke Spotify playing (`state=3`) **without** opening its UI.
  **Verify next drive**: cold boot → tap play → music starts, map stays on screen.
- **Surface the car's own Settings next to the Hotspot tile** — a shortcut to the vendor car
  settings from the Cockpit panel (like the Hotspot pill), and consider an Android-settings
  shortcut in the same row (earlier note). One quick-access row: Hotspot · Car settings · Android
  settings.
- **Tone down the play/pause colour** — ⏳ *DONE `90279aa`, **not yet deployed** (unit went offline
  mid-session).* The button and track title are now neutral; the accent survives only in the soft
  card wash and the thin progress line. Also fixed a latent day-mode contrast bug (white glyph on a
  light fill when no accent existed). **Deploy + eyeball next drive.**

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
- Dark theme follows the system setting — *but note this was never exercised in both states:
  `UiModeManager` is locked on night on this unit, so the app is always dark and the light
  palette has effectively never run on the car. See § docs/night-mode.md 2.*
