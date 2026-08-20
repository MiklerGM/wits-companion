# Car state — typed signal schema

Every signal the companion may consume, with its transport, type, and **honest**
status. Units and scaling are **`UNKNOWN` unless proven** — do not invent them.

Source of truth for names: `analysis/jadx/CenterService/sources/com/middle/UtilExport.java`
`[CODE]`.

---

## 1. Runtime status vocabulary

| Status | Meaning |
|---|---|
| `UNKNOWN` | Declared in firmware code; never observed on this vehicle |
| `OBSERVED` | Seen changing on the device, semantics not yet pinned down |
| `VERIFIED` | Seen, and mapping/units confirmed against a known physical state |
| `UNSUPPORTED` | Confirmed absent/never populated on this BMW profile + MCU |

> **Updated 2026-07-31 from a live capture.** Several signals declared in the firmware
> are **permanently empty on this BMW profile**, and two arrive in a packed form that the
> catalogue did not anticipate.

### What this profile actually publishes `[RUNTIME]`

| Property | Observed | Note |
|---|---|---|
| `wits.acc` | `1`, but **empty for a whole session** on 2026-08-20 even with the engine running | **unreliable** — see below |
| `wits.backcar` | `0` | works |
| `wits.brake` | `0` | works |
| `wits.ill` | `0` | works |
| `wits.source` | `7` throughout, **including while reversing** | works, but is *not* reverse evidence — see below |
| `can.speed` | `0` (stationary) | works; **`car.speed` is empty** |
| `vendor.can.angle` | `0` | works |
| `can.radar` | `"2:0:0:4:0:0:0:0"` | **packed string**, decoding `[HYP]` |
| `can.door` | `"ffffff80"` | **bitmask**, decoding `[HYP]` |
| `car.signal`, `car.type` | `1`, `0` | meaning unknown |
| `wits.mcu.version` | `LFE.ZHTD.BM.…` | works |

### Two signals that do not behave as the table above implies `[RUNTIME]` 2026-08-20

**`wits.source` never reports the reverse camera.** Sampled twice a second across a full
P -> R -> N -> D -> P sequence, `wits.source` stayed `7` (LAUNCHER) for the entire time the
reverse camera was up, while `wits.backcar` went `1` and back to `0`. So on this vehicle
reverse detection rests **entirely** on `wits.backcar`; the `source == BACKCAR` branch in
`CarState.reverseActive` is dead weight here. It is kept because it costs nothing and other
profiles may populate it — but nothing should be written that *relies* on the redundancy.

**`wits.acc` is not reliably populated.** It read `1` on 2026-07-31 and again on the morning
of 2026-08-20, but was **empty for an entire six-minute session that evening**, engine running,
across ignition-on, a gear sequence and a shutdown. Empty means `UNKNOWN`, not `0`, so nothing
false is displayed — but any behaviour gated on ACC simply never fires when it is in that
state, which includes the ACC-based autostart trigger. Cause unknown; worth a `getprop wits.acc`
check at the start of any session that depends on it.

### Permanently empty on this profile `[RUNTIME]`

`car.speed` · `car.rate` (**no RPM**) · `car.lane` · `car.turn.lr` ·
`wits.battery.vol` (**no voltage**) · all `vendor.can.radar0..7` ·
all `vendor.can.cardoor1..5` · all `vendor.can.light0..2` (**no indicators**) ·
`wits.mcu.can.version`

**Consequence for the app.** Polling was trimmed to the signals that exist
(`WitsProperties.POLLED`); the empty ones moved to `EMPTY_ON_THIS_PROFILE` and are only
touched by Signal Explorer snapshots. `CarState` now carries `radarRaw` / `doorsRaw`
as **raw strings** instead of decoded per-sensor values, and `anyDoorOpen` deliberately
returns `null` rather than guessing at the mask.

**Design decision (2026-07-31).** The vehicle's own cluster and HUD already display
speed, doors and PDC, and reverse switches the screen to the OEM view which shows PDC
anyway — the trigger is the gearbox, not the sensors. The companion therefore does not
try to reproduce that data. Decoding `can.radar` / `can.door` is deferred; if it is ever
wanted, capture with the Signal Explorer while opening each door in turn.

**Presence of a property or broadcast in firmware code does not prove that this BMW
profile and MCU populate it.** `[HYP]`

---

## 2. Availability model

The app never renders a missing signal as `0`. See `SignalValue<T>` in
`carstate/SignalValue.kt`:

| `Availability` | Meaning |
|---|---|
| `UNKNOWN` | Never received |
| `OBSERVED` | Received at least once, not yet validated |
| `VALID` | Received and within the declared valid range |
| `STALE` | Last update older than `staleTimeoutMs` |
| `UNSUPPORTED` | Marked unsupported for this device |
| `INVALID` | Received but failed range/parse validation (`rawValue` retained) |

---

## 3. Property signals

