# Day / night mode

> **Read this first.** "Night mode" on this unit is **three independent mechanisms**, not one.
> They do not move together. Most of the confusion in the history of this document — and one
> wrong conclusion that survived several sessions — came from measuring one of them and
> reasoning about another.
>
> | | Mechanism | What the driver sees | Follows the headlights? |
> |---|---|---|---|
> | 1 | **Theme** — `UiModeManager` night bit | Dark theme in apps that honour it, including the companion | **No — locked on night**, never observed to move |
> | 2 | **Backlight** — `screen_brightness` | Panel brightness, 255 <-> 75 | **Yes**, confirmed both directions |
> | 3 | **Launcher skin** | Stock launcher goes black-ish | Reported yes, **not yet confirmed** |
>
> `wits_night_mode` — the key the companion writes — governs **(1) only**, and (1) is pinned
> on this unit. So the companion's day/night control may have no visible effect here. What
> the driver experiences as day/night is (2) and (3).

**Status of the original complaint.** This document opened as: *"on this BMW the headlights
are effectively always on, so the head unit sits in night mode during the day."* That was a
**vehicle setting, not a firmware problem** — switching the headlights from always-on to
**auto** resolved it, and night and tunnels now behave correctly `[RUNTIME]` 2026-08-20.
What remains is a different bug, at engine-off; see section 4.

---

## 1. What moves: the backlight `[RUNTIME]` 2026-08-20

Two samples, one session, both directions of the boolean:

| | `wits.ill` | `Settings.System.screen_brightness` | `dumpsys uimode` |
|---|---|---|---|
| headlights **on** | `1` | **75** | `mNightMode=2 (yes)` |
| headlights **off** | `0` | **255** | `mNightMode=2 (yes)`, `mNightModeLocked=true` |

The stored endpoints, from the same capture:

```
screen_brightness_day   = 255
screen_brightness_night = 75
screen_brightness       = 255   (lights off; 75 with them on)
screen_brightness_mode  = 0     (manual — no ambient sensor involved)
```

The illumination line drives the **panel backlight**, swapping `screen_brightness` between
the two stored endpoints. This is `BacklightControl` doing what its name says: section 7.2
read that class looking for a theme lock and found one, but the class's *other* job — the
day/night backlight swap — is the part the driver actually experiences as "night mode".

## 2. What does not move: the theme `[RUNTIME]` 2026-08-20

`UiModeManager` is pinned to night and stays there regardless of the headlights (see the
table above: `mNightMode=2` in both states, with `mNightModeLocked=true`). The lock is
attributed to `BacklightControl` calling `setNightMode(2)` unconditionally — see section 7.2,
whose conclusion still holds post-OTA.

Consequences:

- The companion's own palette reads the `uiMode` night bit (`DashboardActivity`), so **the app
  is always dark on this unit**, whatever the headlights do. Every screenshot in the README
  was taken in that state.
- Writing `wits_night_mode` may therefore produce **no visible change**. Whether the override
  beats `mNightModeLocked=true` is **untested** — the documented observer fires regardless of
  `UiSettings` (section 7.3), but nothing has confirmed it wins against the lock.

Two facts here are unexplained and worth chasing before relying on the override:
`mNightModeLocked=true` means something called `setNightMode` *with the lock flag*, and
`customStart=22:00 customEnd=06:00` means a custom schedule is configured in `UiModeManager`
even though the `wits_backlight_*` keys are absent from this unit.

## 3. The launcher skin — unconfirmed `[RUNTIME]` 2026-08-20

The stock launcher visibly goes black-ish with the headlights, reported by the driver but not
yet instrumented. Candidate keys, from the capture with the lights **off**:

| Key | Value with lights off | Note |
|---|---|---|
| `ID8UG_SKIN_MODEL` | `daytime` | prime suspect — the name and the value both fit |
| `wits_skin` | unset | section 7.2 shows SystemUI writing `0`/`1` here, but it is absent on this unit |
| `ID8_skin` | `blue` | unclear whether day/night related |

The lights-on sample was never taken, so nothing is confirmed. The backlog carries a runnable
two-state capture that settles it in one pass.

## 4. The problem that is actually left: the engine-off brightness jump

> Switch the engine off -> the car drops the headlights -> the screen goes to full brightness,
> at night, while you are still sitting in the car.

Fully explained by section 1: engine off -> headlights drop -> `wits.ill` goes `0` ->
BacklightControl writes `screen_brightness = screen_brightness_day` (**255**). Nothing to do
with `UiModeManager`, which stays locked on night throughout.

