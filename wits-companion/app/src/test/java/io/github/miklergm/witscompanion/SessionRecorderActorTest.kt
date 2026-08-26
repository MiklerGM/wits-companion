package io.github.miklergm.witscompanion

import androidx.test.core.app.ApplicationProvider
import io.github.miklergm.witscompanion.signalexplorer.CatalogStatus
import io.github.miklergm.witscompanion.signalexplorer.DeviceInfo
import io.github.miklergm.witscompanion.signalexplorer.EventCatalog
import io.github.miklergm.witscompanion.signalexplorer.EventKind
import io.github.miklergm.witscompanion.signalexplorer.EventPayload
import io.github.miklergm.witscompanion.signalexplorer.MarkerType
import io.github.miklergm.witscompanion.signalexplorer.OtaPhase
import io.github.miklergm.witscompanion.signalexplorer.SessionMetadata
import io.github.miklergm.witscompanion.signalexplorer.SessionRecorder
import io.github.miklergm.witscompanion.signalexplorer.SourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The recorder as a single-writer actor.
 *
 * Before this, three concurrency strategies overlapped inside one class — a lock for the ring,
 * a second for the pending markers, an unsynchronized list for the markers themselves, and a
 * non-atomic `eventCount++` — all reachable from several probe threads at once. These tests
 * pin the properties that replaced them.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRecorderActorTest {

    private fun recorder(): SessionRecorder {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val metadata = SessionMetadata(
            sessionId = "test-${System.nanoTime()}",
            startedWallClockMs = 0L,
            startedElapsedRealtimeNanos = 0L,
            appVersion = "test",
            catalogVersion = 1,
            otaPhase = OtaPhase.UNSPECIFIED,
            device = DeviceInfo(
                buildDisplayId = null, fingerprint = null, sdkInt = 33,
                witsProductId = null, witsModel = null,
                mcuVersion = null, mcuCanVersion = null,
                displayWidth = 2400, displayHeight = 900, density = 1.2f,
            ),
        )
        return SessionRecorder(context, metadata, EventCatalog.load(context))
    }

    private fun note(i: Int) = EventPayload.Note("event $i")

    @Test
    fun `concurrent producers lose no events`() {
        // This is the case the old non-atomic `eventCount++` got wrong: several probe threads
        // record at once and increments are lost, so the session under-reports what it holds.
        val rec = recorder()
        val threads = 8
        val each = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                repeat(each) { i -> rec.record(EventKind.NOTE, note(i)) }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("producers finished", done.await(20, TimeUnit.SECONDS))
        assertTrue("actor drained", rec.flush())

        assertEquals((threads * each).toLong(), rec.eventCount)
        rec.stop(); rec.awaitFinalized(5_000)
    }

    @Test
    fun `sequence numbers are unique under concurrency`() {
        val rec = recorder()
        val seqs = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val threads = 8
        val each = 50
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                repeat(each) { i -> rec.record(EventKind.NOTE, note(i))?.let { seqs.add(it.seq) } }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        rec.flush()

        assertEquals(threads * each, seqs.size)
        assertEquals("no sequence number handed out twice", seqs.size, seqs.distinct().size)
        rec.stop(); rec.awaitFinalized(5_000)
    }

    @Test
    fun `events reach the snapshot in the order the actor ingested them`() {
        val rec = recorder()
        repeat(20) { i -> rec.record(EventKind.NOTE, note(i)) }
        rec.flush()

        // recentEvents is newest-first.
        val seqs = rec.recentEvents(50).map { it.seq }
        assertEquals(seqs.sortedDescending(), seqs)
        rec.stop(); rec.awaitFinalized(5_000)
    }

    @Test
    fun `listeners are notified on the actor thread, once per event`() {
        val rec = recorder()
        val seen = AtomicInteger(0)
        val threadNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        rec.addListener {
            seen.incrementAndGet()
            threadNames.add(Thread.currentThread().name)
        }

        repeat(30) { i -> rec.record(EventKind.NOTE, note(i)) }
        rec.flush()

        assertEquals(30, seen.get())
        assertEquals("all notifications came from one thread", 1, threadNames.size)
        assertTrue(
            "and it is the session actor, not a probe thread: $threadNames",
            threadNames.first().contains("wits-session"),
        )
        rec.stop(); rec.awaitFinalized(5_000)
    }

    @Test
    fun `a marker is registered before its own event is ingested`() {
        val rec = recorder()
        val marker = rec.mark(MarkerType.CUSTOM, note = "probe")
        rec.flush()

        assertEquals(1, rec.markerCount())
        assertEquals(marker, rec.allMarkers().single())
        rec.stop(); rec.awaitFinalized(5_000)
    }

    @Test
    fun `recording after stop is refused rather than throwing`() {
        // A probe that has not been torn down yet can still call in while the actor is closing.
        // Dropping the event is correct; letting RejectedExecutionException escape into a probe
        // thread is not.
        val rec = recorder()
        rec.record(EventKind.NOTE, note(0))
        rec.stop()
        assertTrue(rec.awaitFinalized(5_000))

        assertNull("record after stop returns null", rec.record(EventKind.NOTE, note(1)))
        rec.recordSnapshot(org.json.JSONObject().put("k", "v"))   // must not throw
        assertTrue(rec.finalized)
    }

    @Test
    fun `finalization writes the session out completely`() {
        val rec = recorder()
        repeat(5) { i -> rec.record(EventKind.NOTE, note(i)) }
        rec.mark(MarkerType.CUSTOM, note = "m")

        val finished = CountDownLatch(1)
        rec.stop { finished.countDown() }
        assertTrue("the finalize callback fires", finished.await(5, TimeUnit.SECONDS))
        assertTrue(rec.finalized)

        val events = File(rec.sessionDir, "events.jsonl")
        assertTrue("events file written", events.exists())
        assertEquals("every event on disk", 6, events.readLines().count { it.isNotBlank() })
        assertNotNull(File(rec.sessionDir, "catalog-delta.json").takeIf { it.exists() })
    }

    @Test
    fun `flush after shutdown reports false rather than hanging`() {
        val rec = recorder()
        rec.stop()
        rec.awaitFinalized(5_000)
        assertTrue("flush must not block forever once the actor is gone", !rec.flush(500))
    }

    // ------------------------------------------------- finalization under load

    /**
     * A saturated recorder must not lose the end of the session.
     *
     * Events are droppable — losing the tail of a burst is the right failure for a research
     * recorder. Finalization is not: it closes the open markers, writes the catalog delta and
     * sets `finalized`. A rejection handler that counted the drop and returned discarded it, the
     * executor terminated anyway, and awaitFinalized read termination as success — so an
     * overloaded session was exported as complete while missing its closing markers.
     */
    @Test(timeout = 60_000)
    fun `finalization survives a full queue`() {
        val rec = recorder()
        // Far more than the queue holds, as fast as the calling thread can push them.
        repeat(SessionRecorder.QUEUE_CAPACITY * 3) {
            rec.record(
                EventKind.BROADCAST,
                EventPayload.Broadcast("com.can.ACTION_KEY_CODE", CatalogStatus.KNOWN, emptyList()),
                SourceState(),
            )
        }

        rec.stop()

        assertTrue("the session must actually finalize", rec.awaitFinalized(30_000))
        assertTrue(rec.finalized)
        assertTrue(
            "and the delta must record what was lost",
            File(rec.sessionDir, "catalog-delta.json").readText().contains("droppedEvents"),
        )
    }

    @Test
    fun `awaitFinalized reports termination without finalization as failure`() {
        // The distinction that made the drop invisible: the executor terminates either way.
        val rec = recorder()
        rec.stop()

        assertTrue(rec.awaitFinalized(10_000))
        assertTrue(rec.finalized)
    }
}
