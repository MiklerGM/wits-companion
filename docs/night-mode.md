# Day / night mode

The user's problem: on this BMW the headlights are effectively always on, so the head
unit sits in night mode during the day, while the factory KOMBI still reacts to real
ambient light.

**Cause:** Android's day/night here is driven by a **boolean illumination line**, not by
a light sensor or an analog ambient value.

**Fix:** the firmware already has a master override — `wits_night_mode` — writable with
plain `WRITE_SETTINGS`. No root, no firmware change.

---

## 1. The illumination signal

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

---

## 2. The consumer: SystemUI

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

---

## 3. The master key: `wits_night_mode`

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
| `0` | Follow illumination (`wits.ill`) — the problematic default | *Follow headlights* |
| `1` | Follow Witstek time schedule (`wits_backlight_*_hour/minute`) | *Follow Witstek schedule* |
| `2` | Force **night** | *Force night* |
| `3` | Force **day** | *Force day* |

`-1` = unset.

> Important: the `wits_night_mode` **observer fires regardless of `UiSettings`**, unlike
> the ILL branch which additionally requires `UiSettings == "witstek8"`. Writing `2`/`3`
> should therefore deterministically force night/day even if the BMW profile uses a
> different `UiSettings` value. `[CODE]` — needs `[RUNTIME]` confirmation.

Constant declarations: `UtilSetting.java:264` `WITS_NIGHT_MODE = "wits_night_mode"`,
`FactoryKey.java:52` `KEY_MODE_NIGHT = "wits_night_mode"` `[CODE]`.

### Related keys (read-only for us)

| Key | Purpose | Evidence |
|---|---|---|
| `wits_backlight_control_mode` | `1` = time-based backlight control | `BacklightControl.java:66-74` `[CODE]` |
| `wits_backlight_start_hour/_minute` | schedule start | `BacklightControl.java:177-182` `[CODE]` |
| `wits_backlight_end_hour/_minute` | schedule end | as above |
| `wits_skin` | launcher/SystemUI skin (0 dark / 1 light) | `BacklightControl.java:113-119` `[CODE]` |
| `wits_deep_dark_mode`, `_color` | extra dimming layer | `GlobalDef.java:272-296` `[CODE]` |
| `UiSettings` | active UI profile id (e.g. `witstek8`) | `PhoneStatusBarView.java:678` `[CODE]` |

---

## 3.1 Runtime reality on this unit — the cause is different

Captured before the OTA `[RUNTIME]`:

| Key | Value |
|---|---|
| `Settings.System["wits_night_mode"]` | **not set** |
| `Settings.System["UiSettings"]` | **not set** |
| `Settings.System["UiName"]` | `BM_EVOID9_701GEN` |
| `dumpsys uimode` | `mNightMode=2 (yes)`, **`mNightModeLocked=true`**, `mComputedNightMode=true` |

Because `wits_night_mode` is unset, **none of the 0/1/2/3 branches above can be what
holds this unit in night mode.** The likely cause is CenterService instead:

```java
// BacklightControl.java:57-61   [CODE]
String uiSettings = Settings.System.getString(cr, "UiSettings");
if (!"witstek8".equals(uiSettings)) {
    ((UiModeManager) ctx.getSystemService(UiModeManager.class)).setNightMode(2);
}
```

`UiSettings` is unset, so the guard passes and CenterService calls `setNightMode(2)`
unconditionally at start — consistent with `mNightModeLocked=true`. `[HYP-strong]`

**Implication for the companion:** writing `wits_night_mode = 3` should still take effect
(the observer fires on any change regardless of `UiSettings`), but it is now a
*counter-measure* against an unconditional force-night, not a mode selector. Verify that
it survives an ACC cycle — CenterService may re-assert night mode on the next start.

---

## 3.2 Runtime reality **after** the OTA `[RUNTIME]` 2026-08-20

Re-measured on the unit (v2.6.3, engine running, headlights switched to **auto**):

| Key / probe | Value |
|---|---|
| `Settings.System["wits_night_mode"]` | **not set** (still) |
| `Settings.System["UiSettings"]` | **not set** (still) |
| `Settings.System["UiName"]` | `BMW_ID8_UI` — *changed* from `BM_EVOID9_701GEN` |
| `Settings.System["ID8UG_SKIN_MODEL"]` | `daytime` |
| `Settings.System["screen_brightness_night"]` | `75` |
| `dumpsys uimode` | `mNightMode=2 (yes)`, **`mNightModeLocked=true`**, `mComputedNightMode=true`, `customStart=22:00 customEnd=06:00` |

**§3.1 still holds: night mode is locked on.** Two samples minutes apart in one session:

| `wits.ill` | `dumpsys uimode` |
|---|---|
| `1` (headlights on) | `mNightMode=2 (yes)` |
| `0` (headlights off) | `mNightMode=2 (yes)`, `mNightModeLocked=true` |

So the ILL branch is **not** observably driving `UiModeManager` here — the lock from
§3.1's `BacklightControl` path survives the OTA. The `UiName` change is real but did not
alter this. `[RUNTIME]`

> Method note: an earlier reading of this session saw only `ill=1 → night=yes` and briefly
> concluded that day/night was tracking the headlights. One direction of a boolean proves
> nothing when the other state is pinned — `ill=0` was the sample that settled it. Take both
> directions before claiming a signal drives anything.

Two facts here are new and unexplained, and both are worth chasing before touching the
override: `mNightModeLocked=true` means something called `setNightMode` with the lock flag,
and `customStart=22:00 customEnd=06:00` means a custom schedule is configured in
`UiModeManager` even though the `wits_backlight_*` keys are absent.

