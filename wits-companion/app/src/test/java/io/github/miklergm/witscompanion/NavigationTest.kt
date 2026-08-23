package io.github.miklergm.witscompanion

import android.app.Notification
import android.os.Bundle
import io.github.miklergm.witscompanion.nav.NavigationRepository
import io.github.miklergm.witscompanion.nav.NavigationSnapshot
import io.github.miklergm.witscompanion.ui.CockpitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading the next manoeuvre out of a navigation app's notification.
 *
 * The extraction is a best effort against extras whose meaning varies by app and by version,
 * so what is pinned here is the *shape* of the decisions — which field becomes the
 * instruction, when a row is shown at all, and that nothing is invented when the notification
 * carries no usable text.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationTest {

    private fun extras(
        title: String? = null,
        text: String? = null,
        sub: String? = null,
    ) = Bundle().apply {
        title?.let { putCharSequence(Notification.EXTRA_TITLE, it) }
        text?.let { putCharSequence(Notification.EXTRA_TEXT, it) }
        sub?.let { putCharSequence(Notification.EXTRA_SUB_TEXT, it) }
    }

    private val maps = "com.google.android.apps.maps"

    // ---------------------------------------------------------------- parsing

    @Test
    fun `maps puts the distance in the title and the manoeuvre in the text`() {
        val s = NavigationRepository.parse(maps, extras("300 m", "Turn right onto Bornholmer Straße", "12 min"))
        assertEquals("300 m", s.distance)
        assertEquals("Turn right onto Bornholmer Straße", s.instruction)
        assertEquals("12 min", s.eta)
        assertTrue(s.hasInstruction)
    }

    @Test
    fun `a navigator that inverts the two is still readable`() {
        // Title is the manoeuvre, not a distance — so it must be used as the instruction
        // rather than silently shown in the distance slot.
        val s = NavigationRepository.parse("com.waze", extras("Turn left", "in 200 m"))
        assertEquals("Turn left", s.instruction)
        assertNull(s.distance)
    }

    @Test
    fun `distance recognition accepts the usual units and formats`() {
        listOf("300 m", "1.2 km", "0,5 km", "250 ft", "0.5 mi", "80 yd").forEach { d ->
            val s = NavigationRepository.parse(maps, extras(d, "Continue"))
            assertEquals("$d should be read as a distance", d, s.distance)
            assertEquals("Continue", s.instruction)
        }
    }

    @Test
    fun `a street name that starts with a number is not mistaken for a distance`() {
        // "5 Avenue" must not be swallowed into the distance slot and lost.
        val s = NavigationRepository.parse(maps, extras("5 Avenue", "Continue"))
        assertEquals("5 Avenue", s.instruction)
        assertNull(s.distance)
    }

    @Test
    fun `blank extras are treated as absent`() {
        val s = NavigationRepository.parse(maps, extras("   ", "", null))
        assertNull(s.instruction)
        assertNull(s.distance)
        assertFalse("nothing usable means no row", s.hasInstruction)
    }

    @Test
    fun `a notification with only a title still yields an instruction`() {
        val s = NavigationRepository.parse(maps, extras(title = "Head north"))
        assertEquals("Head north", s.instruction)
        assertTrue(s.hasInstruction)
    }

    // ------------------------------------------------------------- the panel

    @Test
    fun `no navigation means no row`() {
        assertFalse(CockpitState.navigation(null).visible)
        assertFalse(CockpitState.navigation(NavigationSnapshot()).visible)
    }

    @Test
    fun `navigation running but unreadable also means no row`() {
        // An empty strip takes space and tells the driver nothing — worse than none.
        val running = NavigationSnapshot(available = true, packageName = maps, instruction = null)
        assertFalse(CockpitState.navigation(running).visible)
    }

    @Test
    fun `the panel passes distance and eta through unchanged`() {
        // Never reformatted: converting the app's own units is how the panel starts
        // disagreeing with the map beside it.
        val s = NavigationSnapshot(
            available = true, packageName = maps,
            instruction = "Turn right", distance = "0.4 mi", eta = "8 min",
        )
        val panel = CockpitState.navigation(s)
        assertTrue(panel.visible)
        assertEquals("0.4 mi", panel.distance)
        assertEquals("8 min", panel.eta)
        assertEquals("Turn right", panel.instruction)
    }

    // ------------------------------------------------------------ allowlist

    @Test
    fun `only allowlisted navigation packages are considered`() {
        // CATEGORY_NAVIGATION is self-declared, so any app could claim it. Reading notification
        // text is a capability worth keeping narrow; both conditions must hold.
        assertTrue(maps in NavigationRepository.NAVIGATION_PACKAGES)
        assertTrue("com.waze" in NavigationRepository.NAVIGATION_PACKAGES)
        assertFalse("com.example.chat" in NavigationRepository.NAVIGATION_PACKAGES)
        assertFalse(
            "the companion must not read its own notifications",
            "io.github.miklergm.witscompanion" in NavigationRepository.NAVIGATION_PACKAGES,
        )
    }
}
