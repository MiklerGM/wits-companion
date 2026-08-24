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

    @Test
    fun `zero distance is shown, because it means the turn is here`() {
        // Not a placeholder. "0 m" is the moment the manoeuvre arrives, which is exactly when
        // the driver needs the number — suppressing it would hide the most important reading
        // `[RUNTIME]` 2026-08-24.
        listOf("0 m", "0,0 km").forEach { z ->
            val s = NavigationRepository.parse(maps, extras(z, "Turn right"))
            assertEquals("$z must be shown", z, s.distance)
            assertEquals("Turn right", s.instruction)
        }
    }

    @Test
    fun `a real distance still shows`() {
        assertEquals("300 m", NavigationRepository.parse(maps, extras("300 m", "Turn")).distance)
        assertEquals("0.4 mi", NavigationRepository.parse(maps, extras("0.4 mi", "Turn")).distance)
    }

    @Test
    fun `the maps notification shape seen on the vehicle parses correctly`() {
        // Exactly what the unit posted on 2026-08-24: an empty title, the road in the text,
        // and the trip summary in subText. The title=distance assumption does not hold here,
        // and the fallback is what makes it work.
        val s = NavigationRepository.parse(
            maps, extras(title = "", text = "toward Norwegerstraße", sub = "27 min · 10 km · 10:35 ETA"),
        )
        assertEquals("toward Norwegerstraße", s.instruction)
        assertNull("there is no per-manoeuvre distance in this shape", s.distance)
        assertEquals("27 min · 10 km · 10:35 ETA", s.eta)
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

    // --------------------------------------------------- posted/removed cycles

    private fun sbn(
        pkg: String = "com.google.android.apps.maps",
        id: Int = 1,
        title: String? = "300 m",
        text: String? = "Turn right",
        ongoing: Boolean = true,
        category: String? = Notification.CATEGORY_NAVIGATION,
        postTime: Long = 1_000L,
    ): android.service.notification.StatusBarNotification {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val n = Notification.Builder(ctx, "chan").apply {
            setSmallIcon(android.R.drawable.ic_menu_directions)
            title?.let { setContentTitle(it) }
            text?.let { setContentText(it) }
            category?.let { setCategory(it) }
            setOngoing(ongoing)
        }.build()
        return android.service.notification.StatusBarNotification(
            pkg, pkg, id, "tag$id", 0, 0, 0, n,
            android.os.UserHandle.getUserHandleForUid(0), postTime,
        )
    }

    @Test
    fun `removing one notification does not clear another`() {
        // The bug this replaces: identity was the package, so removing any Maps notification
        // blanked the instruction from a different one.
        val r = NavigationRepository()
        r.onPosted(sbn(id = 1, title = "300 m", text = "Turn right"))
        r.onPosted(sbn(id = 2, title = "1.2 km", text = "Keep left", postTime = 2_000L))
        assertEquals("Keep left", r.snapshot.instruction)

        r.onRemoved(sbn(id = 1))
        assertTrue("the surviving notification must still be shown", r.snapshot.hasInstruction)
        assertEquals("Keep left", r.snapshot.instruction)

        r.onRemoved(sbn(id = 2))
        assertFalse("with none left, the row goes away", r.snapshot.hasInstruction)
    }

    @Test
    fun `a notification that stops being navigation is dropped`() {
        // The route ends and the same notification is reposted as an ordinary one. Ignoring it
        // because it no longer looks like navigation left the finished manoeuvre on screen.
        val r = NavigationRepository()
        r.onPosted(sbn(id = 1))
        assertTrue(r.snapshot.hasInstruction)

        r.onPosted(sbn(id = 1, category = null, ongoing = false, title = "Rate your trip"))
        assertFalse("stale guidance must not survive the route", r.snapshot.hasInstruction)
    }

    @Test
    fun `removal is honoured even after declassification`() {
        // onRemoved must not re-test isNavigation: a declassified notification would fail it,
        // and the key would never be forgotten.
        val r = NavigationRepository()
        r.onPosted(sbn(id = 1))
        r.onRemoved(sbn(id = 1, category = null, ongoing = false))
        assertFalse(r.snapshot.hasInstruction)
    }

    @Test
    fun `the most recently posted guidance wins`() {
        val r = NavigationRepository()
        r.onPosted(sbn(pkg = "com.google.android.apps.maps", id = 1, text = "Turn right"))
        r.onPosted(sbn(pkg = "com.waze", id = 2, text = "Exit here", postTime = 2_000L))
        assertEquals("Exit here", r.snapshot.instruction)
    }

    @Test
    fun `a non-navigation notification from an allowed app is ignored`() {
        // Maps posts media and service notifications too; those must not overwrite guidance.
        val r = NavigationRepository()
        r.onPosted(sbn(id = 1, text = "Turn right"))
        r.onPosted(sbn(id = 9, category = Notification.CATEGORY_TRANSPORT, text = "Now playing"))
        assertEquals("Turn right", r.snapshot.instruction)
    }

    @Test
    fun `clear empties everything`() {
        val r = NavigationRepository()
        r.onPosted(sbn(id = 1))
        r.clear()
        assertFalse(r.snapshot.hasInstruction)
        assertTrue(r.lastRawExtras.isEmpty())
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