## 3.2a Three separate day/night mechanisms `[RUNTIME]` 2026-08-20

The single biggest source of confusion in this document — and in the sections above — is
that "night mode" on this unit is **at least three independent things**. They do not move
together, and only one of them is `wits_night_mode`'s business.

| # | Mechanism | What it changes | Keys | State on this unit |
|---|---|---|---|---|
| 1 | **Theme** (`UiModeManager` uiMode night bit) | Dark theme in apps that honour it — including the companion's own palette | `wits_night_mode`, `UiSettings` | **Locked on night.** `mNightMode=2`, `mNightModeLocked=true`. Never observed to move. |
| 2 | **Backlight** | Panel brightness | `screen_brightness`, `screen_brightness_day` (255), `screen_brightness_night` (75) | **Follows the headlights.** Confirmed in both directions — see §3.2b. |
| 3 | **Launcher skin** | The stock launcher going black-ish | `ID8UG_SKIN_MODEL` (`daytime` observed), `wits_skin` (unset), `ID8_skin` (`blue`) | **Reported to follow the headlights**; not yet sampled in both states. |

What the driver calls "night mode" is (2) and (3). What `wits_night_mode` controls is (1),
which is pinned and invisible to them. Every earlier conclusion in this document that
reasoned from `dumpsys uimode` alone was reading the one mechanism that does not move.

Mechanism (3) is unconfirmed: `ID8UG_SKIN_MODEL` read `daytime` with the headlights **off**,
which is consistent with it flipping, but the lights-on sample was never taken. See the
backlog for the two-state capture that settles it.

## 3.2b The mechanism: it is the **backlight**, not the theme `[RUNTIME]` 2026-08-20

The user's report — *"switching the headlights from auto to always-on visibly changes
day/night"* — looked at first like it contradicted §3.2's locked `mNightMode`. It does not.
They are two different mechanisms, and only one of them moves:

| | `wits.ill` | `Settings.System.screen_brightness` | `dumpsys uimode` |
|---|---|---|---|
| headlights **on** | `1` | **75** | `mNightMode=2 (yes)` |
| headlights **off** | `0` | **255** | `mNightMode=2 (yes)`, `mNightModeLocked=true` |

And the stored endpoints, from the same capture:

```
screen_brightness_day   = 255
screen_brightness_night = 75
screen_brightness       = 255   (with the lights off; 75 with them on)
screen_brightness_mode  = 0     (manual — no ambient sensor involved)
```

So the illumination line drives the **panel backlight**, swapping `screen_brightness`
between the two stored endpoints. That is the change you see. The dark *theme* is constant
because `UiModeManager` is pinned (§3.2), which is also why every companion screenshot is
dark regardless of the headlight state — the app's palette reads the `uiMode` night bit,
and that bit never moves on this unit.

This is `BacklightControl` doing what its name says. §3.1 read that class looking for the
theme lock and found one; the class's *other* job — the day/night backlight swap — is the
part the user actually experiences as "night mode".

### Consequence for the companion's brightness control

`BrightnessController` writes `Settings.System.screen_brightness`. So does BacklightControl,
on every headlight transition, from values the user never set through us. **A brightness
adjustment made in the Cockpit survives only until the next headlight change**, at which
point it is overwritten with `screen_brightness_day`/`_night`. Anyone reporting "the
brightness buttons don't stick" is seeing this, not a bug in our writer.

Writing `screen_brightness_day` / `screen_brightness_night` instead of (or as well as)
`screen_brightness` would make an adjustment persist — untested, and it changes a vendor
setting the vendor UI also owns, so probe before adopting.

## 3.3 The problem that is actually left: the engine-off flip `[RUNTIME]` 2026-08-20

With the headlights on **auto** the original complaint is gone — night and tunnels behave
correctly. The user reports what remains:

> Switch the engine off → the car drops the headlights → the unit flips to **day** mode and
> the screen goes bright, at night, while you are still sitting in the car.

**Explained by §3.2b, and it is the backlight.** Engine off → the car drops the headlights →
`wits.ill` goes `0` → BacklightControl writes `screen_brightness = screen_brightness_day`
(255). The screen goes to full brightness, at night, while you are still in the car. Nothing
to do with `UiModeManager`, which stays locked on night throughout.

The earlier note here — "headlights off alone does not cause the flip" — was reasoning from
`mNightMode` only, and `mNightMode` is the one thing that never moves on this unit. The
backlight was the signal to watch.

Candidate levers, none yet tried:

| Approach | Notes |
|---|---|
| Lower `screen_brightness_day` | The bluntest fix: if the "day" endpoint were not 255, the engine-off jump would not blind you. Costs real daytime brightness, so probably too blunt on its own. |
| `wits_backlight_control_mode = 1` | Documented as *time-based* backlight control, with `wits_backlight_start/end_hour`. Would decouple the backlight from the headlights entirely — the right shape of fix. Those keys are **absent** on this unit, so this is untested and may need all four written together. |
| `wits_night_mode = 1` (schedule) | Sibling of the above for the *theme*; irrelevant while the theme is locked, but worth understanding. Note `UiModeManager` already carries `customStart=22:00 customEnd=06:00` from somewhere — find out what wrote it. |
| Companion-driven | The app already observes ACC and illumination and owns a guarded, rate-limited brightness writer. Re-asserting the night brightness across an ACC-off transition fits what it already does — and unlike the `wits_night_mode` route it acts on the setting that demonstrably moves. |

Do **not** reach for `wits_night_mode` at all for this problem. It governs the theme, and the
theme is both locked and not what changes. The signal to act on is `screen_brightness`.
