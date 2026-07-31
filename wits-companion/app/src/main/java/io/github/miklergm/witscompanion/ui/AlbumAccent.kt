package io.github.miklergm.witscompanion.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Picks an accent colour from album art, so the panel takes on the colour of what is
 * playing. Borrowed in spirit from Mini AA's `AlbumColors`.
 *
 * Deliberately self-contained rather than pulling in `androidx.palette`: the panel needs
 * one colour, not a palette, and this runs on a 16×16 downscale — a handful of
 * microseconds on the main thread.
 */
object AlbumAccent {

    /**
     * @return a colour suitable for text and thin lines on a dark background, or null
     *         when the art is too grey to yield one worth using
     */
    fun from(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return null

        val small = runCatching {
            Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        }.getOrNull() ?: return null

        val pixels = IntArray(SAMPLE * SAMPLE)
        runCatching { small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE) }
            .onFailure { if (small !== bitmap) small.recycle(); return null }
        if (small !== bitmap) small.recycle()
        return fromPixels(pixels)
    }

    /**
     * The colour decision itself, separated from bitmap handling so it can be tested
     * without a graphics stack.
     *
     * Averages hue on the colour wheel — as a vector, so red at 350° and red at 10°
     * average to red rather than to cyan — weighting each pixel by the square of its
     * saturation, so a few vivid pixels decide the accent rather than a large flat
     * background.
     */
    fun fromPixels(pixels: IntArray): Int? {
        var weight = 0f
        var hueX = 0f
        var hueY = 0f
        var satSum = 0f
        val hsv = FloatArray(3)

        for (pixel in pixels) {
            colorToHsv(pixel, hsv)
            val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
            // Only near-black is discarded: its hue is numerically unstable. White needs
            // no filter — weighting by saturation squared already gives it zero weight,
            // whereas a brightness cut-off would also throw away fully saturated colours,
            // which are exactly the ones worth keeping.
            if (v < MIN_VALUE) continue
            val w = s * s
            weight += w
            satSum += s * w
            val rad = Math.toRadians(h.toDouble())
            hueX += (Math.cos(rad) * w).toFloat()
            hueY += (Math.sin(rad) * w).toFloat()
        }
        if (weight <= 0f) return null

        val saturation = satSum / weight
        if (saturation < MIN_ACCENT_SATURATION) return null

        val hue = ((Math.toDegrees(Math.atan2(hueY.toDouble(), hueX.toDouble()))
            .toFloat()) + 360f) % 360f
        return hsvToColor(hue, saturation.coerceIn(0.35f, 0.85f), ACCENT_VALUE)
    }

    /**
     * Local HSV conversion rather than [Color.colorToHSV], so the decision above stays a
     * plain unit-testable function on the JVM.
     */
    private fun colorToHsv(color: Int, out: FloatArray) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        out[0] = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        out[1] = if (max == 0f) 0f else delta / max
        out[2] = max
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = (((f + m) * 255f).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private const val SAMPLE = 16
    private const val MIN_VALUE = 0.12f

    /** Below this the art is effectively greyscale and a tinted panel looks like a bug. */
    private const val MIN_ACCENT_SATURATION = 0.12f

    /** Bright enough to read on the panel's black background. */
    private const val ACCENT_VALUE = 0.92f
}
