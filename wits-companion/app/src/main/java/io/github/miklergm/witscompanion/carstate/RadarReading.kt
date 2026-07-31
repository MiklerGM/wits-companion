package io.github.miklergm.witscompanion.carstate

/**
 * Decodes the vendor `can.radar` string into eight named PDC sensors.
 *
 * The firmware publishes all sensors as one colon-separated string, e.g. `"2:0:0:4:0:0:0:0"`
 * (`RadarManager` in MiscService reads exactly this property, `can.radar`). Each field is a
 * proximity level; `RadarManager.GetRadarLevel` treats **0 as "no reading / farthest"** and
 * larger values as closer, so `null`/absent and `0` both mean "nothing there".
 *
 * Position labels follow the vendor's own `vendor.can.radarN` naming — indices 0..3 front
 * (LF, LMF, RMF, RF), 4..7 rear (LB, LMB, RMB, RB). **This front/rear split is the vendor
 * convention, not yet confirmed on this vehicle:** whether `can.radar` even carries front
 * data outside reverse is the open question this decoder exists to answer, by letting the
 * value be watched while parking forward. Present all eight raw so the truth is visible
 * regardless of the labels.
 */
data class RadarReading(
    val values: List<Int?>,
) {
    val frontLeft: Int? get() = values.getOrNull(0)
    val frontMidLeft: Int? get() = values.getOrNull(1)
    val frontMidRight: Int? get() = values.getOrNull(2)
    val frontRight: Int? get() = values.getOrNull(3)
    val rearLeft: Int? get() = values.getOrNull(4)
    val rearMidLeft: Int? get() = values.getOrNull(5)
    val rearMidRight: Int? get() = values.getOrNull(6)
    val rearRight: Int? get() = values.getOrNull(7)

    val front: List<Int?> get() = values.take(4)
    val rear: List<Int?> get() = values.drop(4).take(4)

    /** True when any front sensor reports a non-zero level — the forward-parking signal. */
    val anyFrontActive: Boolean get() = front.any { it != null && it > 0 }
    val anyRearActive: Boolean get() = rear.any { it != null && it > 0 }

    /** The closest (largest) level across the front sensors, or null if none read. */
    val frontClosest: Int? get() = front.filterNotNull().filter { it > 0 }.maxOrNull()

    fun isEmpty(): Boolean = values.all { it == null || it == 0 }

    companion object {
        const val SENSOR_COUNT = 8

        val FRONT_LABELS = listOf("F-L", "F-ML", "F-MR", "F-R")
        val REAR_LABELS = listOf("R-L", "R-ML", "R-MR", "R-R")

        /**
         * Parses a `can.radar` string. Returns null only when the input is null or blank;
         * a malformed field becomes a null sensor rather than discarding the whole reading.
         */
        fun parse(raw: String?): RadarReading? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.trim().split(':')
            val values = (0 until SENSOR_COUNT).map { i ->
                parts.getOrNull(i)?.trim()?.toIntOrNull()
            }
            return RadarReading(values)
        }
    }
}
