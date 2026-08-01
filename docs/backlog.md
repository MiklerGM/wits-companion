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

## Cockpit / panel polish

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
