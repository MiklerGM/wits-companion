package io.github.miklergm.witscompanion

import android.os.Bundle
import io.github.miklergm.witscompanion.signalexplorer.AudioSnapshot
import io.github.miklergm.witscompanion.signalexplorer.Availability
import io.github.miklergm.witscompanion.signalexplorer.BroadcastProbe
import io.github.miklergm.witscompanion.signalexplorer.CatalogDelta
import io.github.miklergm.witscompanion.signalexplorer.CapturedExtra
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
import io.github.miklergm.witscompanion.logging.LogRedactor
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

    // -------------------------------------------------------------- redaction

    @Test
    fun `session redaction catches sensitive values inside captured extras`() {
        // The shape that defeated string-level redaction: the real key is *data* in a "name"
        // field, so nothing a key walk looks at ever says "ssid".
        val extra = JSONObject()
            .put("name", "ssid")
            .put("javaType", "String")
            .put("value", "MyHomeNetwork")
        val event = JSONObject()
            .put("kind", "BROADCAST")
            .put("payload", JSONObject().put("extras", org.json.JSONArray().put(extra)))

        val out = LogRedactor.redactJson(event).toString()

        assertFalse("the SSID must not reach disk", out.contains("MyHomeNetwork"))
        assertTrue("the key itself is kept, so the capture stays useful", out.contains("ssid"))
    }

    @Test
    fun `sensitive extras are redacted across value hex and base64`() {
        val extra = JSONObject()
            .put("name", "phoneName")
            .put("javaType", "byte[]")
            .put("hex", "4a6f686e73")
            .put("base64", "Sm9obnM=")
        val out = LogRedactor.redactJson(extra).toString()
        assertFalse(out.contains("4a6f686e73"))
        assertFalse(out.contains("Sm9obnM="))
    }

    @Test
    fun `redaction recurses and leaves ordinary capture data intact`() {
        val nested = JSONObject().put(
            "outer",
            JSONObject().put("inner", JSONObject().put("password", "hunter2")),
        ).put("key_code", 42).put("action", "com.can.ACTION_KEY_CODE")

        val out = LogRedactor.redactJson(nested).toString()
        assertFalse("nested secrets are reached", out.contains("hunter2"))
        assertTrue("unrelated research data survives", out.contains("42"))
        assertTrue(out.contains("com.can.ACTION_KEY_CODE"))
    }

    @Test
    fun `an extra whose name is harmless is not redacted`() {
        val extra = JSONObject()
            .put("name", "key_status").put("javaType", "String").put("value", "down")
        assertTrue(LogRedactor.redactJson(extra).toString().contains("down"))
    }

    @Test
    fun `media metadata is redacted by default and kept under the debug opt-in`() {
        val extra = JSONObject()
            .put("name", "title").put("javaType", "String").put("value", "Some Song")
        assertFalse(LogRedactor.redactJson(extra).toString().contains("Some Song"))
        assertTrue(
            LogRedactor.redactJson(extra, verboseMedia = true).toString().contains("Some Song"),
        )
    }

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

    // ------------------------------------------------- bounds on what arrives

    /**
     * The probe's receiver is registered EXPORTED — it has to be, or the vendor's own
     * broadcasts would not reach it — so any installed app can send a catalogued action with
     * extras of its choosing. The walk that turns those extras into readings had no limit of
     * any kind, and it runs inside `onReceive` on the main thread with 2000 events retained.
     *
     * These pin the three axes. None of them is reachable by a real vendor payload.
     */

    private fun deepBundle(levels: Int): Bundle {
        var b = Bundle().apply { putInt("leaf", 1) }
        repeat(levels) { b = Bundle().apply { putBundle("n", b) } }
        return b
    }

    @Test
    fun `nesting stops at the depth limit instead of recursing`() {
        val extras = BroadcastProbe.flatten(deepBundle(200))

        val marker = extras.single { it.name == BroadcastProbe.TRUNCATION_MARKER }
        assertEquals("truncated:depth", marker.value)
        // Nothing from below the cut: the deepest name has one segment per followed level.
        val deepest = extras.filter { it.name != BroadcastProbe.TRUNCATION_MARKER }
            .maxOf { it.name.count { c -> c == '.' } }
        assertTrue("followed $deepest levels", deepest <= BroadcastProbe.MAX_DEPTH)
    }

    @Test
    fun `a huge array does not become one reading per element`() {
        val extras = BroadcastProbe.describe("arr", IntArray(500_000) { it })

        assertTrue("got ${extras.size} readings", extras.size <= BroadcastProbe.MAX_EXTRAS + 1)
        assertTrue(extras.any { it.name == BroadcastProbe.TRUNCATION_MARKER })
        // What did fit is still real data, and still the start of the array.
        assertEquals("0", extras.first { it.name == "arr[0]" }.value)
    }

    @Test
    fun `a large blob is rendered in part and reports its true size`() {
        val payload = ByteArray(64 * 1024) { 0x41 }
        val extra = BroadcastProbe.describe("data", payload).first { it.name == "data" }

        assertEquals("the true length must survive", 64 * 1024, extra.length)
        assertEquals(BroadcastProbe.MAX_BINARY_BYTES * 2, extra.hex!!.length)
        assertTrue("a partial blob must say so: ${extra.value}", extra.value!!.contains("truncated"))
    }

    @Test
    fun `a long value is shortened and marked`() {
        val extras = BroadcastProbe.describe("s", "x".repeat(200_000))
        val extra = extras.first { it.name == "s" }

        assertTrue(extra.value!!.length <= BroadcastProbe.MAX_VALUE_CHARS + 1)
        assertTrue("an ellipsis marks the cut", extra.value!!.endsWith("\u2026"))
        assertTrue(extras.any { it.name == BroadcastProbe.TRUNCATION_MARKER })
    }

    @Test
    fun `total capture size is bounded across many extras`() {
        val b = Bundle().apply {
            repeat(400) { i -> putString("k$i", "y".repeat(1_000)) }
        }
        val extras = BroadcastProbe.flatten(b)
        val chars = extras.sumOf { it.name.length + (it.value?.length ?: 0) }

        assertTrue("capture held $chars chars", chars <= BroadcastProbe.MAX_CAPTURE_CHARS + 64)
        assertTrue(extras.any { it.name == BroadcastProbe.TRUNCATION_MARKER })
    }

    @Test
    fun `object arrays and lists obey the depth limit too`() {
        // The gate used to sit on the Bundle branch alone, so these two — the container types
        // that can hold each other, and the easiest to nest from an ordinary Intent extra —
        // recursed to whatever depth the sender built.
        var nestedArray: Any = arrayOf("leaf")
        repeat(200) { nestedArray = arrayOf(nestedArray) }
        val fromArray = BroadcastProbe.describe("arr", nestedArray)
        assertTrue(fromArray.any { it.name == BroadcastProbe.TRUNCATION_MARKER })

        var nestedList: Any = arrayListOf("leaf")
        repeat(200) { nestedList = arrayListOf(nestedList) }
        val fromList = BroadcastProbe.describe("list", nestedList)
        assertTrue(fromList.any { it.name == BroadcastProbe.TRUNCATION_MARKER })

        val deepest = fromArray.filter { it.name != BroadcastProbe.TRUNCATION_MARKER }
            .maxOf { it.name.count { c -> c == '[' } }
        assertTrue("followed $deepest levels", deepest <= BroadcastProbe.MAX_DEPTH + 1)
    }

    @Test(timeout = 10_000)
    fun `a cyclic collection is traversed, not rendered`() {
        // LinkedList is Serializable, so a sender can put one in an extra, and Java
        // serialization carries cycles faithfully. It is not an ArrayList, so it fell through to
        // toString() — and AbstractCollection.toString on two mutually referencing lists
        // recurses until the stack goes, on the main thread, inside a receiver any installed app
        // can reach. Reproduced directly before this change.
        val a = java.util.LinkedList<Any>()
        val b = java.util.LinkedList<Any>()
        a.add(b)
        b.add(a)

        val extras = BroadcastProbe.describe("cycle", a)

        assertTrue("the depth limit must cut the cycle", extras.any { it.name == BroadcastProbe.TRUNCATION_MARKER })
        assertTrue("and it must not have been rendered whole", extras.none { (it.value?.length ?: 0) > BroadcastProbe.MAX_VALUE_CHARS })
    }

    @Test
    fun `sets and maps are traversed too`() {
        val b = Bundle().apply {
            putSerializable("set", java.util.LinkedHashSet(listOf("x", "y")))
            putSerializable("map", java.util.LinkedHashMap(mapOf("k" to 1, "j" to 2)))
        }
        val names = BroadcastProbe.flatten(b).map { it.name }

        assertTrue("$names", "set[0]" in names && "set[1]" in names)
        assertTrue("$names", "map{k}" in names && "map{j}" in names)
    }

    @Test
    fun `a bundle stopped at the reading cap says it was truncated`() {
        // Exactly at the boundary the top-level walk used to break out silently: the marker is
        // added by the budget, and breaking before ever calling emit() never told it.
        val b = Bundle().apply {
            repeat(BroadcastProbe.MAX_EXTRAS + 1) { i -> putInt("k$i", i) }
        }
        val extras = BroadcastProbe.flatten(b)

        assertTrue(extras.any { it.name == BroadcastProbe.TRUNCATION_MARKER })
        assertEquals(BroadcastProbe.MAX_EXTRAS + 1, extras.size)
    }

    @Test
    fun `the session-long catalogue summary is bounded and says when it was cut`() {
        // The delta is not part of the ring, so no per-event cap bounds it. Extra keys are
        // sender-chosen, and one indexed array yields a distinct key per element.
        val delta = CatalogDelta()
        repeat(50) { round ->
            delta.record(
                "com.can.ACTION_KEY_CODE",
                (0 until 100).map { CapturedExtra("k${round}_$it", "int", "1") },
                emptyList(), emptyList(),
            )
        }

        assertTrue("5000 distinct keys must not all be kept", delta.truncated)
        val json = delta.toJson(EventCatalog.parse(catalogJson))
        val kept = json.getJSONObject("actionsSeen").getJSONArray("com.can.ACTION_KEY_CODE").length()
        assertEquals(CatalogDelta.MAX_KEYS_PER_ACTION, kept)
        assertTrue(json.has("truncated"))
    }

    @Test
    fun `an ordinary vendor payload is untouched and unmarked`() {
        // What this actually exists to capture: a CAN frame and a few small extras. Nothing
        // here should come near a limit, or the bounds would be quietly costing evidence.
        val b = Bundle().apply {
            putInt("key_code", 13)
            putString("key_status", "0")
            putByteArray("frame", byteArrayOf(0x2D, 0x04, 0x01, 0x00, 0x11, 0x22, 0x33, 0x44))
        }
        val extras = BroadcastProbe.flatten(b)

        assertFalse(extras.any { it.name == BroadcastProbe.TRUNCATION_MARKER })
        assertEquals(3, extras.size)
        assertEquals(8, extras.first { it.name == "frame" }.length)
        assertEquals(null, extras.first { it.name == "frame" }.value)
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