Read via `PropertyReader` (reflection → JNI → `getprop` fallback, see
`known-unknowns.md` §5). Polling is throttled; **no tight loop**.

| Property | Java type on wire | Parsed as | Unit | Valid range | Default/initial | Update rate | Reset behaviour | Stale timeout | Status |
|---|---|---|---|---|---|---|---|---|---|
| `wits.acc` | String | `Int` (0/1) | boolean | 0–1 | absent | on change | — | 30 s | `UNKNOWN` |
| `wits.acc.on.time` | String | `Long` | UNKNOWN | UNKNOWN | absent | on ACC on | — | 30 s | `UNKNOWN` |
| `wits.backcar` | String | `Int` (0/1) | boolean | 0–1 | `0` | on change | — | 10 s | `UNKNOWN` |
| `wits.brake` | String | `Int` (0/1) | boolean | 0–1 | absent | on change | — | 30 s | `UNKNOWN` |
| `wits.ill` | String | `Int` (0/1) | boolean | 0–1 | absent | on change | — | 60 s | `UNKNOWN` |
| `wits.battery.vol` | String | `Float`? | **UNKNOWN** (V? V×10?) | UNKNOWN | absent | periodic? | — | 60 s | `UNKNOWN` |
| `car.speed` | String | `Int`/`Float` | **UNKNOWN** (km/h? ×10?) | UNKNOWN | absent | high | — | 5 s | `UNKNOWN` |
| `can.speed` | String | `Int` | **UNKNOWN** (duplicate of above?) | UNKNOWN | absent | high | — | 5 s | `UNKNOWN` |
| `car.rate` | String | `Int` | **UNKNOWN** (RPM?) | UNKNOWN | absent | high | — | 5 s | `UNKNOWN` |
| `car.signal` | String | `Int` | UNKNOWN | UNKNOWN | absent | UNKNOWN | — | 30 s | `UNKNOWN` |
| `car.lane` | String | `Int` | UNKNOWN | UNKNOWN | absent | UNKNOWN | — | 30 s | `UNKNOWN` |
| `car.turn.lr` | String | `Int` | UNKNOWN (bitmask?) | UNKNOWN | absent | on change | — | 10 s | `UNKNOWN` |
| `car.type` | String | `String`/`Int` | profile id | UNKNOWN | absent | static | — | ∞ | `UNKNOWN` |
| `vendor.can.light0` | String | `Int` (0/1) | turn left | 0–1 | absent | on change | — | 10 s | `UNKNOWN` |
| `vendor.can.light1` | String | `Int` (0/1) | turn right | 0–1 | absent | on change | — | 10 s | `UNKNOWN` |
| `vendor.can.light2` | String | `Int` (0/1) | hazard | 0–1 | absent | on change | — | 10 s | `UNKNOWN` |
| `vendor.can.radar0..7` | String | `Int` | **UNKNOWN** (distance? level?) | UNKNOWN | absent | high in PDC | — | 5 s | `UNKNOWN` |
| `vendor.can.cardoor1` | String | `Int` (0/1) | front-left door | 0–1 | absent | on change | — | 60 s | `UNKNOWN` |
| `vendor.can.cardoor2` | String | `Int` (0/1) | front-right door | 0–1 | absent | on change | — | 60 s | `UNKNOWN` |
| `vendor.can.cardoor3` | String | `Int` (0/1) | rear-left door | 0–1 | absent | on change | — | 60 s | `UNKNOWN` |
| `vendor.can.cardoor4` | String | `Int` (0/1) | rear-right door | 0–1 | absent | on change | — | 60 s | `UNKNOWN` |
| `vendor.can.cardoor5` | String | `Int` (0/1) | tailgate/hood | 0–1 | absent | on change | — | 60 s | `UNKNOWN` |
| `vendor.can.angle` | String | `Int` | **UNKNOWN** (deg? deg×10? signed?) | UNKNOWN | absent | high | — | 5 s | `UNKNOWN` |
| `can.door` | String | `Int` | UNKNOWN (bitmask?) | UNKNOWN | absent | on change | — | 60 s | `UNKNOWN` |
| `can.radar` | String | `Int` | UNKNOWN | UNKNOWN | absent | high | — | 5 s | `UNKNOWN` |
| `can.turn.lr` | String | `Int` | UNKNOWN | UNKNOWN | absent | on change | — | 10 s | `UNKNOWN` |
| `wits.source` | String | `Int` | `AppMode` id | see source table | absent | on change | — | ∞ | `UNKNOWN` |
| `wits.top.package` | String | `String` | package name | — | absent | on change | — | ∞ | `UNKNOWN` |
| `wits.top.activity` | String | `String` | component | — | absent | on change | — | ∞ | `UNKNOWN` |
| `wits.mcu.version` | String | `String` | version | — | absent | static | — | ∞ | `UNKNOWN` |
| `wits.mcu.can.version` | String | `String` | version | — | absent | static | — | ∞ | `UNKNOWN` |

