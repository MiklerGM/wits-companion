package io.github.miklergm.witscompanion

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.github.miklergm.witscompanion.ui.TransportMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Where the play button ends up.
 *
 * The row centres its children and the save-to-collection button is only there for players that
 * offer one, so toggling it between GONE and VISIBLE re-centred the whole group: play moved
 * 39dp — half a slot — depending on which app was playing. In a car that is the control you
 * reach for without looking, so its position must not depend on the player.
 *
 * These measure the arrangement rather than inspect it, because the defect was arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
class TransportRowTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun button(sizeDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            leftMargin = dp(TransportMetrics.MARGIN_DP)
            rightMargin = dp(TransportMetrics.MARGIN_DP)
        }
    }

    /** The row as DashboardActivity builds it: a reserved flank, the transport, the optional. */
    private fun row(
        collectionVisible: Boolean,
        reserveFlank: Boolean = true,
        /** How absence is expressed. GONE surrenders the slot; INVISIBLE keeps it. */
        hideWithGone: Boolean = false,
    ): Pair<LinearLayout, View> {
        val play = button(TransportMetrics.EMPHASISED_DP)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (reserveFlank) addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(TransportMetrics.SLOT_DP), 1)
            })
            addView(button(TransportMetrics.BUTTON_DP))
            addView(play)
            addView(button(TransportMetrics.BUTTON_DP))
            addView(
                button(TransportMetrics.BUTTON_DP).apply {
                    visibility = when {
                        collectionVisible -> View.VISIBLE
                        hideWithGone -> View.GONE
                        else -> View.INVISIBLE
                    }
                }
            )
        }
        val width = dp(840)   // the panel tile on this unit
        layout.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        layout.layout(0, 0, width, layout.measuredHeight)
        return layout to play
    }

    private fun playOffsetFromCentre(
        collectionVisible: Boolean,
        reserveFlank: Boolean = true,
        hideWithGone: Boolean = false,
    ): Int {
        val (layout, play) = row(collectionVisible, reserveFlank, hideWithGone)
        return (play.left + play.right) / 2 - layout.width / 2
    }

    @Test
    fun `play sits on the centre line whether or not the player offers a collection action`() {
        assertEquals("with the button showing", 0, playOffsetFromCentre(true))
        assertEquals("and without it", 0, playOffsetFromCentre(false))
    }

    @Test
    fun `losing the collection action does not move play at all`() {
        // The symptom as it appears: a track loads, its like state resolves, and the row jumps.
        assertEquals(playOffsetFromCentre(true), playOffsetFromCentre(false))
    }

    /**
     * The arrangement this replaced, so the assertions above are known to be able to fail.
     *
     * Four centred buttons with no reserved flank put play half a slot left of centre, and
     * three put it dead centre — which is the jump you see when a track's like state resolves.
     */
    @Test
    fun `without a reserved flank the old arrangement moves play by half a slot`() {
        val withButton = playOffsetFromCentre(
            collectionVisible = true, reserveFlank = false, hideWithGone = true,
        )
        val without = playOffsetFromCentre(
            collectionVisible = false, reserveFlank = false, hideWithGone = true,
        )

        assertEquals(-dp(TransportMetrics.SLOT_DP) / 2, withButton)
        assertEquals(0, without)
        assertEquals(dp(TransportMetrics.SLOT_DP) / 2, without - withButton)
    }

    @Test
    fun `a reserved flank is exactly one optional control wide`() {
        assertEquals(
            TransportMetrics.BUTTON_DP + TransportMetrics.MARGIN_DP * 2,
            TransportMetrics.SLOT_DP,
        )
    }

    /** Structural: the row the app builds must be the one measured above. */
    @Test
    fun `the panel reserves a flank and never removes the optional button`() {
        val src = listOf(
            "src/main/java/io/github/miklergm/witscompanion/ui/DashboardActivity.kt",
            "app/src/main/java/io/github/miklergm/witscompanion/ui/DashboardActivity.kt",
        ).first { File(it).exists() }.let { File(it).readText() }

        assertTrue("the leading slot has to be added first", src.contains("transport.addView(transportSlot())"))
        assertTrue(
            "the optional button keeps its space",
            src.contains("collectionButton.visibility = View.INVISIBLE"),
        )
        val render = src.substringAfter("private fun renderCollection(").substringBefore("\n    }")
        assertFalse("GONE would re-centre the row", render.contains("View.GONE"))
    }
}
