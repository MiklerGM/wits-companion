package io.github.miklergm.witscompanion.wits

/**
 * Vendor intent actions, extras and constants used by the Witstek firmware.
 *
 * Every entry is transcribed from decompiled firmware; the reference is given inline.
 * See docs/window-management.md and docs/source-switching.md.
 *
 * Confidence: all constants below are [CODE] (present in decompiled sources).
 * Whether the receiving component reacts as expected on this vehicle is [HYP]
 * until confirmed by the runtime tests.
 */
object WitsActions {

    // ---------------------------------------------------------------- windows
    /**
     * Handled by `ActivityTaskManagerService.mDynamicReceiver` in system_server.
     * Registered WITHOUT a broadcast permission (ActivityTaskManagerService.java:511-514),
     * which is why a normal /data app may send it.
     *
     * Extras: [EXTRA_PACKAGE_NAME], [EXTRA_WINDOW_MODE],
     *         [EXTRA_LEFT], [EXTRA_TOP], [EXTRA_RIGHT], [EXTRA_BOTTOM]
     */
    const val ACTION_CHANGE_WINDOW = "wits.intent.action.CHANGE_WINDOW"

    /** Moves only the single `default_pip_app` task. Not used as a layout primitive. */
    const val ACTION_MOVE_PIP_WINDOW = "wits.intent.action.MOVE_PIP_WINDOW"

    /** Sends the `default_pip_app` task to the back. */
    const val ACTION_MOVE_PIP_WINDOW_BACK = "wits.intent.action.MOVE_PIP_WINDOW_BACK"

    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_WINDOW_MODE = "windowMode"
    const val EXTRA_LEFT = "left"
    const val EXTRA_TOP = "top"
    const val EXTRA_RIGHT = "right"
    const val EXTRA_BOTTOM = "bottom"

    // ----------------------------------------------------------------- source
    /**
     * Request a video/audio source change. Handled by CenterService
     * (CenterService.java:1874-1888). This is a COMMAND, never a state event.
     *
     * Extras: [EXTRA_STATUS] = target source id, [EXTRA_CALLER] = tagged caller id.
     */
    const val ACTION_REQUEST_SWITCH_SOURCE = "com.can.ACTION_REQUEST_SWITCH_SOURCE"

    /**
     * Authoritative source STATE, emitted by `UtilExport.broadcastSourceInfo`
     * (UtilExport.java:498-505) together with the `wits.source` property.
     */
    const val ACTION_SOURCE_INFO = "com.can.ACTION_SOURCE_INFO"
    const val EXTRA_SOURCE_MODE = "source_mode"

    const val EXTRA_STATUS = "status"
    const val EXTRA_CALLER = "caller"

    // ------------------------------------------------------------- car state
    const val ACTION_ACC_INFO = "com.can.ACTION_ACC_INFO"
    const val ACTION_ILL_INFO = "com.can.ACTION_ILL_INFO"
    const val ACTION_REVSTATUS = "com.can.ACTION_REVSTATUS"
    const val ACTION_REAL_REVSTATUS = "com.real.ACTION_IO_REVSTATUS"
    const val ACTION_BRAKE_INFO = "com.can.ACTION_BRAKE_INFO"
    const val ACTION_RADAR_VIEW = "com.can.ACTION_RADAR_VIEW"
    const val ACTION_KEY_CODE = "com.can.ACTION_KEY_CODE"
    const val ACTION_CAR_VIDEO_STATUS = "com.can.ACTION_CAR_VIDEO_STATUS"
    const val ACTION_MUSIC_INFO = "com.can.ACTION_MUSIC_INFO"
    const val ACTION_RADIO_INFO = "com.can.ACTION_RADIO_INFO"
    const val ACTION_BT_INFO = "com.can.ACTION_BT_INFO"
    const val ACTION_BATTERY_VOL = "com.center.ACTION_BATTERY_VOL"

    const val EXTRA_REVSTATUS = "REVSTATUS"

    /**
     * Every action the car-state receiver subscribes to (read-only).
     *
     * Derived from [WitsProfile] rather than listed again here: this used to be a hand-kept
     * copy, so a signal could be parsed but never subscribed to (or the reverse) with nothing
     * to catch it.
     */
    val CAR_STATE_ACTIONS: List<String> get() = WitsProfile.OBSERVED_ACTIONS

    // ------------------------------------------------------------- FORBIDDEN
    /*
     * The following firmware actions reach the MCU serial line directly and are
     * deliberately NOT declared as usable constants:
     *
     *   com.can.ACTION_CAN_CENTER_REV     + data:ByteArray -> McuManager.sendCmd()
     *   com.center.ACTION_COMMON_CMD_REV  + cmd=0x300000   -> sendCmdWits()
     *
     * The companion contains no code path that constructs a ProtocolWits frame.
     * See docs/mcu-protocol.md §8 and docs/security.md §3.1.
     */
}

/**
 * Source ids from `UtilExport.AppMode` [CODE].
 */
object WitsSource {
    const val DVD = 1
    const val TUNER = 2
    const val TV = 3
    const val AUX = 5
    const val ARM = 7
    const val IPOD = 9

    /** Reverse camera. Safety-critical: never switch away from this. */
    const val BACKCAR = 11

    const val FRONT_AUX = 12
    const val BTHFP = 13
    const val AVOFF = 16
    const val BTA2DP = 17
    const val MUSIC = 38
    const val VIDEO = 39
    const val NAVI = 40

    /** OEM / original car image via the CAN box. */
    const val CAN = 41

    const val CAN_RADIO = 51
    const val OTHER = 240

    /** Android launcher. */
    const val LAUNCHER = 241

    const val SETTINGS = 242
    const val CENTER = 243

    fun name(id: Int?): String = when (id) {
        null -> "unknown"
        DVD -> "DVD"
        TUNER -> "Radio"
        TV -> "TV"
        AUX -> "AUX"
        ARM -> "Android audio"
        IPOD -> "iPod"
        BACKCAR -> "Reverse camera"
        FRONT_AUX -> "Front AUX"
        BTHFP -> "Bluetooth call"
        AVOFF -> "AV off"
        BTA2DP -> "Bluetooth audio"
        MUSIC -> "Music"
        VIDEO -> "Video"
        NAVI -> "Navigation"
        CAN -> "OEM BMW"
        CAN_RADIO -> "OEM radio"
        OTHER -> "Other"
        LAUNCHER -> "Android"
        SETTINGS -> "Settings"
        CENTER -> "CenterService"
        else -> "id=$id"
    }
}

/**
 * `WindowConfiguration.WINDOWING_MODE_*` values, passed straight to
 * `ActivityOptions.setLaunchWindowingMode` by the vendor hook.
 */
object WitsWindowMode {
    const val UNDEFINED = 0
    const val FULLSCREEN = 1
    const val PINNED = 2
    const val SPLIT_PRIMARY = 3
    const val SPLIT_SECONDARY = 4
    const val FREEFORM = 5

    fun name(mode: Int): String = when (mode) {
        UNDEFINED -> "undefined"
        FULLSCREEN -> "fullscreen"
        PINNED -> "pinned (PiP)"
        SPLIT_PRIMARY -> "split primary"
        SPLIT_SECONDARY -> "split secondary"
        FREEFORM -> "freeform"
        else -> "mode=$mode"
    }
}
