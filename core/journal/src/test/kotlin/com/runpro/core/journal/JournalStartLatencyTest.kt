package com.runpro.core.journal

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.junit.jupiter.api.io.TempDir

/** R1: a started run is persisted within 1 s of the start tap. */
class JournalStartLatencyTest {
    @Test
    fun `the run start is synced before append returns, without waiting for any timer`() {
        val output = FakeOutput()
        val clock = FakeClock() // Frozen: no flush or sync interval can pass.
        val writer = JournalWriter(output, clock)

        writer.append(started())

        assertEquals(1, output.syncCount)
        assertEquals(listOf(started()), read(output.synced).records)
        assertEquals(JournalEnd.Clean, read(output.synced).end)
    }

    @Test
    fun `a new journal file holds the run start within one second`(@TempDir dir: Path) {
        val worst = (1..RUNS).maxOf { index ->
            val file = dir.resolve("run-$index.journal")
            val tap = TimeSource.Monotonic.markNow()
            val writer = JournalWriter.create(file)
            writer.append(started())
            val latency = tap.elapsedNow()

            // Read back while the writer is still open, as recovery would after a kill.
            assertEquals(listOf(started()), JournalReader.read(file).records)
            writer.close()
            latency
        }
        assertTrue(worst < 1.seconds, "worst start latency $worst")
    }

    private companion object {
        const val RUNS = 20
    }
}