Candidate levers, none tried:

| Approach | Notes |
|---|---|
| Lower `screen_brightness_day` | Bluntest fix: if the day endpoint were not 255, the jump would not blind you. Costs real daytime brightness. Useful first as a *sanity check* that this endpoint is really what gets written. |
| `wits_backlight_control_mode = 1` | Documented as *time-based* backlight control with `wits_backlight_start/end_hour` (section 7.3). Would decouple the backlight from the headlights entirely — the right shape of fix. Those keys are **absent** here, so untested; may need all four written together. |
| Companion-driven | The app already observes ACC and illumination and owns a guarded, rate-limited brightness writer. Re-asserting the night brightness across an ACC-off transition fits what it already does — and unlike the `wits_night_mode` route it acts on the setting that demonstrably moves. |

Do **not** reach for `wits_night_mode` for this. It governs the theme; the theme is both
locked and not what changes. The signal to act on is `screen_brightness`.

## 5. Consequence for the companion's brightness control

`BrightnessController` writes `Settings.System.screen_brightness`. So does BacklightControl,
on **every headlight transition**, from endpoints the user never set through us. So a
brightness adjustment made in the Cockpit **survives only until the next headlight change**,
when it is overwritten with `screen_brightness_day`/`_night`.

"The brightness buttons don't stick" is this, not a failed write. Writing the *endpoints*
instead of (or as well as) the current value would make an adjustment persist — untested, and
they are vendor-owned keys the vendor UI also edits, so probe before adopting.

---

# Firmware evidence (decompiled)

Everything below is read from the vendor code. It describes what the firmware *can* do;
sections 1-3 record what this unit is observed to actually do, and the two do not fully agree
— see section 8.

## 7.1 The illumination signal

```java
// McuManager.java:3402-3416   [CODE]
public void updateIll(int value) {
    if (this.mIll != value) {
        this.mIll = (byte) value;              // byte, 0/1 only
        updateDeepDarkMode();
        UtilExport.setProp(UtilExport.PROP_CAN_ILL, this.mIll);   // sysprop "wits.ill"
        UtilExport.sendIllStatus(this.mContext, this.mIll);        // com.can.ACTION_ILL_INFO
        ...
        ZlinkMessage.sendIllStatus(this.mContext, value);
    }
}
```

- `mIll` is a **`byte` holding 0 or 1** — a pure boolean. `[CODE]`
- There is **no** BMW ambient-light value, **no** KOMBI brightness, **no** terminal
  58g/58d analog level parsed anywhere in CenterService. `[NOTFOUND]`
- Published as sysprop `wits.ill` and broadcast `com.can.ACTION_ILL_INFO` (extra
  `status`). `[CODE]` `UtilExport.java:621-626`

This part is confirmed live: the companion receives `com.can.ACTION_ILL_INFO` and parses it
correctly (`Illumination(on=true, raw=1)` in the event log), and `wits.ill` tracks the
headlights. `[RUNTIME]`

## 7.2 The consumer: SystemUI

```java
// PhoneStatusBarView.java:616-634   [CODE]
static void setThemeByIll(PhoneStatusBarView v) {
    String s = SystemProperties.get("wits.ill");
    if (TextUtils.isEmpty(s)) return;
    int i = Integer.parseInt(s);
    if (i == 0) {
        v.mUiModeManager.setNightMode(1);                 // UiModeManager.MODE_NIGHT_NO
        setAmapAutoDayNightMode(1, v.mContext);
        Settings.System.putInt(cr, "wits_skin", 1);
    } else if (i == 1) {
        v.mUiModeManager.setNightMode(2);                 // MODE_NIGHT_YES
        setAmapAutoDayNightMode(2, v.mContext);
        Settings.System.putInt(cr, "wits_skin", 0);
    }
}
```

The illumination path only runs when the master key allows it:

```java
// PhoneStatusBarView.java:672-680   [CODE]
if (action.equals("com.can.ACTION_ILL_INFO")) {
    if ("witstek8".equals(Settings.System.getString(cr, "UiSettings"))
        && Settings.System.getInt(cr, "wits_night_mode", -1) == 0) {
        setThemeByIll(...);
    }
}
```

**This path is not live on this unit.** Both `UiSettings` and `wits_night_mode` are unset, so
the gate is false — consistent with the theme never moving (section 2). `[RUNTIME]`

And the theme lock, from the same class family:

```java
// BacklightControl.java:57-61   [CODE]
String uiSettings = Settings.System.getString(cr, "UiSettings");
if (!"witstek8".equals(uiSettings)) {
    ((UiModeManager) ctx.getSystemService(UiModeManager.class)).setNightMode(2);
}
```

