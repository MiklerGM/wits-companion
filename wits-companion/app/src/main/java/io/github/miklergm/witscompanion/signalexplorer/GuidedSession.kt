package io.github.miklergm.witscompanion.signalexplorer

/**
 * The guided volume/steering test matrix from docs/runtime-test-matrix.md.
 *
 * The wizard only *instructs* and *records*. It performs no vendor action: it never
 * switches source, never changes the steering scheme, never touches volume. Every
 * physical action is performed by the tester.
 */
object GuidedSession {

    enum class SourceUnderTest(val label: String, val setupHint: String) {
        ANDROID_SPOTIFY("Android Spotify",
            "Start playback in Spotify on the Android side."),
        MAPS_PLUS_SPOTIFY("Maps prompt + Spotify",
            "Start Spotify, then start Maps navigation so a guidance prompt can play."),
        OEM_RADIO("OEM BMW radio",
            "Switch to the OEM BMW screen and start the radio."),
        OEM_BLUETOOTH("OEM BMW Bluetooth",
            "Switch to the OEM BMW screen and start Bluetooth audio."),
        ZLINK("ZLink / Android Auto",
            "Start ZLink and connect the phone."),
        SILENCE("Silence",
            "Stop all playback."),
    }

    data class Step(
        val index: Int,
        val source: SourceUnderTest,
        val instruction: String,
        val marker: MarkerType,
        /** Ask the tester the observation questions after this step. */
        val askObservations: Boolean = true,
    )

    /**
     * The minimum first pass required by the acceptance criteria:
     * two sources × two controls.
     */
    fun minimalMatrix(): List<Step> = buildList {
        var i = 0
        listOf(SourceUnderTest.ANDROID_SPOTIFY, SourceUnderTest.OEM_RADIO).forEach { src ->
            add(Step(i++, src, "${src.setupHint}\n\nThen press: STEERING Volume +",
                MarkerType.STEERING_VOLUME_UP))
            add(Step(i++, src, "Now turn the NBT knob ONE step UP",
                MarkerType.NBT_KNOB_VOLUME_UP))
        }
    }

    /** The full matrix for one steering scheme. */
    fun fullMatrix(): List<Step> = buildList {
        var i = 0
        SourceUnderTest.entries.forEach { src ->
            add(Step(i++, src, "${src.setupHint}\n\nWhen ready, press NEXT to begin this source.",
                MarkerType.CUSTOM, askObservations = false))
            add(Step(i++, src, "NBT knob: ONE step UP", MarkerType.NBT_KNOB_VOLUME_UP))
            add(Step(i++, src, "NBT knob: ONE step DOWN", MarkerType.NBT_KNOB_VOLUME_DOWN))
            add(Step(i++, src, "NBT knob: MUTE, then UNMUTE", MarkerType.NBT_KNOB_MUTE))
            add(Step(i++, src, "STEERING: Volume +", MarkerType.STEERING_VOLUME_UP))
            add(Step(i++, src, "STEERING: Volume −", MarkerType.STEERING_VOLUME_DOWN))
            add(Step(i++, src, "STEERING: Mute", MarkerType.STEERING_MUTE))
            add(Step(i++, src, "STEERING: Volume + LONG PRESS (hold ~2 s)", MarkerType.STEERING_VOLUME_UP))
            add(Step(i++, src, "STEERING: Volume + three times rapidly", MarkerType.STEERING_VOLUME_UP))
        }
        add(Step(i++, SourceUnderTest.ANDROID_SPOTIFY,
            "Switch OEM → Android now", MarkerType.SOURCE_OEM_TO_ANDROID))
        add(Step(i++, SourceUnderTest.ANDROID_SPOTIFY,
            "STEERING: Volume + (after the source change)", MarkerType.STEERING_VOLUME_UP))
        add(Step(i, SourceUnderTest.ANDROID_SPOTIFY,
            "Switch Android → OEM now", MarkerType.SOURCE_ANDROID_TO_OEM))
    }

    /** Prompts shown after a step; answers become [UserObservations]. */
    val OBSERVATION_QUESTIONS = listOf(
        "Was the BMW/OEM volume graphic visible?",
        "Was an Android volume graphic visible?",
        "Did the audible level change?",
        "Did both domains appear to change?",
        "Did the source change unexpectedly?",
    )
}
