package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.ui.AlbumAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The accent decision, tested without a graphics stack.
 *
 * The panel tints the track title and the progress bar with this colour, so a wrong
 * answer is not cosmetic: a grey or near-black accent makes the title unreadable on the
 * black background.
 */
class AlbumAccentTest {

    private fun fill(color: Int, n: Int = 256) = IntArray(n) { color }

    private fun hueOf(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    @Test
    fun `a vivid image yields an accent of the same hue`() {
        val accent = AlbumAccent.fromPixels(fill(0xFF2050C0.toInt()))
        assertNotNull(accent)
        assertEquals("blue stays blue", hueOf(0xFF2050C0.toInt()), hueOf(accent!!), 8f)
    }

    @Test
    fun `greyscale art yields no accent`() {
        assertNull(AlbumAccent.fromPixels(fill(0xFF808080.toInt())))
    }

    @Test
    fun `an all-black image yields no accent`() {
        assertNull(AlbumAccent.fromPixels(fill(0xFF000000.toInt())))
    }

    @Test
    fun `an empty image yields no accent`() {
        assertNull(AlbumAccent.fromPixels(IntArray(0)))
    }

    /**
     * Hue is averaged as a vector on the colour wheel. Averaging the numbers instead
     * would turn two reds either side of 0° into cyan — the opposite colour.
     */
    @Test
    fun `reds either side of zero average to red, not to the opposite hue`() {
        val pixels = IntArray(256) { i ->
            if (i % 2 == 0) 0xFFFF0033.toInt() else 0xFFFF3300.toInt()
        }
        val accent = AlbumAccent.fromPixels(pixels)
        assertNotNull(accent)
        val hue = hueOf(accent!!)
        assertEquals("must stay near red", 0f, if (hue > 180f) 360f - hue else hue, 20f)
    }

    /** Whatever the source brightness, the accent must be readable on black. */
    @Test
    fun `the accent is always bright enough to read`() {
        listOf(0xFF102030, 0xFF3060FF, 0xFF00FF00, 0xFF801040).forEach { value ->
            val c = value.toInt()
            val accent = AlbumAccent.fromPixels(fill(c)) ?: return@forEach
            val brightness = maxOf(
                (accent shr 16) and 0xFF,
                (accent shr 8) and 0xFF,
                accent and 0xFF,
            )
            org.junit.Assert.assertTrue(
                "accent for ${Integer.toHexString(c)} too dark: $brightness",
                brightness >= 200,
            )
        }
    }
}
