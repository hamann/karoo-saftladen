package io.github.hamann.saftladen.report

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

class ReportStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(maxReports: Int = 200, maxAge: Duration = Duration.ofDays(30)) =
        ReportStore(
            directory = folder.root.resolve("reports"),
            maxReports = maxReports,
            maxAge = maxAge,
        )

    /** Millisecond precision, which is what the buffer round-trips through file names. */
    private val now: Instant = Instant.ofEpochMilli(System.currentTimeMillis())

    private fun report(id: String) = BatteryReport(
        reportId = id,
        trigger = ReportTrigger.RIDE_END,
        createdAt = "2026-10-07T12:00:00Z",
        karoo = KarooDeviceInfo(extensionVersion = "1.0.0"),
        sensors = emptyList(),
    )

    @Test
    fun `buffers a report and returns it with its body`() = runTest {
        val store = store()

        assertNotNull(store.enqueue(report("a"), now))

        val pending = store.pending()
        assertEquals(1, pending.size)
        assertEquals("a", pending.single().reportId)
        assertEquals(now, pending.single().createdAt)
        assertEquals(
            report("a"),
            reportJson.decodeFromString<BatteryReport>(pending.single().body.decodeToString()),
        )
    }

    @Test
    fun `returns buffered reports oldest first`() = runTest {
        // A retention window wide enough to keep the 1970 timestamps below, which is what
        // makes this also a test of the zero-padding in the entry names.
        val store = store(maxAge = Duration.ofDays(36_500))

        store.enqueue(report("b"), Instant.ofEpochMilli(10_000))
        store.enqueue(report("a"), Instant.ofEpochMilli(999))
        store.enqueue(report("c"), Instant.ofEpochMilli(1_700_000_000_000))

        assertEquals(listOf("a", "b", "c"), store.pending().map { it.reportId })
    }

    @Test
    fun `removing a report leaves the rest of the buffer`() = runTest {
        val store = store()
        store.enqueue(report("a"), now.minusSeconds(2))
        store.enqueue(report("b"), now.minusSeconds(1))

        val first = store.pending().first()
        assertTrue(store.remove(first.id))

        assertEquals(listOf("b"), store.pending().map { it.reportId })
        assertEquals(1, store.count())
        assertFalse(store.remove(first.id))
    }

    @Test
    fun `drops the oldest reports when over the cap`() = runTest {
        val store = store(maxReports = 2)

        repeat(4) { i -> store.enqueue(report("r$i"), now.plusMillis(i.toLong())) }

        assertEquals(listOf("r2", "r3"), store.pending().map { it.reportId })
    }

    @Test
    fun `drops reports older than the retention window`() = runTest {
        val store = store(maxAge = Duration.ofDays(7))

        store.enqueue(report("stale"), now.minus(Duration.ofDays(8)))
        store.enqueue(report("fresh"), now.minus(Duration.ofDays(1)))

        assertEquals(listOf("fresh"), store.pending().map { it.reportId })
    }

    @Test
    fun `keeps the report just written even when it is the only one left`() = runTest {
        val store = store(maxReports = 1, maxAge = Duration.ofDays(7))

        store.enqueue(report("old"), now.minus(Duration.ofDays(30)))
        store.enqueue(report("new"), now)

        assertEquals(listOf("new"), store.pending().map { it.reportId })
    }

    @Test
    fun `clear empties the buffer`() = runTest {
        val store = store()
        store.enqueue(report("a"), now)

        store.clear()

        assertEquals(0, store.count())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `ignores files that are not reports`() = runTest {
        val store = store()
        store.enqueue(report("a"), now)
        folder.root.resolve("reports").resolve("notes.txt").writeText("ignore me")
        folder.root.resolve("reports").resolve("bogus.json").writeText("{}")

        // The .txt is skipped by extension; the .json has an unparsable name and is dropped.
        assertEquals(listOf("a"), store.pending().map { it.reportId })
        assertFalse(folder.root.resolve("reports").resolve("bogus.json").exists())
    }
}