Property name constants: `UtilExport.java:112-155` and `:160-186` `[CODE]`.

---

## 4. Broadcast signals

Received by `WitsBroadcastReceiver`, registered **`RECEIVER_EXPORTED`** at runtime — it has to be, because the senders are other processes and a NOT_EXPORTED receiver is never delivered their broadcasts. See security.md §3.2 for what replaces the lost isolation
(never in the manifest — see `security.md`).

| Action | Extra | Extra type | Meaning | Sender evidence | Status |
|---|---|---|---|---|---|
| `com.can.ACTION_ACC_INFO` | `status` | `Int` | ACC on/off | `UtilExport.java:601-605` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_ILL_INFO` | `status` | `Int` 0/1 | Illumination (headlights) | `UtilExport.java:621-626` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_REVSTATUS` | `REVSTATUS` | `Int` | Reverse (CAN) | `UtilExport.java:534-535,573-574` `[CODE]` | `UNKNOWN` |
| `com.real.ACTION_IO_REVSTATUS` | `REVSTATUS` | `Boolean` | Reverse (real IO) | `UtilExport.java:555-558` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_BRAKE_INFO` | UNKNOWN | UNKNOWN | Brake / handbrake | `UtilExport.java:24` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_RADAR_VIEW` | UNKNOWN | UNKNOWN | PDC view request | `UtilExport.java:54` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_KEY_CODE` | `key_code`, `key_status` | `Int` | Steering-wheel / panel key | `UtilExport.java:46,95-96` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_SOURCE_INFO` | `source_mode` | `Int` | **Authoritative source state** | `UtilExport.java:498-505` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_CAR_VIDEO_STATUS` | UNKNOWN | UNKNOWN | OEM video state | `UtilExport.java:31` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_MUSIC_INFO` | UNKNOWN | UNKNOWN | Media info from CAN | `UtilExport.java:51` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_RADIO_INFO` | UNKNOWN | UNKNOWN | Radio info | `UtilExport.java:55` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_BT_INFO` | `state`, `phoneName` | `Int`, `String` | BT HFP state | `UtilExport.java:507-515` `[CODE]` | `UNKNOWN` |
| `com.center.ACTION_BATTERY_VOL` | UNKNOWN | UNKNOWN | Battery voltage update | `UtilExport.java:23` `[CODE]` | `UNKNOWN` |
| `com.can.ACTION_CAN_CENTER_REV` | `data` | `ByteArray` | **Raw CAN/MCU frames** | `UtilExport.java:29` `[CODE]` | `UNKNOWN` |

> `com.can.ACTION_CAN_CENTER_REV` is **also an inbound injection sink** in CenterService
> (`CenterService.java:1831-1837` → `mMcuManager.sendCmd(data)`). The companion
> **listens only** and never sends it. See `mcu-protocol.md` and `security.md`.

---

## 5. Derived signals

Computed by `CarStateRepository`, `SignalSource.DERIVED`:

| Name | Derivation | Availability rule |
|---|---|---|
| `reverseActive` | `wits.backcar != 0` **OR** `REVSTATUS` true **OR** `source == 11` | `UNKNOWN` if all inputs unknown → **fail closed** for automation |
| `androidSourceActive` | `wits.source == 241` | `UNKNOWN` until first `ACTION_SOURCE_INFO` |
| `oemSourceActive` | `wits.source == 41` | as above |
| `anyDoorOpen` | any `vendor.can.cardoor1..5 != 0` | `UNKNOWN` if none observed |
| `turnSignal` | `light0`/`light1`/`light2` | `UNKNOWN` if none observed |

---

## 6. Rules for this document

1. Never write a unit that has not been demonstrated against a known physical state.
   `car.speed` might be km/h, mph, ×10, or a raw CAN byte — leave `UNKNOWN`.
2. A signal only moves `UNKNOWN → OBSERVED` with a capture file reference
   (`research/runtime-signals.csv`).
3. `OBSERVED → VERIFIED` requires a documented physical correlation
   (e.g. "opened front-left door ⇒ `vendor.can.cardoor1` 0→1, twice, reproducible").
4. `UNSUPPORTED` requires evidence of *absence* across a full capture session that
   exercised the relevant physical state.

---

## 7. Capture procedure

```sh
tools/capture-car-state.sh            # read-only; baseline + logcat + property diff
```

Exercise, in this order, with the timestamps written down:

ACC OFF→ON · sidelights → headlights · left/right indicator · each door · tailgate ·
reverse engaged/released · slow drive · steering left/right lock · approach obstacle
(PDC) · volume change · OEM→Android→OEM.

Output:

- `research/runtime-signals.csv` — one row per observed signal change
- `research/runtime-events.jsonl` — timestamped raw events
- `docs/runtime-car-state-results.md` — human summary; update §3/§4 status columns