`UiSettings` is unset, so the guard passes and CenterService calls `setNightMode(2)`
unconditionally at start — consistent with `mNightModeLocked=true`, before **and** after the
OTA. `[HYP-strong]` + `[RUNTIME]`

## 7.3 The master key: `wits_night_mode`

`Settings.System` key, observed by SystemUI:

```java
// PhoneStatusBarView.java:1245-1270   [CODE]
cr.registerContentObserver(Settings.System.getUriFor("wits_night_mode"), false,
    new ContentObserver(new Handler()) {
        public void onChange(boolean z, Uri uri) {
            int i2 = Settings.System.getInt(cr, "wits_night_mode", -1);
            if (i2 == 0)      setThemeByIll(...);                       // follow headlights
            else if (i2 == 1) WitsClock.handleThemeChangeByTime(ctx,false); // by schedule
            else if (i2 == 2) { mUiModeManager.setNightMode(2); ... }   // force NIGHT
            else if (i2 == 3) { mUiModeManager.setNightMode(1); ... }   // force DAY
        }
    });
```

| Value | Behaviour | Companion label |
|---|---|---|
| `0` | Follow illumination (`wits.ill`) | *Follow headlights* |
| `1` | Follow Witstek time schedule (`wits_backlight_*_hour/minute`) | *Follow Witstek schedule* |
| `2` | Force **night** | *Force night* |
| `3` | Force **day** | *Force day* |

`-1` = unset — which is its state on this unit, so **none of these branches is what holds it
in night mode**. That is the lock in section 7.2.

> The `wits_night_mode` **observer fires regardless of `UiSettings`**, unlike the ILL branch.
> Writing `2`/`3` should therefore reach `setNightMode` even on this profile. `[CODE]` —
> still needs `[RUNTIME]` confirmation, and specifically confirmation that it wins against
> `mNightModeLocked=true`.

Constant declarations: `UtilSetting.java:264` `WITS_NIGHT_MODE = "wits_night_mode"`,
`FactoryKey.java:52` `KEY_MODE_NIGHT = "wits_night_mode"` `[CODE]`.

### Related keys

| Key | Purpose | Evidence |
|---|---|---|
| `wits_backlight_control_mode` | `1` = time-based backlight control | `BacklightControl.java:66-74` `[CODE]` |
| `wits_backlight_start_hour/_minute` | schedule start | `BacklightControl.java:177-182` `[CODE]` |
| `wits_backlight_end_hour/_minute` | schedule end | as above |
| `wits_skin` | launcher/SystemUI skin (0 dark / 1 light) | `BacklightControl.java:113-119` `[CODE]` |
| `wits_deep_dark_mode`, `_color` | extra dimming layer | `GlobalDef.java:272-296` `[CODE]` |
| `UiSettings` | active UI profile id (e.g. `witstek8`) | `PhoneStatusBarView.java:678` `[CODE]` |

---

## 8. Runtime history — superseded conclusions

Kept because the measurements are real and the reasoning errors are instructive. **Do not
cite these as current.**

**Pre-OTA `[RUNTIME]`.** `wits_night_mode` unset, `UiSettings` unset, `UiName` =
`BM_EVOID9_701GEN`, `dumpsys uimode` = `mNightMode=2 (yes)`, `mNightModeLocked=true`. The
conclusion drawn — that `BacklightControl`'s unconditional `setNightMode(2)` is what holds the
unit in night mode — **still holds** and is now section 7.2.

**Post-OTA `[RUNTIME]` 2026-08-20.** `UiName` changed to `BMW_ID8_UI`; everything else above
unchanged. The theme lock survived the OTA.

**Superseded: "day/night now tracks the illumination line."** Briefly concluded mid-session
from a single sample — `ill=1` with `night=yes` — while the other state was never checked.
`ill=0` with `night` still `yes` disproved it within minutes.
*One direction of a boolean proves nothing when the other state is pinned.*

**Superseded: "headlights off alone does not cause the engine-off flip."** Correct about
`mNightMode`, wrong about the phenomenon, because `mNightMode` is the one mechanism that never
moves here. The backlight was the signal to watch. This is what motivated the three-mechanism
split at the top.

**Superseded: "Force day (`wits_night_mode = 3`) is the fix."** That answered the original
always-on-headlights framing, which the driver solved at the vehicle instead. It is the wrong
lever for the engine-off jump, and its effect against the theme lock is untested.
