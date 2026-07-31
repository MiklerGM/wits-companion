package io.github.miklergm.witscompanion

import android.os.Bundle
import io.github.miklergm.witscompanion.signalexplorer.AudioSnapshot
import io.github.miklergm.witscompanion.signalexplorer.Availability
import io.github.miklergm.witscompanion.signalexplorer.BroadcastProbe
import io.github.miklergm.witscompanion.signalexplorer.CatalogStatus
import io.github.miklergm.witscompanion.signalexplorer.Correlator
import io.github.miklergm.witscompanion.signalexplorer.EventCatalog
import io.github.miklergm.witscompanion.signalexplorer.EventKind
import io.github.miklergm.witscompanion.signalexplorer.EventPayload
import io.github.miklergm.witscompanion.signalexplorer.MarkerRecord
import io.github.miklergm.witscompanion.signalexplorer.MarkerType
import io.github.miklergm.witscompanion.signalexplorer.SessionEvent
import io.github.miklergm.witscompanion.signalexplorer.SourceState
import io.github.miklergm.witscompanion.signalexplorer.StreamState
import io.github.miklergm.witscompanion.signalexplorer.ValueType
import io.github.miklergm.witscompanion.signalexplorer.VolumeDomain
import io.github.miklergm.witscompanion.signalexplorer.VolumeReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignalExplorerTest {

    // ------------------------------------------------ typed extras / serialization

    @Test
    fun `bundle extras keep their real java types`() {
        val b = Bundle().apply {
            putInt("key_code", 13)
            putBoolean("REVSTATUS", true)
            putString("phoneName", "x")
            putLong("t", 5L)
            putFloat("f", 1.5f)
        }
        val extras = BroadcastProbe.flatten(b).associateBy { it.name }

        assertEquals("java.lang.Integer", extras["key_code"]!!.javaType)
        assertEquals("13", extras["key_code"]!!.value)
        assertEquals("java.lang.Boolean", extras["REVSTATUS"]!!.javaType)
        assertEquals("true", extras["REVSTATUS"]!!.value)
        assertEquals("java.lang.String", extras["phoneName"]!!.javaType)
        assertEquals("java.lang.Long", extras["t"]!!.javaType)
        assertEquals("java.lang.Float", extras["f"]!!.javaType)
    }

    @Test
    fun `byte arrays are recorded as length hex and base64`() {
        val payload = byteArrayOf(0x2D, 0x04, 0x01, 0x00)
        val extra = BroadcastProbe.describe("data", payload).single()

        assertEquals("byte[]", extra.javaType)
        assertEquals(4, extra.length)
        assertEquals("2D040100", extra.hex)
        assertNotNull(extra.base64)
        // base64 of 2D040100
        assertEquals("LQQBAA==", extra.base64)
    }

    @Test
    fun `nested bundles and arrays flatten with dotted and indexed names`() {
        val inner = Bundle().apply { putInt("v", 7) }
        val b = Bundle().apply {
            putBundle("sub", inner)
            putIntArray("arr", intArrayOf(1, 2))
        }
        val names = BroadcastProbe.flatten(b).map { it.name }.toSet()
        assertTrue("sub.v" in names)
        assertTrue("arr[0]" in names)
        assertTrue("arr[1]" in names)
    }

    @Test
    fun `unknown extra types are captured, not dropped`() {
        class Weird { override fun toString() = "weird-value" }
        val extra = BroadcastProbe.describe("x", Weird()).single()
        assertEquals("weird-value", extra.value)
        assertTrue(extra.javaType.contains("Weird"))
    }

    @Test
    fun `null extra is recorded explicitly`() {
        val extra = BroadcastProbe.describe("x", null).single()
        assertEquals("null", extra.javaType)
        assertEquals(null, extra.value)
    }

    // -------------------------------------------------------------- event catalog

    private val catalogJson = """
        {"catalogVersion":3,"generatedFrom":"test",
         "broadcasts":[
           {"action":"com.can.ACTION_KEY_CODE","status":"KNOWN","confidence":"CODE",
            "extras":[{"name":"key_code","type":"INT"},{"name":"key_status","type":"INT"}]},
           {"action":"com.can.ACTION_DEAD","status":"UNSUPPORTED","confidence":"NOTFOUND","extras":[]}
         ],
         "properties":[
           {"name":"wits.ill","type":"BOOL_INT","confidence":"CODE","sampling":"TARGETED"},
           {"name":"ro.build.display.id","type":"STRING","confidence":"CONF","sampling":"SNAPSHOT"}
         ],
         "settingsKeys":[
           {"key":"wits_mcu:1","namespace":"system","type":"INT_PACKED","confidence":"CODE"},
           {"key":"enable_freeform_support","namespace":"global","type":"INT","confidence":"CODE"}
         ]}
    """.trimIndent()

    @Test
    fun `catalog parses and excludes unsupported actions from subscription`() {
        val c = EventCatalog.parse(catalogJson)
        assertEquals(3, c.version)
        assertEquals(listOf("com.can.ACTION_KEY_CODE"), c.subscribableActions())
        assertEquals(CatalogStatus.UNSUPPORTED, c.statusOf("com.can.ACTION_DEAD"))
        assertEquals(CatalogStatus.UNCATALOGUED, c.statusOf("com.example.NOPE"))
    }

    @Test
    fun `catalog separates targeted from snapshot properties and namespaces settings`() {
        val c = EventCatalog.parse(catalogJson)
        assertEquals(listOf("wits.ill"), c.targetedProperties())
        assertEquals(2, c.snapshotProperties().size)
        assertEquals(listOf("wits_mcu:1"), c.watchedSettings("system"))
        assertEquals(listOf("enable_freeform_support"), c.watchedSettings("global"))
    }

    @Test
    fun `catalog flags unexpected extras and type mismatches`() {
        val c = EventCatalog.parse(catalogJson)
        val observed = BroadcastProbe.describe("key_code", 13) +
            BroadcastProbe.describe("surprise", "hello")
        val (unexpected, mismatches) = c.checkExtras("com.can.ACTION_KEY_CODE", observed)

        assertEquals(listOf("surprise"), unexpected)
        assertTrue("Integer is compatible with INT", mismatches.isEmpty())

        val wrongType = BroadcastProbe.describe("key_code", "not-an-int")
        val (_, m2) = c.checkExtras("com.can.ACTION_KEY_CODE", wrongType)
        assertEquals(1, m2.size)
    }

    @Test
    fun `uncatalogued action reports every extra as unexpected`() {
        val c = EventCatalog.parse(catalogJson)
        val (unexpected, _) = c.checkExtras("com.example.NOPE", BroadcastProbe.describe("a", 1))
        assertEquals(listOf("a"), unexpected)
    }

    @Test
    fun `boolean on the wire is accepted for an INT catalog type`() {
        assertEquals(ValueType.BOOL_INT, EventCatalog.javaTypeToValueType("java.lang.Boolean"))
        val c = EventCatalog.parse(catalogJson)
        val (_, mismatches) = c.checkExtras(
            "com.can.ACTION_KEY_CODE", BroadcastProbe.describe("key_status", true)
        )
        assertTrue(mismatches.isEmpty())
    }

    // ----------------------------------------------------------- audio snapshots

    private fun snap(reason: String, music: Int, muted: Boolean = false) = AudioSnapshot(
        reason = reason,
        streams = listOf(StreamState(3, "MUSIC", music, 15, 0, null, muted)),
    )

    @Test
    fun `audio snapshot delta reports only streams that moved`() {
        val deltas = snap("MARKER_POST", 11).deltaFrom(snap("MARKER_PRE", 10))
        assertEquals(1, deltas.size)
        assertTrue(deltas.single().contains("10 -> 11"))

        assertTrue(snap("MARKER_POST", 10).deltaFrom(snap("MARKER_PRE", 10)).isEmpty())
    }

    @Test
    fun `audio snapshot delta detects mute changes`() {
        val deltas = snap("MARKER_POST", 10, muted = true).deltaFrom(snap("MARKER_PRE", 10))
        assertEquals(1, deltas.size)
        assertTrue(deltas.single().contains("muted"))
    }

    // ---------------------------------------------------- truthful volume domains

    @Test
    fun `OEM domain never shows a number until an absolute value is proven`() {
        val oem = VolumeReading(VolumeDomain.OEM_NBT, null, null, Availability.UNKNOWN)
        assertEquals("value unavailable", oem.display())

        // Even if a value were somehow attached, an unproven availability stays honest.
        val bogus = VolumeReading(VolumeDomain.OEM_NBT, 17, 40, Availability.OBSERVED)
        assertEquals("value unavailable", bogus.display())
    }

    @Test
    fun `unread android domain shows a dash, not zero`() {
        val unread = VolumeReading(VolumeDomain.ANDROID_MEDIA, null, null, Availability.UNKNOWN)
        assertEquals("—", unread.display())

        val real = VolumeReading(VolumeDomain.ANDROID_MEDIA, 0, 15, Availability.VALID)
        assertEquals("a real zero is shown", "0 / 15", real.display())
    }

    @Test
    fun `mcu domain renders value over max`() {
        val mcu = VolumeReading(VolumeDomain.WITS_MCU, 22, 40, Availability.VALID)
        assertEquals("22 / 40", mcu.display())
    }

    @Test
    fun `relative estimate is explicitly labelled an estimate`() {
        val est = VolumeReading(VolumeDomain.OEM_RELATIVE_ESTIMATE, 3, null, Availability.OBSERVED)
        val text = est.display()
        assertTrue(text.contains("estimate"))
        assertTrue(text.contains("+3"))
        assertFalse("must not look like an absolute reading", text.matches(Regex("^\\d+\\s*/\\s*\\d+$")))
    }

    @Test
    fun `mcu volume is taken from the low byte of the packed value`() {
        // wits_mcu:1 default 7702 = 0x1E16 -> volume 0x16 = 22 (McuManager.java:1679-1686)
        assertEquals("22", Correlator.normaliseMcu("7702"))
        assertEquals("0", Correlator.normaliseMcu("7680"))     // 0x1E00
        assertEquals("garbage is preserved", "abc", Correlator.normaliseMcu("abc"))
    }

    // ------------------------------------------------------------- correlation

    private fun ev(seq: Long, kind: EventKind, payload: EventPayload, nanos: Long = seq) =
        SessionEvent(seq, seq, nanos, kind, SourceState(source = 241), payload)

    private fun rawEvent(seq: Long, kind: EventKind, json: String, nanos: Long = seq) =
        SessionEvent(seq, seq, nanos, kind, SourceState(source = 241),
            EventPayload.Raw(JSONObject(json)))

    @Test
    fun `correlator reports android stream change when only android moved`() {
        val marker = MarkerRecord(MarkerType.STEERING_VOLUME_UP, "", 3000, 8000, 1, 9)
        val events = listOf(
            ev(1, EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(snap("MARKER_PRE", 10))),
            ev(2, EventKind.BROADCAST, EventPayload.Broadcast(
                "com.can.ACTION_KEY_CODE", CatalogStatus.KNOWN,
                BroadcastProbe.describe("key_code", 13))),
            ev(3, EventKind.KEY_EVENT, EventPayload.KeyEventPayload("WITS_BROADCAST", 13, null, "down")),
            ev(9, EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(snap("MARKER_POST", 11))),
        )
        val f = Correlator.analyse(marker, events)

        assertTrue(f.witsKeyBroadcast)
        assertEquals(listOf(13), f.rawKeyCodes)
        assertEquals(1, f.androidStreamDeltas.size)
        assertEquals(null, f.mcuVolumeDelta)
        assertTrue(f.conclusion().contains("Android stream changed"))
    }

    @Test
    fun `correlator reports MCU-only change`() {
        val marker = MarkerRecord(MarkerType.NBT_KNOB_VOLUME_UP, "", 3000, 8000, 1, 9)
        val events = listOf(
            ev(1, EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(snap("MARKER_PRE", 10))),
            ev(2, EventKind.SETTINGS_CHANGE, EventPayload.KeyValueChange(
                EventKind.SETTINGS_CHANGE, "wits_mcu:1", "system", "7702", "7703")),
            ev(9, EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(snap("MARKER_POST", 10))),
        )
        val f = Correlator.analyse(marker, events)

        assertTrue(f.androidStreamDeltas.isEmpty())
        assertEquals("22" to "23", f.mcuVolumeDelta)
        assertTrue(f.conclusion().contains("MCU volume changed"))
    }

    @Test
    fun `correlator says OEM-only when nothing android-side moved`() {
        val marker = MarkerRecord(MarkerType.NBT_KNOB_VOLUME_UP, "", 3000, 8000, 1, 5)
        val events = listOf(
            ev(1, EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(snap("MARKER_PRE", 10))),
            ev(5, EventKind.AUDIO_SNAPSHOT, EventPayload.AudioSnapshotPayload(snap("MARKER_POST", 10))),
        )
        val f = Correlator.analyse(marker, events)
        assertFalse(f.witsKeyBroadcast)
        assertTrue(f.conclusion().contains("OEM-only"))
    }

    @Test
    fun `correlator ignores events outside the marker window`() {
        val marker = MarkerRecord(MarkerType.STEERING_VOLUME_UP, "", 3000, 8000, 5, 9)
        val events = listOf(
            ev(1, EventKind.KEY_EVENT, EventPayload.KeyEventPayload("WITS_BROADCAST", 99, null, "down")),
            ev(7, EventKind.KEY_EVENT, EventPayload.KeyEventPayload("WITS_BROADCAST", 13, null, "down")),
        )
        val f = Correlator.analyse(marker, events)
        assertEquals(listOf(13), f.rawKeyCodes)
    }

    // ------------------------------------------------------- session event json

    @Test
    fun `session event survives a json round trip in seq order`() {
        val original = ev(42, EventKind.BROADCAST, EventPayload.Broadcast(
            "com.can.ACTION_ILL_INFO", CatalogStatus.KNOWN,
            BroadcastProbe.describe("status", 1)))
        val restored = SessionEvent.fromJson(original.toJson())

        assertEquals(original.seq, restored.seq)
        assertEquals(original.kind, restored.kind)
        assertEquals(original.elapsedRealtimeNanos, restored.elapsedRealtimeNanos)
        assertEquals(241, restored.sourceState.source)
        val json = (restored.payload as EventPayload.Raw).json
        assertEquals("com.can.ACTION_ILL_INFO", json.getString("action"))
    }

    @Test
    fun `replay ordering is deterministic by seq`() {
        val shuffled = listOf(
            rawEvent(3, EventKind.NOTE, """{"text":"c"}"""),
            rawEvent(1, EventKind.NOTE, """{"text":"a"}"""),
            rawEvent(2, EventKind.NOTE, """{"text":"b"}"""),
        )
        val ordered = shuffled.sortedBy { it.seq }.map {
            (it.payload as EventPayload.Raw).json.getString("text")
        }
        assertEquals(listOf("a", "b", "c"), ordered)
    }

    // ------------------------------------------------------- guided test matrix

    @Test
    fun `minimal matrix covers two sources and two controls`() {
        val steps = io.github.miklergm.witscompanion.signalexplorer.GuidedSession.minimalMatrix()
        assertEquals(4, steps.size)
        assertEquals(2, steps.map { it.source }.distinct().size)
        assertEquals(
            setOf(MarkerType.STEERING_VOLUME_UP, MarkerType.NBT_KNOB_VOLUME_UP),
            steps.map { it.marker }.toSet(),
        )
    }

    @Test
    fun `full matrix covers every source and both control families`() {
        val steps = io.github.miklergm.witscompanion.signalexplorer.GuidedSession.fullMatrix()
        val sources = steps.map { it.source }.distinct()
        assertEquals(
            io.github.miklergm.witscompanion.signalexplorer.GuidedSession.SourceUnderTest.entries.size,
            sources.size,
        )
        val markers = steps.map { it.marker }.toSet()
        assertTrue(MarkerType.STEERING_VOLUME_UP in markers)
        assertTrue(MarkerType.NBT_KNOB_VOLUME_UP in markers)
        assertTrue(MarkerType.SOURCE_OEM_TO_ANDROID in markers)
    }
}
