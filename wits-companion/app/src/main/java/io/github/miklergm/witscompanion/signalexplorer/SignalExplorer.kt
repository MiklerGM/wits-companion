package io.github.miklergm.witscompanion.signalexplorer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.miklergm.witscompanion.BuildConfig
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.carstate.PropertyReader
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.wits.WitsProperties
import org.json.JSONObject

/**
 * Coordinates the probes and the recorder.
 *
 * Observation only: it starts and stops readers, and writes what they report. It has no
 * setter of any kind — no broadcast send, no property write, no Settings write, no audio
 * change, no source switch, no key injection.
 */
class SignalExplorer(
    private val appContext: Context,
    private val carStateRepository: CarStateRepository,
    private val propertyReader: PropertyReader,
    private val logger: EventLogger? = null,
) : CarStateRepository.Observer {

    var catalog: EventCatalog = EventCatalog.load(appContext)
        private set

    @Volatile
    var recorder: SessionRecorder? = null
        private set

    val isRecording: Boolean get() = recorder?.active == true

    private val audioProbe = AudioProbe(appContext)
    private var broadcastProbe: BroadcastProbe? = null
    private var propertyProbe: PropertyProbe? = null
    private var settingsProbe: SettingsProbe? = null
    private val inputProbe = InputProbe { code, action, repeat ->
        recorder?.record(
            EventKind.KEY_EVENT,
            EventPayload.KeyEventPayload("ANDROID_FOCUSED", null, code, action, repeat),
            currentSourceState(),
        )
    }

    /** Opt-in relative estimate for the OEM domain; explicitly labelled an estimate. */
    @Volatile
    var oemRelativeEstimate: Int? = null
        private set

    private var latestCarState: CarState = CarState()

    // ------------------------------------------------------------------- catalog

    fun importCatalog(json: String): Result<Int> =
        EventCatalog.saveImported(appContext, json).map { catalog = it; it.version }

    fun resetCatalog() {
        EventCatalog.clearImported(appContext)
        catalog = EventCatalog.load(appContext)
    }

    // ------------------------------------------------------------------ sessions

    fun startSession(
        otaPhase: OtaPhase,
        userContext: String,
        steeringSchemeRaw: String?,
    ): SessionRecorder {
        stopSession()

        val metadata = buildMetadata(otaPhase, userContext, steeringSchemeRaw)
        val rec = SessionRecorder(appContext, metadata, catalog)
        recorder = rec

        // Session-start snapshots.
        writeSnapshot(rec, "SESSION_START")

        broadcastProbe = BroadcastProbe(
            catalog = catalog,
            sourceStateProvider = ::currentSourceState,
            onEvent = { kind, payload, src -> rec.record(kind, payload, src) },
        ).also { it.start(appContext) }

        propertyProbe = PropertyProbe(
            reader = propertyReader,
            targeted = catalog.targetedProperties().ifEmpty { WitsProperties.POLLED },
            allProperties = catalog.snapshotProperties(),
            onChange = { key, old, new ->
                rec.record(
                    EventKind.PROPERTY_CHANGE,
                    EventPayload.KeyValueChange(EventKind.PROPERTY_CHANGE, key, null, old, new),
                    currentSourceState(),
                )
            },
        ).also { it.start() }

        settingsProbe = SettingsProbe(
            context = appContext,
            watchedSystem = catalog.watchedSettings("system"),
            watchedGlobal = catalog.watchedSettings("global"),
            onChange = { ns, key, old, new ->
                rec.record(
                    EventKind.SETTINGS_CHANGE,
                    EventPayload.KeyValueChange(EventKind.SETTINGS_CHANGE, key, ns, old, new),
                    currentSourceState(),
                )
                if (key == MCU_VOLUME_KEY) {
                    // A volume-relevant change deserves an immediate audio snapshot.
                    rec.record(
                        EventKind.AUDIO_SNAPSHOT,
                        EventPayload.AudioSnapshotPayload(audioProbe.snapshot("MCU_VOLUME_CHANGE")),
                        currentSourceState(),
                    )
                }
            },
        ).also { it.start() }

        carStateRepository.addObserver(this)
        logger?.log("signal_explorer", "session_start",
            extras = mapOf("session" to metadata.sessionId, "phase" to otaPhase.name))
        return rec
    }

    fun stopSession() {
        val rec = recorder ?: return
        writeSnapshot(rec, "SESSION_END")
        broadcastProbe?.stop(appContext); broadcastProbe = null
        propertyProbe?.stop(); propertyProbe = null
        settingsProbe?.stop(); settingsProbe = null
        carStateRepository.removeObserver(this)
        rec.stop()
        logger?.log("signal_explorer", "session_stop",
            extras = mapOf("session" to rec.metadata.sessionId, "events" to rec.eventCount))
        recorder = null
    }

    // -------------------------------------------------------------------- markers

    /**
     * Records a marker with an audio snapshot before and after, so a stream delta can be
     * computed for exactly this physical action.
     */
    fun mark(type: MarkerType, note: String = ""): MarkerRecord? {
        val rec = recorder ?: return null
        val src = currentSourceState()

        rec.record(
            EventKind.AUDIO_SNAPSHOT,
            EventPayload.AudioSnapshotPayload(audioProbe.snapshot("MARKER_PRE")), src
        )
        val marker = rec.mark(type, note, sourceState = src)
        settingsProbe?.diffNow()

        // Post snapshot after the audio system has had a moment to settle.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (rec.active) {
                rec.record(
                    EventKind.AUDIO_SNAPSHOT,
                    EventPayload.AudioSnapshotPayload(audioProbe.snapshot("MARKER_POST")),
                    currentSourceState(),
                )
                settingsProbe?.diffNow()
            }
        }, MARKER_POST_SNAPSHOT_DELAY_MS)

        // Relative OEM estimate — opt-in, never presented as exact.
        if (oemRelativeEstimate != null) {
            when (type) {
                MarkerType.NBT_KNOB_VOLUME_UP, MarkerType.STEERING_VOLUME_UP ->
                    oemRelativeEstimate = (oemRelativeEstimate ?: 0) + 1
                MarkerType.NBT_KNOB_VOLUME_DOWN, MarkerType.STEERING_VOLUME_DOWN ->
                    oemRelativeEstimate = (oemRelativeEstimate ?: 0) - 1
                else -> Unit
            }
        }
        return marker
    }

    fun attachObservations(o: UserObservations) = recorder?.attachObservations(o)

    fun addNote(text: String) {
        recorder?.record(EventKind.NOTE, EventPayload.Note(text), currentSourceState())
    }

    fun enableRelativeEstimate(enabled: Boolean) {
        oemRelativeEstimate = if (enabled) 0 else null
    }

    fun resetRelativeEstimate() {
        if (oemRelativeEstimate != null) oemRelativeEstimate = 0
    }

    // --------------------------------------------------------------------- reads

    fun audioSnapshotNow(reason: String = "MANUAL") = audioProbe.snapshot(reason)

    fun volumeReadings(): List<VolumeReading> = audioProbe.volumeReadings(
        mcuRaw = settingsProbe?.readSystem(MCU_VOLUME_KEY)
            ?: runCatching {
                android.provider.Settings.System.getString(appContext.contentResolver, MCU_VOLUME_KEY)
            }.getOrNull(),
        relativeEstimate = oemRelativeEstimate,
    )

    fun steeringSchemeRaw(): String? = runCatching {
        android.provider.Settings.System.getString(appContext.contentResolver, STEERING_SCHEME_KEY)
    }.getOrNull()

    fun steeringSchemeLabel(raw: String?): String = when (raw?.trim()) {
        "0" -> "Type 1"; "1" -> "Type 2"; "2" -> "Type 3"
        null, "" -> "unset"
        else -> "raw=$raw"
    }

    fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (isRecording) inputProbe.onKeyEvent(event) else false

    fun propertyStrategy(): String = propertyProbe?.strategy?.name ?: propertyReader.activeStrategy.name
    fun propertyDiagnostics(): String = propertyReader.diagnostics
    fun missedSamples(): Long = propertyProbe?.missedSamples ?: 0

    // ------------------------------------------------------------------ internals

    override fun onCarState(state: CarState) {
        latestCarState = state
    }

    private fun currentSourceState() = SourceState(
        source = latestCarState.source.takeIf { it.isKnown }?.value,
        reverse = latestCarState.reverseActive,
        topPackage = latestCarState.topPackage.takeIf { it.isKnown }?.value,
    )

    private fun writeSnapshot(rec: SessionRecorder, reason: String) {
        val audio = audioProbe.snapshot(reason)
        rec.record(EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(audio), currentSourceState())

        val props = propertyProbe?.snapshot()
            ?: catalog.snapshotProperties().associateWith { propertyReader.get(it) }
        val settings = settingsProbe?.snapshot() ?: emptyMap()
        val filtered = settingsProbe?.filteredScan() ?: emptyMap()

        rec.recordSnapshot(JSONObject().apply {
            put("reason", reason)
            put("wallClockMs", System.currentTimeMillis())
            put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            put("properties", JSONObject(props.mapValues { it.value ?: "" } as Map<*, *>))
            put("settings", JSONObject(settings.mapValues { it.value ?: "" } as Map<*, *>))
            put("settingsFiltered", JSONObject(filtered.mapValues { it.value ?: "" } as Map<*, *>))
            put("audio", audio.toJson())
        })
    }

    private fun buildMetadata(
        otaPhase: OtaPhase,
        userContext: String,
        steeringSchemeRaw: String?,
    ): SessionMetadata {
        val dm = appContext.resources.displayMetrics
        val pm = appContext.packageManager
        val packages = listOf(
            "com.spotify.music", "com.google.android.apps.maps",
            "com.wits.launcher", "com.wits.pms", "com.zjinnova.zlink",
        ).mapNotNull { p ->
            runCatching {
                @Suppress("DEPRECATION")
                p to (pm.getPackageInfo(p, 0).versionName ?: "?")
            }.getOrNull()
        }.toMap()

        return SessionMetadata(
            sessionId = SessionRecorder.newSessionId(),
            startedWallClockMs = System.currentTimeMillis(),
            startedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            appVersion = BuildConfig.VERSION_NAME,
            catalogVersion = catalog.version,
            otaPhase = otaPhase,
            device = DeviceInfo(
                buildDisplayId = propertyReader.get("ro.build.display.id"),
                fingerprint = Build.FINGERPRINT,
                sdkInt = Build.VERSION.SDK_INT,
                witsProductId = propertyReader.get(WitsProperties.PRODUCT_ID),
                witsModel = propertyReader.get(WitsProperties.MODEL_ID),
                mcuVersion = propertyReader.get(WitsProperties.MCU_VERSION),
                mcuCanVersion = propertyReader.get(WitsProperties.MCU_CAN_VERSION),
                displayWidth = dm.widthPixels,
                displayHeight = dm.heightPixels,
                density = dm.density,
            ),
            steeringSchemeRaw = steeringSchemeRaw,
            steeringSchemeLabel = steeringSchemeLabel(steeringSchemeRaw),
            sourceAtStart = latestCarState.source.takeIf { it.isKnown }?.value,
            packages = packages,
            userContext = userContext,
            propertyStrategy = propertyReader.activeStrategy.name,
        )
    }

    companion object {
        /** M701 packs the MCU volume into the low byte of this key. */
        const val MCU_VOLUME_KEY = "wits_mcu:1"
        const val STEERING_SCHEME_KEY = "control_type"
        const val MARKER_POST_SNAPSHOT_DELAY_MS = 1_200L
    }
}
