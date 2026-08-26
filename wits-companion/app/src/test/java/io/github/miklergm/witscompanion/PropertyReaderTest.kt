package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.carstate.PropertyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The bulk `getprop` fallback, and specifically whether its timeout is real.
 *
 * It was not. The timed `waitFor()` sat in a `finally` around the read loop, so it could only
 * run once the child had closed stdout — which is exactly the thing a wedged child never does.
 * A dump that stalled mid-write blocked the reader indefinitely and the bound was never
 * reached. On the constructor path that is a hang in `Application.onCreate`, on a unit whose
 * vendor watchdog wipes to recovery if the system does not come up in 80 s.
 *
 * Plain JUnit rather than Robolectric: these drive a real subprocess, and the point is the
 * wall clock.
 */
class PropertyReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun requireShell() {
        // The wedge needs a real shell. Skip rather than fail where there isn't one.
        assumeTrue("no /bin/sh", File("/bin/sh").canExecute())
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)

    // --------------------------------------------------------------- happy path

    @Test
    fun `a complete dump is parsed and served`() {
        val reader = PropertyReader(sh("""printf '[a.b]: [1]\n[c.d]: [two words]\n'"""))

        assertEquals(PropertyReader.Strategy.GETPROP_BULK, reader.activeStrategy)
        assertEquals("1", reader.get("a.b"))
        assertEquals("two words", reader.get("c.d"))
        assertNull(reader.get("absent.property"))
    }

    // ------------------------------------------------------------- the observed bug

    @Test(timeout = 15_000)
    fun `a child that wedges mid-dump does not hold the caller`() {
        // Writes one property, then holds the pipe open for far longer than anyone will wait.
        // `exec` matters: it makes the sleep *be* the process we kill, rather than a child of
        // it still holding the write end open after its parent dies.
        val wedged = sh("""printf '[a.b]: [1]\n'; exec sleep 30""")

        val startedAt = System.nanoTime()
        val reader = PropertyReader(wedged, bulkTimeoutMs = 500)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "constructing must return on the timeout, not on the child; took ${elapsedMs}ms",
            elapsedMs < 5_000,
        )
        assertEquals(PropertyReader.Strategy.UNAVAILABLE, reader.activeStrategy)
        assertTrue("diagnostics should name the timeout: ${reader.diagnostics}",
            reader.diagnostics.contains("Timeout"))
    }

    @Test(timeout = 15_000)
    fun `a truncated dump is discarded, not published`() {
        // The one property that did arrive before the stall must not be served: a partial dump
        // replacing the cache would turn every property after the stall point into "absent".
        val reader = PropertyReader(sh("""printf '[a.b]: [1]\n'; exec sleep 30"""), bulkTimeoutMs = 500)

        assertNull(reader.get("a.b"))
    }

    @Test(timeout = 20_000)
    fun `a timed-out refresh leaves the previous cache intact`() {
        // Absence of a property is *no new information* to CarSignalReducer, never a negative
        // reading — which is what stops a wedged subprocess from being able to clear a safety
        // signal. That only holds if a failed refresh keeps what the last good one read.
        val marker = File(temp.root, "probed")
        val firstGoodThenWedged = sh(
            """if [ -e '${marker.absolutePath}' ]; then exec sleep 30; """ +
                """else : > '${marker.absolutePath}'; printf '[wits.reverse]: [1]\n[a.b]: [2]\n'; fi""",
        )

        val reader = PropertyReader(firstGoodThenWedged, bulkTimeoutMs = 500)
        assertEquals(PropertyReader.Strategy.GETPROP_BULK, reader.activeStrategy)
        assertEquals("1", reader.get("wits.reverse"))

        assertEquals("a wedged refresh reports nothing", 0, reader.refreshBulk())
        assertEquals("the last good reading must survive it", "1", reader.get("wits.reverse"))
        assertEquals("2", reader.get("a.b"))
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a command that does not exist leaves the reader unavailable`() {
        val reader = PropertyReader(listOf("/nonexistent/getprop"))

        assertEquals(PropertyReader.Strategy.UNAVAILABLE, reader.activeStrategy)
        assertNull(reader.get("a.b"))
    }

    @Test
    fun `a dump with no parseable lines is not accepted as a strategy`() {
        val reader = PropertyReader(sh("""printf 'not a property line\n'"""))

        assertEquals(PropertyReader.Strategy.UNAVAILABLE, reader.activeStrategy)
        assertTrue(reader.diagnostics.contains("returned nothing"))
    }
}
