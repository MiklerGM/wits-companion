package io.github.miklergm.witscompanion.wits

/**
 * System property names published by CenterService.
 *
 * Names are [CODE] (from `UtilExport.java`). Whether this BMW profile + MCU
 * actually populate a given property is [HYP] until observed — see
 * docs/car-state.md.
 */
object WitsProperties {

    // --- vehicle state -------------------------------------------------------
    const val ACC = "wits.acc"
    const val ACC_ON_TIME = "wits.acc.on.time"
    const val BACKCAR = "wits.backcar"
    const val BRAKE = "wits.brake"
    const val ILL = "wits.ill"
    const val BATTERY_VOL = "wits.battery.vol"

    const val CAR_SPEED = "car.speed"
    const val CAN_SPEED = "can.speed"
    const val CAR_RATE = "car.rate"
    const val CAR_SIGNAL = "car.signal"
    const val CAR_LANE = "car.lane"
    const val CAR_TURN_LR = "car.turn.lr"
    const val CAR_TYPE = "car.type"

    const val CAN_DOOR = "can.door"
    const val CAN_RADAR = "can.radar"
    const val CAN_TURN_LR = "can.turn.lr"

    const val CAN_ANGLE = "vendor.can.angle"

    // --- head-unit state -----------------------------------------------------
    const val SOURCE = "wits.source"
    const val TOP_PACKAGE = "wits.top.package"
    const val TOP_ACTIVITY = "wits.top.activity"
    const val SHOWING_PIP = "wits.showing.pip"
    const val SHOWING_TASKVIEW = "wits.showing.taskview"

    const val MCU_VERSION = "wits.mcu.version"
    const val MCU_CAN_VERSION = "wits.mcu.can.version"

    /**
     * Set by the MCU/CenterService when the unit wakes from deep sleep with apps still
     * alive, cleared on a real cold boot. `[CODE]` `McuManager` reads
     * `persist.wits.memory.boot`, acts on it, then resets it to 0 — so read it early and
     * treat a race as "unknown", falling back to the live-task check.
     */
    const val MEMORY_BOOT = "persist.wits.memory.boot"

    const val PRODUCT_ID = "ro.wits.product.id"
    const val MODEL_ID = "ro.wits.model"
    const val BUILD_DISPLAY_ID = "ro.build.display.id"

    /** `vendor.can.radar0` .. `vendor.can.radar7` */
    fun radar(index: Int): String = "vendor.can.radar$index"

    /** `vendor.can.cardoor1` .. `vendor.can.cardoor5` */
    fun carDoor(index: Int): String = "vendor.can.cardoor$index"

    /** `vendor.can.light0` = turn left, `1` = turn right, `2` = hazard [HYP] */
    fun light(index: Int): String = "vendor.can.light$index"

    val RADAR_ALL: List<String> = (0..7).map { radar(it) }
    val DOOR_ALL: List<String> = (1..5).map { carDoor(it) }
    val LIGHT_ALL: List<String> = (0..2).map { light(it) }

    /**
     * Everything the repository polls.
     *
     * Trimmed on 2026-07-31 against a live capture: this BMW profile leaves
     * `vendor.can.radar0..7`, `vendor.can.cardoor1..5`, `vendor.can.light0..2`,
     * `car.speed`, `car.rate`, `car.lane`, `car.turn.lr` and `wits.battery.vol`
     * **permanently empty** `[RUNTIME]`. Polling them was pure overhead and made the
     * dashboard show dashes for data that does arrive — in a different shape:
     *
     *  - parking sensors come as one string in `can.radar` (`"2:0:0:4:0:0:0:0"`),
     *  - doors come as a bitmask in `can.door` (`"ffffff80"`).
     *
     * Both formats are `[HYP]` and are left raw rather than guessed at. The vehicle's
     * own cluster and HUD already show speed, doors and PDC, so the companion does not
     * try to reproduce them; the Signal Explorer records the raw values when a capture
     * session is actually wanted.
     */
    val POLLED: List<String> = listOf(
        ACC, BACKCAR, BRAKE, ILL,
        SOURCE, TOP_PACKAGE,
        CAN_SPEED,          // populated; car.speed is not
        CAN_ANGLE,
        CAN_DOOR, CAN_RADAR, // raw, unparsed
    )

    /**
     * Declared in the firmware but never populated on this profile `[RUNTIME]`.
     * Kept for the Signal Explorer's snapshots, excluded from steady-state polling.
     */
    val EMPTY_ON_THIS_PROFILE: List<String> = buildList {
        addAll(listOf(CAR_SPEED, CAR_RATE, CAR_LANE, CAR_TURN_LR, BATTERY_VOL))
        addAll(RADAR_ALL); addAll(DOOR_ALL); addAll(LIGHT_ALL)
    }

    /** Read once at startup; these never change while running. */
    val STATIC: List<String> = listOf(
        MCU_VERSION, MCU_CAN_VERSION, PRODUCT_ID, MODEL_ID, BUILD_DISPLAY_ID, CAR_TYPE,
    )
}

/**
 * `Settings.System` / `Settings.Global` keys we read or write.
 * Only [WITS_NIGHT_MODE] is ever written. See docs/night-mode.md.
 */
object WitsSettingsKeys {
    /** 0 = follow illumination, 1 = schedule, 2 = force night, 3 = force day. */
    const val WITS_NIGHT_MODE = "wits_night_mode"

    const val UI_SETTINGS = "UiSettings"
    const val WITS_SKIN = "wits_skin"
    const val BACKLIGHT_CONTROL_MODE = "wits_backlight_control_mode"
    const val BACKLIGHT_START_HOUR = "wits_backlight_start_hour"
    const val BACKLIGHT_START_MINUTE = "wits_backlight_start_minute"
    const val BACKLIGHT_END_HOUR = "wits_backlight_end_hour"
    const val BACKLIGHT_END_MINUTE = "wits_backlight_end_minute"

    const val DEFAULT_PIP_APP = "default_pip_app"
    const val REMEMBER_PIP_APP = "REMEMBER_PIP_APP"
    const val DEFAULT_TASKVIEW_APP = "default_taskview_app"

    // The vendor's own choice of third-party apps, set in system settings and stored in
    // Settings.System. Read-only for us — reading Settings.System needs no permission,
    // only writing does. Lets the companion pre-fill layout slots with what the user
    // already picked instead of guessing. `[CODE]` UtilSetting / ID8LauncherConstants.
    const val THIRD_APP_MUSIC_PKG = "KEY_THIRD_APP_MUSIC_PKG"
    const val THIRD_APP_VIDEO_PKG = "KEY_THIRD_APP_VIDEO_PKG"
    const val THIRD_APP_VOICE_PKG = "KEY_THIRD_APP_VOICE_PKG"
    const val NAVI_APP = "NaviApp"
    const val MUSIC_APP = "MusicApp"

    // Settings.Global
    const val ENABLE_FREEFORM_SUPPORT = "enable_freeform_support"
    const val FORCE_RESIZABLE_ACTIVITIES = "force_resizable_activities"
}

/** Well-known packages the companion interacts with. */
object WitsPackages {
    const val MAPS = "com.google.android.apps.maps"
    const val SPOTIFY = "com.spotify.music"
    const val CHROME = "com.android.chrome"
    const val WITS_LAUNCHER = "com.wits.launcher"
    const val CENTER_SERVICE = "com.wits.pms"
    const val SELF = "io.github.miklergm.witscompanion"
}
